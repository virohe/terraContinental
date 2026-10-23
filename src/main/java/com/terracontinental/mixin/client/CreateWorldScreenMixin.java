package com.terracontinental.mixin.client;

import com.terracontinental.worldtype.DefaultWorldType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks a fresh Create World screen, so its starting world type can be TC: Large Biomes. */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {

    @Inject(method = "openFresh", at = @At("HEAD"))
    private static void terraContinental$freshStart(Minecraft minecraft, Screen lastScreen, CallbackInfo ci) {
        DefaultWorldType.OPENING_FRESH.set(true);
    }

    @Inject(method = "openFresh", at = @At("RETURN"))
    private static void terraContinental$freshEnd(Minecraft minecraft, Screen lastScreen, CallbackInfo ci) {
        DefaultWorldType.OPENING_FRESH.remove();
    }
}
