package com.terracontinental.terrain;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 1 / argument. Registered as terra_continental:invert; Tectonic's tectonic:invert, absorbed
 * (MIT, (c) the Tectonic authors). Its caves use it for the depth cutoff.
 */
public record InvertFunction(DensityFunction input, double min, double max) implements DensityFunction {

    public static final MapCodec<InvertFunction> CODEC =
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("argument").xmap(InvertFunction::create, InvertFunction::input);

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
