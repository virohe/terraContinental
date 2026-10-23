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
    public static final ModConfigSpec.BooleanValue SPAWN_VILLAGES_ENABLED;
    public static final ModConfigSpec.IntValue SPAWN_VILLAGE_COUNT;
    public static final ModConfigSpec.IntValue SPAWN_VILLAGE_MIN_GAP;
    public static final ModConfigSpec.IntValue SPAWN_VILLAGE_MAX_RELIEF;
    public static final ModConfigSpec.IntValue SPAWN_VILLAGE_THEMED_MAX;

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

        BUILDER.push("spawn_villages");
        SPAWN_VILLAGES_ENABLED = BUILDER
                .comment("Plan the spawn continent's villages instead of leaving them to the village grids: a central",
                         "village nearest the continent's centre, the rest spread across the land (farthest-first), each",
                         "given the village type used least so far that its biome allows. The village grids stay off",
                         "the spawn continent while this is on; every other continent keeps them. New chunks only.")
                .define("enabled", true);
        SPAWN_VILLAGE_COUNT = BUILDER
                .comment("Villages on the spawn continent, central village included. Up to 8 players start there and each",
                         "needs one, plus the central village: keep this at 9 or more.")
                .defineInRange("count", 10, 1, 32);
        SPAWN_VILLAGE_MIN_GAP = BUILDER
                .comment("Minimum distance between spawn-continent villages, in blocks. Relaxed (to half at most) only",
                         "if the land cannot fit `count` villages this far apart.")
                .defineInRange("min_gap", 3000, 500, 20000);
        SPAWN_VILLAGE_MAX_RELIEF = BUILDER
                .comment("Steepest ground a spawn-continent village may start on: the most the surface may rise or fall",
                         "across its ~100-block footprint (7x7 samples). Height above sea level is not limited.")
                .defineInRange("max_relief", 14, 2, 200);
        SPAWN_VILLAGE_THEMED_MAX = BUILDER
                .comment("At most this many spawn-continent villages may be climate villages (desert, savanna, snowy",
                         "buildings) standing on temperate ground. The temperate spawn climate leaves no desert,",
                         "savanna or snowy biomes there, so without this the spawn continent gets plains, taiga and",
                         "BWG villages only. Their buildings come, their biome doesn't. 0 = off.")
                .defineInRange("themed_max", 0, 0, 32);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }
}
