package com.terracontinental.mixin.terrain;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Remembers the hash of vanilla's density-function records after the first call. Every NoiseChunk
 * (one per getBaseHeight call, one per generated chunk) maps the whole noise router and keys a
 * HashMap on each new node, and a record's hash walks its entire subtree down to the noises (which
 * C2ME hashes over their permutation arrays), so a deep terrain tree is re-hashed once per
 * ancestor: that was most of getBaseHeight's time in this pack.
 * <p>
 * Safe because these records are immutable all the way down (density functions, noise holders,
 * splines and their float arrays are never changed after construction), so the hash can't go
 * stale; equals is untouched. 0 means "not computed yet": a record whose hash really is 0 just
 * recomputes it each time. Holder.Direct is left alone: it is generic and may hold mutable values.
 */
@Mixin(targets = {
        "net.minecraft.world.level.levelgen.DensityFunctions$Ap2",
        "net.minecraft.world.level.levelgen.DensityFunctions$BlendDensity",
        "net.minecraft.world.level.levelgen.DensityFunctions$Clamp",
        "net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder",
        "net.minecraft.world.level.levelgen.DensityFunctions$Mapped",
        "net.minecraft.world.level.levelgen.DensityFunctions$Marker",
        "net.minecraft.world.level.levelgen.DensityFunctions$MulOrAdd",
        "net.minecraft.world.level.levelgen.DensityFunctions$Noise",
        "net.minecraft.world.level.levelgen.DensityFunctions$RangeChoice",
        "net.minecraft.world.level.levelgen.DensityFunctions$Shift",
        "net.minecraft.world.level.levelgen.DensityFunctions$ShiftA",
        "net.minecraft.world.level.levelgen.DensityFunctions$ShiftB",
        "net.minecraft.world.level.levelgen.DensityFunctions$ShiftedNoise",
        "net.minecraft.world.level.levelgen.DensityFunctions$Spline",
        "net.minecraft.world.level.levelgen.DensityFunctions$Spline$Coordinate",
        "net.minecraft.world.level.levelgen.DensityFunctions$WeirdScaledSampler",
        "net.minecraft.world.level.levelgen.DensityFunction$NoiseHolder",
        "net.minecraft.util.CubicSpline$Multipoint"
})
public abstract class DensityHashCacheMixin {

    @Unique
    private int terra_continental$hash;

    // require = 0: a speed-up only; if another mod reshapes one of these methods, that record
    // simply hashes the slow way.
    @Inject(method = "hashCode", at = @At("HEAD"), cancellable = true, require = 0)
    private void terra_continental$cachedHash(CallbackInfoReturnable<Integer> cir) {
        int h = terra_continental$hash;
        if (h != 0) cir.setReturnValue(h);
    }

    @Inject(method = "hashCode", at = @At("RETURN"), require = 0)
    private void terra_continental$keepHash(CallbackInfoReturnable<Integer> cir) {
        terra_continental$hash = cir.getReturnValueI();
    }
}
