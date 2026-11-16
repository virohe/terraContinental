package com.terracontinental.mixin;

import com.terracontinental.spawn.SpawnVillagePlanner;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the two village grids (vanilla's and BWG's) off the spawn continent while the spawn-village
 * planner places that continent's villages; every other continent keeps the grids. Without this the
 * grids would add their own random villages beside the planned ones and bring the clumping back.
 * Elsewhere a grid village also needs level ground, as a planned one does.
 */
@Mixin(StructurePlacement.class)
public abstract class StructurePlacementSpawnMixin {

    @Inject(method = "isStructureChunk", at = @At("HEAD"), cancellable = true)
    private void terraContinental$offSpawnContinent(ChunkGeneratorStructureState state, int x, int z,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof RandomSpreadStructurePlacement)) return;
        int salt = ((StructurePlacementAccessor) this).terraContinental$getSalt();
        if (salt != 10387312 && salt != 437845874) return;
        SpawnVillagePlanner.Plan plan = SpawnVillagePlanner.forState(state);
        if (plan != null && plan.suppressGrid((x << 4) + 8, (z << 4) + 8)) cir.setReturnValue(false);
    }

    /**
     * A grid village only where the ground suits one ({@link VillageFootprint}, the planner's own rule):
     * vanilla checks nothing but the biome, so grid villages stood on hillsides with their houses cut
     * into the slope (user, 2026-11-11). Checked only for chunks the grid picked, so it costs nothing
     * elsewhere.
     */
    @Inject(method = "isStructureChunk", at = @At("RETURN"), cancellable = true)
    private void terraContinental$levelGround(ChunkGeneratorStructureState state, int x, int z,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() || !((Object) this instanceof RandomSpreadStructurePlacement)) return;
        int salt = ((StructurePlacementAccessor) this).terraContinental$getSalt();
        if (salt != 10387312 && salt != 437845874) return;
        if (!com.terracontinental.spawn.VillageFootprint.gridAllows(state, x, z)) cir.setReturnValue(false);
    }
}
