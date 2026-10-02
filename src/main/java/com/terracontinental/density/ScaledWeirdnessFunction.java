package com.terracontinental.density;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.terracontinental.config.TerraConfig;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Samples a wrapped density function with x/z coordinates scaled by TerraConfig.WEIRDNESS_SCALE.
 * Registered as terra_continental:scaled_weirdness.
 * Scale is read live at sample time so config hot-reloads take effect on world reload.
 */
public class ScaledWeirdnessFunction implements DensityFunction.SimpleFunction {

    public static final MapCodec<ScaledWeirdnessFunction> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
                    DensityFunction.HOLDER_HELPER_CODEC.fieldOf("argument").forGetter(f -> f.argument)
            ).apply(instance, ScaledWeirdnessFunction::new));

    private final DensityFunction argument;

    public ScaledWeirdnessFunction(DensityFunction argument) {
        this.argument = argument;
    }

    @Override
    public double compute(FunctionContext context) {
        double scale = TerraConfig.WEIRDNESS_SCALE.get();
        FunctionContext scaled = new FunctionContext() {
            @Override public int blockX() { return (int)(context.blockX() * scale); }
            @Override public int blockY() { return context.blockY(); }
            @Override public int blockZ() { return (int)(context.blockZ() * scale); }
        };
        return argument.compute(scaled);
    }

    @Override
    public double minValue() { return argument.minValue(); }

    @Override
    public double maxValue() { return argument.maxValue(); }

    @Override
    public KeyDispatchDataCodec<ScaledWeirdnessFunction> codec() {
        return KeyDispatchDataCodec.of(CODEC);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new ScaledWeirdnessFunction(argument.mapAll(visitor)));
    }
}
