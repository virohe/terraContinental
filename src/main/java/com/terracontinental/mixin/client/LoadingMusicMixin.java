package com.terracontinental.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.terracontinental.client.LoadingFade;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The menu music keeps playing while a world loads from the menu. Vanilla stops every sound when the
 * load begins, and its wait loop never ticks the music or the sound engine, so a streamed track would
 * run dry and no next track would start. Here the stop is skipped for a load started from the menu,
 * and the wait loop ticks music and sound at the client tick rate. From 100% the music crossfades
 * into the game's sounds ({@link LoadingFade}); the join's stop-all is skipped while that runs.
 */
@Mixin(Minecraft.class)
public abstract class LoadingMusicMixin {

    @Shadow @Nullable public ClientLevel level;

    @Unique private static boolean terra_continental$loadingFromMenu;
    @Unique private static long terra_continental$lastSoundTick;

    @Inject(method = "doWorldLoad", at = @At("HEAD"))
    private void terra_continental$loadStarts(LevelStorageSource.LevelStorageAccess levelStorage, PackRepository packs,
                                              WorldStem stem, boolean newWorld, CallbackInfo ci) {
        terra_continental$loadingFromMenu = this.level == null;
        terra_continental$lastSoundTick = Util.getMillis();
    }

    @Inject(method = "doWorldLoad", at = @At("RETURN"))
    private void terra_continental$loadEnds(LevelStorageSource.LevelStorageAccess levelStorage, PackRepository packs,
                                            WorldStem stem, boolean newWorld, CallbackInfo ci) {
        // Reached only when the load finished (a seed retry cancels earlier): the crossfade begins.
        if (terra_continental$loadingFromMenu) {
            LoadingFade.start();
        }
        terra_continental$loadingFromMenu = false;
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void terra_continental$joined(ClientLevel level, ReceivingLevelScreen.Reason reason, CallbackInfo ci) {
        LoadingFade.joined();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void terra_continental$tickFade(CallbackInfo ci) {
        LoadingFade.tick((Minecraft) (Object) this);
    }

    @WrapOperation(method = "updateScreenAndTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/SoundManager;stop()V"))
    private void terra_continental$keepMenuMusic(SoundManager sounds, Operation<Void> original) {
        // The load's own disconnect, and the first join while the crossfade runs. With a level already
        // set (leaving a world, changing dimension) vanilla's stop stands.
        if ((terra_continental$loadingFromMenu || LoadingFade.active()) && this.level == null) {
            return;
        }
        original.call(sounds);
    }

    @Inject(method = "doWorldLoad", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/Minecraft;runTick(Z)V"))
    private void terra_continental$tickMusic(LevelStorageSource.LevelStorageAccess levelStorage, PackRepository packs,
                                             WorldStem stem, boolean newWorld, CallbackInfo ci) {
        if (!terra_continental$loadingFromMenu) {
            return;
        }
        long now = Util.getMillis();
        if (now - terra_continental$lastSoundTick < 50L) {
            return;
        }
        terra_continental$lastSoundTick = now;
        Minecraft mc = (Minecraft) (Object) this;
        mc.getMusicManager().tick();
        mc.getSoundManager().tick(false);
    }
}
