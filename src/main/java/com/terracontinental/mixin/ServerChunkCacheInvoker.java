package com.terracontinental.mixin;

import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.CompletableFuture;

/**
 * The pregen asks for chunks on the server thread without waiting for them. The public
 * {@code getChunkFuture} blocks the server thread until the chunk is done when called from it
 * (first rig run, 2026-11-16: 32 chunks per 10-second tick, 2.8 chunks/s); the private
 * main-thread variant returns the future at once.
 */
@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheInvoker {
    @Invoker("getChunkFutureMainThread")
    CompletableFuture<ChunkResult<ChunkAccess>> terra_continental$getChunkFutureMainThread(
            int x, int z, ChunkStatus status, boolean requireChunk);
}
