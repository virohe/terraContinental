package com.terracontinental.mixin.terrain;

import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A chunk saved with a taller world than the current one carries post-processing entries for
 * sections that no longer exist; vanilla indexes past the array and throws. Hand those a
 * throwaway list. Absorbed from Tectonic (MIT, (c) the Tectonic authors).
 */
@Mixin(ChunkAccess.class)
public abstract class OffsetListMixin {

    @Inject(method = "getOrCreateOffsetList", at = @At("HEAD"), cancellable = true)
    private static void terra_continental$outOfRange(ShortList[] lists, int index, CallbackInfoReturnable<ShortList> cir) {
        if (index >= lists.length) {
            cir.setReturnValue(new ShortArrayList());
        }
    }
}
