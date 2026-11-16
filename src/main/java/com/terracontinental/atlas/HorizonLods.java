package com.terracontinental.atlas;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiLevelType;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.terracontinental.config.TerraConfig;
import com.terracontinental.plan.WorldPlan;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Distant Horizons' LODs for land nobody has generated (user, 2026-11-16: the first look should be
 * a full world, the neighbouring continents on the horizon), drawn from the world plan's estimate
 * rather than generated: ground height from {@code terra_continental:depth} at y 0 (within ~1 block
 * of the real ground, microseconds a column), sea or land from the biome at sea level, and a top
 * block by biome and altitude. DH's own generators ran 1-2 chunks/s on this terrain (rig,
 * 2026-11-16); this answers a 2 km LOD section in well under a second, and DH only asks for areas
 * it has no data for. Chunks that exist on disk are left out, so real land is never covered.
 *
 * <p>Registered on the server's overworld when Distant Horizons loads it (dedicated or
 * single-player) and {@code horizon_lods} is on. A real chunk replaces its LOD the moment it is
 * generated or loaded, as always with DH.</p>
 */
public final class HorizonLods implements IDhApiWorldGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * Stone under the surface, in blocks. Columns reaching the world's floor (thousands of blocks
     * down in this pack) overflowed DH's render packing ("Negative y size", smoke test 2026-11-16);
     * nothing below the surface shows in a distant LOD anyway.
     */
    private static final int DEPTH = 32;

    private final ServerLevel level;
    private final IDhApiLevelWrapper wrapper;
    private final DensityFunction ground;
    private final BiomeSource biomes;
    private final Climate.Sampler sampler;
    private final int sea, minY, maxY;
    private final Path regionDir;
    private final ConcurrentHashMap<Long, int[]> regionHeaders = new ConcurrentHashMap<>();
    private final IDhApiBlockStateWrapper air, water, stone, grass, sand, snow, gravel, redSand;
    private final ConcurrentHashMap<Holder<Biome>, IDhApiBiomeWrapper> biomeWrappers = new ConcurrentHashMap<>();
    private volatile long sections, columns, nanos;

    private HorizonLods(ServerLevel level, IDhApiLevelWrapper wrapper) {
        this.level = level;
        this.wrapper = wrapper;
        this.ground = WorldPlan.groundFunction(level);
        this.biomes = level.getChunkSource().getGenerator().getBiomeSource();
        this.sampler = level.getChunkSource().randomState().sampler();
        this.sea = level.getChunkSource().getGenerator().getSeaLevel();
        this.minY = level.getMinBuildHeight();
        this.maxY = level.getMaxBuildHeight();
        this.regionDir = level.getServer().getWorldPath(LevelResource.ROOT).resolve("region");
        this.air = DhApi.Delayed.wrapperFactory.getAirBlockStateWrapper();
        this.water = block(Blocks.WATER.defaultBlockState());
        this.stone = block(Blocks.STONE.defaultBlockState());
        this.grass = block(Blocks.GRASS_BLOCK.defaultBlockState());
        this.sand = block(Blocks.SAND.defaultBlockState());
        this.snow = block(Blocks.SNOW_BLOCK.defaultBlockState());
        this.gravel = block(Blocks.GRAVEL.defaultBlockState());
        this.redSand = block(Blocks.RED_SAND.defaultBlockState());
    }

    /** Hooks DH's level load; call once at mod construction when DH is installed. */
    public static void register() {
        DhApi.events.bind(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
            @Override
            public void onLevelLoad(DhApiEventParam<EventParam> e) {
                IDhApiLevelWrapper w = e.value.levelWrapper;
                try {
                    if (w.getLevelType() != EDhApiLevelType.SERVER_LEVEL || !enabled()) return;
                    if (!(w.getWrappedMcObject() instanceof ServerLevel sl) || sl.dimension() != Level.OVERWORLD) return;
                    if (WorldPlan.get(sl) == null) return; // not Terra Continental's terrain
                    var r = DhApi.worldGenOverrides.registerWorldGeneratorOverride(w, new HorizonLods(sl, w));
                    LOGGER.info("[horizon] Distant Horizons' LODs for ungenerated land come from the world plan: {}",
                            r.success ? "on" : r.message);
                } catch (Throwable t) {
                    LOGGER.warn("[horizon] couldn't hook Distant Horizons: {}", t.toString());
                }
            }
        });
    }

    private static boolean enabled() {
        try {
            return !TerraConfig.SPEC.isLoaded() || TerraConfig.HORIZON_LODS.get();
        } catch (Throwable t) {
            return true;
        }
    }

    // -- the generator ----------------------------------------------------------------------------

    @Override
    public EDhApiWorldGeneratorReturnType getReturnType() {
        return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES;
    }

    @Override
    public byte getSmallestDataDetailLevel() {
        return 0;
    }

    @Override
    public byte getLargestDataDetailLevel() {
        return 12;
    }

    @Override
    public boolean runApiValidation() {
        return false;
    }

    @Override
    public CompletableFuture<Void> generateLod(int chunkMinX, int chunkMinZ, int lodX, int lodZ, byte detail,
                                               IDhApiFullDataSource out, EDhApiDistantGeneratorMode mode,
                                               ExecutorService executor, Consumer<IDhApiFullDataSource> done) {
        return CompletableFuture.runAsync(() -> {
            long t0 = System.nanoTime();
            int step = 1 << detail;
            int width = out.getWidthInDataColumns();
            int baseX = chunkMinX << 4, baseZ = chunkMinZ << 4;
            // Heights on a coarse grid, bilinear between (the terrain's 2-D estimate is smooth at this
            // scale); biomes on a coarser one. Every column sampled cost ~0.7 ms (smoke test).
            int hs = Math.max(1, Math.min(width, Math.max(16, 2 * step) / step)); // columns per height sample
            int bs = Math.max(hs, Math.min(width, Math.max(128, 8 * step) / step)); // columns per biome sample
            int hn = width / hs + 1, bn = width / bs + 1;
            int[] hg = new int[hn * hn];
            for (int i = 0; i < hn; i++)
                for (int j = 0; j < hn; j++)
                    hg[i * hn + j] = height(baseX + i * hs * step, baseZ + j * hs * step);
            @SuppressWarnings("unchecked")
            Holder<Biome>[] bg = new Holder[bn * bn];
            for (int i = 0; i < bn; i++)
                for (int j = 0; j < bn; j++)
                    bg[i * bn + j] = biomes.getNoiseBiome(QuartPos.fromBlock(baseX + i * bs * step + bs * step / 2),
                            QuartPos.fromBlock(sea), QuartPos.fromBlock(baseZ + j * bs * step + bs * step / 2), sampler);
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < width; z++) {
                    int bx = baseX + x * step + step / 2, bz = baseZ + z * step + step / 2;
                    if (chunkOnDisk(bx >> 4, bz >> 4)) continue; // real land: DH reads it itself
                    int i = x / hs, j = z / hs;
                    double fx = (x % hs) / (double) hs, fz = (z % hs) / (double) hs;
                    int i1 = Math.min(hn - 1, i + 1), j1 = Math.min(hn - 1, j + 1);
                    double h = hg[i * hn + j] * (1 - fx) * (1 - fz) + hg[i1 * hn + j] * fx * (1 - fz)
                            + hg[i * hn + j1] * (1 - fx) * fz + hg[i1 * hn + j1] * fx * fz;
                    out.setApiDataPointColumn(x, z, EDhApiWorldGenerationStep.SURFACE,
                            column((int) Math.round(h), bg[Math.min(bn - 1, x / bs) * bn + Math.min(bn - 1, z / bs)]));
                }
            }
            done.accept(out);
            nanos += System.nanoTime() - t0;
            columns += (long) width * width;
            if (++sections % 200 == 0 || sections == 1) {
                LOGGER.info("[horizon] {} LOD sections drawn, {} µs a column", sections, nanos / 1000 / Math.max(1, columns));
            }
        }, executor);
    }

    private int height(int x, int z) {
        int h = (int) Math.round(128.0 * ground.compute(new DensityFunction.SinglePointContext(x, 0, z)));
        // Kept at y >= 1 + DEPTH: DH packs API data points from y 0 here, and a negative bottom wrapped
        // to ~4090 ("Negative y size", smoke test 2026-11-16). A distant sea floor never shows.
        return Math.max(DEPTH + 1, Math.min(maxY - 3, h));
    }

    private List<DhApiTerrainDataPoint> column(int h, Holder<Biome> biome) {
        IDhApiBiomeWrapper bw = biomeWrappers.computeIfAbsent(biome, b -> DhApi.Delayed.wrapperFactory.getBiomeWrapper(new Object[]{b}, wrapper));
        boolean ocean = h < sea - 1;
        List<DhApiTerrainDataPoint> col = new ArrayList<>(4);
        // Layers top down, touching, each at least a block thick (DH refuses gaps and empty layers).
        if (ocean) {
            int floor = h;
            col.add(point(air, bw, sea, maxY, 15));
            col.add(point(water, bw, floor + 1, sea, 15));
            col.add(point(floor > sea - 12 ? sand : gravel, bw, floor, floor + 1, 0));
            col.add(point(stone, bw, Math.max(0, floor - DEPTH), floor, 0));
        } else {
            int top = Math.max(h, sea - 1);
            col.add(point(air, bw, top + 1, maxY, 15));
            col.add(point(WorldPlan.isOcean(biome) || top <= sea ? sand : top(biome, top), bw, top, top + 1, 15));
            col.add(point(stone, bw, Math.max(0, top - DEPTH), top, 0));
        }
        return col;
    }

    private IDhApiBlockStateWrapper top(Holder<Biome> b, int h) {
        if (h >= 500) return snow; // the alpine snow line (resourcepacks/terrain alpine/snow_line, y 500)
        if (h >= 380) return stone;
        if (b.is(BiomeTags.IS_BEACH) || b.is(net.neoforged.neoforge.common.Tags.Biomes.IS_DESERT)) return sand;
        if (b.is(BiomeTags.IS_BADLANDS)) return redSand;
        if (b.is(net.neoforged.neoforge.common.Tags.Biomes.IS_SNOWY)) return snow;
        return grass;
    }

    private static DhApiTerrainDataPoint point(IDhApiBlockStateWrapper block, IDhApiBiomeWrapper biome, int bottom, int top, int sky) {
        return DhApiTerrainDataPoint.create((byte) 0, 0, sky, bottom, top, block, biome);
    }

    private IDhApiBlockStateWrapper block(BlockState s) {
        return DhApi.Delayed.wrapperFactory.getBlockStateWrapper(new Object[]{s}, wrapper);
    }

    /** True if the chunk is in its region file (the header's offset table), read once per region. */
    private boolean chunkOnDisk(int cx, int cz) {
        long key = ((long) (cx >> 5) << 32) ^ ((cz >> 5) & 0xFFFFFFFFL);
        int[] header = regionHeaders.computeIfAbsent(key, k -> readHeader(cx >> 5, cz >> 5));
        return header.length > 0 && header[(cx & 31) + (cz & 31) * 32] != 0;
    }

    private int[] readHeader(int rx, int rz) {
        Path f = regionDir.resolve("r." + rx + "." + rz + ".mca");
        if (!Files.isRegularFile(f)) return new int[0];
        int[] h = new int[1024];
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
            if (raf.length() < 4096) return new int[0];
            for (int i = 0; i < 1024; i++) h[i] = raf.readInt();
        } catch (IOException e) {
            return new int[0];
        }
        return h;
    }

    @Override
    public void preGeneratorTaskStart() {
        // Region headers change as chunks are generated: re-read them for each batch.
        regionHeaders.clear();
    }

    @Override
    public void close() {
    }
}
