package com.terracontinental.mixin.client;

import com.terracontinental.client.LoadingFade;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every sound's volume passes through here; {@link LoadingFade} scales it during the load crossfade. */
@Mixin(SoundEngine.class)
public abstract class SoundVolumeFadeMixin {

    @Inject(method = "calculateVolume(FLnet/minecraft/sounds/SoundSource;)F", at = @At("RETURN"), cancellable = true)
    private void terra_continental$fade(float multiplier, SoundSource source, CallbackInfoReturnable<Float> cir) {
        if (LoadingFade.active()) {
            cir.setReturnValue(LoadingFade.scale(cir.getReturnValueF(), source));
        }
    }
}
