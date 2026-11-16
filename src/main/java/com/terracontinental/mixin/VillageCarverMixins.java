package com.terracontinental.mixin;

import com.terracontinental.spawn.VillageCarverGuard;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Brackets each chunk's carving with {@link VillageCarverGuard}'s village areas. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class VillageCarverMixins {

    @Inject(method = "applyCarvers", at = @At("HEAD"))
    private void terraContinental$guardVillages(WorldGenRegion level, long seed, RandomState random, BiomeManager biomeManager,
                                                StructureManager structureManager, ChunkAccess chunk, GenerationStep.Carving step,
                                                CallbackInfo ci) {
        VillageCarverGuard.begin(structureManager, chunk.getPos());
    }

    @Inject(method = "applyCarvers", at = @At("RETURN"))
    private void terraContinental$guardDone(WorldGenRegion level, long seed, RandomState random, BiomeManager biomeManager,
                                            StructureManager structureManager, ChunkAccess chunk, GenerationStep.Carving step,
                                            CallbackInfo ci) {
        VillageCarverGuard.end();
    }
}
