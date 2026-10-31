package com.terracontinental.plan;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the world-loading screen says while the server works after the spawn area is ready: a line
 * of text, the spot being checked right now, and the spots that were chosen. Written by the
 * server thread (Terra Towns' spawn-hamlet search posts here through its own bridge, so it needs
 * no compile-time link to this mod), read by the render thread; single player only, same JVM.
 *
 * <p>Plain Java types on purpose: callers reach these methods by reflection.
 */
public final class LoadingStatus {

    private static volatile String message;
    private static volatile int[] probe;
    private static final List<int[]> chosen = new CopyOnWriteArrayList<>();

    private LoadingStatus() {}

    /** The line shown under the map; null for the default. */
    public static void message(String text) {
        message = text;
    }

    /** The block column being checked now. */
    public static void probe(int x, int z) {
        probe = new int[] {x, z};
    }

    /** A checked column that qualified: drawn as a lasting dot. */
    public static void chosen(int x, int z) {
        chosen.add(new int[] {x, z});
        probe = null;
    }

    /** Nothing is being checked (the search moved on to building). */
    public static void clearProbe() {
        probe = null;
    }

    public static void reset() {
        message = null;
        probe = null;
        chosen.clear();
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
}
