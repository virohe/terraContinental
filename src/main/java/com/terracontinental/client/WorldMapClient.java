package com.terracontinental.client;

import com.terracontinental.atlas.WorldMapPayload;
import net.minecraft.client.Minecraft;

/** Client side of the World Atlas payload; only loaded on the client. */
public final class WorldMapClient {
    private WorldMapClient() {
    }

    public static void open(WorldMapPayload payload) {
        Minecraft.getInstance().setScreen(new WorldMapScreen(payload));
    }
}
