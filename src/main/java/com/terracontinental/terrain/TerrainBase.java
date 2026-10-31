package com.terracontinental.terrain;

/**
 * Terrain values applied from Java (they were Tectonic's config/tectonic.json settings until Terra
 * Continental absorbed its terrain, 2026-10-30). The data-side values (heights, scales, ocean
 * depths) are in {@code resourcepacks/terrain}; keep these in step with it.
 */
public final class TerrainBase {

    /** Overworld floor, as in the terrain pack's noise_settings and dimension_type. */
    public static final int MIN_Y = -512;

    /** Noise settings that carry Terra Continental's terrain: vanilla's overworld and the TC world types. */
    public static final java.util.Set<String> OVERWORLD_SETTINGS = java.util.Set.of(
            "minecraft:overworld", "terra_continental:overworld_large", "terra_continental:overworld_climates");

    /** Monuments sit lower to suit the ~400-block ocean floor. */
    public static final int MONUMENT_OFFSET = -30;

    private TerrainBase() {}
}
