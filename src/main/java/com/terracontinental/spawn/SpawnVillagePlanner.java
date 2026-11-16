package com.terracontinental.spawn;

import com.mojang.logging.LogUtils;
import com.terracontinental.config.TerraConfig;
import com.terracontinental.config.TerraStructureConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Places the spawn continent's villages as a planned set instead of leaving them to the random
 * village grids (audit 2026-10-18: the grids gave 0-7 per spawn continent, mean 3, clumped,
 * mostly plains). Up to 8 players start on the spawn continent and each needs a village, plus the
 * central village everyone visits first, so the plan guarantees {@code count} villages (default 10,
 * never fewer than 9 when the land allows), spread across the whole landmass.
 *
 * <p>Computed once per world, lazily, the first time a spawn-village placement or a suppressed
 * village grid asks about a chunk. Everything is read from the generator: no chunks are built.</p>
 * <ol>
 *   <li><b>Land.</b> A 128-block lattice around the origin; a point is land when its biome at sea
 *       level is not an ocean (rivers and lakes stay land, so they don't cut the continent). The spawn
 *       continent is the land connected to the origin.</li>
 *   <li><b>Candidates.</b> Every 256 blocks on that land: surface height and biome, and which village
 *       types accept the biome.</li>
 *   <li><b>Central village</b> on the candidate nearest the land's centroid that passes the checks.</li>
 *   <li><b>The rest</b> by farthest-point selection: each next village goes on the candidate farthest
 *       from every village so far (never closer than {@code min_gap}), so they fill the land evenly
 *       instead of clumping. Among the farthest few, a candidate that offers a village type used
 *       least so far wins, and it gets that type.</li>
 *   <li><b>Checks</b> on each pick: not steep (relief within 48 blocks at most {@code max_relief},
 *       within 128 blocks at most twice that, and no crags), not on water, and the game's own {@code findValidGenerationPoint}
 *       for the assigned type.</li>
 * </ol>
 */
public final class SpawnVillagePlanner {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Village type key -> structure id. Order = tie-break order. */
    public static final Map<String, String> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put("plains", "minecraft:village_plains");
        TYPES.put("taiga", "minecraft:village_taiga");
        TYPES.put("snowy", "minecraft:village_snowy");
        TYPES.put("desert", "minecraft:village_desert");
        TYPES.put("savanna", "minecraft:village_savanna");
        TYPES.put("skyris", "biomeswevegone:village/skyris");
        TYPES.put("salem", "biomeswevegone:village/salem");
        TYPES.put("red_rock", "biomeswevegone:village/red_rock");
        TYPES.put("swamp", "biomeswevegone:village/swamp");
        // Climate villages on temperate ground (desert, savanna and snowy buildings where a plains or taiga
        // village could stand), so the temperate spawn continent can still have them. Capped by
        // themed_max; they bring their buildings, not their biome, so sand and ice stay rare.
        TYPES.put("desert_themed", "terra_continental:spawn_village/desert");
        TYPES.put("savanna_themed", "terra_continental:spawn_village/savanna");
        TYPES.put("snowy_themed", "terra_continental:spawn_village/snowy");
    }

    private static final int LAND_STEP = 128;
    private static final int CAND_STEP = 256;
    private static final int[] OFFSETS = {0, -40, 40, -20, 20, -48, 48};
    /** Spots rejected near the centre, further out, by the structure, and passed; and time in checks: logged with the plan. */
    private static final int[] CHECKS = new int[4];
    private static final long[] CHECK_NANOS = new long[1];

    public record Site(ChunkPos chunk, String type, boolean central, int y, int relief) {
    }

    public static final class Plan {
        public final long seed;
        public final int originX, originZ, n;
        final boolean[] land;
        public final Map<Long, Site> sites = new LinkedHashMap<>();
        public double centroidX, centroidZ, areaKm2, gapUsed;
        public String note = "";
        /** Share of the land farther than the even-spread radius from any village; see {@link #assess}. */
        public double uncovered;
        /** Why the village spread looks poor, or empty when it looks fine. */
        public final List<String> problems = new ArrayList<>();

        Plan(long seed, int originX, int originZ, int n) {
            this.seed = seed;
            this.originX = originX;
            this.originZ = originZ;
            this.n = n;
            this.land = new boolean[n * n];
        }

        /** Is the block on the spawn continent's land? */
        public boolean onContinent(int bx, int bz) {
            int ix = Math.round((bx - originX) / (float) LAND_STEP);
            int iz = Math.round((bz - originZ) / (float) LAND_STEP);
            return ix >= 0 && iz >= 0 && ix < n && iz < n && land[iz * n + ix];
        }

        /**
         * Should the random village grids stay off this block? On the spawn continent, within two
         * lattice steps of its coast (the land test is coarse there), and within min_gap of a planned
         * village (audit 2026-10-19: a grid village 1.1 km from a planned one sat on a coastal cell
         * the lattice called sea).
         */
        public boolean suppressGrid(int bx, int bz) {
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    if (onContinent(bx + dx * LAND_STEP, bz + dz * LAND_STEP)) return true;
            for (Site s : sites.values()) {
                if (Math.hypot(s.chunk().getMiddleBlockX() - bx, s.chunk().getMiddleBlockZ() - bz) < gapUsed) return true;
            }
            return false;
        }

        @Nullable
        public Site siteAt(int chunkX, int chunkZ) {
            return sites.get(ChunkPos.asLong(chunkX, chunkZ));
        }

        private int[] coast;

        /**
         * The continent's outer coast as a closed loop of its land cells, in order round the continent:
         * {x, z, seaDx, seaDz} per cell, the sea direction pointing at the off-continent neighbour the trace
         * passed. Terra Towns spaces its starting hamlets along it (the user, 2026-10-31: its own 32-block
         * biome trace of the coast ran 120000 cells without closing and bunched all eight hamlets in the
         * west). Lakes inside the continent aren't traced; the edge of the plan counts as coast.
         */
        public synchronized int[] coast() {
            if (coast == null) coast = traceCoast();
            return coast;
        }

        /** Clockwise Moore neighbours, starting west. */
        private static final int[][] MOORE = {{-1, 0}, {-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}};

        private boolean landCell(int ix, int iz) {
            return ix >= 0 && iz >= 0 && ix < n && iz < n && land[iz * n + ix];
        }

        /** Moore-neighbour trace from the first land cell in raster order (its west neighbour is off the land). */
        private int[] traceCoast() {
            int start = -1;
            for (int i = 0; i < n * n && start < 0; i++) if (land[i]) start = i;
            if (start < 0) return new int[0];
            int sx = start % n, sz = start / n;
            int cx = sx, cz = sz, bx = sx - 1, bz = sz;
            int fx = sx, fz = sz; // where the trace first went from the start
            List<int[]> out = new ArrayList<>();
            for (int steps = 0; steps < 4 * n * n; steps++) {
                int[] cell = {originX + cx * LAND_STEP, originZ + cz * LAND_STEP,
                        Integer.signum(bx - cx), Integer.signum(bz - cz)};
                int from = 0;
                for (int k = 0; k < 8; k++) if (MOORE[k][0] == bx - cx && MOORE[k][1] == bz - cz) from = k;
                int px = bx, pz = bz, nx = cx, nz = cz;
                boolean moved = false;
                for (int k = 1; k <= 8 && !moved; k++) {
                    int[] d = MOORE[(from + k) % 8];
                    nx = cx + d[0];
                    nz = cz + d[1];
                    if (landCell(nx, nz)) moved = true;
                    else {
                        px = nx;
                        pz = nz;
                    }
                }
                if (!moved) { // a one-cell continent
                    out.add(cell);
                    break;
                }
                // Closed: back at the start and about to repeat the first step.
                if (steps > 0 && cx == sx && cz == sz && nx == fx && nz == fz) break;
                if (steps == 0) {
                    fx = nx;
                    fz = nz;
                }
                out.add(cell);
                bx = px;
                bz = pz;
                cx = nx;
                cz = nz;
            }
            int[] flat = new int[out.size() * 4];
            for (int i = 0; i < out.size(); i++) System.arraycopy(out.get(i), 0, flat, i * 4, 4);
            return flat;
        }
    }

    private static volatile Plan plan;
    private static volatile Object planFor;

    private SpawnVillagePlanner() {
    }

    public static boolean enabled() {
        try {
            return TerraStructureConfig.SPEC.isLoaded() && TerraStructureConfig.SPAWN_VILLAGES_ENABLED.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /** The overworld's plan, or null if {@code state} isn't the overworld's or the planner is off. */
    @Nullable
    public static Plan forState(ChunkGeneratorStructureState state) {
        if (!enabled()) return null;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        ServerLevel overworld = server.overworld();
        if (overworld == null || overworld.getChunkSource().getGeneratorState() != state) return null;
        return get(overworld);
    }

    @Nullable
    public static Plan get(ServerLevel level) {
        Object key = level.getChunkSource().getGeneratorState();
        Plan p = plan;
        if (p != null && planFor == key) return p;
        synchronized (SpawnVillagePlanner.class) {
            if (plan != null && planFor == key) return plan;
            long t = System.nanoTime();
            java.util.Arrays.fill(CHECKS, 0);
            CHECK_NANOS[0] = 0;
            try {
                p = compute(level);
            } catch (Throwable e) {
                LOGGER.error("[spawn-villages] planning failed; spawn continent keeps the random village grids", e);
                p = null;
            } finally {
                com.terracontinental.plan.LoadingStatus.message(null);
                com.terracontinental.plan.LoadingStatus.villagePlanDone();
            }
            plan = p;
            planFor = key;
            if (p != null) {
                LOGGER.info("[spawn-villages] {} villages planned on a {} km2 spawn continent in {} ms (gap {} blocks){}",
                        p.sites.size(), Math.round(p.areaKm2), (System.nanoTime() - t) / 1_000_000L, Math.round(p.gapUsed), p.note);
                LOGGER.info("[spawn-villages] spots checked: {} too steep or wet nearby, {} too rough further out, {} no structure fit, {} passed; {} ms in checks",
                        CHECKS[0], CHECKS[1], CHECKS[2], CHECKS[3], CHECK_NANOS[0] / 1_000_000L);
                p.sites.values().forEach(s -> LOGGER.info("[spawn-villages]   {} {} at {}, {} (y {}, relief {})",
                        s.central() ? "central" : "village", s.type(), s.chunk().getMiddleBlockX(), s.chunk().getMiddleBlockZ(), s.y(), s.relief()));
            }
            return p;
        }
    }

    // ------------------------------------------------------------------------------------------

    private record Cand(int x, int z, int h, List<String> types) {
    }

    private static Plan compute(ServerLevel level) {
        MinecraftServer server = level.getServer();
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        BiomeSource bs = gen.getBiomeSource();
        Climate.Sampler sampler = rs.sampler();
        long seed = level.getSeed();
        int sea = gen.getSeaLevel();
        int radius = centralRadius();
        int half = (int) Math.ceil(2.2 * radius / LAND_STEP);
        int n = 2 * half + 1;
        Plan p = new Plan(seed, -half * LAND_STEP, -half * LAND_STEP, n);
        int count = TerraStructureConfig.SPAWN_VILLAGE_COUNT.get();
        double minGap = TerraStructureConfig.SPAWN_VILLAGE_MIN_GAP.get();
        int maxRelief = TerraStructureConfig.SPAWN_VILLAGE_MAX_RELIEF.get();
        themedMax = TerraStructureConfig.SPAWN_VILLAGE_THEMED_MAX.get();
        com.terracontinental.plan.LoadingStatus.villagePlanStart(count);
        com.terracontinental.plan.LoadingStatus.message("Placing villages: finding the spawn continent");

        // 1. Land: biome at sea level is not an ocean. Read from the world plan (same lattice and test);
        // points beyond its reach are sampled here.
        boolean[] raw = new boolean[n * n];
        com.terracontinental.plan.WorldPlan worldPlan = com.terracontinental.plan.WorldPlan.get(level);
        if (worldPlan != null) worldPlan.awaitReach(half * LAND_STEP);
        IntStream.range(0, n * n).parallel().forEach(i -> {
            int x = p.originX + (i % n) * LAND_STEP, z = p.originZ + (i / n) * LAND_STEP;
            if (worldPlan != null && worldPlan.isKnown(x, z)) {
                raw[i] = worldPlan.isLand(x, z);
                return;
            }
            Holder<Biome> b = bs.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(sea), QuartPos.fromBlock(z), sampler);
            raw[i] = !isOcean(b);
        });
        int start = nearestLand(raw, n, half, half);
        if (start < 0) {
            p.note = " (no land near the origin)";
            return p;
        }
        ArrayDeque<Integer> q = new ArrayDeque<>();
        q.add(start);
        p.land[start] = true;
        long sx = 0, sz = 0, cells = 0;
        while (!q.isEmpty()) {
            int i = q.poll();
            int ix = i % n, iz = i / n;
            sx += ix;
            sz += iz;
            cells++;
            int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : nb) {
                int jx = ix + d[0], jz = iz + d[1];
                if (jx < 0 || jz < 0 || jx >= n || jz >= n) continue;
                int j = jz * n + jx;
                if (raw[j] && !p.land[j]) {
                    p.land[j] = true;
                    q.add(j);
                }
            }
        }
        p.centroidX = p.originX + (double) sx / cells * LAND_STEP;
        p.centroidZ = p.originZ + (double) sz / cells * LAND_STEP;
        p.areaKm2 = cells * (double) LAND_STEP * LAND_STEP / 1e6;

        // 2. Candidates on the land, with the village types their surface biome accepts.
        var structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Map<String, Holder<Structure>> holders = new LinkedHashMap<>();
        TYPES.forEach((k, id) -> structures.getHolder(ResourceKey.create(Registries.STRUCTURE, ResourceLocation.parse(id)))
                .ifPresent(h -> holders.put(k, h)));
        List<int[]> spots = new ArrayList<>();
        for (int z = p.originZ; z <= p.originZ + (n - 1) * LAND_STEP; z += CAND_STEP) {
            for (int x = p.originX; x <= p.originX + (n - 1) * LAND_STEP; x += CAND_STEP) {
                // keep one lattice step off the coast
                if (p.onContinent(x, z) && p.onContinent(x + LAND_STEP, z) && p.onContinent(x - LAND_STEP, z)
                        && p.onContinent(x, z + LAND_STEP) && p.onContinent(x, z - LAND_STEP)) {
                    spots.add(new int[]{x, z});
                }
            }
        }
        com.terracontinental.plan.LoadingStatus.message("Placing villages: measuring " + spots.size() + " spots");
        List<Cand> cands = Collections.synchronizedList(new ArrayList<>());
        spots.parallelStream().forEach(s -> {
            if (!server.isRunning()) return; // the player asked for a new seed: this world is going
            int h = gen.getBaseHeight(s[0], s[1], Heightmap.Types.WORLD_SURFACE_WG, level, rs);
            if (h <= sea) return; // water surface
            Holder<Biome> b = bs.getNoiseBiome(QuartPos.fromBlock(s[0]), QuartPos.fromBlock(h), QuartPos.fromBlock(s[1]), sampler);
            List<String> ok = new ArrayList<>();
            holders.forEach((k, hs) -> {
                if (hs.value().biomes().contains(b)) ok.add(k);
            });
            if (themedMax == 0) ok.removeIf(t -> t.endsWith("_themed"));
            if (!ok.isEmpty()) cands.add(new Cand(s[0], s[1], h, ok));
        });
        List<Cand> pool = new ArrayList<>(cands);
        for (Cand c : pool) com.terracontinental.plan.LoadingStatus.villageCandidate(c.x, c.z);
        com.terracontinental.plan.LoadingStatus.message("Placing villages: 0 of " + count + " placed");
        pool.sort((a, b) -> a.x != b.x ? Integer.compare(a.x, b.x) : Integer.compare(a.z, b.z)); // deterministic order
        Random rng = new Random(seed ^ 0x5EED_7111_A6E5L);
        Collections.shuffle(pool, rng);

        Map<String, Integer> used = new HashMap<>();
        List<double[]> chosen = new ArrayList<>();

        // 3. Central village: nearest the centroid.
        List<Cand> byCentre = new ArrayList<>(pool);
        byCentre.sort((a, b) -> Double.compare(dist2(a, p.centroidX, p.centroidZ), dist2(b, p.centroidX, p.centroidZ)));
        for (Cand c : byCentre) {
            if (!server.isRunning()) return stopped(p);
            Site s = check(level, gen, bs, rs, seed, sea, c, pickType(c.types, used, rng), holders, maxRelief, true);
            if (s != null) {
                accept(p, s, used, chosen);
                pool.remove(c);
                break;
            }
        }

        // 4. The rest: farthest point, least-used type first among the near-farthest.
        double gap = minGap;
        p.gapUsed = minGap;
        java.util.Set<Cand> rejected = new java.util.HashSet<>();
        while (p.sites.size() < count && !chosen.isEmpty()) {
            if (!server.isRunning()) return stopped(p);
            List<Cand> open = new ArrayList<>();
            double best = 0;
            Map<Cand, Double> d = new HashMap<>();
            for (Cand c : pool) {
                if (rejected.contains(c)) continue;
                double m = Double.MAX_VALUE;
                for (double[] o : chosen) m = Math.min(m, Math.hypot(c.x - o[0], c.z - o[1]));
                if (m >= gap) {
                    open.add(c);
                    d.put(c, m);
                    best = Math.max(best, m);
                }
            }
            if (open.isEmpty()) {
                if (gap <= minGap * 0.5) break;
                gap *= 0.9;
                p.gapUsed = gap;
                continue;
            }
            final double bestD = best;
            List<Cand> top = new ArrayList<>();
            // A rarer village type may win over a spot up to 30% nearer (was 20%: still 68% plains).
            for (Cand c : open) if (d.get(c) >= 0.7 * bestD) top.add(c);
            // least-used type available, then farthest
            top.sort((a, b) -> {
                int ua = minUse(a.types, used), ub = minUse(b.types, used);
                if (ua != ub) return Integer.compare(ua, ub);
                return Double.compare(d.get(b), d.get(a));
            });
            boolean placed = false;
            for (Cand c : top) {
                Site s = check(level, gen, bs, rs, seed, sea, c, pickType(c.types, used, rng), holders, maxRelief, false);
                if (s != null) {
                    accept(p, s, used, chosen);
                    pool.remove(c);
                    placed = true;
                    break;
                }
                rejected.add(c);
            }
            if (!placed && top.size() == open.size()) {
                // every open spot failed its checks at this gap: relax
                if (gap <= minGap * 0.5) break;
                gap *= 0.9;
                p.gapUsed = gap;
            }
        }
        if (p.sites.size() < count) p.note = " (land ran out at " + p.sites.size() + ")";
        assess(p, count, minGap);
        return p;
    }

    /**
     * The world is being thrown away (the loading screen's "Try a New Seed" stops the server): stop
     * planning at once instead of spending another half minute on checks nobody will use.
     */
    private static Plan stopped(Plan p) {
        p.note = " (stopped: the server is shutting down)";
        return p;
    }

    /**
     * At most this share of the land may lie beyond {@link #COVER_RADIUS} of every village. The 180 km2
     * test continent (seed 4151832666630476678), well spread, has 16%; the user's 2026-11-10 world 42%.
     */
    static final double UNCOVERED_WARN = 0.25;
    /**
     * The even-spread radius, as a share of sqrt(land per village). Villages on a hexagonal grid
     * leave no land farther than 0.62 of that, so 0.75 counts only real gaps.
     */
    static final double COVER_RADIUS = 0.75;

    /**
     * Does this spawn continent give its villages a poor spread? Then the loading screen says so, so
     * the player can try a new seed before the hamlets are built (the user, 2026-11-10, on a 58 km2
     * continent where 8 of 10 villages fit, at half the gap, all down one side). Two tests: not all
     * villages fit, or a large share of the land is far from every village (steep or broken ground
     * the planner had to leave empty). A shrunken gap alone is no fault: on a small continent the
     * villages just sit closer (the user, 2026-11-10: 10 villages at gap 2187 on 63 km2 was "pretty good").
     */
    static void assess(Plan p, int count, double minGap) {
        if (p.sites.isEmpty()) {
            p.problems.add("no village fit");
        } else {
            double radius = COVER_RADIUS * Math.sqrt(p.areaKm2 * 1e6 / count);
            long land = 0, far = 0;
            for (int i = 0; i < p.n * p.n; i++) {
                if (!p.land[i]) continue;
                land++;
                double x = p.originX + (i % p.n) * LAND_STEP, z = p.originZ + (i / p.n) * LAND_STEP;
                boolean near = false;
                for (Site s : p.sites.values()) {
                    if (Math.hypot(s.chunk().getMiddleBlockX() - x, s.chunk().getMiddleBlockZ() - z) <= radius) {
                        near = true;
                        break;
                    }
                }
                if (!near) far++;
            }
            p.uncovered = land == 0 ? 0 : far / (double) land;
            if (p.sites.size() < count) p.problems.add(p.sites.size() + " of " + count + " villages fit");
            if (p.uncovered > UNCOVERED_WARN) {
                p.problems.add(Math.round(p.uncovered * 100) + "% of the land over " + Math.round(radius) + " blocks from a village");
            }
        }
        LOGGER.info("[spawn-villages] spread: {}% of the land over the even-spread radius from a village, gap {}; {}",
                Math.round(p.uncovered * 100), Math.round(p.gapUsed),
                p.problems.isEmpty() ? "looks fine" : "non-optimal: " + String.join(", ", p.problems));
        if (!p.problems.isEmpty()) {
            com.terracontinental.plan.LoadingStatus.warn("Village placement appears to be non-optimal");
        }
    }

    private static void accept(Plan p, Site s, Map<String, Integer> used, List<double[]> chosen) {
        p.sites.put(s.chunk().toLong(), s);
        used.merge(s.type(), 1, Integer::sum);
        chosen.add(new double[]{s.chunk().getMiddleBlockX(), s.chunk().getMiddleBlockZ()});
        com.terracontinental.plan.LoadingStatus.village(s.chunk().getMiddleBlockX(), s.chunk().getMiddleBlockZ());
        com.terracontinental.plan.LoadingStatus.message("Placing villages: " + p.sites.size() + " of "
                + Math.max(p.sites.size(), com.terracontinental.plan.LoadingStatus.villageTarget()) + " placed");
    }

    private static int themedMax;

    /** The types a spot may still take: climate villages drop out once themed_max are placed. */
    private static List<String> allowed(List<String> types, Map<String, Integer> used) {
        int themed = 0;
        for (var e : used.entrySet()) if (e.getKey().endsWith("_themed")) themed += e.getValue();
        if (themed < themedMax) return types;
        List<String> out = new ArrayList<>();
        for (String t : types) if (!t.endsWith("_themed")) out.add(t);
        return out;
    }

    private static int minUse(List<String> types, Map<String, Integer> used) {
        int m = Integer.MAX_VALUE;
        for (String t : allowed(types, used)) m = Math.min(m, used.getOrDefault(t, 0));
        return m;
    }

    /** The least-used type this spot allows; ties by fixed order, then seeded. */
    private static String pickType(List<String> types, Map<String, Integer> used, Random rng) {
        int m = minUse(types, used);
        List<String> best = new ArrayList<>();
        for (String t : allowed(types, used)) if (used.getOrDefault(t, 0) == m) best.add(t);
        return best.get(rng.nextInt(best.size()));
    }

    @Nullable
    private static Site check(ServerLevel level, ChunkGenerator gen, BiomeSource bs, RandomState rs, long seed, int sea,
                              Cand c, String type, Map<String, Holder<Structure>> holders, int maxRelief, boolean central) {
        long t = System.nanoTime();
        com.terracontinental.plan.LoadingStatus.villageProbe(c.x, c.z);
        Site s = null;
        try {
            s = checkSite(level, gen, bs, rs, seed, sea, c, type, holders, maxRelief, central);
            return s;
        } finally {
            CHECK_NANOS[0] += System.nanoTime() - t;
            if (s == null) com.terracontinental.plan.LoadingStatus.villageRejected(c.x, c.z);
        }
    }

    @Nullable
    private static Site checkSite(ServerLevel level, ChunkGenerator gen, BiomeSource bs, RandomState rs, long seed, int sea,
                                  Cand c, String type, Map<String, Holder<Structure>> holders, int maxRelief, boolean central) {
        com.terracontinental.plan.WorldPlan wp = com.terracontinental.plan.WorldPlan.get(level);
        // Level ground over the village's whole spread (VillageFootprint): near the centre, then out to
        // 128 blocks. Audit 2026-10-19: 3x3 samples 8 blocks off passed a 30-block cliff inside the
        // village; playtest 2026-10-31: crags 100-128 blocks out.
        ChunkPos chunk = new ChunkPos(c.x >> 4, c.z >> 4);
        int mx = chunk.getMiddleBlockX(), mz = chunk.getMiddleBlockZ();
        java.util.function.IntBinaryOperator ground = (x, z) -> height(wp, gen, level, rs, x, z);
        int fit = VillageFootprint.check(ground, sea, mx, mz, maxRelief);
        if (fit != VillageFootprint.OK) { CHECKS[fit - 1]++; return null; }
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int dx : OFFSETS) {
            for (int dz : OFFSETS) {
                int h = ground.applyAsInt(mx + dx, mz + dz);
                lo = Math.min(lo, h);
                hi = Math.max(hi, h);
            }
        }
        Holder<Structure> holder = holders.get(type);
        if (holder == null) return null;
        Structure s = holder.value();
        Structure.GenerationContext ctx = new Structure.GenerationContext(level.registryAccess(), gen, bs, rs,
                level.getServer().getStructureManager(), seed, chunk, level, s.biomes()::contains);
        var stub = s.findValidGenerationPoint(ctx);
        if (stub.isEmpty()) { CHECKS[2]++; return null; }
        CHECKS[3]++;
        return new Site(chunk, type, central, stub.get().position().getY(), hi - lo);
    }

    /**
     * Surface height for the footprint checks: the world plan's estimate (within ~1 block, and
     * microseconds) when there is a plan, else getBaseHeight (50-65 ms a call: with it, rejecting
     * the crag sites took the planner from 97 s to 265 s on the test seed). Water reads as sea level,
     * as WORLD_SURFACE_WG does.
     */
    private static int height(@Nullable com.terracontinental.plan.WorldPlan wp, ChunkGenerator gen, ServerLevel level,
                              RandomState rs, int x, int z) {
        int h = wp == null ? com.terracontinental.plan.WorldPlan.NO_HEIGHT : wp.estimateHeight(x, z);
        if (h == com.terracontinental.plan.WorldPlan.NO_HEIGHT) {
            return gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
        }
        return Math.max(h, gen.getSeaLevel());
    }

    private static double dist2(Cand c, double x, double z) {
        double dx = c.x - x, dz = c.z - z;
        return dx * dx + dz * dz;
    }

    private static int nearestLand(boolean[] raw, int n, int cx, int cz) {
        for (int r = 0; r < n / 2; r++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = cx + dx, z = cz + dz;
                    if (x >= 0 && z >= 0 && x < n && z < n && raw[z * n + x]) return z * n + x;
                }
            }
        }
        return -1;
    }

    static boolean isOcean(Holder<Biome> b) {
        return com.terracontinental.plan.WorldPlan.isOcean(b);
    }

    private static int centralRadius() {
        try {
            if (TerraConfig.SPEC.isLoaded()) return TerraConfig.CENTRAL_LAND_RADIUS.get();
        } catch (Throwable ignored) {
        }
        return 14500;
    }
}
