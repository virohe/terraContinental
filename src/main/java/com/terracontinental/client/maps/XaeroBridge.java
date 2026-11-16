package com.terracontinental.client.maps;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.region.MapBlock;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTile;
import xaero.map.region.MapTileChunk;

/**
 * Writes sketched land into Xaero's World Map (1.47), the way its own writer does: on the render
 * thread, into a loaded leaf region of the surface layer, one tile (chunk) at a time, marking the
 * region as being written so Xaero saves it and the tile chunk so it rebuilds its texture.
 *
 * <p>A sketched tile keeps {@link #SKETCH} plus its blocks-per-pixel in Xaero's saved per-tile
 * "world interpretation version" byte (Xaero's own writes put 1 there and only test it for
 * {@code > 0}). So a sketch never covers ground you have explored, a finer sketch replaces a
 * coarser one, and when you walk there Xaero's writer replaces the sketch with the real thing.</p>
 *
 * <p>Only loaded when Xaero's World Map is installed. Any failure turns the sketching off for the
 * session (logged once) rather than risk Xaero's map.</p>
 */
final class XaeroBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final int SKETCH = 0x40;
    private static final int SURFACE = Integer.MAX_VALUE;

    enum Result { WRITTEN, SKIPPED, NOT_READY }

    /** One column of a sketch: the top block, its height, its biome. */
    record Column(BlockState state, int height, ResourceKey<Biome> biome) {
    }

    private static boolean broken;

    private XaeroBridge() {
    }

    static boolean ready() {
        if (broken) return false;
        try {
            WorldMapSession s = WorldMapSession.getCurrentSession();
            if (s == null) return false;
            MapProcessor mp = s.getMapProcessor();
            return mp != null && mp.getWorld() != null && !mp.isWritingPaused() && !mp.isWaitingForWorldUpdate()
                    && mp.isCurrentMultiworldWritable() && mp.getMapWorld() != null && mp.getCurrentDimension() != null;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    /** The level a chunk was sketched at (blocks a pixel), 0 if never mapped, -1 if explored for real or unknown yet. */
    static int sketchLevel(int chunkX, int chunkZ) {
        try {
            MapProcessor mp = WorldMapSession.getCurrentSession().getMapProcessor();
            MapRegion region = mp.getLeafMapRegion(SURFACE, chunkX >> 5, chunkZ >> 5, false);
            if (region == null) return 0;
            if (region.getLoadState() != 2) return -1;
            MapTileChunk tc = region.getChunk((chunkX >> 2) & 7, (chunkZ >> 2) & 7);
            if (tc == null) return 0;
            MapTile tile = tc.getTile(chunkX & 3, chunkZ & 3);
            if (tile == null || !tile.isLoaded()) return 0;
            int v = tile.getWorldInterpretationVersion();
            return (v & SKETCH) != 0 ? v & 0x3F : -1;
        } catch (Throwable t) {
            fail(t);
            return -1;
        }
    }

    /**
     * Writes one chunk of sketch at {@code level} blocks a pixel. {@code cols[z * 16 + x]} for the
     * chunk's 256 columns (repeats for coarse levels are the caller's). Render thread only.
     */
    static Result write(int chunkX, int chunkZ, int level, Column[] cols) {
        try {
            MapProcessor mp = WorldMapSession.getCurrentSession().getMapProcessor();
            MapRegion region = mp.getLeafMapRegion(SURFACE, chunkX >> 5, chunkZ >> 5, true);
            if (region == null) return Result.NOT_READY;
            synchronized (region.writerThreadPauseSync) {
                if (region.isWritingPaused()) return Result.NOT_READY;
                synchronized (region) {
                    if (!region.isResting()) return Result.NOT_READY;
                    if (region.getLoadState() != 2) {
                        if (region.canRequestReload_unsynced()) {
                            region.setBeingWritten(true);
                            mp.getMapSaveLoad().requestLoad(region, "terra_continental sketch");
                        }
                        return Result.NOT_READY;
                    }
                    region.registerVisit();
                    region.setBeingWritten(true);
                    int tcx = chunkX >> 2, tcz = chunkZ >> 2;
                    MapTileChunk tc = region.getChunk(tcx & 7, tcz & 7);
                    if (tc == null) {
                        tc = new MapTileChunk(region, tcx, tcz);
                        tc.setLoadState((byte) 2);
                        region.setChunk(tcx & 7, tcz & 7, tc);
                        region.setAllCachePrepared(false);
                    }
                    if (tc.getLoadState() != 2) return Result.NOT_READY;
                    MapTile tile = tc.getTile(chunkX & 3, chunkZ & 3);
                    if (tile != null && tile.isLoaded()) {
                        int v = tile.getWorldInterpretationVersion();
                        if ((v & SKETCH) == 0) return Result.SKIPPED;          // explored: never cover it
                        int had = v & 0x3F;
                        if (had != 0 && had <= level) return Result.SKIPPED;   // already this fine
                    } else {
                        tile = mp.getTilePool().get(mp.getCurrentDimension(), chunkX, chunkZ);
                    }
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            Column c = cols[z * 16 + x];
                            MapBlock b = tile.isLoaded() ? tile.getBlock(x, z) : null;
                            if (b == null) b = new MapBlock();
                            b.prepareForWriting(0);
                            if (c != null) b.write(c.state(), c.height(), c.height(), c.biome(), (byte) 0, false, false);
                            tile.setBlock(x, z, b);
                        }
                    }
                    tile.setWorldInterpretationVersion(SKETCH | level);
                    tile.setWrittenCave(Integer.MAX_VALUE, mp.getCaveModeDepthConfig());
                    tile.setWrittenOnce(true);
                    tile.setLoaded(true);
                    tc.setTile(chunkX & 3, chunkZ & 3, tile, mp.getBlockStateShortShapeCache(), mp);
                    tc.setHasHadTerrain();
                    tc.setChanged(true);
                    tc.setToUpdateBuffers(true);
                    return Result.WRITTEN;
                }
            }
        } catch (Throwable t) {
            fail(t);
            return Result.NOT_READY;
        }
    }

    static boolean broken() {
        return broken;
    }

    private static void fail(Throwable t) {
        if (!broken) {
            broken = true;
            LOGGER.error("[reveal] Xaero's World Map didn't take a sketch; sketching is off for this session", t);
        }
    }

    static ResourceKey<Biome> biomeKey(Object wrapped) {
        if (wrapped instanceof net.minecraft.core.Holder<?> h && h.unwrapKey().isPresent()
                && h.unwrapKey().get().isFor(Registries.BIOME)) {
            @SuppressWarnings("unchecked")
            ResourceKey<Biome> k = (ResourceKey<Biome>) h.unwrapKey().get();
            return k;
        }
        return null;
    }
}
