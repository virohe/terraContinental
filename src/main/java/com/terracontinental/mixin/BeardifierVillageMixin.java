package com.terracontinental.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Gentler ground round village houses. A rigid piece with {@code beard_thin} (every vanilla and BWG
 * village house) levels the terrain to its floor only within vanilla's 12-block kernel, with most of
 * the change in the first 4-5 blocks: on a slope that leaves a wall of bare dirt and stone beside the
 * house (user, 2026-11-11). Here the kernel is read at {@link #SPREAD} times the horizontal distance,
 * so the same rise is spread over about twice the width and grades into a grassy bank. Other terrain
 * adaptations (outposts' beard_box, buried and encapsulated structures) keep vanilla's.
 *
 * <p>C2ME replaces {@code compute} wholesale (its {@code MixinStructureWeightSampler}, priority 1100),
 * keeping the same {@code getBeardContribution} calls, its first being beard_thin's; hence priority
 * 1200, to wrap whichever body is there. Not required: if a C2ME update drops the call, chunks still
 * generate with vanilla's beard (rig 2026-11-11: a required wrap at priority 1000 failed every chunk).</p>
 */
@Mixin(value = Beardifier.class, priority = 1200)
public abstract class BeardifierVillageMixin {

    /** Horizontal distances are scaled by this before the kernel: 0.5 = twice as wide. */
    private static final double SPREAD = 0.5;
    private static volatile boolean terraContinental$announced;

    @WrapOperation(method = "compute",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/Beardifier;getBeardContribution(IIII)D",
                    ordinal = 0),
            require = 0)
    private double terraContinental$wideBeard(int x, int y, int z, int height, Operation<Double> original,
                                              @Local Beardifier.Rigid rigid) {
        if (rigid.terrainAdjustment() != TerrainAdjustment.BEARD_THIN) return original.call(x, y, z, height);
        if (!terraContinental$announced) {
            terraContinental$announced = true;
            com.mojang.logging.LogUtils.getLogger().info("[village-ground] wide terrain fit round village houses is active");
        }
        double sx = x * SPREAD, sz = z * SPREAD;
        if (sx >= 12 || sz >= 12 || y < -12 || y >= 12) return 0.0;
        // Vanilla's formula (Beardifier.getBeardContribution) on the scaled distances: a direction term
        // (negative above the floor, positive below) times exp(-dist^2 / 16).
        double d0 = height + 0.5;
        double d1 = sx * sx + d0 * d0 + sz * sz;
        double dir = -d0 / Math.sqrt(d1 / 2.0) / 2.0;
        double yk = y + 0.5;
        double kernel = Math.exp(-(sx * sx + yk * yk + sz * sz) / 16.0);
        return dir * kernel;
    }
}
