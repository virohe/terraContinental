package com.terracontinental.atlas;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.Comparator;

/**
 * Generates every chunk of a {@link PregenMask}: the engine behind /continental pregenerate.
 *
 * <p>Chunky can't be used: with Distant Horizons it crashes single-player on world load, and the
 * command must work in game as well as on a server. This does what Chunky does: hold a ticket on
 * each chunk being made (FULL status, not ticking), ask the chunk cache for it, and let go when it
 * is done so it saves and unloads. With C2ME the generation itself runs on its worker threads; the
 * server thread only hands out tickets, so {@link #inFlight} decides how busy the workers are.</p>
 *
 * <p>Progress is saved to {@code <world>/data/terra_continental_pregen.json} every 30 s and when the
 * server stops; a started job resumes when the world loads again. Chunks past the saved mark that
 * were already done are just loaded and dropped, which is quick.</p>
 *
 * <p>When the chunks are done and Distant Horizons is installed, its own pregen builds the LODs
 * from them (the user's first look should be the whole continent).</p>
 */
public final class PregenJob {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE = "terra_continental_pregen.json";
    private static final TicketType<ChunkPos> TICKET =
            TicketType.create("terra_continental_pregen", Comparator.comparingLong(ChunkPos::toLong));

    /** What's saved. */
    public static final class State {
        public String continent;
        long seed;
        int total;
        /** Every chunk before this index is done. */
        int mark;
        String phase = "chunks"; // chunks, lods, done
        boolean paused;
        boolean lods = true;
        long millis; // generation time so far, across restarts
        int inFlight;
    }

    private static volatile PregenJob active;

    private final MinecraftServer server;
    private final ServerLevel level;
    private final PregenMask mask;
    final State state;
    private final BitSet done;
    private int next;        // next index to hand out
    private int running;     // tickets held
    private int doneCount;   // done since start of this session (mark included)
    private long sessionStart = System.currentTimeMillis();
    private long lastSave = System.currentTimeMillis(), lastLog = System.currentTimeMillis(), lastNotify;
    private int lastLogDone;
    private double rate; // chunks/s, smoothed
    private int failures;

    private PregenJob(MinecraftServer server, ServerLevel level, PregenMask mask, State state) {
        this.server = server;
        this.level = level;
        this.mask = mask;
        this.state = state;
        this.done = new BitSet(mask.chunks.length);
        this.done.set(0, Math.min(state.mark, mask.chunks.length));
        this.next = state.mark;
        this.doneCount = state.mark;
        this.lastLogDone = state.mark;
    }

    @Nullable
    public static PregenJob active() {
        return active;
    }

    public PregenMask mask() {
        return mask;
    }

    // -- starting and stopping ----------------------------------------------------------------------

    /** Starts a new job (the caller has checked none is running). */
    public static PregenJob start(MinecraftServer server, ServerLevel level, PregenMask mask, boolean lods, int inFlight) {
        State s = new State();
        s.continent = mask.continent.key();
        s.seed = level.getSeed();
        s.total = mask.chunks.length;
        s.lods = lods;
        s.inFlight = inFlight;
        PregenJob job = new PregenJob(server, level, mask, s);
        active = job;
        job.save();
        LOGGER.info("[pregen] {}: {} chunks ({} km2 of land, {} islands, chunks {},{} to {},{}), {} at a time",
                mask.continent.name(), mask.chunks.length, Math.round(mask.landKm2), mask.islands,
                mask.minChunkX, mask.minChunkZ, mask.maxChunkX, mask.maxChunkZ, s.inFlight);
        return job;
    }

    /** The saved job for this world, if one is unfinished. */
    @Nullable
    public static State saved(MinecraftServer server) {
        Path f = file(server);
        if (!Files.isRegularFile(f)) return null;
        try {
            State s = GSON.fromJson(Files.readString(f), State.class);
            return s == null || "done".equals(s.phase) || s.seed != server.overworld().getSeed() ? null : s;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[pregen] couldn't read {}: {}", f, e.toString());
            return null;
        }
    }

