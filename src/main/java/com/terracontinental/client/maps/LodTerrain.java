package com.terracontinental.client.maps;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The land as Distant Horizons has it: what the player sees out past the render distance. Columns
 * are read from DH's LOD data and kept per session (the LODs of generated land don't change).
 * Only loaded when Distant Horizons is installed. Thread-safe.
 */
final class LodTerrain {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** No LOD there (not generated, or not loaded into DH yet). */
    static final XaeroBridge.Column NONE = new XaeroBridge.Column(null, Integer.MIN_VALUE, null);

    private final ConcurrentHashMap<Long, XaeroBridge.Column> cache = new ConcurrentHashMap<>();
    private IDhApiTerrainDataCache dhCache;
    private IDhApiLevelWrapper level;
    private boolean warned;

    /** Binds to the client's current level; false while DH isn't up. */
    boolean bind() {
        try {
            if (DhApi.Delayed.worldProxy == null || DhApi.Delayed.terrainRepo == null || !DhApi.Delayed.worldProxy.worldLoaded()) {
                return false;
            }
            IDhApiLevelWrapper l = DhApi.Delayed.worldProxy.getSinglePlayerLevel();
            if (l == null) {
                for (IDhApiLevelWrapper w : DhApi.Delayed.worldProxy.getAllLoadedLevelWrappers()) {
                    if (w.getDimensionName().endsWith("overworld")) l = w;
                }
            }
            if (l == null) return false;
            if (l != level) {
                level = l;
                cache.clear();
                dhCache = DhApi.Delayed.terrainRepo.createSoftCache();
            }
            return true;
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                LOGGER.warn("[reveal] Distant Horizons isn't answering: {}", t.toString());
            }
            return false;
        }
    }

    /** The top of the land at (x, z): from DH, cached by {@code step}-aligned cell. */
    XaeroBridge.Column at(int x, int z, int step) {
        int cx = Math.floorDiv(x, step), cz = Math.floorDiv(z, step);
        long key = ((long) step << 56) ^ ((long) cx << 28) ^ (cz & 0xFFFFFFFL);
        XaeroBridge.Column c = cache.get(key);
        if (c != null) return c;
        c = read(cx * step + step / 2, cz * step + step / 2, step);
        // An empty answer may only mean DH hasn't loaded that area yet: don't keep it.
        if (c != NONE) cache.put(key, c);
        return c;
    }

    /** Surface height for line of sight (water counts at its surface), or Integer.MIN_VALUE. */
    int height(int x, int z, int step) {
        return at(x, z, step).height();
    }

    /**
     * DH's own column lookup at a coarser detail level: one column per {@code step} blocks, read from
     * a section of 64 x 64 such columns. The public getColumnDataAtBlockPos always reads full
     * detail, one 64-block section per disk fetch (~13 ms each in the smoke test, 2026-11-16: a
     * 3 km survey took 13 s and found 12 cells); at 32 blocks a column one fetch covers 2 km.
     */
    private static java.lang.invoke.MethodHandle columnAtDetail;
    private static boolean columnAtDetailTried;

    @SuppressWarnings("unchecked")
    private DhApiResult<DhApiTerrainDataPoint[]> column(int x, int z, int step) throws Throwable {
        if (!columnAtDetailTried) {
            columnAtDetailTried = true;
            try {
                Class<?> repo = Class.forName("com.seibel.distanthorizons.core.api.external.methods.data.DhApiTerrainDataRepo");
                java.lang.reflect.Method m = repo.getDeclaredMethod("getTerrainDataColumnArray", IDhApiLevelWrapper.class,
                        long.class, Integer.class, IDhApiTerrainDataCache.class);
                m.setAccessible(true);
                columnAtDetail = java.lang.invoke.MethodHandles.lookup().unreflect(m);
            } catch (Throwable t) {
                LOGGER.warn("[reveal] Distant Horizons' coarse column lookup isn't there ({}); reading full detail, slowly", t.toString());
            }
        }
        if (columnAtDetail != null) {
            byte detail = (byte) Integer.numberOfTrailingZeros(Math.max(1, step));
            long pos = com.seibel.distanthorizons.core.pos.DhSectionPos.encode(detail, Math.floorDiv(x, step), Math.floorDiv(z, step));
            return (DhApiResult<DhApiTerrainDataPoint[]>) columnAtDetail.invoke(level, pos, (Integer) null, dhCache);
        }
        return DhApi.Delayed.terrainRepo.getColumnDataAtBlockPos(level, x, z, dhCache);
    }

    private XaeroBridge.Column read(int x, int z, int step) {
        try {
            DhApiResult<DhApiTerrainDataPoint[]> r = column(x, z, step);
            if (r == null || !r.success || r.payload == null) return NONE;
            DhApiTerrainDataPoint top = null;
            for (DhApiTerrainDataPoint p : r.payload) {
                if (p == null || p.blockStateWrapper == null || p.blockStateWrapper.isAir()) continue;
                if (top == null || p.topYBlockPos > top.topYBlockPos) top = p;
            }
            if (top == null) return NONE;
            BlockState state = unwrapState(top.blockStateWrapper.getWrappedMcObject());
            if (state == null) return NONE;
            return new XaeroBridge.Column(state, top.topYBlockPos - 1,
                    top.biomeWrapper == null ? null : XaeroBridge.biomeKey(top.biomeWrapper.getWrappedMcObject()));
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                LOGGER.warn("[reveal] reading Distant Horizons' LODs failed: {}", t.toString());
            }
            return NONE;
        }
    }

    @Nullable
    private static BlockState unwrapState(Object o) {
        return o instanceof BlockState s ? s : null;
    }
}
