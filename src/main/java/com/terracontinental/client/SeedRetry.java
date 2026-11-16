package com.terracontinental.client;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import com.terracontinental.mixin.MinecraftServerAccessor;
import com.terracontinental.mixin.client.CreateWorldScreenInvoker;
import com.terracontinental.plan.LoadingStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * "Try a New Seed" on the world-loading map (the user, 2026-11-10): a player who doesn't like the
 * spawn continent's shape, or whose villages or hamlets came out badly spread, throws the world
 * away while it is still loading and gets the same world again (name, game mode, rules, world
 * type, data packs) on a random seed.
 *
 * <p>Only for a world being created in this load: an existing save is never offered for deletion.
 * The steps: stop the integrated server by clearing its running flag (the planners and Terra
 * Towns' hamlet search check {@code isRunning()} and give up), let vanilla's load loop end once the
 * server thread is gone ({@code MinecraftWorldLoadMixin}), then delete the world and run the
 * Create World screen's own create path on a re-created copy of its settings, as vanilla's
 * "Re-Create" does.</p>
 */
@EventBusSubscriber(modid = com.terracontinental.TerraContinental.MOD_ID, value = Dist.CLIENT)
public final class SeedRetry {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile String levelId;
    private static volatile boolean newWorld;
    /** A discarded world's id, waiting for the next client tick to be made again. */
    private static volatile String pendingRecreate;

    private SeedRetry() {}

    /** A world load is starting (vanilla's doWorldLoad). */
    public static void begin(String id, boolean fresh) {
        LoadingStatus.reset();
        levelId = id;
        newWorld = fresh;
    }

    /** Is this load creating its world? (Only then are there starting hamlets to wait for.) */
    public static boolean isNewWorld() {
        return newWorld;
    }

    /** Can this load be thrown away? Only a world created just now, and only once. */
    public static boolean available() {
        return newWorld && levelId != null && !LoadingStatus.retryRequested();
    }

    public static boolean requested() {
        return newWorld && LoadingStatus.retryRequested();
    }

    /** The player confirmed: stop the server. Its thread winds down on its own. */
    public static void confirm() {
        if (!available()) return;
        LOGGER.info("[seed-retry] discarding world '{}' to try a new seed", levelId);
        LoadingStatus.requestRetry();
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) ((MinecraftServerAccessor) server).terra_continental$setRunning(false);
    }

    /** True once a retried world's server thread has finished (saved and closed its files). */
    public static boolean serverGone(IntegratedServer server) {
        return requested() && !server.getRunningThread().isAlive();
    }

    /** Vanilla's load loop has ended for a retry: leave the dead server and make the world again. */
    public static void restart(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server != null && server.getRunningThread().isAlive()) {
            // It became ready in the same instant as the click: stop it the same way, and wait.
            ((MinecraftServerAccessor) server).terra_continental$setRunning(false);
            try {
                server.getRunningThread().join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        String id = levelId;
        levelId = null;
        newWorld = false;
        mc.disconnect(new GenericMessageScreen(Component.literal("Clearing this world for a new seed")));
        // Made again on the next client tick, after vanilla's doWorldLoad has returned, instead of
        // nesting a second world load inside the first one. Not with mc.tell: a world loaded from a
        // queued task runs its whole load loop inside that task, where Minecraft queues every mouse
        // click as another task, and the load loop (runTick(false)) runs no tasks, so the loading
        // screen's buttons answered only once the world had loaded (playtest 2026-11-10: the second
        // "Try a New Seed" did nothing until the world was ready). A client tick isn't a task.
        pendingRecreate = id;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        String id = pendingRecreate;
        if (id == null) return;
        pendingRecreate = null;
        recreate(Minecraft.getInstance(), id);
    }

    private static void recreate(Minecraft mc, String id) {
        LevelSettings settings;
        WorldCreationContext context;
        Path packs;
        try (LevelStorageSource.LevelStorageAccess access = mc.getLevelSource().validateAndCreateAccess(id)) {
            Pair<LevelSettings, WorldCreationContext> data = mc.createWorldOpenFlows().recreateWorldData(access);
            settings = data.getFirst();
            context = data.getSecond();
            packs = CreateWorldScreen.createTempDataPackDirFromExistingWorld(access.getLevelPath(LevelResource.DATAPACK_DIR), mc);
            try {
                access.deleteLevel();
            } catch (Exception e) {
                // Something still holds a file. The new world then gets its own folder name.
                LOGGER.warn("[seed-retry] couldn't delete the discarded world '{}'; delete it from the world list", id, e);
            }
        } catch (Exception e) {
            LOGGER.error("[seed-retry] couldn't read the discarded world '{}' back", id, e);
            mc.setScreen(new AlertScreen(() -> mc.setScreen(new SelectWorldScreen(new TitleScreen())),
                    Component.literal("Couldn't start a new seed"),
                    Component.literal("The world was stopped, but its settings couldn't be read back. "
                            + "Create a new world from the world list.")));
            return;
        }
        // Built after the delete, so the new world takes the same folder name.
        CreateWorldScreen screen = CreateWorldScreen.createFromExisting(mc, new SelectWorldScreen(new TitleScreen()),
                settings, context, packs);
        mc.setScreen(screen);
        screen.getUiState().setSeed(""); // empty = a random seed, as on a fresh Create World screen
        LOGGER.info("[seed-retry] creating '{}' again on seed {}", settings.levelName(),
                screen.getUiState().getSettings().options().seed());
        ((CreateWorldScreenInvoker) screen).terra_continental$create();
    }
}
