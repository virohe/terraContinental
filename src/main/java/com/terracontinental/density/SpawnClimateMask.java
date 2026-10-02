package com.terracontinental.density;

import com.mojang.serialization.MapCodec;
import com.terracontinental.config.TerraConfig;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * How strongly the spawn continent's climate is pulled toward temperate at a point: the
 * configured strength across the spawn continent, easing to 0 beyond it. Registered as
 * {@code terra_continental:spawn_climate_mask}; used by the Tectonic seam's
 * {@code noise_router/temperature} override as {@code t + mask * (temperate - t)}.
 *
 * <p>Why: Tectonic's temperature noise is very large-scale, so the whole spawn continent can
 * land inside one climate band. On the seed that prompted this (2026-09-26) it was 99.5%
 * frozen, and Terra Towns found one warm enough coast for its starter hamlets on the whole
 * continent. The spawn continent now always gets Terra Continental's temperate climate
 * (still varied — it is a noise, not a constant), and every other continent keeps Tectonic's.
 *
 * <p>Radii are multiples of {@code central_land_radius} (the spawn continent's bump), because
 * the continent's coast lies well outside the bump radius itself; the realgen seed's coast
 * reached ~12,000 blocks with the default 9,000 radius. Biome selection only: temperature does
 * not feed terrain, so landforms are unchanged.
 *
 * <p>A bare {@link SimpleFunction} on purpose — it reads nothing but the block position, so
 * there is no upstream cache to re-evaluate (see {@link CaveDepthFunction} for that trap).
 */
public final class SpawnClimateMask implements DensityFunction.SimpleFunction {

    public static final SpawnClimateMask INSTANCE = new SpawnClimateMask();
    public static final MapCodec<SpawnClimateMask> CODEC = MapCodec.unit(INSTANCE);

    // Resolved on first compute, after configs have loaded. Benign race: identical values.
    private volatile double[] params;

    private SpawnClimateMask() {
    }

    private double[] params() {
        double[] p = params;
        if (p == null) {
            double strength = 1.0, innerFactor = 1.5, outerFactor = 2.0, radius = 9000.0;
            try {
                strength = TerraConfig.TEMPERATE_SPAWN_STRENGTH.get();
                innerFactor = TerraConfig.TEMPERATE_SPAWN_INNER.get();
                outerFactor = TerraConfig.TEMPERATE_SPAWN_OUTER.get();
                radius = TerraConfig.CENTRAL_LAND_RADIUS.get();
            } catch (IllegalStateException notLoadedYet) {
                // Fall back to the defaults above; configs load long before worldgen in practice.
            }
            double inner = radius * innerFactor;
            double outer = Math.max(inner + 1.0, radius * outerFactor);
            p = new double[]{strength, inner * inner, inner, outer};
            params = p;
        }
        return p;
    }

    @Override
    public double compute(FunctionContext ctx) {
        double[] p = params();
        if (p[0] <= 0.0) {
            return 0.0;
        }
        double x = ctx.blockX(), z = ctx.blockZ();
        double d2 = x * x + z * z;
        if (d2 <= p[1]) {
            return p[0];
        }
        double d = Math.sqrt(d2);
        if (d >= p[3]) {
            return 0.0;
        }
        double t = (p[3] - d) / (p[3] - p[2]); // 1 at the inner radius, 0 at the outer
        return p[0] * t * t * (3.0 - 2.0 * t);
    }

    @Override
    public double minValue() {
        return 0.0;
    }

    @Override
    public double maxValue() {
        return 1.0;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KeyDispatchDataCodec.of(CODEC);
    }
}
