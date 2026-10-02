package com.terracontinental.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class TerraConfig {

    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue CORE_RADIUS;
    public static final ModConfigSpec.IntValue BORDER_RADIUS;
    public static final ModConfigSpec.IntValue TRANSITION_WIDTH;
    public static final ModConfigSpec.DoubleValue OCEAN_FLOOR_VALUE;
    public static final ModConfigSpec.DoubleValue OCEAN_BIAS;
    public static final ModConfigSpec.DoubleValue RADIAL_BIAS_STRENGTH;
    public static final ModConfigSpec.DoubleValue CENTRAL_LAND_BUMP;
    public static final ModConfigSpec.IntValue CENTRAL_LAND_RADIUS;
    public static final ModConfigSpec.IntValue ADDITIONAL_CONTINENTS;
    public static final ModConfigSpec.IntValue CONTINENT_SPREAD;
    public static final ModConfigSpec.IntValue CONTINENT_RADIUS;
    public static final ModConfigSpec.DoubleValue CONTINENT_BUMP;
    public static final ModConfigSpec.DoubleValue WEIRDNESS_SCALE;
    public static final ModConfigSpec.DoubleValue OCEAN_FORMATION_HUMIDITY_BAND;
    public static final ModConfigSpec.DoubleValue TEMPERATE_SPAWN_STRENGTH;
    public static final ModConfigSpec.DoubleValue TEMPERATE_SPAWN_INNER;
    public static final ModConfigSpec.DoubleValue TEMPERATE_SPAWN_OUTER;
    public static final ModConfigSpec.DoubleValue CAVE_DEPTH_FLOOR_VALUE;
    public static final ModConfigSpec.IntValue CAVE_DEPTH_REFERENCE_Y;
    public static final ModConfigSpec.IntValue CAVE_DEPTH_FALLBACK_MIN_Y;

    static {
        BUILDER.push("continent_shaping");

        CORE_RADIUS = BUILDER
                .comment("Reference distance for density gradient. Bias reaches +radial_bias_strength at this distance, then keeps growing past it. Default: 50000")
                .defineInRange("core_radius", 50000, 1000, 2000000);

        BORDER_RADIUS = BUILDER
                .comment("Distance from origin where the soft world-border sigmoid is centered. Set well beyond core_radius for a long frontier zone. Default: 150000")
                .defineInRange("border_radius", 150000, 1000, 4000000);

        TRANSITION_WIDTH = BUILDER
                .comment("Width of the soft border sigmoid in blocks. Wider = more gradual fade to deep ocean. Default: 30000")
                .defineInRange("transition_width", 30000, 500, 500000);

        OCEAN_FLOOR_VALUE = BUILDER
                .comment("Continentalness value lerped toward beyond core_radius. -1.0 = deep ocean. Default: -1.0")
                .defineInRange("ocean_floor_value", -1.0, -2.0, 1.0);

        OCEAN_BIAS = BUILDER
                .comment("Subtracted from upstream noise before falloff. Positive = wider ocean gaps between continents. Default: 0.65")
                .defineInRange("ocean_bias", 0.65, -1.0, 1.0);

        RADIAL_BIAS_STRENGTH = BUILDER
                .comment("Extra ocean_bias added at the edge of core_radius (ramps linearly from center). Produces denser continents at origin, sparser at edges. Default: 0.2")
                .defineInRange("radial_bias_strength", 0.2, 0.0, 2.0);

        CENTRAL_LAND_BUMP = BUILDER
                .comment("Added to upstream noise within central_land_radius (Gaussian smoothstep falloff). Guarantees a continent at origin for spawn. Default: 1.45")
                .defineInRange("central_land_bump", 1.45, 0.0, 5.0);

        CENTRAL_LAND_RADIUS = BUILDER
                .comment("Radius of the spawn-continent bump in blocks. Default: 9000")
                .defineInRange("central_land_radius", 9000, 100, 100000);

        ADDITIONAL_CONTINENTS = BUILDER
                .comment("Extra continents placed deterministically from the world seed, distributed by angle around the origin. 0 disables. Default: 5")
                .defineInRange("additional_continents", 5, 0, 32);

        CONTINENT_SPREAD = BUILDER
                .comment("Max distance (blocks) of extra continent centers from origin. Centers land between 40% and 95% of this. Default: 60000")
                .defineInRange("continent_spread", 60000, 5000, 2000000);

        CONTINENT_RADIUS = BUILDER
                .comment("Base radius of each extra continent bump in blocks (jittered ±30% per seed). Default: 14000")
                .defineInRange("continent_radius", 14000, 1000, 100000);

        CONTINENT_BUMP = BUILDER
                .comment("Continentalness added at the center of each extra continent. Default: 1.45")
                .defineInRange("continent_bump", 1.45, 0.0, 5.0);

        BUILDER.pop();

        BUILDER.push("noise");

        WEIRDNESS_SCALE = BUILDER
                .comment("Scale multiplier for the weirdness/ridges noise. 1.0 = vanilla frequency. " +
                         "Lower values (e.g. 0.5) produce wider biome bands and less terrain variety. " +
                         "Higher values (e.g. 2.0) produce more frequent transitions.")
                .defineInRange("weirdness_scale", 1.0, 0.1, 4.0);

        BUILDER.pop();

        BUILDER.push("biomes");

        OCEAN_FORMATION_HUMIDITY_BAND = BUILDER
                .comment("Rarity of BWG ocean formations (lush_stacks, dead_sea), which otherwise blanket all temperate/warm ocean.",
                         "This is the half-width of the humidity band they occupy (0..1); the rest of the ocean falls back to vanilla ocean.",
                         "0.08 = rare (~3% of ocean, recommended). 0.0 = disabled entirely. 1.0 = unchanged vanilla-BWG behavior (full ocean coverage).",
                         "Requires Oh The Biomes We've Gone; has no effect without it. Changing this requires regenerating affected chunks.")
                .defineInRange("ocean_formation_humidity_band", 0.08, 0.0, 1.0);

        TEMPERATE_SPAWN_STRENGTH = BUILDER
                .comment("How far the spawn continent's climate is pulled toward temperate (0..1). Tectonic's temperature",
                         "zones are continent-sized, so a whole spawn continent can come out frozen; 1.0 gives it Terra",
                         "Continental's temperate climate (still varied), 0.0 leaves Tectonic's climate alone. Other continents",
                         "are never affected. Biome selection only - terrain is unchanged. Requires Tectonic; new chunks only.")
                .defineInRange("temperate_spawn_strength", 1.0, 0.0, 1.0);
        TEMPERATE_SPAWN_INNER = BUILDER
                .comment("Full-strength radius of the temperate spawn climate, as a multiple of central_land_radius.",
                         "The spawn continent's coast lies well outside central_land_radius itself (~1.3x on the tested seed).")
                .defineInRange("temperate_spawn_inner_factor", 1.5, 0.1, 10.0);
        TEMPERATE_SPAWN_OUTER = BUILDER
                .comment("Radius, as a multiple of central_land_radius, by which the temperate spawn climate has faded out.")
                .defineInRange("temperate_spawn_outer_factor", 2.0, 0.1, 20.0);

        BUILDER.pop();

        BUILDER.push("caves");

        CAVE_DEPTH_FLOOR_VALUE = BUILDER
                .comment("Biome-depth value the BOTTOM of the world should map to, used by terra_continental:cave_depth.",
                         "Vanilla's depth axis puts the underground-biome window (dripstone_caves, lush_caves) at 0.2-0.9,",
                         "surface biomes at 0.0 and 1.0, and deep_dark at 1.1. Mapping the world floor to 1.3 reproduces",
                         "vanilla's banding proportionally: caves mostly inherit their surface biome, dripstone and lush",
                         "appear at real cave depths, and deep_dark is confined to roughly the bottom 15%.",
                         "Raise toward 1.6 for MORE deep_dark (a thicker bottom band); lower toward 1.05 for less.",
                         "Below ~1.05 deep_dark stops generating entirely. Changing this requires regenerating chunks.")
                .defineInRange("cave_depth_floor_value", 1.3, 0.5, 2.5);

        CAVE_DEPTH_REFERENCE_Y = BUILDER
                .comment("Y treated as the 'surface' when measuring the world's vertical span for cave_depth.",
                         "The scale is derived as floor_value * 128 / (reference_y - tectonic min_y), so the banding",
                         "rescales automatically when Tectonic's min_y changes - no hand-edited splines.",
                         "Sea level (63) is the right answer for almost every world; raise it only if your terrain",
                         "sits far above sea level on average and caves feel like they band too high.")
                .defineInRange("cave_depth_reference_y", 63, -2048, 2048);

        CAVE_DEPTH_FALLBACK_MIN_Y = BUILDER
                .comment("Used only if config/tectonic.json cannot be read (Tectonic absent or unparseable).",
                         "Normally min_y is taken live from Tectonic's own config so the two cannot drift apart.")
                .defineInRange("cave_depth_fallback_min_y", -64, -2048, 0);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }
}