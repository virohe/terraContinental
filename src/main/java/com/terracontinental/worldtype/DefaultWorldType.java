package com.terracontinental.worldtype;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * TC: Large Biomes is the world type a fresh Create World screen starts on (user, 2026-10-23: no
 * cycling through types for a new world; vanilla's Default stays in the list). Vanilla hard-codes
 * its starting selection to minecraft:normal, so the client mixins swap it for this one, and only
 * when the screen opens fresh: "Re-Create" of an existing world keeps that world's type.
 */
public final class DefaultWorldType {
    public static final ResourceKey<WorldPreset> TC_LARGE_BIOMES = ResourceKey.create(Registries.WORLD_PRESET,
            ResourceLocation.fromNamespaceAndPath("terra_continental", "large_biomes"));

    /** Set while CreateWorldScreen.openFresh runs (on the client thread). */
    public static final ThreadLocal<Boolean> OPENING_FRESH = ThreadLocal.withInitial(() -> false);

    private DefaultWorldType() {
    }
}
