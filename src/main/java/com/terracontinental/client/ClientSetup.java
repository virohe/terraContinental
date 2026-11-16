package com.terracontinental.client;

import com.terracontinental.client.maps.JourneyMapDebug;
import com.terracontinental.client.maps.MapReveal;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only listeners; called from the mod constructor on the physical client. */
public final class ClientSetup {
    private ClientSetup() {
    }

    public static void init() {
        SmokeTest.init();
        if (MapReveal.available()) {
            NeoForge.EVENT_BUS.addListener(MapReveal::onLogin);
            NeoForge.EVENT_BUS.addListener(MapReveal::onLogout);
            NeoForge.EVENT_BUS.addListener(MapReveal::onClientTick);
        }
        if (JourneyMapDebug.available()) {
            NeoForge.EVENT_BUS.addListener(JourneyMapDebug::onLogin);
            NeoForge.EVENT_BUS.addListener(JourneyMapDebug::onClientTick);
        }
    }
}
