package com.terracontinental.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.terracontinental.density.RadialFalloffFunction;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /continental villages: every village on the continent you're standing on, nearest first, each
 * a click away (user request, 2026-10-16: hop between villages the way /terraTowns hamlets hops
 * between hamlets). /continental villages tp &lt;n&gt; goes to the n-th of your last list.
 *
 * <p><b>Your continent</b> is the seed-placed continent you're proportionally nearest to ({@link
 * RadialFalloffFunction#continents}: distance over its radius), out to {@link #MARGIN} radii; a
 * village belongs to the continent it is nearest to the same way. The land circles alone were too
 * tight: a playtest village sat 11,350 blocks from the origin, past the spawn continent's 9,000.
 * Villages never generate in the ocean, so nearest-continent splits the land between them.</p>
 *
 * <p><b>The search</b> walks each village structure set's grid and asks each candidate spot, as /locate
 * does, whether a village starts there. For a spot never visited that answer lays the village out,
 * so a continent's ~1,000 spots took 4 minutes in one tick on the real-pack rig: the search runs
 * {@link #BUDGET_NANOS} per server tick instead, and a continent's answer is kept for the session.</p>
 */
public final class VillagesCommand {

    /**
     * In the village tag but not villages (user, 2026-10-16): BWG's Pumpkin Patches are home to
     * pumpkin wardens, not villagers, and its Forgotten villages are ruins with nobody in them.
     */
    private static final Set<String> NOT_VILLAGES = Set.of(
            "biomeswevegone:village/pumpkin_patch", "biomeswevegone:village/forgotten");

    /** A continent reaches this many of its radii: its land goes well past its circle. */
    private static final double MARGIN = 2.0;
    /** Search time per server tick: a sliver of the 50 ms tick. */
    private static final long BUDGET_NANOS = 25_000_000L;

    private record Village(ChunkPos chunk, String structure) {
    }

    private record Candidate(ChunkPos chunk, StructurePlacement placement, Set<Holder<Structure>> structures) {
    }

    /** A search under way: who asked, from where, and what's left to check. */
    private static final class Job {
        final CommandSourceStack source;
        final double[] continent;
        final double x;
        final double z;
        final List<Candidate> candidates;
        final List<Village> found = new ArrayList<>();
        int next;
        int reportedQuarter;
        final long startNanos = System.nanoTime();
        /** Verify mode: generate every spot's structure starts and compare with the quick answer. */
        boolean verify;
        /** Verify mode: where the quick check said yes, asked BEFORE that spot was generated. */
        final Set<Long> quick = new HashSet<>();

        Job(CommandSourceStack source, double[] continent, double x, double z, List<Candidate> candidates) {
            this.source = source;
            this.continent = continent;
            this.x = x;
            this.z = z;
            this.candidates = candidates;
        }
    }

    private static final List<Job> JOBS = new ArrayList<>();
    /** Each continent's villages, by "x,z" of its centre, for the rest of the session. */
    private static final Map<String, List<Village>> FOUND = new HashMap<>();
    /** Each player's last listing, nearest first, for tp by number. */
    private static final Map<UUID, List<Village>> LAST = new HashMap<>();

    private VillagesCommand() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("continental")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("villages")
                        .executes(VillagesCommand::list)
                        .then(Commands.literal("verify").executes(VillagesCommand::verify))
                        .then(Commands.literal("tp")
                                .then(Commands.argument("n", IntegerArgumentType.integer(1))
                                        .executes(VillagesCommand::tp)))));
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getServer().overworld();
        if (source.getLevel() != level) {
            source.sendFailure(Component.literal("[continental] Stand in the overworld to list its villages"));
            return 0;
        }
        // From the console (no player) it lists the continent around the world spawn.
        double px = source.getPosition().x;
        double pz = source.getPosition().z;
        RadialFalloffFunction falloff = falloff(level);
        double[] continent = falloff == null ? null : nearest(falloff.continents(), px, pz);
        if (continent == null) {
            source.sendFailure(Component.literal("[continental] You aren't on a continent (no Terra Continental terrain here)"));
            return 0;
        }
        List<Village> known = FOUND.get(key(continent));
        if (known != null) {
            report(source, continent, known, px, pz, 0);
            return known.size();
        }
        List<Candidate> candidates = candidates(level, falloff.continents(), continent);
        JOBS.add(new Job(source, continent, px, pz, candidates));
        source.sendSuccess(() -> Component.literal(String.format(
                "[continental] Searching %d spots on this continent (centre %d, %d) for villages; the list follows when done.",
                candidates.size(), (int) continent[0], (int) continent[1])), false);
        return 1;
    }

    /**
     * /continental villages verify: the same search done the slow, certain way. Every candidate
     * spot's structure starts are generated (what the world builds a village from) and the result
     * compared with the quick answer: confirmed, listed but absent, present but missed.
     */
    private static int verify(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getServer().overworld();
        double px = source.getPosition().x;
        double pz = source.getPosition().z;
        RadialFalloffFunction falloff = falloff(level);
        double[] continent = falloff == null ? null : nearest(falloff.continents(), px, pz);
        if (continent == null) {
            source.sendFailure(Component.literal("[continental] You aren't on a continent"));
            return 0;
        }
        List<Candidate> candidates = candidates(level, falloff.continents(), continent);
        Job job = new Job(source, continent, px, pz, candidates);
        job.verify = true;
        JOBS.add(job);
        source.sendSuccess(() -> Component.literal("[continental] Verifying " + candidates.size()
                + " spots by generating their structure starts (slow)."), false);
        return 1;
    }

    /** Work on the searches under way, a little each tick. */
    public static void onServerTick(ServerTickEvent.Post event) {
        if (JOBS.isEmpty()) {
            return;
        }
        ServerLevel level = event.getServer().overworld();
        long deadline = System.nanoTime() + BUDGET_NANOS;
        Job job = JOBS.get(0);
        while (job.next < job.candidates.size() && System.nanoTime() < deadline) {
            Candidate c = job.candidates.get(job.next++);
            if (job.verify && villageAt(level, c) != null) {
                job.quick.add(c.chunk().toLong()); // before generating, or it would read the answer back
            }
            String which = job.verify ? villageStartedAt(level, c) : villageAt(level, c);
            if (which != null) {
                job.found.add(new Village(c.chunk(), which));
            }
        }
        int quarter = job.candidates.isEmpty() ? 4 : 4 * job.next / job.candidates.size();
        if (quarter > job.reportedQuarter && quarter < 4) {
            job.reportedQuarter = quarter;
            int pct = quarter * 25;
            job.source.sendSuccess(() -> Component.literal("[continental] Villages: " + pct + "% searched"), false);
        }
        if (job.next >= job.candidates.size() && job.verify) {
            JOBS.remove(0);
            reportVerify(level, job);
            return;
        }
        if (job.next >= job.candidates.size()) {
            JOBS.remove(0);
            List<Village> found = List.copyOf(job.found);
            FOUND.put(key(job.continent), found);
            report(job.source, job.continent, found, job.x, job.z, (System.nanoTime() - job.startNanos) / 1_000_000L);
        }
    }

    private static void reportVerify(ServerLevel level, Job job) {
        Set<Long> real = new HashSet<>();
        job.found.forEach(v -> real.add(v.chunk().toLong()));
        Set<Long> quick = job.quick;
        long confirmed = quick.stream().filter(real::contains).count();
        long phantom = quick.stream().filter(k -> !real.contains(k)).count();
        long missed = real.stream().filter(k -> !quick.contains(k)).count();
        String msg = String.format("[continental] Verified: %d villages really start on this continent; the quick search "
                        + "lists %d (%d confirmed, %d listed but absent, %d missed) (%d s)",
                real.size(), quick.size(), confirmed, phantom, missed, (System.nanoTime() - job.startNanos) / 1_000_000_000L);
        job.source.sendSuccess(() -> Component.literal(msg), false);
    }

    /** List the villages nearest first, each clickable; remember the order for tp. */
    private static void report(CommandSourceStack source, double[] continent, List<Village> villages,
                               double x, double z, long ms) {
        List<Village> sorted = new ArrayList<>(villages);
        sorted.sort(Comparator.comparingDouble(v -> distance(v, x, z)));
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            LAST.put(player.getUUID(), sorted);
        }
        source.sendSuccess(() -> Component.literal(String.format(
                "[continental] %d villages on this continent (centre %d, %d; radius %d), nearest first%s:",
                sorted.size(), (int) continent[0], (int) continent[1], (int) continent[2],
                ms > 0 ? " (searched in " + ms / 1000 + " s)" : "")), false);
        for (int i = 0; i < sorted.size(); i++) {
            Village v = sorted.get(i);
            int n = i + 1;
            String command = "/continental villages tp " + n;
            Component line = Component.literal(String.format("  %d. %s at %d, %d (%d blocks)", n,
                            v.structure(), v.chunk().getMiddleBlockX(), v.chunk().getMiddleBlockZ(), (int) distance(v, x, z)))
                    .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Teleport there"))));
            source.sendSuccess(() -> line, false);
        }
    }

    private static double distance(Village v, double x, double z) {
        return Math.hypot(v.chunk().getMiddleBlockX() - x, v.chunk().getMiddleBlockZ() - z);
    }

    private static int tp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        int n = IntegerArgumentType.getInteger(ctx, "n");
        List<Village> villages = player == null ? null : LAST.get(player.getUUID());
        if (villages == null || n > villages.size()) {
            source.sendFailure(Component.literal("[continental] Run /continental villages first, then pick a number from it"));
            return 0;
        }
        ServerLevel level = source.getServer().overworld();
        ChunkPos c = villages.get(n - 1).chunk();
        int x = c.getMiddleBlockX();
        int z = c.getMiddleBlockZ();
        level.getChunk(c.x, c.z); // generate it, so the height is real
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        player.teleportTo(level, x + 0.5, y, z + 0.5, player.getYRot(), player.getXRot());
        source.sendSuccess(() -> Component.literal("[continental] Village " + n + " at " + x + ", " + y + ", " + z), false);
        return 1;
    }

    private static String key(double[] continent) {
        return (int) continent[0] + "," + (int) continent[1];
    }

    /** The continent {x, z, radius} the position is proportionally nearest to, within {@link #MARGIN} radii. */
    @Nullable
    private static double[] nearest(double[][] continents, double x, double z) {
        double[] best = null;
        double bestRatio = Double.MAX_VALUE;
        for (double[] c : continents) {
            double ratio = Math.hypot(x - c[0], z - c[1]) / c[2];
            if (ratio < MARGIN && ratio < bestRatio) {
                best = c;
                bestRatio = ratio;
            }
        }
        return best;
    }

    /** The seeded radial-falloff function in this world's noise router, if it has one. */
    @Nullable
    private static RadialFalloffFunction falloff(ServerLevel level) {
        RadialFalloffFunction[] found = new RadialFalloffFunction[1];
        level.getChunkSource().randomState().router().mapAll(new DensityFunction.Visitor() {
            @Override
            public DensityFunction apply(DensityFunction df) {
                if (found[0] == null && df instanceof RadialFalloffFunction r) {
                    found[0] = r;
                }
                return df;
            }
        });
        return found[0];
    }

    /** Every spot on {@code continent} where some village structure set could start a village. */
    private static List<Candidate> candidates(ServerLevel level, double[][] all, double[] continent) {
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        var state = level.getChunkSource().getGeneratorState();
        // Village structures, grouped by the placement (structure set) that spaces them.
        Map<StructurePlacement, Set<Holder<Structure>>> byPlacement = new HashMap<>();
        registry.getTagOrEmpty(StructureTags.VILLAGE).forEach(holder -> {
            if (holder.unwrapKey().map(k -> NOT_VILLAGES.contains(k.location().toString())).orElse(false)) {
                return;
            }
            for (StructurePlacement p : state.getPlacementsForStructure(holder)) {
                byPlacement.computeIfAbsent(p, k -> new LinkedHashSet<>()).add(holder);
            }
        });
        double reach = continent[2] * MARGIN;
        int minChunkX = (int) Math.floor((continent[0] - reach) / 16.0);
        int maxChunkX = (int) Math.ceil((continent[0] + reach) / 16.0);
        int minChunkZ = (int) Math.floor((continent[1] - reach) / 16.0);
        int maxChunkZ = (int) Math.ceil((continent[1] + reach) / 16.0);
        List<Candidate> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (var e : byPlacement.entrySet()) {
            if (!(e.getKey() instanceof RandomSpreadStructurePlacement spread)) {
                continue;
            }
            int spacing = spread.spacing();
            for (int rx = Math.floorDiv(minChunkX, spacing); rx <= Math.floorDiv(maxChunkX, spacing); rx++) {
                for (int rz = Math.floorDiv(minChunkZ, spacing); rz <= Math.floorDiv(maxChunkZ, spacing); rz++) {
                    ChunkPos chunk = spread.getPotentialStructureChunk(state.getLevelSeed(), rx * spacing, rz * spacing);
                    int bx = chunk.getMiddleBlockX();
                    int bz = chunk.getMiddleBlockZ();
                    if (Math.hypot(bx - continent[0], bz - continent[1]) <= reach
                            && Arrays.equals(nearest(all, bx, bz), continent)
                            && spread.isStructureChunk(state, chunk.x, chunk.z)
                            && seen.add(chunk.toLong() ^ ((long) System.identityHashCode(spread) << 1))) {
                        out.add(new Candidate(chunk, spread, e.getValue()));
                    }
                }
            }
        }
        return out;
    }

    /** The village whose structure start really exists at the candidate (generating it if need be), or null. */
    @Nullable
    private static String villageStartedAt(ServerLevel level, Candidate c) {
        var manager = level.structureManager();
        net.minecraft.world.level.chunk.ChunkAccess chunk = level.getChunk(c.chunk().x, c.chunk().z,
                net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_STARTS);
        for (Holder<Structure> holder : c.structures()) {
            var start = manager.getStartForStructure(net.minecraft.core.SectionPos.bottomOf(chunk), holder.value(), chunk);
            if (start != null && start.isValid()) {
                return holder.unwrapKey().map(k -> k.location().toString()).orElse("village");
            }
        }
        return null;
    }

    /**
     * The village that starts at the candidate, by structure id, or null. Vanilla's presence
     * check answers "maybe" only after laying the village out ({@code canCreateStructure}), so a
     * maybe is taken as a yes here instead of generating the chunk's structure starts as well,
     * which /locate does and which doubled the cost.
     */
    @Nullable
    private static String villageAt(ServerLevel level, Candidate c) {
        var manager = level.structureManager();
        for (Holder<Structure> holder : c.structures()) {
            StructureCheckResult result = manager.checkStructurePresence(c.chunk(), holder.value(), c.placement(), false);
            if (result != StructureCheckResult.START_NOT_PRESENT) {
                return holder.unwrapKey().map(k -> k.location().toString()).orElse("village");
            }
        }
        return null;
    }
}
