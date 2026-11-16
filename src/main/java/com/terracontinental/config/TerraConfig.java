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
    public static final ModConfigSpec.BooleanValue SPAWN_STEERING;
    public static final ModConfigSpec.DoubleValue SPAWN_TARGET_AREA;
    public static final ModConfigSpec.DoubleValue SPAWN_GAP_MIN;
    public static final ModConfigSpec.DoubleValue SPAWN_GAP_MAX;
    public static final ModConfigSpec.IntValue SPAWN_CANDIDATES;
    public static final ModConfigSpec.DoubleValue SPAWN_MAX_OTHER_LAND;
    public static final ModConfigSpec.IntValue SPAWN_ROUNDS;
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
    public static final ModConfigSpec.IntValue TREE_LINE_GAP;
    public static final ModConfigSpec.IntValue STRUCTURE_LINE_GAP;
    public static final ModConfigSpec.IntValue DRY_START_MAX_WATER;

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
                .comment("Radius of the spawn-continent bump in blocks without steering; with spawn_steering, the scale",
                         "the layouts are drawn at before they are sized to spawn_target_area. Default: 14500")
                .defineInRange("central_land_radius", 14500, 100, 100000);

        SPAWN_STEERING = BUILDER
                .comment("Steer the spawn continent: try spawn_candidates layouts on a preview of the seed's land",
                         "and keep the one closest to the size and neighbour targets below. Off = a round dome of",
                         "central_land_radius at the origin, with the other continents where the seed puts them.")
                .define("spawn_steering", true);
        SPAWN_TARGET_AREA = BUILDER
                .comment("Spawn continent's land area to aim for, in km2, as the loading map draws land (steering aims",
                         "within about 15%). Default: 290")
                .defineInRange("spawn_target_area", 290.0, 20.0, 5000.0);
        SPAWN_GAP_MIN = BUILDER
                .comment("Least distance in km from the spawn continent's coast to the nearest other continent's. Default: 3")
                .defineInRange("spawn_gap_min", 3.0, 0.0, 50.0);
        SPAWN_GAP_MAX = BUILDER
                .comment("Greatest distance in km from the spawn continent's coast to the nearest other continent's. Default: 6")
                .defineInRange("spawn_gap_max", 6.0, 0.0, 50.0);
        SPAWN_CANDIDATES = BUILDER
                .comment("Layouts tried per world when steering. More finds a closer fit, taking longer at world creation.",
                         "Default: 48")
                .defineInRange("spawn_candidates", 48, 1, 512);
        SPAWN_MAX_OTHER_LAND = BUILDER
                .comment("Most of the loading map (65 km across) that may be land besides the spawn continent, in %.",
                         "Default: 15")
                .defineInRange("spawn_max_other_land", 15.0, 0.0, 100.0);
        SPAWN_ROUNDS = BUILDER
                .comment("When no layout meets every target, rounds that try spawn_candidates more layouts on the",
                         "seed's noise shifted elsewhere (a different continent shape, same seed). 1 = no retries.",
                         "Default: 8")
                .defineInRange("spawn_rounds", 8, 1, 64);

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
                .comment("How far the spawn continent's climate is pulled toward temperate (0..1). The temperature",
                         "zones are continent-sized, so a whole spawn continent can come out frozen; 1.0 gives it a",
                         "temperate climate (still varied), 0.0 leaves its climate alone. Other continents are never",
                         "affected. Biome selection only - terrain is unchanged. New chunks only.")
                .defineInRange("temperate_spawn_strength", 1.0, 0.0, 1.0);
        TEMPERATE_SPAWN_INNER = BUILDER
                .comment("Full-strength radius of the temperate spawn climate, as a multiple of central_land_radius.",
                         "The spawn continent's coast lies around 0.8-1.1x central_land_radius.")
                .defineInRange("temperate_spawn_inner_factor", 1.1, 0.1, 10.0);
        TEMPERATE_SPAWN_OUTER = BUILDER
                .comment("Radius, as a multiple of central_land_radius, by which the temperate spawn climate has faded out.")
                .defineInRange("temperate_spawn_outer_factor", 1.5, 0.1, 20.0);

        BUILDER.pop();

        BUILDER.push("caves");

        CAVE_DEPTH_FLOOR_VALUE = BUILDER
                .comment("Biome-depth value the BOTTOM of the world maps to, for terra_continental:cave_depth_scale",
                         "(scale = floor_value * 128 / (reference_y - world floor); the default gives 0.42).",
                         "Vanilla's depth axis puts the underground-biome window (dripstone_caves, lush_caves) at 0.2-0.9,",
                         "surface biomes at 0.0 and 1.0, and deep_dark at 1.1; below y 32 the value is capped at 1.6.",
                         "Lower it for less deep_dark and deeper dripstone and lush caves; below ~1.05 deep_dark stops",
                         "generating entirely. Changing this requires regenerating chunks. Default: 1.88671875")
                .defineInRange("cave_depth_floor_value", 1.88671875, 0.5, 2.5);

        CAVE_DEPTH_REFERENCE_Y = BUILDER
                .comment("Y treated as the 'surface' when measuring the world's vertical span for cave_depth_scale.",
                         "The scale is derived as floor_value * 128 / (reference_y - world floor), so the banding",
                         "follows the world floor (-512) - no hand-edited splines.",
                         "Sea level (63) is the right answer for almost every world; raise it only if your terrain",
                         "sits far above sea level on average and caves feel like they band too high.")
                .defineInRange("cave_depth_reference_y", 63, -2048, 2048);

        BUILDER.pop();

        BUILDER.push("alpine");

        TREE_LINE_GAP = BUILDER
                .comment("How far below the snow line (terra_continental:alpine/snow_line in the terrain pack: y 500,",
                         "wandering +-40) worldgen trees stop, thinning out over 20 blocks. New chunks only.")
                .defineInRange("tree_line_gap", 60, 0, 1000);
        STRUCTURE_LINE_GAP = BUILDER
                .comment("How far below the snow line surface structures stop starting. New chunks only.")
                .defineInRange("structure_line_gap", 60, 0, 1000);
        DRY_START_MAX_WATER = BUILDER
                .comment("Land surface structures (no ocean, beach, river or swamp biome in their list) don't start",
                         "where the ground is more than this many blocks under sea level. -1 turns the check off.",
                         "New chunks only.")
                .defineInRange("dry_start_max_water", 1, -1, 64);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }
}