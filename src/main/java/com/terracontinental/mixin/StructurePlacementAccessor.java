package com.terracontinental.mixin;

import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes StructurePlacement.salt, which RandomSpreadStructurePlacementMixin needs in order
 * to tell the village structure_sets apart from every other placement.
 *
 * Why this exists rather than a plain @Shadow: salt is declared on StructurePlacement, the
 * SUPERCLASS of RandomSpreadStructurePlacement. Mixin's @Shadow only resolves members
 * declared on the mixin's own target class - it does not walk up the hierarchy - so
 * shadowing salt() from a mixin targeting the subclass fails at APPLY time with
 * "was not located in the target class", taking the whole server down during registry
 * bootstrap. An accessor mixin on the declaring class is the supported way to reach it.
 */
@Mixin(StructurePlacement.class)
public interface StructurePlacementAccessor {
    @Accessor("salt")
    int terraContinental$getSalt();
}
