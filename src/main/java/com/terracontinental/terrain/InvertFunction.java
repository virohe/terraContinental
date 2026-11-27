package com.terracontinental.terrain;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 1 / argument. Registered as terra_continental:invert; Tectonic's tectonic:invert, absorbed
 * (MIT, (c) the Tectonic authors). Its caves use it for the depth cutoff.
 */
public final class InvertFunction implements DensityFunction {

    public static final MapCodec<InvertFunction> CODEC =
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("argument").xmap(InvertFunction::create, InvertFunction::input);

    private final DensityFunction input;
    private final double min;
    private final double max;
    // Hashed once: NoiseChunk keys a map on every node it wraps (see DensityHashCacheMixin).
    private final int hash;

    public InvertFunction(DensityFunction input, double min, double max) {
        this.input = input;
        this.min = min;
        this.max = max;
        this.hash = (31 * input.hashCode() + Double.hashCode(min)) * 31 + Double.hashCode(max);
    }

    public DensityFunction input() { return input; }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof InvertFunction f && hash == f.hash && Double.compare(min, f.min) == 0
                && Double.compare(max, f.max) == 0 && input.equals(f.input);
    }

    @Override
    public int hashCode() { return hash; }

    public static InvertFunction create(DensityFunction input) {
        double min = input.minValue();
        double max = input.maxValue();
        // Across zero the result is unbounded either way.
        return min < 0.0 && max > 0.0
                ? new InvertFunction(input, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
                : new InvertFunction(input, min, max);
    }

    @Override
    public double compute(FunctionContext context) {
        return 1.0 / input.compute(context);
    }

    @Override
    public void fillArray(double[] densities, ContextProvider context) {
        input.fillArray(densities, context);
        for (int i = 0; i < densities.length; i++) {
            densities[i] = 1.0 / densities[i];
        }
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return create(input.mapAll(visitor));
    }

    @Override
    public double minValue() { return min; }

    @Override
    public double maxValue() { return max; }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KeyDispatchDataCodec.of(CODEC);
    }
}
