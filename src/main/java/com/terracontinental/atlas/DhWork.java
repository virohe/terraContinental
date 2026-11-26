package com.terracontinental.atlas;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * How much work Distant Horizons (3.3) has in hand: the tasks queued and running in its own thread
 * pools for world generation, building LODs from chunks, saving them, and propagating them to the
 * coarser detail levels, plus how many it has finished. Its pregen command reports done while the
 * coarser levels are still being built in the background, which a player joining then sees as LODs
 * filling in and a busy game for minutes; {@link PregenJob} waits until this goes quiet instead.
 *
 * <p>Read by reflection from DH's internal {@code ThreadPoolUtil}: nothing in its public API says
 * this. If the classes aren't there (another DH version) {@link #snapshot} returns null and the
 * pregen ends when DH's own pregen does.</p>
 */
public final class DhWork {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String[] POOLS = {"getWorldGenExecutor", "getChunkToLodBuilderExecutor",
            "getFileHandlerExecutor", "getUpdatePropagatorExecutor"};
    private static Method[] getters;
    private static boolean broken;

    /** Pending (queued + running) and completed task counts, summed over the pools. */
    public record Snapshot(long pending, long completed) {
    }

    private DhWork() {
    }

    @Nullable
    public static Snapshot snapshot() {
        if (broken) return null;
        try {
            if (getters == null) {
                Class<?> util = Class.forName("com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil");
                Method[] g = new Method[POOLS.length];
                for (int i = 0; i < POOLS.length; i++) g[i] = util.getMethod(POOLS[i]);
                getters = g;
            }
            long pending = 0, completed = 0;
            for (Method g : getters) {
                Object ex = g.invoke(null);
                if (ex == null) continue;
                Class<?> c = ex.getClass();
                pending += ((Number) c.getMethod("getQueueSize").invoke(ex)).longValue();
                pending += ((Number) c.getMethod("getRunningTaskCount").invoke(ex)).longValue();
                completed += ((Number) c.getMethod("getCompletedTaskCount").invoke(ex)).longValue();
            }
            return new Snapshot(pending, completed);
        } catch (Throwable t) {
            broken = true;
            LOGGER.warn("[pregen] can't see Distant Horizons' work queues ({}); the pregen ends with DH's own", t.toString());
            return null;
        }
    }
}
