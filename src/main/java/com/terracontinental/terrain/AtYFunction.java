package com.terracontinental.terrain;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * The argument evaluated at a fixed y, the same in every block of a column. Registered as
 * terra_continental:at_y. The biome router reads terrain height as 128 x depth at y 0 (the world
 * plan's estimate), so biomes can follow altitude: peak biomes only above the snow line.
 */
public final class AtYFunction implements DensityFunction {

    public static final MapCodec<AtYFunction> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("argument").forGetter(AtYFunction::argument),
            Codec.INT.optionalFieldOf("y", 0).forGetter(AtYFunction::y)
    ).apply(i, AtYFunction::new));

    private final DensityFunction argument;
    private final int y;
    // Hashed once: NoiseChunk keys a map on every node it wraps (see DensityHashCacheMixin).
    private final int hash;

    public AtYFunction(DensityFunction argument, int y) {
        this.argument = argument;
        this.y = y;
        this.hash = 31 * argument.hashCode() + y;
    }

    public DensityFunction argument() { return argument; }

    public int y() { return y; }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof AtYFunction a && hash == a.hash && y == a.y && argument.equals(a.argument);
    }

    @Override
    public int hashCode() { return hash; }

    @Override
    public double compute(FunctionContext context) {
        return argument.compute(new SinglePointContext(context.blockX(), y, context.blockZ()));
    }

    @Override
    public void fillArray(double[] densities, ContextProvider context) {
        context.fillAllDirectly(densities, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new AtYFunction(argument.mapAll(visitor), y));
    }

    @Override
    public double minValue() { return argument.minValue(); }

    @Override
    public double maxValue() { return argument.maxValue(); }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KeyDispatchDataCodec.of(CODEC);
    }
}
