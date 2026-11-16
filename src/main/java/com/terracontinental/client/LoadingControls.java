package com.terracontinental.client;

import com.terracontinental.TerraContinental;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * The "Try a New Seed" button under the loading map, and its confirmation (the user, 2026-11-10).
 * A click doesn't discard anything yet: the button turns into "Keep This World" where it stands, and
 * the button that really discards appears somewhere else, so a double click, or a second click
 * where the first one was, keeps the world. {@link PlanMapRenderer} places and shows them each frame.
 */
@EventBusSubscriber(modid = TerraContinental.MOD_ID, value = Dist.CLIENT)
public final class LoadingControls {

    static final int BUTTON_H = 20, TRY_W = 120, CONFIRM_W = 150;

    private static final Component TRY = Component.literal("Try a New Seed");
    private static final Component KEEP = Component.literal("Keep This World");
    private static final Component DISCARD = Component.literal("Discard and Retry").withStyle(ChatFormatting.RED);

    /** Asking "are you sure?"; kept across re-inits (a window resize builds new buttons). */
    static boolean confirming;
    static Button tryButton, confirmButton;

    private LoadingControls() {}

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof LevelLoadingScreen)) return;
        tryButton = Button.builder(TRY, b -> {
            confirming = !confirming;
            b.setMessage(confirming ? KEEP : TRY);
        }).size(TRY_W, BUTTON_H).build();
        tryButton.setMessage(confirming ? KEEP : TRY);
        confirmButton = Button.builder(DISCARD, b -> {
            confirming = false;
            SeedRetry.confirm();
        }).size(CONFIRM_W, BUTTON_H).build();
        tryButton.visible = confirmButton.visible = false; // until the map is drawn
        event.addListener(tryButton);
        event.addListener(confirmButton);
    }

    /** The screen is gone: forget the buttons and any half-made confirmation. */
    public static void reset() {
        confirming = false;
        tryButton = confirmButton = null;
    }
}
