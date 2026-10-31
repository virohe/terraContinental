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
 *   <li><b>Checks</b> on each pick: not steep (relief over the village footprint at most
 *       {@code max_relief}), not on water, and the game's own {@code findValidGenerationPoint}
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

    public record Site(ChunkPos chunk, String type, boolean central, int y, int relief) {
    }

    public static final class Plan {
        public final long seed;
        public final int originX, originZ, n;
        final boolean[] land;
        public final Map<Long, Site> sites = new LinkedHashMap<>();
        public double centroidX, centroidZ, areaKm2, gapUsed;
        public String note = "";

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
            try {
                p = compute(level);
            } catch (Throwable e) {
                LOGGER.error("[spawn-villages] planning failed; spawn continent keeps the random village grids", e);
                p = null;
            }
            plan = p;
            planFor = key;
            if (p != null) {
                LOGGER.info("[spawn-villages] {} villages planned on a {} km2 spawn continent in {} ms (gap {} blocks){}",
                        p.sites.size(), Math.round(p.areaKm2), (System.nanoTime() - t) / 1_000_000L, Math.round(p.gapUsed), p.note);
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
        List<Cand> cands = Collections.synchronizedList(new ArrayList<>());
        spots.parallelStream().forEach(s -> {
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
        pool.sort((a, b) -> a.x != b.x ? Integer.compare(a.x, b.x) : Integer.compare(a.z, b.z)); // deterministic order
        Random rng = new Random(seed ^ 0x5EED_7111_A6E5L);
        Collections.shuffle(pool, rng);

        Map<String, Integer> used = new HashMap<>();
        List<double[]> chosen = new ArrayList<>();

        // 3. Central village: nearest the centroid.
        List<Cand> byCentre = new ArrayList<>(pool);
        byCentre.sort((a, b) -> Double.compare(dist2(a, p.centroidX, p.centroidZ), dist2(b, p.centroidX, p.centroidZ)));
        for (Cand c : byCentre) {
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
        return p;
    }

    private static void accept(Plan p, Site s, Map<String, Integer> used, List<double[]> chosen) {
        p.sites.put(s.chunk().toLong(), s);
        used.merge(s.type(), 1, Integer::sum);
        chosen.add(new double[]{s.chunk().getMiddleBlockX(), s.chunk().getMiddleBlockZ()});
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
        // Steepness over the footprint, measured around the chunk's middle (where the village starts),
        // 7x7 samples out to 48 blocks; any sample on water fails. Audit 2026-10-19: 3x3 samples at the
        // lattice point, 8 blocks off, passed a spot with a 30-block cliff inside the village.
        ChunkPos chunk = new ChunkPos(c.x >> 4, c.z >> 4);
        int mx = chunk.getMiddleBlockX(), mz = chunk.getMiddleBlockZ();
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int dx : OFFSETS) {
            for (int dz : OFFSETS) {
                int h = gen.getBaseHeight(mx + dx, mz + dz, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
                if (h <= sea) return null;
                lo = Math.min(lo, h);
                hi = Math.max(hi, h);
                if (hi - lo > maxRelief) return null;
            }
        }
        Holder<Structure> holder = holders.get(type);
        if (holder == null) return null;
        Structure s = holder.value();
        Structure.GenerationContext ctx = new Structure.GenerationContext(level.registryAccess(), gen, bs, rs,
                level.getServer().getStructureManager(), seed, chunk, level, s.biomes()::contains);
        var stub = s.findValidGenerationPoint(ctx);
        if (stub.isEmpty()) return null;
        return new Site(chunk, type, central, stub.get().position().getY(), hi - lo);
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
        return 9000;
    }
}
