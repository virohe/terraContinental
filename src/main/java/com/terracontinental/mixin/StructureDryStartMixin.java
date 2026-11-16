package com.terracontinental.mixin;

import com.terracontinental.structure.DryStarts;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Land surface structures don't start under water ({@link DryStarts}). The spawn-village planner
 * and per-continent placement ask this same method, so their picks agree.
 */
@Mixin(Structure.class)
public abstract class StructureDryStartMixin {

    @Inject(method = "findValidGenerationPoint", at = @At("RETURN"), cancellable = true)
    private void terra_continental$dryStart(Structure.GenerationContext context,
                                            CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        Structure self = (Structure) (Object) this;
        if (self.step() != GenerationStep.Decoration.SURFACE_STRUCTURES) return;
        Optional<Structure.GenerationStub> stub = cir.getReturnValue();
        if (stub == null || stub.isEmpty()) return;
        BlockPos p = stub.get().position();
        if (!DryStarts.allowed(self, context, p.getX(), p.getZ())) cir.setReturnValue(Optional.empty());
    }
}