    /** Picks a saved job back up (its mask is rebuilt the same from the seed). */
    public static PregenJob resume(MinecraftServer server, ServerLevel level, PregenMask mask, State s) {
        PregenJob job = new PregenJob(server, level, mask, s);
        if (s.total != mask.chunks.length) {
            LOGGER.warn("[pregen] {}: the mask now has {} chunks, the saved job {}; restarting from the start",
                    s.continent, mask.chunks.length, s.total);
            s.total = mask.chunks.length;
            s.mark = 0;
            job.next = 0;
            job.doneCount = 0;
            job.done.clear();
        }
        active = job;
        LOGGER.info("[pregen] {}: resuming at {} / {} ({})", mask.continent.name(), s.mark, s.total, s.phase);
        return job;
    }

    public void cancel() {
        state.phase = "done";
        releaseAll();
        save();
        active = null;
        LOGGER.info("[pregen] {}: cancelled at {} / {}", mask.continent.name(), state.mark, state.total);
    }

    /** Server stopping: let go of everything, keep the job for next time. */
    public static void onServerStopping() {
        PregenJob j = active;
        if (j == null) return;
        j.releaseAll();
        j.save();
        active = null;
    }

    private void releaseAll() {
        // Tickets go with the server; nothing to undo on a stop. On cancel, outstanding futures finish
        // and remove their own tickets (onDone checks active).
    }

    // -- the loop -----------------------------------------------------------------------------------

    /** Server tick: hand out tickets up to the in-flight limit. */
    public void tick() {
        if (!"chunks".equals(state.phase) || state.paused) return;
        ServerChunkCache cache = level.getChunkSource();
        int startsThisTick = 0;
        int limit = Math.max(1, state.inFlight);
        while (running < limit && next < mask.chunks.length && startsThisTick < Math.max(16, limit / 2)) {
            int idx = next++;
            if (done.get(idx)) continue;
            ChunkPos pos = new ChunkPos(mask.chunks[idx]);
            running++;
            startsThisTick++;
            cache.addRegionTicket(TICKET, pos, 0, pos);
            ((com.terracontinental.mixin.ServerChunkCacheInvoker) cache)
                    .terra_continental$getChunkFutureMainThread(pos.x, pos.z, ChunkStatus.FULL, true)
                    .whenCompleteAsync((result, err) -> onDone(idx, pos, result == null || !result.isSuccess() || err != null), server);
        }
        long now = System.currentTimeMillis();
        if (running == 0 && next >= mask.chunks.length) {
            finishChunks();
            return;
        }
        if (now - lastLog >= 10_000) report(now);
        if (now - lastSave >= 30_000) save();
    }

    private void onDone(int idx, ChunkPos pos, boolean failed) {
        level.getChunkSource().removeRegionTicket(TICKET, pos, 0, pos);
        running--;
        if (active != this) return;
        if (failed && ++failures <= 20) LOGGER.warn("[pregen] chunk {} failed to generate", pos);
        done.set(idx);
        doneCount++;
        int m = state.mark;
        while (m < mask.chunks.length && done.get(m)) m++;
        state.mark = m;
    }

    private void report(long now) {
        double inst = (doneCount - lastLogDone) * 1000.0 / Math.max(1, now - lastLog);
        rate = rate == 0 ? inst : rate * 0.8 + inst * 0.2;
        state.millis += now - lastLog;
        lastLog = now;
        lastLogDone = doneCount;
        int left = mask.chunks.length - doneCount;
        String eta = rate > 0.1 ? duration((long) (left / rate)) : "?";
        String line = String.format("[pregen] %s: %d / %d chunks (%.1f%%), %.1f chunks/s, %d in flight, ETA %s",
                mask.continent.name(), doneCount, mask.chunks.length, 100.0 * doneCount / mask.chunks.length,
                rate, running, eta);
        LOGGER.info(line);
        if (now - lastNotify >= 60_000) {
            lastNotify = now;
            notifyOps(Component.literal(line.substring(9)).withStyle(ChatFormatting.GRAY));
        }
    }

