package com.terracontinental.client;

import com.mojang.logging.LogUtils;
import com.terracontinental.atlas.WorldAtlasItem;
import com.terracontinental.client.maps.MapReveal;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * A self-driving client check for the map features (dev only: {@code -Dterra_continental.smoke=<world>},
 * the {@code smokeClient} Gradle run). Opens the world, flies up, lets the reveal sketch, opens
 * Xaero's map, the World Atlas and JourneyMap, takes a screenshot of each into
 * {@code run-smoke/screenshots/}, logs what it found ("[smoke]") and quits. Written because the
 * features need a real client and nobody was at the keyboard to drive one (2026-11-16).
 */
public final class SmokeTest {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static String world;
    private static boolean opened;
    private static int t = -1;

    private SmokeTest() {
    }

    public static void init() {
        world = System.getProperty("terra_continental.smoke");
        if (world == null || world.isEmpty()) return;
        LOGGER.info("[smoke] client smoke test for world '{}'", world);
        NeoForge.EVENT_BUS.addListener(SmokeTest::onScreen);
        NeoForge.EVENT_BUS.addListener(SmokeTest::onTick);
    }

    private static void onScreen(ScreenEvent.Opening e) {
        if (!opened && e.getNewScreen() instanceof TitleScreen) {
            opened = true;
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> mc.createWorldOpenFlows().openWorld(world, () -> {
                LOGGER.error("[smoke] couldn't open world {}", world);
                mc.stop();
            }));
        } else if (opened && t < 0 && e.getNewScreen() != null) {
            LOGGER.info("[smoke] screen while loading: {}", e.getNewScreen().getClass().getName());
        }
    }

    private static void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        t++;
        try {
            // Distant Horizons' work in hand every 10 s: a pregenerated world should open with none.
            if (t % 200 == 1 && net.neoforged.fml.ModList.get().isLoaded("distanthorizons")) {
                LOGGER.info("[smoke] t={} distant horizons work: {}", t, com.terracontinental.atlas.DhWork.snapshot());
            }
            step(mc);
        } catch (Throwable ex) {
            LOGGER.error("[smoke] step at t={} failed", t, ex);
        }
    }

    private static void step(Minecraft mc) {
        switch (t) {
            case 0 -> {
                LOGGER.info("[smoke] in world at {}", mc.player.blockPosition());
                // A world made on a server has cheats off: make the player an admin for the checks.
                var server = mc.getSingleplayerServer();
                if (server != null) server.execute(() -> server.getPlayerList().getPlayers()
                        .forEach(sp -> server.getPlayerList().op(sp.getGameProfile())));
            }
            case 200 -> {
                LOGGER.info("[smoke] xaero using world save: {}", xaeroUsingWorldSave());
                command(mc, "gamemode spectator @s");
                command(mc, "time set noon");
                command(mc, "gamerule doDaylightCycle false");
                // Over the land with LODs (terra_continental.smokeAt="x z"), high up.
                String at = System.getProperty("terra_continental.smokeAt", "~ ~");
                command(mc, "tp @s " + at.split(" ")[0] + " 450 " + at.split(" ")[1]);
                shot(mc, "smoke-0-ground");
            }
            case 3700 -> {
                LOGGER.info("[smoke] reveal: {}", MapReveal.debugStatus());
                shot(mc, "smoke-1-view");
            }
            case 3720 -> MapReveal.debugSpyglass();
            case 3900 -> {
                LOGGER.info("[smoke] after spyglass: {}", MapReveal.debugStatus());
                press(mc, "gui.xaero_open_map");
            }
            case 3940, 3945, 3950, 3955, 3960, 3965 -> {
                if (mc.screen != null) mc.screen.mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0,
                        mc.getWindow().getGuiScaledHeight() / 2.0, 0, -1);
            }
            case 4020 -> shot(mc, "smoke-2-xaero");
            case 4030 -> mc.setScreen(null);
            case 4040 -> {
                var server = mc.getSingleplayerServer();
                if (server != null) server.execute(() -> {
                    ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
                    WorldAtlasItem.open(sp);
                });
            }
            case 4340 -> {
                LOGGER.info("[smoke] screen with the atlas: {}", mc.screen == null ? null : mc.screen.getClass().getName());
                shot(mc, "smoke-3-atlas");
            }
            case 4350 -> mc.setScreen(null);
            case 4360 -> press(mc, "key.journeymap.map_toggle_alt");
            case 4560 -> shot(mc, "smoke-4-journeymap");
            case 4570 -> mc.setScreen(null);
            case 4580 -> {
                LOGGER.info("[smoke] final reveal: {}", MapReveal.debugStatus());
                LOGGER.info("[smoke] done; quitting");
                mc.level.disconnect();
                mc.disconnect();
                mc.stop();
            }
            default -> {
            }
        }
    }

    private static void command(Minecraft mc, String cmd) {
        mc.player.connection.sendCommand(cmd);
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(),
                c -> LOGGER.info("[smoke] screenshot {}: {}", name, c.getString()));
    }

    /** Presses the key binding with this name (opening a mod's map). */
    private static void press(Minecraft mc, String name) {
        for (KeyMapping k : mc.options.keyMappings) {
            if (k.getName().equals(name) && !k.isUnbound()) {
                LOGGER.info("[smoke] pressing {} ({})", k.getName(), k.getKey().getName());
                KeyMapping.click(k.getKey());
                return;
            }
        }
        LOGGER.warn("[smoke] no key binding {}", name);
    }

    private static Object xaeroUsingWorldSave() {
        try {
            Class<?> s = Class.forName("xaero.map.WorldMapSession");
            Object session = s.getMethod("getCurrentSession").invoke(null);
            Object mp = s.getMethod("getMapProcessor").invoke(session);
            Object mw = mp.getClass().getMethod("getMapWorld").invoke(mp);
            Object dim = mw.getClass().getMethod("getCurrentDimension").invoke(mw);
            return dim.getClass().getMethod("isUsingWorldSave").invoke(dim);
        } catch (Throwable ex) {
            return "unknown (" + ex + ")";
        }
    }
}
