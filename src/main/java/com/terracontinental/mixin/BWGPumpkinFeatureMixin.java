package com.terracontinental.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.StructureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Removes a blocking chunk load from BWG's medium/large pumpkin features, which
 * otherwise deadlocks world save and shutdown.
 *
 * <p>Both features open {@code place()} with
 * {@code getLevel().structureManager().hasAnyStructureAt(pos)} — presumably to avoid
 * dropping pumpkins inside villages. {@code hasAnyStructureAt} calls
 * {@code LevelReader.getChunk}, which on a {@code ServerLevel} resolves to
 * {@code ServerChunkCache.getChunk} and <em>joins</em> the resulting future. Feature
 * placement runs on a worldgen worker, so that worker parks until the server thread
 * services the chunk request.
 *
 * <p>During normal play this only costs throughput (it shows up as
 * "Can't keep up! Is the server overloaded?"). During shutdown it is fatal:
 * {@code MinecraftServer.stopServer} spins on
 * {@code while (chunkMap.hasWork()) chunkSource.tick()} and never schedules the load the
 * parked worker is waiting for. The worker never completes, {@code hasWork()} never goes
 * false, and "Saving worlds" hangs forever at 100% of one core. Observed on 2026-08-01
 * with BWG 2.6.0; confirmed by thread dump in both
 * {@code MediumPumpkinFeature} and {@code LargePumpkinFeature}.
 *
 * <p>Redirecting the lookup to a constant {@code false} means "no structure here", so
 * pumpkins still generate normally — they may now also appear inside pumpkin patch
 * villages, which is cosmetic. Only {@code cika_woods} and {@code pumpkin_valley}
 * reference these features. {@code small_pumpkin} uses vanilla
 * {@code minecraft:patch_pumpkin} and was never affected.
 *
 * <p>String-targeted + {@code remap = false} so this compiles and loads without BWG on
 * the classpath; the surrounding mixin config is {@code required:false} so absence of BWG
 * is a clean no-op. NeoForge 1.21.1 runs on Mojang official mappings, so the
 * {@code StructureManager} descriptor is correct verbatim in both dev and production.
 */
@Mixin(targets = {
        "net.potionstudios.biomeswevegone.world.level.levelgen.feature.MediumPumpkinFeature",
        "net.potionstudios.biomeswevegone.world.level.levelgen.feature.LargePumpkinFeature"
}, remap = false)
public class BWGPumpkinFeatureMixin {

    @Redirect(
            method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/StructureManager;"
                            + "hasAnyStructureAt(Lnet/minecraft/core/BlockPos;)Z"),
            remap = false)
    private boolean terraContinental$skipBlockingStructureLookup(StructureManager manager, BlockPos pos) {
        return false;
    }
}
