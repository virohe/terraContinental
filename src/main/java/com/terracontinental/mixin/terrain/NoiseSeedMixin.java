package com.terracontinental.mixin.terrain;

import com.terracontinental.terrain.RenamedNoises;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.Noises;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * A noise's seed is the hash of its id. The terrain's noises were tectonic:&lt;path&gt; until 0.10.0
 * (absorbed from Tectonic, MIT, (c) the Tectonic authors); renamed to terra_continental:&lt;path&gt;
 * they would seed differently and every world would get other land. So the renamed ones hash as
 * their old ids, and parameter/&lt;x&gt; as minecraft:&lt;x&gt;, which is how Tectonic seeded them.
 */
@Mixin(Noises.class)
public abstract class NoiseSeedMixin {

    @ModifyArg(method = "instantiate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;fromHashOf(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/util/RandomSource;"))
    private static ResourceLocation terra_continental$sameSeeds(ResourceLocation id) {
        return RenamedNoises.seedId(id);
    }
}
