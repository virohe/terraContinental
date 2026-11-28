package com.terracontinental.plan;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * A column cache for density functions evaluated one point at a time, outside a NoiseChunk: it
 * stands in for a {@code flat_cache} or {@code cache_2d} marker and keeps the last column's value,
 * per thread. Outside a NoiseChunk vanilla's markers cache nothing, so a function the terrain refers
 * to several times was worked out again at every reference; the giant's range terms refer to the
 * terrain's base offset 8-12 times, and the world plan and rare-site search ran 4-7 times slower
 * (rig, seed 4151832666630476678, 2026-11-28: world plan 88 s, was 18 s). Same values: a flat_cache
 * or cache_2d value depends on x and z only.
 */
final class PointCache implements DensityFunction {
    private final DensityFunction wrapped;
    private final ThreadLocal<double[]> last = ThreadLocal.withInitial(() -> new double[]{Double.NaN, Double.NaN, 0.0});

    PointCache(DensityFunction wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public double compute(FunctionContext ctx) {
        double[] c = last.get();
        int x = ctx.blockX(), z = ctx.blockZ();
        if (c[0] == x && c[1] == z) return c[2];
        double v = wrapped.compute(ctx);
        c[0] = x;
        c[1] = z;
        c[2] = v;
        return v;
    }

    @Override
    public void fillArray(double[] array, ContextProvider provider) {
        provider.fillAllDirectly(array, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new PointCache(wrapped.mapAll(visitor)));
    }

    @Override
    public double minValue() {
        return wrapped.minValue();
    }

    @Override
    public double maxValue() {
        return wrapped.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        // Never serialized: it only lives in functions built for point sampling (WorldPlan.seeded).
        return KeyDispatchDataCodec.of(MapCodec.unit(this));
    }
}
