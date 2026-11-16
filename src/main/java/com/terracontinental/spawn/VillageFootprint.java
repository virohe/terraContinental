package com.terracontinental.spawn;

import com.terracontinental.config.TerraStructureConfig;
import com.terracontinental.plan.WorldPlan;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.function.IntBinaryOperator;

/**
 * Is the ground under a village level enough? One rule for every village: the planned spawn
 * villages, the random village grids on the other continents, and the rare per-continent ones.
 *
 * <ul>
 *   <li><b>Near</b>: 7x7 samples out to 48 blocks from the centre, none on water, rising or falling
 *   at most {@code max_relief} across them.</li>
 *   <li><b>Whole spread</b>: rings of 16 points every 32 blocks out to 128, at most
 *   {@link #SPREAD_FACTOR} x {@code max_relief} across everything and at most {@link #RING_STEP}
 *   between neighbouring rings along a ray. Until 2026-11-11 the spread allowed twice
 *   {@code max_relief} (28) and 24 per ring: the playtest's central village (relief 14 within 48
 *   blocks) still stood across crags further out, and a village's outer houses were cut into the
 *   hillside.</li>
 * </ul>
 */
public final class VillageFootprint {
    public static final int OK = 0, NEAR = 1, SPREAD = 2;

    private static final int[] OFFSETS = {0, -40, 40, -20, 20, -48, 48};
    private static final int RADIUS = 128, RAYS = 16;
    /** Most the ground may change between rings 32 blocks apart. */
    static final int RING_STEP = 8;
    /** The whole spread's relief, as a multiple of max_relief. */
    static final double SPREAD_FACTOR = 1.3;

    private VillageFootprint() {
    }

    /** {@link #OK}, or which part failed. {@code height} gives the ground height at (x, z). */
    public static int check(IntBinaryOperator height, int sea, int mx, int mz, int maxRelief) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int dx : OFFSETS) {
            for (int dz : OFFSETS) {
                int h = height.applyAsInt(mx + dx, mz + dz);
                if (h <= sea) return NEAR;
                lo = Math.min(lo, h);
                hi = Math.max(hi, h);
                if (hi - lo > maxRelief) return NEAR;
            }
        }
        int spreadMax = (int) Math.round(SPREAD_FACTOR * maxRelief);
        int h0 = height.applyAsInt(mx, mz);
        int[] prev = new int[RAYS];
        java.util.Arrays.fill(prev, h0);
        int flo = Math.min(lo, h0), fhi = Math.max(hi, h0);
        for (int r = 32; r <= RADIUS; r += 32) {
            for (int i = 0; i < RAYS; i++) {
                double a = 2 * Math.PI * i / RAYS;
                int h = height.applyAsInt(mx + (int) Math.round(r * Math.cos(a)), mz + (int) Math.round(r * Math.sin(a)));
                flo = Math.min(flo, h);
                fhi = Math.max(fhi, h);
                if (Math.abs(h - prev[i]) > RING_STEP || fhi - flo > spreadMax) return SPREAD;
                prev[i] = h;
            }
        }
        return OK;
    }

    public static int maxRelief() {
        try {
            if (TerraStructureConfig.SPEC.isLoaded()) return TerraStructureConfig.SPAWN_VILLAGE_MAX_RELIEF.get();
        } catch (Throwable ignored) {
        }
        return 14;
    }

    /** Height from the world plan's ground function (deterministic, microseconds); water reads as sea level. */
    public static IntBinaryOperator groundHeight(DensityFunction ground, int sea) {
        return (x, z) -> Math.max(sea, (int) Math.round(128.0 * ground.compute(new DensityFunction.SinglePointContext(x, 0, z))));
    }

    private static volatile Object groundFor;
    private static volatile DensityFunction ground;
    private static volatile int groundSea;

    /**
     * May a random-grid village start in this chunk? Always yes outside Terra Continental's overworld.
     * Not the spawn continent's planned villages: the planner checks those itself.
     */
    public static boolean gridAllows(ChunkGeneratorStructureState state, int chunkX, int chunkZ) {
        DensityFunction g = ground;
        if (groundFor != state) {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            ServerLevel overworld = server == null ? null : server.overworld();
            if (overworld == null || overworld.getChunkSource().getGeneratorState() != state) return true;
            synchronized (VillageFootprint.class) {
                if (groundFor != state) {
                    ground = WorldPlan.get(overworld) != null ? WorldPlan.groundFunction(overworld) : null;
                    groundSea = overworld.getChunkSource().getGenerator().getSeaLevel();
                    groundFor = state;
                }
                g = ground;
            }
        }
        if (g == null) return true;
        return check(groundHeight(g, groundSea), groundSea, (chunkX << 4) + 8, (chunkZ << 4) + 8, maxRelief()) == OK;
    }
}
