package com.terracontinental.plan;

import com.mojang.logging.LogUtils;
import com.terracontinental.terrain.TerrainBase;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

/**
 * The world plan: one coarse scan of the overworld per world, shared by everything that needs to
 * know the land before chunks exist. Until 2026-10-30 the spawn-village planner, /continental
 * villages and the planned rivers (wip/rivers) each scanned the same ground on their own.
 *
 * <p>Points sit on a 128-block lattice centred on (0, 0) (the spawn-village planner's lattice, so
 * its sites are unchanged), filled in square rings outward from spawn. Each point holds:
 * <ul>
 *   <li>ground height, {@code 128 * depth(y = 0)}: terra_continental:depth is {@code 1 - y/128 + offset},
 *       so the surface sits where that crosses 0. Read from depth itself, not the offset, so
 *       anything the seam adds there (the planned rivers' valleys) is in the plan too. The river study (preview/RIVERS.md) measured this
 *       2-D estimate within a median of 1 block of in-game heights, ~50x cheaper than solving
 *       columns. It ignores caves, overhangs and the 3-D jaggedness on peaks.</li>
 *   <li>whether the biome at sea level is an ocean, the land test the village planner has always used
 *       (rivers and lakes count as land).</li>
 * </ul>
 * This version describes what the noise makes; it does not shape terrain yet. Plan-driven shaping
 * (ranges, rivers placed by the plan and read back by density functions) builds on it.
 */
public final class WorldPlan implements PlanPreview {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int STEP = 128;
    /** Reach of the plan from (0, 0), in blocks: the spawn continent and its neighbours. */
    public static final int REACH = 32_768;
    public static final int NO_HEIGHT = Short.MIN_VALUE;

    private static final ResourceKey<DensityFunction> GROUND_DEPTH = ResourceKey.create(
            Registries.DENSITY_FUNCTION, ResourceLocation.fromNamespaceAndPath("terra_continental", "depth"));

    private static volatile WorldPlan current;
    private static volatile Object currentFor;

    public final long seed;
    public final int half, n, sea;
    private final short[] height;
    // 0 not yet sampled, 1 land, 2 ocean (biome at sea level)
    private final byte[] kind;
    private volatile int ringsDone = -1;
    private final CompletableFuture<WorldPlan> done = new CompletableFuture<>();

    private WorldPlan(long seed, int sea) {
        this.seed = seed;
        this.sea = sea;
        this.half = REACH / STEP;
        this.n = 2 * half + 1;
        this.height = new short[n * n];
        this.kind = new byte[n * n];
        java.util.Arrays.fill(height, (short) NO_HEIGHT);
    }

