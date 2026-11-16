package com.terracontinental.atlas;

import com.mojang.logging.LogUtils;
import com.terracontinental.config.TerraConfig;
import com.terracontinental.plan.WorldPlan;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The whole overworld at a glance: every point on a {@link #STEP}-block lattice out to the soft
 * world border, land or sea, with its ground height, and the land split into named continents.
 *
 * <p>The world is finite in practice: past {@code border_radius} the continent shaping fades the
 * continentalness to deep ocean, so a scan out to the border plus two transition widths sees all
 * the land there is. Land is the loading map's test (the biome at sea level is not an ocean), so
 * the atlas, the loading map and /continental villages agree on where the coast is.</p>
 *
 * <p>A <b>continent</b> is a 4-connected landmass of at least {@link #MIN_CONTINENT_KM2}; the
 * names are the Cobblemon starter regions, shuffled by the seed and handed out nearest the
 * origin first, so a world's names never change. Smaller land is an island: shown on the map,
 * pregenerated with a continent whose coast is near ({@link PregenMask}).</p>
 *
 * <p>Built once per world (~15 s on 16 threads) and kept in {@code <world>/data/}.</p>
 */
public final class ContinentAtlas {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int STEP = 512;
    public static final double MIN_CONTINENT_KM2 = 25.0;
    /** Bump when the file layout or the land test changes: older files are rebuilt. */
    private static final int VERSION = 1;
    private static final String FILE = "terra_continental_atlas.dat";

    /** Placeholder names (user, 2026-11-16): the regions on Cobblemon's starter screen. */
    public static final List<String> NAMES = List.of(
            "Kanto", "Johto", "Hoenn", "Sinnoh", "Unova", "Kalos", "Alola", "Galar", "Paldea", "Hisui");

    public record Continent(int id, String name, double areaKm2, int centerX, int centerZ,
                            int minX, int minZ, int maxX, int maxZ) {
        /** Command-friendly key: the name in lower case ("kanto"). */
        public String key() {
            return name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
        }
    }

    public final long seed;
    public final int half, n, sea;
    /** Ground height (blocks) per point; land and sea alike. */
    public final short[] height;
    /** 0 sea, otherwise 1 + index of the land component (continent or island) the point is on. */
    public final int[] label;
    /** Area per component in points, indexed by label - 1. */
    public final int[] componentCells;
    /** Continent index per component (label - 1), or -1 for an island. */
    public final int[] componentContinent;
    public final List<Continent> continents;

    private static volatile ContinentAtlas cached;
    private static volatile CompletableFuture<ContinentAtlas> building;

    private ContinentAtlas(long seed, int half, int sea, short[] height, int[] label, int[] componentCells,
                           int[] componentContinent, List<Continent> continents) {
        this.seed = seed;
        this.half = half;
        this.n = 2 * half + 1;
        this.sea = sea;
        this.height = height;
        this.label = label;
        this.componentCells = componentCells;
        this.componentContinent = componentContinent;
        this.continents = continents;
    }

    // -- access -----------------------------------------------------------------------------------

    /**
     * This world's atlas: from memory, else from the world's data folder, else built in the
     * background. Completes on a worker thread; callers on the server thread hop back themselves.
     */
    public static CompletableFuture<ContinentAtlas> get(ServerLevel level) {
        long seed = level.getSeed();
        int half = extent() / STEP;
        ContinentAtlas a = cached;
        if (a != null && a.seed == seed && a.half == half) return CompletableFuture.completedFuture(a);
        synchronized (ContinentAtlas.class) {
            if (building != null && !building.isDone()) return building;
            Path file = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE);
            building = CompletableFuture.supplyAsync(() -> {
                ContinentAtlas loaded = read(file, seed, half);
                if (loaded != null) {
                    cached = loaded;
                    return loaded;
                }
                ContinentAtlas built = build(level, seed, half);
                write(file, built);
                cached = built;
                return built;
            }, net.minecraft.Util.backgroundExecutor());
            return building;
        }
    }

    /** The atlas if it's already in memory for this world, else null (and nothing starts). */
    @Nullable
    public static ContinentAtlas ifReady(ServerLevel level) {
        ContinentAtlas a = cached;
        return a != null && a.seed == level.getSeed() && a.half == extent() / STEP ? a : null;
    }

    /** Blocks out from (0, 0) the atlas covers: the soft border plus two of its widths. */
    static int extent() {
        int border = 150_000, width = 10_000;
        try {
            if (TerraConfig.SPEC.isLoaded()) {
                border = TerraConfig.BORDER_RADIUS.get();
                width = TerraConfig.TRANSITION_WIDTH.get();
            }
        } catch (Throwable ignored) {
        }
        return Math.min(400_000, border + 2 * width);
    }

    public int index(int bx, int bz) {
        int ix = Math.floorDiv(bx + STEP / 2, STEP) + half, iz = Math.floorDiv(bz + STEP / 2, STEP) + half;
        return ix < 0 || iz < 0 || ix >= n || iz >= n ? -1 : iz * n + ix;
    }

    public int blockX(int i) {
        return (i % n - half) * STEP;
    }

    public int blockZ(int i) {
        return (i / n - half) * STEP;
    }

    /** The continent at (x, z): on its land, or failing that, the one whose coast is nearest within 4 km. */
    @Nullable
    public Continent continentAt(int bx, int bz) {
        int i = index(bx, bz);
        if (i >= 0 && label[i] > 0) {
            int c = componentContinent[label[i] - 1];
            if (c >= 0) return continents.get(c);
        }
        int ix = Math.floorDiv(bx + STEP / 2, STEP) + half, iz = Math.floorDiv(bz + STEP / 2, STEP) + half;
        int reach = 4000 / STEP;
        Continent best = null;
        long bestD = Long.MAX_VALUE;
        for (int dz = -reach; dz <= reach; dz++) {
            for (int dx = -reach; dx <= reach; dx++) {
                int jx = ix + dx, jz = iz + dz;
                if (jx < 0 || jz < 0 || jx >= n || jz >= n) continue;
                int l = label[jz * n + jx];
                if (l == 0 || componentContinent[l - 1] < 0) continue;
                long d = (long) dx * dx + (long) dz * dz;
                if (d < bestD && d <= (long) reach * reach) {
                    bestD = d;
                    best = continents.get(componentContinent[l - 1]);
                }
            }
        }
        return best;
    }

    @Nullable
    public Continent byKey(String key) {
        for (Continent c : continents) if (c.key().equalsIgnoreCase(key)) return c;
        return null;
    }

    // -- building ---------------------------------------------------------------------------------

    private static ContinentAtlas build(ServerLevel level, long seed, int half) {
        long t0 = System.nanoTime();
        int n = 2 * half + 1;
        var gen = level.getChunkSource().getGenerator();
        BiomeSource bs = gen.getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        int sea = gen.getSeaLevel();
        DensityFunction ground = WorldPlan.groundFunction(level);
        short[] height = new short[n * n];
        boolean[] land = new boolean[n * n];
        IntStream.range(0, n * n).parallel().forEach(i -> {
            int x = (i % n - half) * STEP, z = (i / n - half) * STEP;
            double depth = ground.compute(new DensityFunction.SinglePointContext(x, 0, z));
            height[i] = (short) Math.max(-2000, Math.min(4000, Math.round(128.0 * depth)));
            land[i] = !WorldPlan.isOcean(bs.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(sea),
                    QuartPos.fromBlock(z), sampler));
        });
        long t1 = System.nanoTime();
        ContinentAtlas atlas = label(seed, half, sea, height, land);
        LOGGER.info("[atlas] {} points ({} km across) sampled in {} ms; {} continents, {} islands: {}",
                n * n, Math.round(n * (double) STEP / 1000.0), (t1 - t0) / 1_000_000L,
                atlas.continents.size(), atlas.componentCells.length - atlas.continents.size(),
                atlas.continents.stream().map(c -> c.name() + " " + Math.round(c.areaKm2()) + " km2 @ "
                        + c.centerX() + "," + c.centerZ()).toList());
        return atlas;
    }

    /** Splits the land into components and names the continents. Pure: the tests call it. */
    static ContinentAtlas label(long seed, int half, int sea, short[] height, boolean[] land) {
        int n = 2 * half + 1;
        int[] label = new int[n * n];
        List<int[]> comps = new ArrayList<>(); // {cells, sumX, sumZ, minIx, minIz, maxIx, maxIz} as long-safe ints
        List<long[]> sums = new ArrayList<>();
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int s = 0; s < n * n; s++) {
            if (!land[s] || label[s] != 0) continue;
            int id = comps.size() + 1;
            int cells = 0;
            long sx = 0, sz = 0;
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            label[s] = id;
            q.add(s);
            while (!q.isEmpty()) {
                int i = q.poll();
                int ix = i % n, iz = i / n;
                cells++;
                sx += ix;
                sz += iz;
                minX = Math.min(minX, ix);
                maxX = Math.max(maxX, ix);
                minZ = Math.min(minZ, iz);
                maxZ = Math.max(maxZ, iz);
                if (ix > 0) visit(i - 1, land, label, id, q);
                if (ix < n - 1) visit(i + 1, land, label, id, q);
                if (iz > 0) visit(i - n, land, label, id, q);
                if (iz < n - 1) visit(i + n, land, label, id, q);
            }
            comps.add(new int[]{cells, minX, minZ, maxX, maxZ});
            sums.add(new long[]{sx, sz});
        }
        double cellKm2 = (double) STEP * STEP / 1e6;
        int[] componentCells = new int[comps.size()];
        int[] componentContinent = new int[comps.size()];
        Arrays.fill(componentContinent, -1);
        List<Integer> big = new ArrayList<>();
        for (int c = 0; c < comps.size(); c++) {
            componentCells[c] = comps.get(c)[0];
            if (componentCells[c] * cellKm2 >= MIN_CONTINENT_KM2) big.add(c);
        }
        // Nearest the origin first, so the spawn continent is always handed the first name.
        big.sort(Comparator.comparingDouble(c -> {
            double cx = (double) sums.get(c)[0] / comps.get(c)[0] - half, cz = (double) sums.get(c)[1] / comps.get(c)[0] - half;
            return cx * cx + cz * cz;
        }));
        List<String> names = new ArrayList<>(NAMES);
        Collections.shuffle(names, new Random(seed ^ 0x5DEECE66DL ^ 0x7E44A1C0FFEEL));
        List<Continent> continents = new ArrayList<>();
        for (int k = 0; k < big.size(); k++) {
            int c = big.get(k);
            int[] cc = comps.get(c);
            String name = k < names.size() ? names.get(k) : "Continent " + (k + 1);
            int cx = (int) Math.round(((double) sums.get(c)[0] / cc[0] - half) * STEP);
            int cz = (int) Math.round(((double) sums.get(c)[1] / cc[0] - half) * STEP);
            continents.add(new Continent(k, name, cc[0] * cellKm2, cx, cz,
                    (cc[1] - half) * STEP - STEP / 2, (cc[2] - half) * STEP - STEP / 2,
                    (cc[3] - half) * STEP + STEP / 2, (cc[4] - half) * STEP + STEP / 2));
            componentContinent[c] = k;
        }
        return new ContinentAtlas(seed, half, sea, height, label, componentCells, componentContinent,
                List.copyOf(continents));
    }

    private static void visit(int j, boolean[] land, int[] label, int id, ArrayDeque<Integer> q) {
        if (land[j] && label[j] == 0) {
            label[j] = id;
            q.add(j);
        }
    }

    // -- file -------------------------------------------------------------------------------------

    @Nullable
    private static ContinentAtlas read(Path file, long seed, int half) {
        if (!Files.isRegularFile(file)) return null;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != VERSION || in.readLong() != seed || in.readInt() != half) return null;
            int sea = in.readInt();
            int n = 2 * half + 1;
            short[] height = new short[n * n];
            boolean[] land = new boolean[n * n];
            for (int i = 0; i < n * n; i++) height[i] = in.readShort();
            for (int i = 0; i < n * n; i++) land[i] = in.readBoolean();
            return label(seed, half, sea, height, land);
        } catch (IOException e) {
            LOGGER.warn("[atlas] couldn't read {}, rebuilding: {}", file, e.toString());
            return null;
        }
    }

    private static void write(Path file, ContinentAtlas a) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(FILE + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(VERSION);
                out.writeLong(a.seed);
                out.writeInt(a.half);
                out.writeInt(a.sea);
                for (short h : a.height) out.writeShort(h);
                for (int l : a.label) out.writeBoolean(l > 0);
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("[atlas] couldn't save {}: {}", file, e.toString());
        }
    }
}
