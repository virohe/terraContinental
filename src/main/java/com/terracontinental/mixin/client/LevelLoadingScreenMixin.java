package com.terracontinental.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.terracontinental.client.PlanMapRenderer;
import com.terracontinental.plan.PlanPreviews;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.progress.StoringChunkProgressListener;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * World loading shows the world plan's map filling in instead of the chunk-status grid (user
 * request, 2026-10-30). WrapOperation rather than a redirect, so another mod wrapping the same call
 * still chains; without a plan (non-TC overworld, or a dedicated server's client) vanilla's grid draws.
 */
@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenMixin extends Screen {

    @Shadow @Final private StoringChunkProgressListener progressListener;

    protected LevelLoadingScreenMixin(Component title) {
        super(title);
    }

    @WrapOperation(method = "render", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/LevelLoadingScreen;renderChunks(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/server/level/progress/StoringChunkProgressListener;IIII)V"))
    private void terra_continental$drawPlan(GuiGraphics g, StoringChunkProgressListener listener, int x, int y,
                                            int cellSize, int spacing, Operation<Void> original) {
        if (!PlanMapRenderer.draw(g, x, y, width, height, listener.getProgress())) {
            original.call(g, listener, x, y, cellSize, spacing);
        }
    }

    @ModifyArg(method = "render", require = 0, index = 3, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawCenteredString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
    private int terra_continental$textAboveMap(int y) {
        return PlanMapRenderer.textY(y);
    }

    @Inject(method = "removed", at = @At("TAIL"), require = 0)
    private void terra_continental$releasePlan(CallbackInfo ci) {
        PlanMapRenderer.close();
        PlanPreviews.release();
        com.terracontinental.plan.LoadingStatus.reset();
    }
}
