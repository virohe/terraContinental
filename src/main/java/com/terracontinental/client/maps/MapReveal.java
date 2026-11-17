package com.terracontinental.client.maps;

import com.mojang.logging.LogUtils;
import com.terracontinental.config.TerraClientConfig;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Maps the land you can see but haven't walked (user, 2026-11-16): "it's unrealistic (and maybe
 * unfair) to expect a player to run up and down a continent ... when they can see the land rendered
 * by LOD."
 *
 * <ul>
 *   <li><b>Vantage survey</b>, every couple of seconds when you've moved: line of sight from your eye
 *   over Distant Horizons' land, out to {@code base_range + height_factor * sqrt(height above the land
 *   around you)}, so a mountain top sees kilometres and a valley floor little. What is in sight is
 *   sketched into Xaero's World Map at {@link #COARSE} blocks a pixel.</li>
 *   <li><b>Spyglass</b>: where you look through a spyglass, the land sharpens to {@link #FINE} blocks a
 *   pixel after a dwell that grows with distance, a few chunks at a time ("slowly and not fully").</li>
 * </ul>
 * Full detail only comes from going there: Xaero's own writer replaces a sketch with the real chunk.
 *
 * <p>The heavy work (sampling the LODs, line of sight) runs on one worker thread; writes go to Xaero
 * on the client thread, a few dozen chunks a tick, a few map regions at a time so Xaero doesn't load
 * hundreds of them at once. What has been sketched is remembered per world in
 * {@code terra_continental/reveal/}.</p>
 */
public final class MapReveal {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final int COARSE = 32, FINE = 8;
    private static final int ACTIVE_REGIONS = 4;

    private static MapReveal instance;

    private final LodTerrain terrain = new LodTerrain();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Terra Continental map reveal");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final AtomicBoolean surveying = new AtomicBoolean(), looking = new AtomicBoolean();

    // chunk key -> finest level written (or queued); persisted
    private final LongOpenHashSet doneCoarse = new LongOpenHashSet(), doneFine = new LongOpenHashSet();
    // queued writes, by Xaero region (512 blocks)
    private final Map<Long, ArrayDeque<long[]>> byRegion = new HashMap<>();
    private final List<Long> activeRegions = new ArrayList<>();
    // when each active region first answered "not ready" (Xaero loading it); given up after a while
    private final Map<Long, Long> waitingSince = new HashMap<>();
    private final Long2FloatOpenHashMap dwell = new Long2FloatOpenHashMap();
    private Path saveFile;
    private boolean dirty;
    private double lastX = Double.NaN, lastY, lastZ;
    private long lastSurvey, lastSave;
    private int written, tick;

    // -- lifecycle --------------------------------------------------------------------------------

    public static boolean available() {
        return ModList.get().isLoaded("xaeroworldmap") && ModList.get().isLoaded("distanthorizons");
    }

    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn e) {
        if (!available()) return;
        instance = new MapReveal();
        instance.saveFile = saveFileFor(Minecraft.getInstance());
        instance.load();
    }

    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        MapReveal r = instance;
        instance = null;
        if (r != null) {
            r.save();
            r.worker.shutdownNow();
        }
    }

    public static void onClientTick(ClientTickEvent.Post e) {
        MapReveal r = instance;
        if (r != null) r.tick();
    }

    // -- smoke test hooks (client/SmokeTest) ----------------------------------------------------------

    public static String debugStatus() {
        MapReveal r = instance;
        if (r == null) return "not running (needs Xaero's World Map and Distant Horizons)";
        int queued;
        synchronized (r.byRegion) {
            queued = r.byRegion.values().stream().mapToInt(ArrayDeque::size).sum();
        }
        return String.format("written %d chunks, %d coarse and %d fine known, %d queued in %d regions, xaero ready %s, dh bound %s, broken %s",
                r.written, r.doneCoarse.size(), r.doneFine.size(), queued, r.byRegion.size(),
                XaeroBridge.ready(), r.terrain.bind(), XaeroBridge.broken());
    }

    /** As if the player looked 8 degrees down, due east, through a spyglass for 10 seconds. */
    public static void debugSpyglass() {
        MapReveal r = instance;
        LocalPlayer p = Minecraft.getInstance().player;
        if (r == null || p == null) return;
        Vec3 eye = p.getEyePosition();
        double pitch = Math.toRadians(8);
        Vec3 look = new Vec3(Math.cos(pitch), -Math.sin(pitch), 0);
        for (int i = 0; i < 40; i++) r.worker.execute(() -> r.spyglass(eye, look, 0.25f));
    }

    // -- per tick ---------------------------------------------------------------------------------

    private void tick() {
        tick++;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.level.dimension() != Level.OVERWORLD) return;
        if (!TerraClientConfig.get(TerraClientConfig.REVEAL, true) || XaeroBridge.broken()) return;
        if (!XaeroBridge.ready() || !terrain.bind()) return;

        Vec3 eye = p.getEyePosition();
        long now = System.currentTimeMillis();
        // Also every 30 s standing still: DH fills its LODs in over time.
        boolean moved = Double.isNaN(lastX) || Math.hypot(eye.x - lastX, eye.z - lastZ) > 48 || Math.abs(eye.y - lastY) > 8
                || now - lastSurvey > 30_000;
        if (moved && now - lastSurvey > 2000 && surveying.compareAndSet(false, true)) {
            lastSurvey = now;
            lastX = eye.x;
            lastY = eye.y;
            lastZ = eye.z;
            int skip = (mc.options.getEffectiveRenderDistance() - 1) * 16;
            worker.execute(() -> {
                try {
                    survey(eye, skip);
                } catch (Throwable t) {
                    LOGGER.warn("[reveal] survey failed", t);
                } finally {
                    surveying.set(false);
                }
            });
        }
        if (p.isScoping() && tick % 5 == 0 && looking.compareAndSet(false, true)) {
            Vec3 look = p.getViewVector(1f);
            worker.execute(() -> {
                try {
                    spyglass(eye, look, 0.25f);
                } catch (Throwable t) {
                    LOGGER.warn("[reveal] spyglass failed", t);
                } finally {
                    looking.set(false);
                }
            });
        }
        drain();
        if (dirty && now - lastSave > 60_000) save();
    }

    // -- vantage survey (worker) -------------------------------------------------------------------

    private void survey(Vec3 eye, int skip) {
        long t0 = System.nanoTime();
        int ex = (int) Math.floor(eye.x), ez = (int) Math.floor(eye.z);
        // Height above the land around: the middle and a ring 128 blocks out.
        double sum = 0;
        int n = 0;
        for (int k = 0; k < 9; k++) {
            int dx = k == 0 ? 0 : (int) Math.round(128 * Math.cos(k * Math.PI / 4));
            int dz = k == 0 ? 0 : (int) Math.round(128 * Math.sin(k * Math.PI / 4));
            int h = terrain.height(ex + dx, ez + dz, COARSE);
            if (h != Integer.MIN_VALUE) {
                sum += h;
                n++;
            }
        }
        double ground = n > 0 ? sum / n : eye.y - 2;
        double above = Math.max(2.0, eye.y - ground);
        double range = Math.min(TerraClientConfig.REVEAL_MAX_RANGE.get(),
                TerraClientConfig.REVEAL_BASE_RANGE.get() + TerraClientConfig.REVEAL_HEIGHT_FACTOR.get() * Math.sqrt(above));
        int rays = (int) Math.max(64, Math.min(4096, Math.ceil(2 * Math.PI * range / COARSE)));
        LongOpenHashSet seen = new LongOpenHashSet();
        for (int r = 0; r < rays; r++) {
            double a = 2 * Math.PI * r / rays, cos = Math.cos(a), sin = Math.sin(a);
            double maxSlope = Double.NEGATIVE_INFINITY;
            for (double d = COARSE; d <= range; d += COARSE) {
                int x = (int) Math.floor(eye.x + d * cos), z = (int) Math.floor(eye.z + d * sin);
                int h = terrain.height(x, z, COARSE);
                if (h == Integer.MIN_VALUE) continue;
                double slope = (h + 1 - eye.y) / d;
                if (slope >= maxSlope && d > skip) seen.add(cellKey(Math.floorDiv(x, COARSE), Math.floorDiv(z, COARSE)));
                if (slope > maxSlope) maxSlope = slope;
            }
        }
        List<long[]> jobs = new ArrayList<>();
        for (long cell : seen) {
            int cx = (int) (cell >> 32), cz = (int) cell;
            // A coarse cell is 2 x 2 chunks.
            for (int k = 0; k < 4; k++) {
                int chx = cx * 2 + (k & 1), chz = cz * 2 + (k >> 1);
                jobs.add(new long[]{chx, chz, COARSE});
            }
        }
        int queued = enqueue(jobs, eye);
        LOGGER.debug("[reveal] survey from {} above the land: range {} m, {} cells in sight, {} chunks queued ({} ms)",
                Math.round(above), Math.round(range), seen.size(), queued, (System.nanoTime() - t0) / 1_000_000);
    }

    // -- spyglass (worker) -------------------------------------------------------------------------

    private void spyglass(Vec3 eye, Vec3 look, float dt) {
        double max = TerraClientConfig.REVEAL_MAX_RANGE.get();
        double hitX = 0, hitZ = 0, dist = -1;
        for (double t = 16; t <= max; t += t < 512 ? 8 : 16) {
            double x = eye.x + look.x * t, y = eye.y + look.y * t, z = eye.z + look.z * t;
            int h = terrain.height((int) Math.floor(x), (int) Math.floor(z), FINE);
            if (h != Integer.MIN_VALUE && y <= h + 1) {
                hitX = x;
                hitZ = z;
                dist = t;
                break;
            }
        }
        if (dist < 0) return;
        double radius = Math.max(24, Math.min(400, 0.06 * dist));
        double need = TerraClientConfig.SPYGLASS_DWELL_SECONDS.get() * Math.max(1.0, dist / 1000.0);
        int c0x = (int) Math.floor((hitX - radius) / 16), c1x = (int) Math.floor((hitX + radius) / 16);
        int c0z = (int) Math.floor((hitZ - radius) / 16), c1z = (int) Math.floor((hitZ + radius) / 16);
        List<long[]> ready = new ArrayList<>();
        synchronized (dwell) {
            for (int cz = c0z; cz <= c1z; cz++) {
                for (int cx = c0x; cx <= c1x; cx++) {
                    double dx = cx * 16 + 8 - hitX, dz = cz * 16 + 8 - hitZ;
                    if (dx * dx + dz * dz > radius * radius) continue;
                    long key = cellKey(cx, cz);
                    synchronized (doneFine) {
                        if (doneFine.contains(key)) continue;
                    }
                    float d = dwell.addTo(key, dt) + dt;
                    if (d >= need) ready.add(new long[]{cx, cz, FINE, (long) (dx * dx + dz * dz)});
                }
            }
        }
        // A few chunks a look, nearest the middle first: it sharpens slowly, and never past FINE.
        ready.sort(Comparator.comparingLong(j -> j[3]));
        List<long[]> jobs = ready.subList(0, Math.min(8, ready.size()));
        for (long[] j : jobs) {
            // Warm the four fine columns now, off the client thread.
            for (int k = 0; k < 4; k++) terrain.at((int) j[0] * 16 + (k & 1) * 8, (int) j[1] * 16 + (k >> 1) * 8, FINE);
        }
        enqueue(new ArrayList<>(jobs), eye);
    }

    // -- the queue --------------------------------------------------------------------------------

    private int enqueue(List<long[]> jobs, Vec3 eye) {
        int n = 0;
        synchronized (byRegion) {
            for (long[] j : jobs) {
                long key = cellKey((int) j[0], (int) j[1]);
                LongOpenHashSet done = j[2] == FINE ? doneFine : doneCoarse;
                synchronized (done) {
                    if (!done.add(key)) continue;
                }
                if (j[2] == COARSE) {
                    synchronized (doneFine) {
                        if (doneFine.contains(key)) continue;
                    }
                }
                byRegion.computeIfAbsent(cellKey((int) j[0] >> 5, (int) j[1] >> 5), k -> new ArrayDeque<>()).add(j);
                n++;
            }
        }
        if (n > 0) dirty = true;
        return n;
    }

    /** Client thread: write queued chunks into Xaero, a few regions at a time. */
    private void drain() {
        int budget = TerraClientConfig.REVEAL_CHUNKS_PER_TICK.get();
        synchronized (byRegion) {
            if (byRegion.isEmpty()) return;
            activeRegions.removeIf(k -> !byRegion.containsKey(k));
            if (activeRegions.size() < ACTIVE_REGIONS) {
                Minecraft mc = Minecraft.getInstance();
                int prx = mc.player == null ? 0 : mc.player.getBlockX() >> 9, prz = mc.player == null ? 0 : mc.player.getBlockZ() >> 9;
                byRegion.keySet().stream()
                        .filter(k -> !activeRegions.contains(k))
                        .sorted(Comparator.comparingLong(k -> {
                            long dx = (k >> 32) - prx, dz = (int) (long) k - prz;
                            return dx * dx + dz * dz;
                        }))
                        .limit(ACTIVE_REGIONS - activeRegions.size())
                        .forEach(activeRegions::add);
            }
            for (Iterator<Long> it = activeRegions.iterator(); it.hasNext() && budget > 0; ) {
                Long rk = it.next();
                ArrayDeque<long[]> q = byRegion.get(rk);
                while (q != null && !q.isEmpty() && budget > 0) {
                    long[] j = q.peek();
                    XaeroBridge.Result res = writeChunk((int) j[0], (int) j[1], (int) j[2]);
                    if (res == XaeroBridge.Result.NOT_READY) {
                        // Region still loading: next tick. One that never loads is dropped after 30 s
                        // and forgotten, so a later survey queues it again.
                        long since = waitingSince.computeIfAbsent(rk, k -> System.currentTimeMillis());
                        if (System.currentTimeMillis() - since > 30_000) {
                            for (long[] d : q) forget(d);
                            q.clear();
                        }
                        break;
                    }
                    waitingSince.remove(rk);
                    q.poll();
                    budget--;
                    if (res == XaeroBridge.Result.WRITTEN) written++;
                    else if (res == null) forget(j); // no LOD there yet: try again on a later survey
                }
                if (q == null || q.isEmpty()) {
                    byRegion.remove(rk);
                    waitingSince.remove(rk);
                    it.remove();
                }
            }
        }
    }

    private XaeroBridge.Result writeChunk(int chx, int chz, int level) {
        XaeroBridge.Column[] cols = new XaeroBridge.Column[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                XaeroBridge.Column c = terrain.at(chx * 16 + x, chz * 16 + z, level);
                cols[z * 16 + x] = c == LodTerrain.NONE ? null : c;
            }
        }
        if (cols[0] == null && cols[255] == null && cols[136] == null) return null; // no LOD here (yet)
        return XaeroBridge.write(chx, chz, level, cols);
    }

    private void forget(long[] j) {
        LongOpenHashSet done = j[2] == FINE ? doneFine : doneCoarse;
        synchronized (done) {
            done.remove(cellKey((int) j[0], (int) j[1]));
        }
    }

    private static long cellKey(int x, int z) {
        return (long) x << 32 | (z & 0xFFFFFFFFL);
    }

    // -- memory of what's been sketched --------------------------------------------------------------

    private static Path saveFileFor(Minecraft mc) {
        String id;
        if (mc.getSingleplayerServer() != null) {
            // The world's own folder: getWorldPath(ROOT) is "<saves>/<world>/." and normalises to
            // "<saves>/<world>". Taking its parent named every world "saves", so all single-player
            // worlds shared one file and a new world skipped chunks revealed in another.
            id = "sp-" + mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .toAbsolutePath().normalize().getFileName();
        } else if (mc.getCurrentServer() != null) {
            id = "mp-" + mc.getCurrentServer().ip;
        } else {
            id = "unknown";
        }
        id = id.replaceAll("[^A-Za-z0-9._-]", "_");
        return mc.gameDirectory.toPath().resolve("terra_continental").resolve("reveal").resolve(id + ".bin");
    }

    private void load() {
        if (!Files.isRegularFile(saveFile)) return;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(saveFile)))) {
            if (in.readInt() != 1) return;
            for (LongOpenHashSet set : new LongOpenHashSet[]{doneCoarse, doneFine}) {
                int count = in.readInt();
                for (int i = 0; i < count; i++) set.add(in.readLong());
            }
            LOGGER.info("[reveal] {} chunks sketched coarse, {} fine, from {}", doneCoarse.size(), doneFine.size(), saveFile.getFileName());
        } catch (IOException e) {
            LOGGER.warn("[reveal] couldn't read {}: {}", saveFile, e.toString());
        }
    }

    private void save() {
        lastSave = System.currentTimeMillis();
        dirty = false;
        try {
            Files.createDirectories(saveFile.getParent());
            Path tmp = saveFile.resolveSibling(saveFile.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(1);
                for (LongOpenHashSet set : new LongOpenHashSet[]{doneCoarse, doneFine}) {
                    synchronized (set) {
                        out.writeInt(set.size());
                        for (long k : set) out.writeLong(k);
                    }
                }
            }
            Files.move(tmp, saveFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOGGER.debug("[reveal] saved; {} chunks written this session", written);
        } catch (IOException e) {
            LOGGER.warn("[reveal] couldn't save {}: {}", saveFile, e.toString());
        }
    }
}
