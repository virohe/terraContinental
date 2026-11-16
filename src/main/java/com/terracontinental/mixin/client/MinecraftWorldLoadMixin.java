package com.terracontinental.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.terracontinental.client.SeedRetry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.WorldStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * "Try a New Seed" on the loading map (the user, 2026-11-10). Vanilla's load waits in a loop until
 * the integrated server is ready and has no way out; a server stopped before then would leave the
 * screen waiting forever. So the loop also ends once a retried world's server thread has finished,
 * and the join that follows is skipped for {@link SeedRetry#restart}.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftWorldLoadMixin {

    @Inject(method = "doWorldLoad", at = @At("HEAD"))
    private void terra_continental$loadStarts(LevelStorageSource.LevelStorageAccess levelStorage, PackRepository packs,
                                              WorldStem stem, boolean newWorld, CallbackInfo ci) {
        SeedRetry.begin(levelStorage.getLevelId(), newWorld);
    }

    @WrapOperation(method = "doWorldLoad", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/server/IntegratedServer;isReady()Z"))
    private boolean terra_continental$stopWaiting(IntegratedServer server, Operation<Boolean> original) {
        return original.call(server) || SeedRetry.serverGone(server);
    }

    @Inject(method = "doWorldLoad", cancellable = true, at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/util/profiling/ProfilerFiller;pop()V", shift = At.Shift.AFTER))
    private void terra_continental$skipJoin(LevelStorageSource.LevelStorageAccess levelStorage, PackRepository packs,
                                           WorldStem stem, boolean newWorld, CallbackInfo ci) {
        if (SeedRetry.requested()) {
            ci.cancel();
            SeedRetry.restart((Minecraft) (Object) this);
        }
    }
}
