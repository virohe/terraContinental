package com.terracontinental.mixin.terrain;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Underground biomes (entries whose depth is a range, such as lush_caves and dripstone_caves at depth
 * 0.2-0.9) only where the climate lies inside their box; anywhere else the cave takes the biome its
 * climate picks at the surface. Vanilla picks the NEAREST box, and the surface biomes sit at depth 0 and
 * 1, so mid-band they are about 0.45 away: dripstone then wins wherever continentalness passes about
 * 0.35, not 0.8, and lush wherever humidity passes about 0.25. With this rule, the cave pockets in
 * {@code noise_router/continents} and {@code noise_router/vegetation} decide where those biomes are, and
 * the caves between them keep the climate's own biome. Deep dark (a depth point, 1.1) is untouched.
 *
 * <p>Wraps the whole method so TerraBlender's region lookup, which runs inside it, is wrapped too.
 */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class CaveBiomeContainmentMixin {

    @Shadow
    private Climate.ParameterList<Holder<Biome>> parameters() { throw new AssertionError(); }

    @Unique
    private volatile Map<Holder<Biome>, List<Climate.ParameterPoint>> terraContinental$caveBoxes;

    @WrapMethod(method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;")
    private Holder<Biome> terraContinental$containCaveBiomes(int x, int y, int z, Climate.Sampler sampler,
                                                             Operation<Holder<Biome>> original) {
        Holder<Biome> biome = original.call(x, y, z, sampler);
        List<Climate.ParameterPoint> boxes = terraContinental$caveBoxes().get(biome);
        if (boxes == null) return biome;
        Climate.TargetPoint t = sampler.sample(x, y, z);
        for (Climate.ParameterPoint p : boxes) {
            if (terraContinental$inside(p, t)) return biome;
        }
        Climate.Sampler surface = new Climate.Sampler(sampler.temperature(), sampler.humidity(),
            sampler.continentalness(), sampler.erosion(), DensityFunctions.zero(), sampler.weirdness(),
            sampler.spawnTarget());
        return original.call(x, y, z, surface);
    }

    @Unique
    private Map<Holder<Biome>, List<Climate.ParameterPoint>> terraContinental$caveBoxes() {
        Map<Holder<Biome>, List<Climate.ParameterPoint>> m = terraContinental$caveBoxes;
        if (m == null) {
            m = new IdentityHashMap<>();
            for (Pair<Climate.ParameterPoint, Holder<Biome>> e : parameters().values()) {
                Climate.Parameter d = e.getFirst().depth();
                if (d.min() != d.max()) m.computeIfAbsent(e.getSecond(), k -> new ArrayList<>()).add(e.getFirst());
            }
            terraContinental$caveBoxes = m;
        }
        return m;
    }

    @Unique
    private static boolean terraContinental$inside(Climate.ParameterPoint p, Climate.TargetPoint t) {
        return in(p.temperature(), t.temperature()) && in(p.humidity(), t.humidity())
            && in(p.continentalness(), t.continentalness()) && in(p.erosion(), t.erosion())
            && in(p.depth(), t.depth()) && in(p.weirdness(), t.weirdness());
    }

    @Unique
    private static boolean in(Climate.Parameter p, long v) {
        return v >= p.min() && v <= p.max();
    }
}