    private void finishChunks() {
        long secs = (state.millis + System.currentTimeMillis() - lastLog) / 1000;
        state.millis += System.currentTimeMillis() - lastLog;
        lastLog = System.currentTimeMillis();
        LOGGER.info("[pregen] {}: all {} chunks done ({} of generating, {} failed)", mask.continent.name(),
                mask.chunks.length, duration(secs), failures);
        notifyOps(Component.literal("[pregen] " + mask.continent.name() + ": all " + mask.chunks.length
                + " chunks generated in " + duration(secs)).withStyle(ChatFormatting.GREEN));
        level.save(null, true, false);
        markPregenerated(server, mask.continent.key());
        if (state.lods && ModList.get().isLoaded("distanthorizons")) {
            state.phase = "lods";
            save();
            startLods();
        } else {
            state.phase = "done";
            save();
            active = null;
        }
    }

    /**
     * Distant Horizons builds LODs for the generated chunks through its own pregen, which takes a
     * centre and a radius in chunks: the mask's box. Its progress is in the log ("PregenManager").
     */
    private void startLods() {
        int cx = (mask.minChunkX + mask.maxChunkX) / 2, cz = (mask.minChunkZ + mask.maxChunkZ) / 2;
        int radius = Math.max(mask.maxChunkX - mask.minChunkX, mask.maxChunkZ - mask.minChunkZ) / 2 + 2;
        String cmd = "dh pregen start minecraft:overworld " + (cx << 4) + " " + (cz << 4) + " " + radius;
        LOGGER.info("[pregen] {}: building LODs: /{}", mask.continent.name(), cmd);
        try {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd);
            notifyOps(Component.literal("[pregen] Distant Horizons is building the LODs (/dh pregen status)")
                    .withStyle(ChatFormatting.GREEN));
        } catch (RuntimeException e) {
            LOGGER.warn("[pregen] couldn't start the DH pregen: {}", e.toString());
        }
        state.phase = "done";
        save();
        active = null;
    }

    public String status() {
        long now = System.currentTimeMillis();
        int left = mask.chunks.length - doneCount;
        String eta = rate > 0.1 ? duration((long) (left / rate)) : "?";
        return String.format("%s: %s%s, %d / %d chunks (%.1f%%), %.1f chunks/s, %d in flight (limit %d), ETA %s, running %s",
                mask.continent.name(), state.phase, state.paused ? " (paused)" : "", doneCount, mask.chunks.length,
                100.0 * doneCount / mask.chunks.length, rate, running, state.inFlight, eta,
                duration((now - sessionStart) / 1000));
    }

    public void setPaused(boolean p) {
        state.paused = p;
        save();
    }

    public void setInFlight(int n) {
        state.inFlight = n;
        save();
    }

    // -- helpers ------------------------------------------------------------------------------------

    private void notifyOps(Component msg) {
        for (var p : server.getPlayerList().getPlayers()) {
            if (server.getPlayerList().isOp(p.getGameProfile()) || server.isSingleplayerOwner(p.getGameProfile())) {
                p.sendSystemMessage(msg);
            }
        }
    }

    void save() {
        lastSave = System.currentTimeMillis();
        Path f = file(server);
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(state));
        } catch (IOException e) {
            LOGGER.warn("[pregen] couldn't save {}: {}", f, e.toString());
        }
    }

    private static final String DONE_FILE = "terra_continental_pregenerated.json";

    /** Keys of the continents whose chunks are all generated (what the World Atlas marks). */
    public static java.util.Set<String> pregenerated(MinecraftServer server) {
        Path f = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DONE_FILE);
        try {
            if (Files.isRegularFile(f)) {
                String[] keys = GSON.fromJson(Files.readString(f), String[].class);
                if (keys != null) return new java.util.TreeSet<>(java.util.Arrays.asList(keys));
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[pregen] couldn't read {}: {}", f, e.toString());
        }
        return new java.util.TreeSet<>();
    }

    private static void markPregenerated(MinecraftServer server, String key) {
        java.util.Set<String> keys = pregenerated(server);
        keys.add(key);
        Path f = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DONE_FILE);
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(keys));
        } catch (IOException e) {
            LOGGER.warn("[pregen] couldn't save {}: {}", f, e.toString());
        }
    }

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE);
    }

    static String duration(long secs) {
        long h = secs / 3600, m = secs / 60 % 60, s = secs % 60;
        return h > 0 ? h + "h " + m + "m" : m > 0 ? m + "m " + s + "s" : s + "s";
    }
}
