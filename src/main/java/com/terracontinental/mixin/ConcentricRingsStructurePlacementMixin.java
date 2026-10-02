package com.terracontinental.mixin;

import com.terracontinental.config.TerraStructureConfig;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Globally scale total count on ConcentricRingsStructurePlacement — strongholds.
 * Reducing count = effectively rarer.
 */
@Mixin(ConcentricRingsStructurePlacement.class)
public abstract class ConcentricRingsStructurePlacementMixin {

    @Shadow @Final private int count;

    private static double terraContinental$multiplier() {
        try {
            if (TerraStructureConfig.SPEC.isLoaded()) {
                return TerraStructureConfig.STRUCTURE_SPACING_MULTIPLIER.get();
            }
        } catch (Throwable ignored) {}
        return 1.0;
    }

    @Inject(method = "count()I", at = @At("HEAD"), cancellable = true)
    private void terraContinental$scaleCount(CallbackInfoReturnable<Integer> cir) {
        double mult = terraContinental$multiplier();
        if (mult > 1.0) {
            int scaled = Math.max(1, (int) Math.round(this.count / mult));
            cir.setReturnValue(scaled);
        }
    }
}
