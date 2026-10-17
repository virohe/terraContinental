package com.terracontinental.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Per-structure spacing lives in the structure_set datapack JSONs
 * (data/minecraft/worldgen/structure_set/*.json). This config only holds the
 * global runtime multiplier applied on top of those by the placement mixins.
 */
public class TerraStructureConfig {

    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.DoubleValue STRUCTURE_SPACING_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue VILLAGE_SPACING_MULTIPLIER;

    static {
        BUILDER.push("global");
        STRUCTURE_SPACING_MULTIPLIER = BUILDER
                .comment("Global spacing multiplier applied to ALL structure_set placements (vanilla + modded).",
                         "Applied at runtime via Mixin, so it scales whatever spacing each mod's structure declares.",
                         "Also divides stronghold ring count. 1.0 = no change. 2.0 = structures 2x further apart. Default: 1.5")
                .defineInRange("structure_spacing_multiplier", 1.5, 0.1, 20.0);
        BUILDER.pop();

        BUILDER.push("villages");
        VILLAGE_SPACING_MULTIPLIER = BUILDER
                .comment("EXTRA spacing multiplier for villages only, applied on top of structure_spacing_multiplier.",
                         "Villages need their own dial because two independent structure_sets place them —",
                         "minecraft:villages and biomeswevegone:villages, each with its own grid and salt — and BWG",
                         "also adds ~21 of its biomes to the vanilla village tags, so far more of the map is eligible.",
                         "NOT vanilla's spacing: villages sit on the built-in distant_towns grid of 129 chunks (vanilla",
                         "uses 34), times structure_spacing_multiplier, times this. 1.0 with the global 1.5 puts",
                         "villages ~193 chunks (~3,100 blocks) apart. Measured 2026-10-16 on the real pack: 1.5 x 1.1",
                         "gave 7 on both a 15.7 km and the 9 km spawn continent; 1.0 aims at ~8-9.",
                         "Village count scales as 1/multiplier^2, so double this to quarter the villages. Default: 1.0")
                .defineInRange("village_spacing_multiplier", 1.0, 0.1, 20.0);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }
}
