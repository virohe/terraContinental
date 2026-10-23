package com.terracontinental.mixin.client;

import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * World Type button order: TC: Large Biomes, TC: Temperate, TC: Climates, Default, then everything
 * else as loaded (user, 2026-10-23). Done here rather than by replacing the minecraft:normal tag:
 * replacing it dropped the types other mods add (Create Aeronautics' Airship Ready and End Sea).
 */
@Mixin(WorldCreationUiState.class)
public abstract class WorldCreationUiStateOrderMixin {
    private static final List<String> TERRA_CONTINENTAL$FIRST = List.of(
            "terra_continental:large_biomes", "terra_continental:temperate", "terra_continental:climates", "minecraft:normal");

    @Shadow
    @Final
    private List<WorldCreationUiState.WorldTypeEntry> normalPresetList;

    @Inject(method = "updatePresetLists", at = @At("RETURN"))
    private void terraContinental$tcTypesFirst(CallbackInfo ci) {
        List<WorldCreationUiState.WorldTypeEntry> first = new ArrayList<>();
        for (String id : TERRA_CONTINENTAL$FIRST) {
            for (WorldCreationUiState.WorldTypeEntry e : normalPresetList) {
                if (e.preset() != null && e.preset().unwrapKey().map(ResourceKey::location)
                        .map(Object::toString).filter(id::equals).isPresent()) {
                    first.add(e);
                    break;
                }
            }
        }
        List<WorldCreationUiState.WorldTypeEntry> rest = new ArrayList<>(normalPresetList);
        rest.removeAll(first);
        normalPresetList.clear();
        normalPresetList.addAll(first);
        normalPresetList.addAll(rest);
    }
}
