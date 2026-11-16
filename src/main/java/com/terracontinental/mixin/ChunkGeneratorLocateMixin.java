package com.terracontinental.mixin;

import com.mojang.datafixers.util.Pair;
import com.terracontinental.spawn.PerContinentPlacement;
import com.terracontinental.spawn.SpawnVillagePlacement;
import com.terracontinental.spawn.SpawnVillagePlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * /locate (and anything else that calls findNearestMapStructure, such as Terra Towns' VillageAudit)
 * only walks random-spread and concentric-ring placements, so it never saw the planned spawn
 * villages and answered with a village on another continent. After vanilla's search, the nearest
 * planned village of the requested structures wins if it is nearer; so does the nearest per-continent
 * site (abandoned villages, Forgotten villages, Pumpkin Patches), which /locate can't walk either.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorLocateMixin {

    @Inject(method = "findNearestMapStructure", at = @At("RETURN"), cancellable = true)
    private void terraContinental$plannedVillages(ServerLevel level, HolderSet<Structure> structures, BlockPos from,
                                                  int searchRadius, boolean skipKnownStructures,
                                                  CallbackInfoReturnable<Pair<BlockPos, Holder<Structure>>> cir) {
        var state = level.getChunkSource().getGeneratorState();
        SpawnVillagePlanner.Plan plan = SpawnVillagePlanner.forState(state);
        Pair<BlockPos, Holder<Structure>> found = cir.getReturnValue();
        double best = found == null ? Double.MAX_VALUE : horizontalDist2(found.getFirst(), from);
        Pair<BlockPos, Holder<Structure>> nearest = null;
        for (Holder<Structure> holder : structures) {
            for (StructurePlacement p : state.getPlacementsForStructure(holder)) {
                // Per-continent sites (abandoned villages and the like): a site counts only where this
                // structure would actually start, since one set holds several biome-bound structures.
                if (p instanceof PerContinentPlacement rare) {
                    var sites = rare.sitesFor(state);
                    if (sites == null) continue;
                    for (long l : sites) {
                        ChunkPos cp = new ChunkPos(l);
                        BlockPos pos = rare.getLocatePos(cp);
                        double d = horizontalDist2(pos, from);
                        if (d >= best) continue;
                        StructureCheckResult r = level.structureManager().checkStructurePresence(cp, holder.value(), rare, skipKnownStructures);
                        if (r == StructureCheckResult.START_NOT_PRESENT) continue;
                        best = d;
                        nearest = Pair.of(pos, holder);
                    }
                    continue;
                }
                if (plan == null) continue;
                if (!(p instanceof SpawnVillagePlacement planned)) continue;
                for (SpawnVillagePlanner.Site site : plan.sites.values()) {
                    if (!site.type().equals(planned.villageType())) continue;
                    BlockPos pos = planned.getLocatePos(site.chunk());
                    double d = horizontalDist2(pos, from);
                    if (d < best) {
                        best = d;
                        nearest = Pair.of(pos, holder);
                    }
                }
            }
        }
        if (nearest != null) cir.setReturnValue(nearest);
    }

    private static double horizontalDist2(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
