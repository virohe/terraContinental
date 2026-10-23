package com.terracontinental.mixin.client;

import com.terracontinental.worldtype.DefaultWorldType;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A fresh Create World screen starts on TC: Large Biomes instead of vanilla's Default. Done through
 * setWorldType, the same call the World Type button makes, so the world's dimensions follow the
 * label. Left alone if the preset is missing (the screen then starts on Default as in vanilla).
 */
@Mixin(WorldCreationUiState.class)
public abstract class WorldCreationUiStateMixin {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void terraContinental$startOnLargeBiomes(Path savesFolder, WorldCreationContext settings,
                                                     Optional<ResourceKey<WorldPreset>> preset, OptionalLong seed,
                                                     CallbackInfo ci) {
        if (!DefaultWorldType.OPENING_FRESH.get() || !preset.equals(Optional.of(WorldPresets.NORMAL))) return;
        settings.worldgenLoadContext().registryOrThrow(Registries.WORLD_PRESET)
                .getHolder(DefaultWorldType.TC_LARGE_BIOMES)
                .ifPresent(holder -> ((WorldCreationUiState) (Object) this)
                        .setWorldType(new WorldCreationUiState.WorldTypeEntry(holder)));
    }
}
