package com.terracontinental.mixin.alpine;

import com.terracontinental.alpine.Alpine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * The structure line: no surface structure starts high on a mountain (the same line as the trees,
 * playtest 2026-10-31). Underground structures (ancient cities, mineshafts, strongholds) are other
 * steps and unaffected. The spawn-village planner asks this same method, so its picks agree.
 */
@Mixin(Structure.class)
public abstract class StructureLineMixin {

    @Inject(method = "findValidGenerationPoint", at = @At("RETURN"), cancellable = true)
    private void terra_continental$structureLine(Structure.GenerationContext context,
                                                 CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        if (!Alpine.active()) return;
        if (((Structure) (Object) this).step() != GenerationStep.Decoration.SURFACE_STRUCTURES) return;
        Optional<Structure.GenerationStub> stub = cir.getReturnValue();
        if (stub == null || stub.isEmpty()) return;
        BlockPos p = stub.get().position();
        if (!Alpine.structureAllowed(p.getX(), p.getY(), p.getZ())) cir.setReturnValue(Optional.empty());
    }
}
