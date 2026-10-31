package com.terracontinental.mixin.terrain;

import com.google.common.base.Suppliers;
import com.terracontinental.terrain.TerrainBase;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * Moves the global lava level from vanilla's fixed y -54 to 10 above the world floor. With the
 * floor at -512, vanilla's level fills every open cave below -54 with lava. Absorbed from Tectonic
 * (MIT, (c) the Tectonic authors), which matched only noise settings named "overworld", so the TC
 * Large Biomes and Climates types never got it; they do now.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class LavaLevelMixin {

    @Shadow @Final @Mutable
    private Supplier<Aquifer.FluidPicker> globalFluidPicker;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void terra_continental$lowerLava(BiomeSource source, Holder<NoiseGeneratorSettings> settings, CallbackInfo ci) {
        boolean ours = settings.unwrapKey().map(k -> TerrainBase.OVERWORLD_SETTINGS.contains(k.location().toString())).orElse(false);
        if (!ours) return;
        globalFluidPicker = Suppliers.memoize(() -> {
            NoiseGeneratorSettings s = settings.value();
            int lavaLevel = s.noiseSettings().minY() + 10;
            Aquifer.FluidStatus lava = new Aquifer.FluidStatus(lavaLevel, Blocks.LAVA.defaultBlockState());
            Aquifer.FluidStatus sea = new Aquifer.FluidStatus(s.seaLevel(), s.defaultFluid());
            int below = Math.min(lavaLevel, s.seaLevel());
            return (x, y, z) -> y < below ? lava : sea;
        });
    }
}
