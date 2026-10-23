package com.terracontinental.mixin;

import com.terracontinental.worldtype.DefaultWorldType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * "Re-Create" names a world's type with fromSettings, which calls every noise world Default (client
 * check, 2026-10-23: a TC: Large Biomes world re-created as "Default"). Worlds on the TC world types'
 * own noise settings get their own type back. TC: Temperate uses minecraft:overworld itself, so it
 * cannot be told from Default and stays labelled Default; its world is the same either way.
 */
@Mixin(WorldPresets.class)
public abstract class WorldPresetsMixin {
    private static final ResourceKey<WorldPreset> TERRA_CONTINENTAL$CLIMATES = ResourceKey.create(Registries.WORLD_PRESET,
            ResourceLocation.fromNamespaceAndPath("terra_continental", "climates"));

    @Inject(method = "fromSettings", at = @At("HEAD"), cancellable = true)
    private static void terraContinental$tcTypes(WorldDimensions dimensions,
                                                 CallbackInfoReturnable<Optional<ResourceKey<WorldPreset>>> cir) {
        Optional<LevelStem> overworld = dimensions.get(LevelStem.OVERWORLD);
        if (overworld.isEmpty() || !(overworld.get().generator() instanceof NoiseBasedChunkGenerator noise)) return;
        noise.generatorSettings().unwrapKey().map(k -> k.location().toString()).ifPresent(id -> {
            if (id.equals("terra_continental:overworld_large")) cir.setReturnValue(Optional.of(DefaultWorldType.TC_LARGE_BIOMES));
            else if (id.equals("terra_continental:overworld_climates")) cir.setReturnValue(Optional.of(TERRA_CONTINENTAL$CLIMATES));
        });
    }
}
