package com.terracontinental.alpine;

import com.terracontinental.config.TerraConfig;
import com.terracontinental.plan.WorldPlan;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.jetbrains.annotations.Nullable;

/**
 * The snow line, and the tree and structure lines below it (playtest 2026-10-31: snow came with
 * the peak biomes as a palette from y ~250, trees grew to y ~1,000). One field,
 * terra_continental:alpine/snow_line in the terrain pack (y 500, wandering +-40 over ~1.5 km), is read
 * by the biome router (peak biomes only above it), by snowfall ({@code BiomeSnowLineMixin}), by the
 * alpine surface pass, and by the tree and structure lines here. Set per overworld as it loads.
 */
public final class Alpine {

    public static final ResourceKey<DensityFunction> SNOW_LINE = ResourceKey.create(Registries.DENSITY_FUNCTION,
            ResourceLocation.fromNamespaceAndPath("terra_continental", "alpine/snow_line"));
    /** The field's base, for a caller that reads it before a world loads (others check {@link #active()}). */
    public static final double FALLBACK = 500.0;

    @Nullable
    private static volatile DensityFunction snowLine;

    private Alpine() {
    }

    /** Called as the overworld loads; a world without the field (not a TC terrain) gets the fallback. */
    public static void load(ServerLevel level) {
        snowLine = null;
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator)) return;
        level.registryAccess().registryOrThrow(Registries.DENSITY_FUNCTION).getHolder(SNOW_LINE).ifPresent(h ->
                snowLine = WorldPlan.seeded(h.value(), level.getChunkSource().randomState()));
    }

    /** True once a TC overworld with the snow-line field has loaded. */
    public static boolean active() {
        return snowLine != null;
    }

    public static double snowLine(int x, int z) {
        DensityFunction f = snowLine;
        return f == null ? FALLBACK : f.compute(new DensityFunction.SinglePointContext(x, 0, z));
    }

    /** Trees stop {@code tree_line_gap} below the snow line, thinning out over 20 blocks. */
    public static boolean treeAllowed(int x, int y, int z) {
        double line = snowLine(x, z) - TerraConfig.TREE_LINE_GAP.get();
        if (y < line - 10) return true;
        if (y >= line + 10) return false;
        // The last 20 blocks: a fixed per-column chance, so the edge is ragged, not a contour.
        long h = (x * 3129871L) ^ (z * 116129781L);
        h = h * h * 42317861L + h * 11L;
        double r = ((h >> 16) & 0xFFFF) / 65535.0;
        return r > (y - (line - 10)) / 20.0;
    }

    /** No surface structure starts at or above {@code structure_line_gap} below the snow line. */
    public static boolean structureAllowed(int x, int y, int z) {
        return y < snowLine(x, z) - TerraConfig.STRUCTURE_LINE_GAP.get();
    }
}
