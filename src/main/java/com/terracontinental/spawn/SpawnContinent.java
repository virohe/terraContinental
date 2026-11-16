package com.terracontinental.spawn;

import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * The spawn continent's plan in plain types, for Terra Towns, which doesn't compile against Terra
 * Continental and reaches these by reflection: where the coast runs and where the villages go, so its
 * starting hamlets can ring the coast and keep their distance from the villages (the user, 2026-10-31).
 */
public final class SpawnContinent {
    private SpawnContinent() {
    }

    /** {@link SpawnVillagePlanner.Plan#coast()}; null when the planner is off or found no continent. */
    @Nullable
    public static int[] coast(ServerLevel level) {
        SpawnVillagePlanner.Plan p = SpawnVillagePlanner.enabled() ? SpawnVillagePlanner.get(level) : null;
        if (p == null) return null;
        int[] c = p.coast();
        return c.length == 0 ? null : c;
    }

    /** The planned villages' middles, {x, z} each, the central village first; null when the planner is off. */
    @Nullable
    public static int[] villages(ServerLevel level) {
        SpawnVillagePlanner.Plan p = SpawnVillagePlanner.enabled() ? SpawnVillagePlanner.get(level) : null;
        if (p == null) return null;
        int[] out = new int[p.sites.size() * 2];
        int i = 0;
        for (SpawnVillagePlanner.Site s : p.sites.values()) {
            out[i++] = s.chunk().getMiddleBlockX();
            out[i++] = s.chunk().getMiddleBlockZ();
        }
        return out;
    }
}
