package com.terracontinental.mixin;

import com.terracontinental.config.TerraStructureConfig;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Globally scale spacing and separation on every RandomSpreadStructurePlacement —
 * covers villages, mansions, monuments, outposts, ancient cities, ocean ruins,
 * shipwrecks, jungle temples, swamp huts, end cities, and every modded structure
 * that uses this (the vast majority).
 *
 * <p>The fields themselves are scaled, once, as each placement is built (0.8.2). Until then this
 * mixin only changed what {@code spacing()} and {@code separation()} REPORT, but the placement
 * math reads the fields, so the configured multipliers never reached generation and only made
 * /locate wrong (TI-102): a continent the design meant to hold ~8-12 villages held 33.</p>
 */
@Mixin(RandomSpreadStructurePlacement.class)
public abstract class RandomSpreadStructurePlacementMixin {

    @Shadow @Final @Mutable private int spacing;
    @Shadow @Final @Mutable private int separation;

    /**
     * Salts identifying the village structure_sets. Villages get an extra multiplier
     * because two independent sets place them — minecraft:villages and
     * biomeswevegone:villages, each with its own grid — and BWG widens the eligible
     * biome tags, so their combined density is far higher than the spacing suggests.
     * Salt is the only per-set identity visible from inside a placement.
     */
    private static final int TERRA_CONTINENTAL$SALT_VANILLA_VILLAGES = 10387312;
    private static final int TERRA_CONTINENTAL$SALT_BWG_VILLAGES = 437845874;

    /**
     * The full constructor: the codec and the short constructor both end up here, so this
     * scales every placement exactly once. The salt is already set (the super constructor ran).
     */
    @Inject(method = "<init>(Lnet/minecraft/core/Vec3i;Lnet/minecraft/world/level/levelgen/structure/placement/StructurePlacement$FrequencyReductionMethod;FILjava/util/Optional;IILnet/minecraft/world/level/levelgen/structure/placement/RandomSpreadType;)V",
            at = @At("RETURN"))
    private void terraContinental$scale(CallbackInfo ci) {
        // Rounded first: 1.5 * 0.7 is 1.0499999999999998 in floating point, which made separation round
        // to 31 instead of 32 and moved every village (audit 2026-10-18).
        double mult = Math.round(terraContinental$multiplier() * 1_000_000d) / 1_000_000d;
        if (mult == 1.0) {
            return;
        }
        // Spacing stays within the codec's 0-4096 and above separation, as vanilla requires.
        int scaledSpacing = Math.min(4096, Math.max(2, (int) Math.round(this.spacing * mult)));
        int scaledSeparation = Math.min(scaledSpacing - 1, Math.max(1, (int) Math.round(this.separation * mult)));
        this.spacing = scaledSpacing;
        this.separation = scaledSeparation;
    }

    private double terraContinental$multiplier() {
        try {
            if (TerraStructureConfig.SPEC.isLoaded()) {
                double mult = TerraStructureConfig.STRUCTURE_SPACING_MULTIPLIER.get();
                int s = ((StructurePlacementAccessor) (Object) this).terraContinental$getSalt();
                if (s == TERRA_CONTINENTAL$SALT_VANILLA_VILLAGES
                        || s == TERRA_CONTINENTAL$SALT_BWG_VILLAGES) {
                    mult *= TerraStructureConfig.VILLAGE_SPACING_MULTIPLIER.get();
                }
                return mult;
            }
        } catch (Throwable ignored) {}
        return 1.0;
    }
}
