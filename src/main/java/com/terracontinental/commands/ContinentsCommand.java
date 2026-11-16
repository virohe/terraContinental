package com.terracontinental.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import com.terracontinental.atlas.ContinentAtlas;
import com.terracontinental.atlas.PregenJob;
import com.terracontinental.atlas.PregenMask;
import com.terracontinental.atlas.WorldMapImage;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Admin commands over the whole world's continents (user, 2026-11-16): an admin picks a continent
 * by name, or the one they're on, and pregenerates all of it with its coast and sea margin. Works
 * the same in game and over RCON (the PowerShell route on a dedicated server).
 *
 * <pre>
 * /continental continents                        list them: name, area, centre, size of a pregen
 * /continental pregenerate occupied|&lt;name&gt; [lods]   start (lods: also build DH LODs, default true)
 * /continental pregenerate status|pause|resume|cancel
 * /continental pregenerate parallel &lt;n&gt;           chunks in flight at once
 * </pre>
 *
 * "occupied" is the continent you stand on (or nearest, within 4 km of its coast); from the
 * console, the one under the world spawn.
 */
public final class ContinentsCommand {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final SuggestionProvider<CommandSourceStack> CONTINENTS = (ctx, b) -> {
        List<String> keys = new ArrayList<>();
        keys.add("occupied");
        ContinentAtlas a = ContinentAtlas.ifReady(ctx.getSource().getServer().overworld());
        if (a != null) for (ContinentAtlas.Continent c : a.continents) keys.add(c.key());
        return SharedSuggestionProvider.suggest(keys, b);
    };

