package com.terracontinental.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client settings: how the map mods treat the pregenerated world (user, 2026-11-16). The pack ships
 * Xaero's World Map as an explorer's map: it shows what you have seen, not what was pregenerated.
 * JourneyMap, when installed, is the debug view of everything generated.
 */
public final class TerraClientConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue XAERO_EXPLORE_ONLY;
    public static final ModConfigSpec.BooleanValue JOURNEYMAP_DEBUG_AUTOMAP;
    public static final ModConfigSpec.BooleanValue REVEAL;
    public static final ModConfigSpec.IntValue REVEAL_BASE_RANGE;
    public static final ModConfigSpec.DoubleValue REVEAL_HEIGHT_FACTOR;
    public static final ModConfigSpec.IntValue REVEAL_MAX_RANGE;
    public static final ModConfigSpec.DoubleValue SPYGLASS_DWELL_SECONDS;
    public static final ModConfigSpec.IntValue REVEAL_CHUNKS_PER_TICK;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("maps");
        XAERO_EXPLORE_ONLY = b
                .comment("Xaero's World Map only maps chunks you have loaded. Off: in single-player Xaero reads the",
                        "world save and maps every generated chunk, pregenerated ones included.")
                .define("xaero_explore_only", true);
        JOURNEYMAP_DEBUG_AUTOMAP = b
                .comment("With JourneyMap installed, single-player worlds are auto-mapped from the save on joining",
                        "(regions not mapped yet): JourneyMap becomes a debug view of all generated terrain.")
                .define("journeymap_debug_automap", true);
        b.pop();
        b.push("reveal");
        REVEAL = b
                .comment("Sketch distant land you can see into Xaero's World Map from Distant Horizons' LODs: coarse",
                        "(32 blocks a pixel) from where you stand, finer (8 blocks a pixel) where you look through a",
                        "spyglass. Walking there fills it in at full detail.")
                .define("reveal", true);
        REVEAL_BASE_RANGE = b
                .comment("How far you see standing on flat ground, in blocks.")
                .defineInRange("base_range", 512, 0, 16384);
        REVEAL_HEIGHT_FACTOR = b
                .comment("Extra range per square root of your height above the land around you: range =",
                        "base_range + height_factor * sqrt(height). 160 gives ~1.6 km from 50 m up, ~4.4 km from 600 m.")
                .defineInRange("height_factor", 160.0, 0.0, 2000.0);
        REVEAL_MAX_RANGE = b
                .comment("Farthest the sketch reaches, in blocks (also limited by the LODs you have).")
                .defineInRange("max_range", 8192, 256, 65536);
        SPYGLASS_DWELL_SECONDS = b
                .comment("Seconds of looking through a spyglass before land 1 km away sharpens (more further off).")
                .defineInRange("spyglass_dwell_seconds", 1.5, 0.0, 60.0);
        REVEAL_CHUNKS_PER_TICK = b
                .comment("Map chunks written per client tick at most (keeps frames smooth).")
                .defineInRange("chunks_per_tick", 48, 1, 4096);
        b.pop();
        SPEC = b.build();
    }

    private TerraClientConfig() {
    }

    public static boolean get(ModConfigSpec.BooleanValue v, boolean fallback) {
        try {
            return SPEC.isLoaded() ? v.get() : fallback;
        } catch (Throwable e) {
            return fallback;
        }
    }
}
