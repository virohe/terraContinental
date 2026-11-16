package com.terracontinental.mixin;

import com.terracontinental.spawn.VillageCarverGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Carvers leave village ground alone ({@link VillageCarverGuard}). */
@Mixin(WorldCarver.class)
public abstract class WorldCarverVillageMixin {

    @Inject(method = "carveBlock", at = @At("HEAD"), cancellable = true)
    private void terraContinental$notUnderVillages(@SuppressWarnings("rawtypes") net.minecraft.world.level.levelgen.carver.CarvingContext context,
                                                   net.minecraft.world.level.levelgen.carver.CarverConfiguration config,
                                                   net.minecraft.world.level.chunk.ChunkAccess chunk,
                                                   java.util.function.Function<?, ?> biomeGetter,
                                                   net.minecraft.world.level.chunk.CarvingMask carvingMask,
                                                   BlockPos.MutableBlockPos pos, BlockPos.MutableBlockPos checkPos,
                                                   net.minecraft.world.level.levelgen.Aquifer aquifer,
                                                   org.apache.commons.lang3.mutable.MutableBoolean reachedSurface,
                                                   CallbackInfoReturnable<Boolean> cir) {
        if (VillageCarverGuard.protects(pos)) cir.setReturnValue(false);
    }
}