    /**
     * This overworld's plan, started on first call and filled in the background. Null if the
     * overworld doesn't carry Terra Continental's terrain. Callers needing an area finished wait on
     * {@link #awaitReach}.
     */
    @Nullable
    public static WorldPlan get(ServerLevel level) {
        Object key = level.getChunkSource().getGeneratorState();
        WorldPlan p = current;
        if (p != null && currentFor == key) return p;
        synchronized (WorldPlan.class) {
            if (current != null && currentFor == key) return current;
            if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator gen)
                    || !gen.generatorSettings().unwrapKey()
                            .map(k -> TerrainBase.OVERWORLD_SETTINGS.contains(k.location().toString())).orElse(false)) {
                return null;
            }
            p = new WorldPlan(level.getSeed(), gen.getSeaLevel());
            LoadingStatus.reset(); // a new world: nothing chosen yet
            current = p;
            currentFor = key;
            WorldPlan plan = p;
            Thread t = new Thread(() -> plan.fill(level), "Terra Continental world plan");
            t.setDaemon(true);
            t.start();
            return p;
        }
    }

    private void fill(ServerLevel level) {
        long t0 = System.nanoTime();
        try {
            RandomState rs = level.getChunkSource().randomState();
            BiomeSource bs = level.getChunkSource().getGenerator().getBiomeSource();
            Climate.Sampler sampler = rs.sampler();
            DensityFunction ground = seeded(level.registryAccess().registryOrThrow(Registries.DENSITY_FUNCTION)
                    .getHolderOrThrow(GROUND_DEPTH).value(), rs);
            // Rings of 8 at a time: enough work per batch to keep the cores busy, small enough that
            // the loading screen sees the map grow.
            for (int r0 = 0; r0 <= half; r0 += 8) {
                int r1 = Math.min(half, r0 + 7);
                int[] cells = ring(r0, r1);
                IntStream.of(cells).parallel().forEach(i -> sample(i, ground, bs, sampler));
                ringsDone = r1;
            }
            done.complete(this);
            LOGGER.info("[world-plan] {} points ({} km across) planned in {} ms", n * n,
                    Math.round(n * (double) STEP / 1000.0), (System.nanoTime() - t0) / 1_000_000L);
        } catch (Throwable e) {
            LOGGER.error("[world-plan] planning failed", e);
            done.completeExceptionally(e);
        }
    }

    private void sample(int i, DensityFunction ground, BiomeSource bs, Climate.Sampler sampler) {
        int x = (i % n - half) * STEP, z = (i / n - half) * STEP;
        double depth = ground.compute(new DensityFunction.SinglePointContext(x, 0, z));
        height[i] = (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, Math.round(128.0 * depth)));
        Holder<Biome> b = bs.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(sea), QuartPos.fromBlock(z), sampler);
        kind[i] = isOcean(b) ? (byte) 2 : (byte) 1;
    }

    /** Indices of every point whose Chebyshev distance from the centre is in [r0, r1]. */
    private int[] ring(int r0, int r1) {
        int count = 0;
        for (int r = r0; r <= r1; r++) count += r == 0 ? 1 : 8 * r;
        int[] out = new int[count];
        int k = 0;
        for (int r = r0; r <= r1; r++) {
            if (r == 0) {
                out[k++] = half * n + half;
                continue;
            }
            for (int d = -r; d < r; d++) {
                out[k++] = (half - r) * n + half + d;      // north edge, west to east
                out[k++] = (half + d) * n + half + r;      // east edge, north to south
                out[k++] = (half + r) * n + half - d;      // south edge, east to west
                out[k++] = (half - d) * n + half - r;      // west edge, south to north
            }
        }
        return out;
    }

    /** A registered density function, seeded the way RandomState wires the noise router. */
    private static DensityFunction seeded(DensityFunction f, RandomState rs) {
        Map<DensityFunction, DensityFunction> wrapped = new HashMap<>();
        return f.mapAll(new DensityFunction.Visitor() {
            @Override
            public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder h) {
                return new DensityFunction.NoiseHolder(h.noiseData(), rs.getOrCreateNoise(h.noiseData().unwrapKey().orElseThrow()));
            }

            @Override
            public DensityFunction apply(DensityFunction df) {
                // depth is 2-D plus a y gradient: no BlendedNoise or End islands to reseed.
                return wrapped.computeIfAbsent(df, d -> d);
            }
        });
    }

    public static boolean isOcean(Holder<Biome> b) {
        return b.unwrapKey().map(k -> k.location().getPath().contains("ocean")).orElse(false);
    }

    // -- queries ----------------------------------------------------------------------------------

    /** Blocks out from (0, 0) (square) that are fully sampled; -1 before the first ring. */
    public int reach() {
        int r = ringsDone;
        return r < 0 ? -1 : r * STEP;
    }

    /** Blocks until everything within {@code blocks} of (0, 0) is sampled (or the plan failed). */
    public WorldPlan awaitReach(int blocks) {
        while (reach() < Math.min(blocks, REACH) && !done.isDone()) {
            Thread.onSpinWait();
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (done.isCompletedExceptionally()) done.join();
        return this;
    }

    private int index(int bx, int bz) {
        int ix = Math.round(bx / (float) STEP) + half, iz = Math.round(bz / (float) STEP) + half;
        return ix < 0 || iz < 0 || ix >= n || iz >= n ? -1 : iz * n + ix;
    }

    /** True if the nearest point is sampled and its sea-level biome is not an ocean. */
    public boolean isLand(int bx, int bz) {
        int i = index(bx, bz);
        return i >= 0 && kind[i] == 1;
    }

    /** True if the nearest point is sampled (land or ocean). */
    public boolean isKnown(int bx, int bz) {
        int i = index(bx, bz);
        return i >= 0 && kind[i] != 0;
    }

    /** Estimated ground height at the nearest point, or {@link #NO_HEIGHT}. */
    public int groundHeight(int bx, int bz) {
        int i = index(bx, bz);
        return i < 0 || kind[i] == 0 ? NO_HEIGHT : height[i];
    }

    // -- PlanPreview: each lattice point is drawn as the 128-block cell around it ------------------

    @Override public int cellsX() { return n; }
    @Override public int cellsZ() { return n; }
    @Override public int blocksPerCell() { return STEP; }
    @Override public int originX() { return -half * STEP - STEP / 2; }
    @Override public int originZ() { return -half * STEP - STEP / 2; }

    @Override
    public float progress() {
        return done.isDone() ? 1.0f : Math.max(0, ringsDone + 1) / (float) (half + 1);
    }

    @Override
    public int colorAt(int cx, int cz) {
        int i = cz * n + cx;
        byte k = kind[i];
        if (k == 0) return 0;
        int h = height[i];
        if (k == 2) {
            // Ocean by depth below sea level: shelf to abyss (the floor reaches ~400 below sea).
            double t = clamp((sea - h) / 400.0);
            return argb(lerp(0x4E, 0x12, t), lerp(0x8F, 0x2B, t), lerp(0xC4, 0x5C, t));
        }
        // Land by height above sea: lowland green, upland olive, rock grey, snow above ~+700.
        double a = h - sea;
        if (a < 0) return argb(0x6A, 0x9E, 0x5A);                       // land below sea level: lakes, basins
        if (a < 120) return mix(0x6F, 0xA8, 0x5C, 0x9C, 0xA0, 0x5E, a / 120.0);
        if (a < 350) return mix(0x9C, 0xA0, 0x5E, 0x8C, 0x7E, 0x62, (a - 120) / 230.0);
        if (a < 700) return mix(0x8C, 0x7E, 0x62, 0xA8, 0xA4, 0xA0, (a - 350) / 350.0);
        return mix(0xA8, 0xA4, 0xA0, 0xF2, 0xF4, 0xF6, (a - 700) / 400.0);
    }

    private static double clamp(double t) {
        return t < 0 ? 0 : t > 1 ? 1 : t;
    }

    private static int lerp(int a, int b, double t) {
        return (int) Math.round(a + (b - a) * t);
    }

    private static int mix(int r0, int g0, int b0, int r1, int g1, int b1, double t) {
        t = clamp(t);
        return argb(lerp(r0, r1, t), lerp(g0, g1, t), lerp(b0, b1, t));
    }

    private static int argb(int r, int g, int b) {
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
}
