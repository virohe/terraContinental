package com.terracontinental.mixin.maps;

import com.terracontinental.config.TerraClientConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Xaero's World Map as an explorer's map (user, 2026-11-16: the shipped map shows none of the
 * pregenerated terrain). In single-player Xaero normally builds its map from the world save,
 * every generated chunk whether you've been there or not; that is the "renders areas I never
 * explored" the user had seen from map mods. With this off it keeps its own map of the chunks
 * your client has loaded, exactly as it does on a server. Config: maps.xaero_explore_only.
 */
@Pseudo
@Mixin(targets = "xaero.map.world.MapDimension", remap = false)
public abstract class XaeroWorldSaveMixin {

    @Inject(method = {"isUsingWorldSave", "isFutureUsingWorldSaveUnsynced"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void terra_continental$exploreOnly(CallbackInfoReturnable<Boolean> cir) {
        if (TerraClientConfig.get(TerraClientConfig.XAERO_EXPLORE_ONLY, true)) cir.setReturnValue(false);
    }
}
