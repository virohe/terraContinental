package com.terracontinental.plan;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the world-loading screen says while the server works after the spawn area is ready: a line
 * of text, the spot being checked right now, the spots that were chosen and built, and the
 * planned villages. Written by the
 * server thread (Terra Towns' spawn-hamlet search posts here through its own bridge, so it needs
 * no compile-time link to this mod), read by the render thread; single player only, same JVM.
 *
 * <p>Plain Java types on purpose: callers reach these methods by reflection.
 */
public final class LoadingStatus {

    private static volatile String message;
    private static volatile int[] probe;
    private static final List<int[]> chosen = new CopyOnWriteArrayList<>();
    private static final List<int[]> built = new CopyOnWriteArrayList<>();
    private static final List<int[]> villages = new CopyOnWriteArrayList<>();
    private static final List<int[]> buildOrder = new CopyOnWriteArrayList<>();

    private LoadingStatus() {}

    /** The line shown under the map; null for the default. */
    public static void message(String text) {
        message = text;
    }

    /** The block column being checked now. */
    public static void probe(int x, int z) {
        probe = new int[] {x, z};
    }

    /** A checked column that qualified: drawn as a lasting hollow marker until it's built. */
    public static void chosen(int x, int z) {
        chosen.add(new int[] {x, z});
        probe = null;
    }

    /**
     * A chosen spot now has its hamlet: its marker fills in. Found and built look different (the
     * user, 2026-10-31); a spot that was never reported as chosen is added as built.
     */
    public static void built(int x, int z) {
        chosen.removeIf(c -> c[0] == x && c[1] == z);
        built.add(new int[] {x, z});
        int[] p = probe;
        if (p != null && p[0] == x && p[1] == z) probe = null;
    }

    /**
     * A hamlet is being built here now: the map zooms in on it until it's {@link #built}, then back
     * out (the user, 2026-10-31). Kept in order so the zoom can play them one after another even
     * when the building runs ahead of the animation.
     */
    public static void building(int x, int z) {
        probe = new int[] {x, z};
        buildOrder.add(new int[] {x, z});
    }

    public static List<int[]> buildOrder() {
        return buildOrder;
    }

    public static boolean isBuilt(int x, int z) {
        for (int[] b : built) {
            if (b[0] == x && b[1] == z) return true;
        }
        return false;
    }

    /** A village the spawn-village planner placed. */
    public static void village(int x, int z) {
        villages.add(new int[] {x, z});
    }

    /** Nothing is being checked (the search moved on to building). */
    public static void clearProbe() {
        probe = null;
    }

    public static void reset() {
        message = null;
        probe = null;
        chosen.clear();
        built.clear();
        villages.clear();
        buildOrder.clear();
    }

    public static String message() {
        return message;
    }

    public static int[] currentProbe() {
        return probe;
    }

    public static List<int[]> chosenSpots() {
        return chosen;
    }

    public static List<int[]> builtSpots() {
        return built;
    }

    public static List<int[]> villageSpots() {
        return villages;
    }
}
