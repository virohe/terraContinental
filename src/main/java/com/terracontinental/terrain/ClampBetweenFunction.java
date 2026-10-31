package com.terracontinental.terrain;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Clamps input between two density functions; minecraft:clamp only takes constant bounds.
 * Registered as terra_continental:clamp_between; Tectonic's tectonic:config_clamp, absorbed
 * (MIT, (c) the Tectonic authors). Bounds the terrain offset to the world's height limits.
 */
public record ClampBetweenFunction(DensityFunction input, DensityFunction min, DensityFunction max) implements DensityFunction {

    public static final MapCodec<ClampBetweenFunction> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("input").forGetter(ClampBetweenFunction::input),
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("min").forGetter(ClampBetweenFunction::min),
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("max").forGetter(ClampBetweenFunction::max)
    ).apply(i, ClampBetweenFunction::new));

    @Override
    public double compute(FunctionContext context) {
        return Math.min(Math.max(input.compute(context), min.compute(context)), max.compute(context));
    }

    @Override
    public void fillArray(double[] densities, ContextProvider context) {
        context.fillAllDirectly(densities, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new ClampBetweenFunction(input.mapAll(visitor), min.mapAll(visitor), max.mapAll(visitor)));
    }

    @Override
    public double minValue() { return min.minValue(); }

    @Override
    public double maxValue() { return max.maxValue(); }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KeyDispatchDataCodec.of(CODEC);
    }
}