    private ContinentsCommand() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("continental")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("continents").executes(ContinentsCommand::list))
                .then(Commands.literal("world-map").executes(ctx -> worldMap(ctx, null))
                        .then(Commands.argument("targets", net.minecraft.commands.arguments.EntityArgument.players())
                                .executes(ctx -> worldMap(ctx, net.minecraft.commands.arguments.EntityArgument.getPlayers(ctx, "targets")))))
                .then(Commands.literal("pregenerate")
                        .then(Commands.literal("status").executes(ctx -> control(ctx, "status")))
                        .then(Commands.literal("pause").executes(ctx -> control(ctx, "pause")))
                        .then(Commands.literal("resume").executes(ctx -> control(ctx, "resume")))
                        .then(Commands.literal("cancel").executes(ctx -> control(ctx, "cancel")))
                        .then(Commands.literal("parallel")
                                .then(Commands.argument("n", IntegerArgumentType.integer(1, 4096))
                                        .executes(ContinentsCommand::parallel)))
                        .then(Commands.argument("continent", StringArgumentType.word()).suggests(CONTINENTS)
                                .executes(ctx -> pregen(ctx, true))
                                .then(Commands.argument("lods", BoolArgumentType.bool())
                                        .executes(ctx -> pregen(ctx, BoolArgumentType.getBool(ctx, "lods")))))));
    }

    // -- /continental continents ------------------------------------------------------------------

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();
        ServerLevel level = server.overworld();
        boolean waiting = ContinentAtlas.ifReady(level) == null;
        if (waiting) src.sendSystemMessage(Component.literal("[continental] Mapping the world's continents (about 15 s)...")
                .withStyle(ChatFormatting.GRAY));
        Vec3 at = src.getPosition();
        ContinentAtlas.get(level).whenCompleteAsync((atlas, err) -> {
            if (err != null) {
                src.sendFailure(Component.literal("[continental] Mapping failed: " + err));
                return;
            }
            ContinentAtlas.Continent here = atlas.continentAt((int) at.x, (int) at.z);
            src.sendSuccess(() -> Component.literal("[continental] " + atlas.continents.size() + " continents:")
                    .withStyle(ChatFormatting.GOLD), false);
            for (ContinentAtlas.Continent c : atlas.continents) {
                String line = String.format("  %s%s: %.0f km2, centre %d %d, %d x %d km", c.name(),
                        c == here ? " (occupied)" : "", c.areaKm2(), c.centerX(), c.centerZ(),
                        Math.round((c.maxX() - c.minX()) / 1000.0), Math.round((c.maxZ() - c.minZ()) / 1000.0));
                src.sendSuccess(() -> Component.literal(line), false);
                LOGGER.info("[continental]{}", line);
            }
        }, server);
        return 1;
    }

    // -- /continental world-map -------------------------------------------------------------------

    /**
     * Gives the World Atlas to the targets (or to the player running it), and writes the map as a
     * PNG beside the world for whoever runs it from the console or RCON.
     */
    private static int worldMap(CommandContext<CommandSourceStack> ctx,
                                java.util.Collection<net.minecraft.server.level.ServerPlayer> targets) {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();
        if (targets == null) targets = src.getPlayer() != null ? List.of(src.getPlayer()) : List.of();
        for (var p : targets) {
            net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(
                    com.terracontinental.TerraContinental.WORLD_ATLAS.get());
            if (!p.getInventory().add(stack)) p.drop(stack, false);
        }
        int given = targets.size();
        if (given > 0) src.sendSuccess(() -> Component.literal("[continental] Gave the World Atlas to " + given
                + (given == 1 ? " player" : " players") + ". Use it to open the map."), true);
        java.nio.file.Path png = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("terra_continental_world_map.png");
        ContinentAtlas.get(server.overworld()).thenAcceptAsync(atlas -> {
            try {
                WorldMapImage.write(atlas, png);
                LOGGER.info("[continental] World map written to {}", png.toAbsolutePath().normalize());
                server.execute(() -> src.sendSystemMessage(Component.literal("[continental] Map image: "
                        + png.toAbsolutePath().normalize()).withStyle(ChatFormatting.GRAY)));
            } catch (java.io.IOException e) {
                LOGGER.warn("[continental] couldn't write {}: {}", png, e.toString());
            }
        }, net.minecraft.Util.backgroundExecutor());
        return Math.max(1, given);
    }

    // -- /continental pregenerate -----------------------------------------------------------------

    private static int pregen(CommandContext<CommandSourceStack> ctx, boolean lods) {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();
        ServerLevel level = server.overworld();
        if (PregenJob.active() != null) {
            src.sendFailure(Component.literal("[continental] A pregen is already running: " + PregenJob.active().status()
                    + ". /continental pregenerate cancel first."));
            return 0;
        }
        String which = StringArgumentType.getString(ctx, "continent");
        Vec3 at = src.getEntity() != null ? src.getPosition() : Vec3.atCenterOf(level.getSharedSpawnPos());
        src.sendSystemMessage(Component.literal("[continental] Drawing the pregen border...").withStyle(ChatFormatting.GRAY));
        ContinentAtlas.get(level).thenApplyAsync(atlas -> {
            ContinentAtlas.Continent c = "occupied".equalsIgnoreCase(which)
                    ? atlas.continentAt((int) at.x, (int) at.z) : atlas.byKey(which);
            if (c == null) throw new IllegalArgumentException("occupied".equalsIgnoreCase(which)
                    ? "You're not on (or within 4 km of) a continent" : "No continent called '" + which + "' (/continental continents)");
            return PregenMask.build(level, atlas, c);
        }, net.minecraft.Util.backgroundExecutor()).whenCompleteAsync((mask, err) -> {
            if (err != null) {
                Throwable t = err.getCause() != null ? err.getCause() : err;
                src.sendFailure(Component.literal("[continental] " + t.getMessage()));
                if (!(t instanceof IllegalArgumentException)) LOGGER.error("[pregen] couldn't start", t);
                return;
            }
            if (PregenJob.active() != null) {
                src.sendFailure(Component.literal("[continental] Another pregen started meanwhile"));
                return;
            }
            PregenJob.start(server, level, mask, lods, defaultInFlight());
            // Replies after the command returned miss RCON's answer: the log is the PowerShell route's record.
            src.sendSuccess(() -> Component.literal(String.format(
                    "[continental] Pregenerating %s: %d chunks (%.0f km2 of land, %d islands, %d-block sea margin)%s. "
                            + "Progress: /continental pregenerate status",
                    mask.continent.name(), mask.chunks.length, mask.landKm2, mask.islands, PregenMask.SEA_MARGIN,
                    lods ? ", then Distant Horizons LODs" : "")).withStyle(ChatFormatting.GREEN), true);
        }, server);
        return 1;
    }

    private static int control(CommandContext<CommandSourceStack> ctx, String what) {
        CommandSourceStack src = ctx.getSource();
        PregenJob job = PregenJob.active();
        if (job == null) {
            src.sendFailure(Component.literal("[continental] No pregen running"));
            return 0;
        }
        switch (what) {
            case "pause" -> job.setPaused(true);
            case "resume" -> job.setPaused(false);
            case "cancel" -> job.cancel();
            default -> {
            }
        }
        String s = "cancel".equals(what) ? "cancelled" : job.status();
        src.sendSuccess(() -> Component.literal("[continental] " + s), false);
        return 1;
    }

    private static int parallel(CommandContext<CommandSourceStack> ctx) {
        PregenJob job = PregenJob.active();
        if (job == null) {
            ctx.getSource().sendFailure(Component.literal("[continental] No pregen running"));
            return 0;
        }
        job.setInFlight(IntegerArgumentType.getInteger(ctx, "n"));
        ctx.getSource().sendSuccess(() -> Component.literal("[continental] " + job.status()), false);
        return 1;
    }

    static int defaultInFlight() {
        return Math.max(16, Runtime.getRuntime().availableProcessors() * 4);
    }

    // -- server lifecycle -------------------------------------------------------------------------

    public static void onServerTick(ServerTickEvent.Post e) {
        PregenJob job = PregenJob.active();
        if (job != null) job.tick();
    }

    public static void onServerStarted(ServerStartedEvent e) {
        MinecraftServer server = e.getServer();
        PregenJob.State s = PregenJob.saved(server);
        if (s == null) return;
        ServerLevel level = server.overworld();
        ContinentAtlas.get(level).thenApplyAsync(atlas -> {
            ContinentAtlas.Continent c = atlas.byKey(s.continent);
            return c == null ? null : PregenMask.build(level, atlas, c);
        }, net.minecraft.Util.backgroundExecutor()).whenCompleteAsync((mask, err) -> {
            if (err != null || mask == null) {
                LOGGER.warn("[pregen] couldn't resume the pregen of {}: {}", s.continent, err);
                return;
            }
            if (PregenJob.active() == null) PregenJob.resume(server, level, mask, s);
        }, server);
    }

    public static void onServerStopping(ServerStoppingEvent e) {
        PregenJob.onServerStopping();
    }
}
