package com.terracontinental.client.maps;

import com.mojang.logging.LogUtils;
import com.terracontinental.config.TerraClientConfig;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

/**
 * JourneyMap as the debug map (user, 2026-11-16: the map mod that doesn't ship "will show all of
 * the pregenerated terrain as a debug source"). In single-player, a few seconds after joining, it
 * runs JourneyMap's own "Automap: missing regions" over the save, the same as its Actions menu.
 * Only loaded when JourneyMap is installed; JourneyMap 6.0.9's classes are called directly.
 */
public final class JourneyMapDebug {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DELAY_TICKS = 200;
    private static int countdown = -1;

    private JourneyMapDebug() {
    }

    public static boolean available() {
        return ModList.get().isLoaded("journeymap");
    }

    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn e) {
        countdown = available() && TerraClientConfig.get(TerraClientConfig.JOURNEYMAP_DEBUG_AUTOMAP, true)
                && Minecraft.getInstance().isLocalServer() ? DELAY_TICKS : -1;
    }

    public static void onClientTick(ClientTickEvent.Post e) {
        if (countdown < 0 || --countdown > 0) return;
        countdown = -1;
        start();
    }

    /** Maps every region in the save that JourneyMap hasn't mapped yet. */
    public static void start() {
        try {
            journeymap.client.JourneymapClient jm = journeymap.client.JourneymapClient.getInstance();
            if (jm == null || !jm.isMapping()) {
                LOGGER.info("[debug-map] JourneyMap isn't mapping yet; skipping the automap");
                return;
            }
            journeymap.client.task.multi.MapRegionTask.MAP_TYPE = null;
            jm.toggleTask(journeymap.client.task.multi.MapRegionTask.Manager.class, true, Boolean.FALSE);
            LOGGER.info("[debug-map] JourneyMap is mapping the saved regions it hasn't mapped (the debug map)");
        } catch (Throwable t) {
            LOGGER.warn("[debug-map] couldn't start JourneyMap's automap: {}", t.toString());
        }
    }
}
