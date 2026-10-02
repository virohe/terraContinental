package com.terracontinental.mixin;

import com.terracontinental.config.TerraStructureConfig;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Globally scale spacing and separation on every RandomSpreadStructurePlacement —
 * covers villages, mansions, monuments, outposts, ancient cities, ocean ruins,
 * shipwrecks, jungle temples, swamp huts, end cities, and every modded structure
 * that uses this (the vast majority).
 */
@Mixin(RandomSpreadStructurePlacement.class)
public abstract class RandomSpreadStructurePlacementMixin {

    @Shadow @Final private int spacing;
    @Shadow @Final private int separation;

    // salt is declared on StructurePlacement, the SUPERCLASS of the target. @Shadow only
    // resolves members declared on the target class itself - it does not walk the hierarchy -
    // so shadowing it here fails at mixin APPLY time and takes the dedicated server down
    // during registry bootstrap. Reached through an accessor on the declaring class instead.
    private int terraContinental$salt() {
        return ((StructurePlacementAccessor) (Object) this).terraContinental$getSalt();
    }

    /**
     * Salts identifying the village structure_sets. Villages get an extra multiplier
     * because two independent sets place them — minecraft:villages and
     * biomeswevegone:villages, each with its own grid — and BWG widens the eligible
     * biome tags, so their combined density is far higher than the spacing suggests.
     * Salt is the only per-set identity visible from inside a placement.
     */
    private static final int TERRA_CONTINENTAL$SALT_VANILLA_VILLAGES = 10387312;
    private static final int TERRA_CONTINENTAL$SALT_BWG_VILLAGES = 437845874;

    private double terraContinental$multiplier() {
        try {
            if (TerraStructureConfig.SPEC.isLoaded()) {
                double mult = TerraStructureConfig.STRUCTURE_SPACING_MULTIPLIER.get();
                int s = this.terraContinental$salt();
                if (s == TERRA_CONTINENTAL$SALT_VANILLA_VILLAGES
                        || s == TERRA_CONTINENTAL$SALT_BWG_VILLAGES) {
                    mult *= TerraStructureConfig.VILLAGE_SPACING_MULTIPLIER.get();
                }
                return mult;
            }
        } catch (Throwable ignored) {}
        return 1.0;
    }

    @Inject(method = "spacing()I", at = @At("HEAD"), cancellable = true)
    private void terraContinental$scaleSpacing(CallbackInfoReturnable<Integer> cir) {
        double mult = terraContinental$multiplier();
        if (mult != 1.0) {
            int scaled = Math.max(2, (int) Math.round(this.spacing * mult));
            cir.setReturnValue(scaled);
        }
    }

    @Inject(method = "separation()I", at = @At("HEAD"), cancellable = true)
    private void terraContinental$scaleSeparation(CallbackInfoReturnable<Integer> cir) {
        double mult = terraContinental$multiplier();
        if (mult != 1.0) {
            int scaledSpacing = Math.max(2, (int) Math.round(this.spacing * mult));
            int scaledSeparation = Math.min(scaledSpacing - 1,
                                            Math.max(1, (int) Math.round(this.separation * mult)));
            cir.setReturnValue(scaledSeparation);
        }
    }
}
