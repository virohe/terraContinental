package com.terracontinental.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Stops a server without {@code IntegratedServer.halt}, which first runs a task on the server
 * thread and waits for it: while the hamlet search holds that thread in ServerStartedEvent the
 * client would freeze until the search ends. Clearing the flag alone lets the long jobs see
 * {@code isRunning()} go false and stop, and the server shuts down as it would on quit.
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {
    @Accessor("running")
    void terra_continental$setRunning(boolean running);
}
