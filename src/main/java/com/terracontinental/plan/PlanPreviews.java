package com.terracontinental.plan;

import com.terracontinental.terrain.TerrainBase;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

import javax.annotation.Nullable;

/**
 * Hands out the plan preview for a running server. The loading screen runs in the same JVM as the
 * integrated server, so it asks for the server's overworld directly; a dedicated server's clients
 * get nothing (that would need a packet) and keep vanilla's chunk grid.
 */
public final class PlanPreviews {

    private PlanPreviews() {}

    /** The overworld's world plan (started on first call); null if there's none (yet). */
    @Nullable
    public static PlanPreview forServer(@Nullable MinecraftServer s) {
        if (s == null) return null;
        ServerLevel overworld;
        try {
            // Read from the render thread while the server thread builds its levels: the
            // overworld simply isn't there for the first frames.
            overworld = s.getLevel(Level.OVERWORLD);
        } catch (RuntimeException e) {
            return null;
        }
        return overworld == null ? null : WorldPlan.get(overworld);
    }

    /**
     * True if this server's overworld will carry a plan, known from its registries before the
     * level exists. The loading screen draws the empty map frame meanwhile, so it doesn't open on
     * vanilla's small grid and snap to the map a few frames later (the user, 2026-10-31).
     */
    public static boolean expected(@Nullable MinecraftServer s) {
        if (s == null) return false;
        try {
            LevelStem stem = s.registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(LevelStem.OVERWORLD);
            return stem != null && stem.generator() instanceof NoiseBasedChunkGenerator gen
                    && gen.generatorSettings().unwrapKey()
                            .map(k -> TerrainBase.OVERWORLD_SETTINGS.contains(k.location().toString())).orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Called when the loading screen closes. The world plan keeps going: the village planner and
     * later the rivers read it after loading.
     */
    public static void release() {}
}
