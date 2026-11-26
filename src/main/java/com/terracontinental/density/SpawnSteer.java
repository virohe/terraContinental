package com.terracontinental.density;

import com.mojang.logging.LogUtils;
import com.terracontinental.plan.LoadingStatus;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/**
 * Steers the spawn continent toward what the player wants on every seed (the user, 2026-11-10:
 * many retries to find an acceptable spawn continent; too big, too small, too round, coasts
 * smoother than the neighbours', neighbours touching or missing). For each world it draws
 * {@code spawn_candidates} layouts, each a branching chain of lobes scaled to the target area with
 * the two nearest continents moved to sit a target gap from its coast, builds each one's continent
 * field on the loading map's grid (513 x 513 cells, 128 blocks each) from the seed's own upstream
 * noise, measures the spawn landmass and keeps the layout closest to the targets: area
 * {@code spawn_target_area} (+-15%), roundness 0.55-0.82 (a disc is 1.0) and the nearest
 * neighbour's coast {@code spawn_gap_min}-{@code spawn_gap_max} km away. On 40 previewed seeds this
 * hit every target on 82%, against 5% for 0.13.0's fixed lobes and 18% for a single layout per seed.
 *
 * <p>Everything is drawn from the seeded jitter noise, so a seed always gives the same world. The
 * search runs once per world, on the first continent sample, and the result is kept in the world
 * ({@code data/terra_continental/spawn_layout.dat}) so later loads skip it.
 *
 * <p>1:1 with {@code preview/steer_core.py}: SplitMix64 for every draw, sums taken in row-major
 * order one value at a time, stable sorts, 4-connected labelling and an exact Euclidean distance
 * transform. StrictMath keeps the draws identical on every machine.
 */
public final class SpawnSteer {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int R = 32768, STEP = 128, N = 2 * (R / STEP) + 1;
    static final double PX = STEP * STEP / 1e6;
    /** The loading map's coastline: vanilla's ocean biomes end at continentalness -0.19. */
    static final double COAST = -0.19;
    /** Retry rounds shift the noise by up to this many blocks. */
    static final double OFFSET = 400000.0;
    static final double BIG = 50.0;
    static final double LAND_FRAC = 0.555;
    static final double FLOOR = -1.02;
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    private SpawnSteer() {}

    /** The settings the search reads (terra_continental-common.toml [continent_shaping]). */
    public record Params(double oceanBias, double radialBias, double coreRadius, double borderRadius,
                         double transitionWidth, double oceanFloor, double centralBump, double centralRadius,
                         double continentBump, double targetArea, double gapMin, double gapMax, int candidates,
                         double maxOtherLand, int rounds) {
    }

    /**
     * The chosen layout. {@code lobes}: {x, z, radius, height} each, the core lobe (at the origin)
     * first; {@code centers}: every other continent's {x, z, radius}, steered.
     */
    public record Layout(NoiseHolderRef noise, double[][] lobes, double soft, double warp, double wavelength,
                         double core, double[][] centers, double reach, int index, double score,
                         double area, double roundness, double gap, double others, int round, int ox, int oz) {

        /** The spawn continent's bump at x, z (d = distance from the origin). */
        public double spawnBump(double x, double z, double d) {
            if (d >= reach) return 0.0;
            double t = Math.min(1.0, d / core);
            double amp = warp * (t * t * (3.0 - 2.0 * t));
            double f = 64.0 / wavelength;
            double wx = clamp1(noise.get(x * f + 5.0e5, z * f) * 2.0);
            double wz = clamp1(noise.get(x * f - 5.0e5, z * f + 3.0e5) * 2.0);
            double xw = x + amp * wx, zw = z + amp * wz;
            double best = 0.0;
            for (double[] l : lobes) {
                double dx = xw - l[0], dz = zw - l[1];
                best = Math.max(best, l[3] * smooth(Math.sqrt(dx * dx + dz * dz) / (l[2] * soft)));
            }
            return best;
        }
    }

    /** The jitter noise, as the search and the bump read it (getValue at y 0). */
    @FunctionalInterface
    public interface NoiseHolderRef {
        double get(double x, double z);
    }

    private static final Map<Long, CompletableFuture<Layout>> CACHE = new ConcurrentHashMap<>();

    /**
     * This world's layout: searched by the first caller, waited for by the rest. {@code upstream}
     * is the continentalness before the radial falloff; {@code baseCenters} the seed's own
     * continents ({@link RadialFalloffFunction#buildCenters}).
     */
    public static Layout get(DensityFunction upstream, NoiseHolderRef noise, Params p, double[][] baseCenters) {
        long key = steerSeed(noise) * 31L + p.hashCode();
        CompletableFuture<Layout> mine = new CompletableFuture<>();
        if (CACHE.size() > 8) CACHE.clear();
        CompletableFuture<Layout> f = CACHE.putIfAbsent(key, mine);
        if (f != null) {
            Layout l = f.join();
            save(key, l);
            return l;
        }
        try {
            Layout l = load(key, noise);
            if (l == null) l = search(upstream, noise, p, baseCenters);
            mine.complete(l);
            save(key, l);
            return l;
        } catch (Throwable t) {
            mine.completeExceptionally(t);
            CACHE.remove(key, mine);
            throw t;
        }
    }

    // -- kept in the world (WorldCache) ---------------------------------------------------------

    private static final String FILE = "spawn_layout.dat";
    /** Bump when the search or the layout's fields change: older files are searched again. */
    private static final int VERSION = 1;
    private static volatile String savedFor;

    /** The layout saved in the running world for this key, or null. */
    private static Layout load(long key, NoiseHolderRef noise) {
        net.minecraft.server.MinecraftServer server = com.terracontinental.plan.WorldCache.server();
        if (server == null) return null;
        java.nio.file.Path file = com.terracontinental.plan.WorldCache.file(server, FILE);
        long fp = com.terracontinental.plan.WorldCache.fingerprint(server, key);
        Layout l = com.terracontinental.plan.WorldCache.read(file, VERSION, fp, in -> {
            double[][] lobes = readArray(in);
            double soft = in.readDouble(), warp = in.readDouble(), wavelength = in.readDouble(), core = in.readDouble();
            double[][] centers = readArray(in);
            double reach = in.readDouble();
            int index = in.readInt();
            double score = in.readDouble(), area = in.readDouble(), roundness = in.readDouble(),
                    gap = in.readDouble(), others = in.readDouble();
            int round = in.readInt(), ox = in.readInt(), oz = in.readInt();
            return new Layout(noise, lobes, soft, warp, wavelength, core, centers, reach, index, score,
                    area, roundness, gap, others, round, ox, oz);
        });
        if (l != null) {
            savedFor = file + "#" + fp;
            LOGGER.info("[spawn-steer] read from the world: round {} layout {}, {} km2", l.round(), l.index(), Math.round(l.area()));
        }
        return l;
    }

    /** Keeps the layout in the running world, once a session (every chunk worker may get here at once). */
    private static synchronized void save(long key, Layout l) {
        net.minecraft.server.MinecraftServer server = com.terracontinental.plan.WorldCache.server();
        if (server == null) return;
        java.nio.file.Path file = com.terracontinental.plan.WorldCache.file(server, FILE);
        long fp = com.terracontinental.plan.WorldCache.fingerprint(server, key);
        String id = file + "#" + fp;
        if (id.equals(savedFor)) return;
        savedFor = id;
        com.terracontinental.plan.WorldCache.write(file, VERSION, fp, out -> {
            writeArray(out, l.lobes());
            out.writeDouble(l.soft());
            out.writeDouble(l.warp());
            out.writeDouble(l.wavelength());
            out.writeDouble(l.core());
            writeArray(out, l.centers());
            out.writeDouble(l.reach());
            out.writeInt(l.index());
            out.writeDouble(l.score());
            out.writeDouble(l.area());
            out.writeDouble(l.roundness());
            out.writeDouble(l.gap());
            out.writeDouble(l.others());
            out.writeInt(l.round());
            out.writeInt(l.ox());
            out.writeInt(l.oz());
        });
    }

    private static void writeArray(java.io.DataOutputStream out, double[][] a) throws java.io.IOException {
        out.writeInt(a.length);
        for (double[] row : a) {
            out.writeInt(row.length);
            for (double v : row) out.writeDouble(v);
        }
    }

    private static double[][] readArray(java.io.DataInputStream in) throws java.io.IOException {
        int n = in.readInt();
        if (n < 0 || n > 4096) throw new java.io.IOException("bad length " + n);
        double[][] a = new double[n][];
        for (int i = 0; i < n; i++) {
            int m = in.readInt();
            if (m < 0 || m > 16) throw new java.io.IOException("bad length " + m);
            a[i] = new double[m];
            for (int j = 0; j < m; j++) a[i][j] = in.readDouble();
        }
        return a;
    }

    // -- the search ---------------------------------------------------------------------------

    static Layout search(DensityFunction upstream, NoiseHolderRef noise, Params p, double[][] baseCenters) {
        long start = System.nanoTime();
        LoadingStatus.message("Shaping the spawn continent");
        SplitMix rng = new SplitMix(steerSeed(noise));
        SplitMix offsets = new SplitMix(steerSeed(noise) ^ 0x0FF5E7L);
        Layout best = null;
        int rounds = Math.max(1, p.rounds());
        // Round 0 reads the seed's noise where it is. While no layout hits every target, each
        // further round reads it shifted by a drawn offset, which the continent field then keeps
        // (RadialFalloffFunction samples its noise there): a new continent shape, same seed (the
        // user, 2026-11-10: bad results should search harder without a visible new seed).
        for (int round = 0; round < rounds; round++) {
            int ox = 0, oz = 0;
            if (round > 0) {
                ox = (int) offsets.uniform(-OFFSET, OFFSET);
                oz = (int) offsets.uniform(-OFFSET, OFFSET);
            }
            double[] up = new double[N * N];
            int fox = ox, foz = oz;
            IntStream.range(0, N).parallel().forEach(i -> {
                double z = -R + i * (double) STEP;
                for (int j = 0; j < N; j++) {
                    double x = -R + j * (double) STEP;
                    up[i * N + j] = upstream.compute(new DensityFunction.SinglePointContext(
                            (int) x + fox, 0, (int) z + foz));
                }
            });
            Grid grid = new Grid(up, noise);
            List<Draw> drawn = new ArrayList<>();
            for (int i = 0; i < p.candidates(); i++) drawn.add(scale(draw(rng, p.centralRadius()), p.targetArea()));
            Layout[] laid = new Layout[drawn.size()];
            int r = round;
            IntStream.range(0, drawn.size()).parallel()
                    .forEach(i -> laid[i] = evaluate(i, drawn.get(i), grid, baseCenters, p, r, fox, foz));
            for (Layout l : laid) {
                if (best == null || l.score() < best.score()) best = l;
            }
            if (best.score() <= 0.0) break;
        }
        LOGGER.info("[spawn-steer] round {} layout {}: {} km2, roundness {}, nearest neighbour {} km, other land {}%,"
                        + " score {} ({} ms)",
                best.round(), best.index(), Math.round(best.area()), String.format("%.2f", best.roundness()),
                Double.isNaN(best.gap()) ? "none" : String.format("%.1f", best.gap()),
                String.format("%.1f", best.others()), String.format("%.3f", best.score()),
                (System.nanoTime() - start) / 1_000_000L);
        return best;
    }

    private record Draw(double[][] lobes, double soft, double warp, double wavelength, double level,
                        double nAngle, double nSpread, double gap) {
    }

    /** One candidate layout, before scaling (steer_core.draw). */
    static Draw draw(SplitMix rng, double radius) {
        List<double[]> lobes = new ArrayList<>();
        lobes.add(new double[]{0.0, 0.0, radius * rng.uniform(0.42, 0.55)});
        int k = 3 + rng.below(4);
        for (int n = 0; n < k; n++) {
            // Branching chain: mostly grow from the newest lobe, sometimes from an older one.
            double[] parent = rng.unit() < 0.65 ? lobes.get(lobes.size() - 1) : lobes.get(rng.below(lobes.size()));
            double r = radius * rng.uniform(0.28, 0.5);
            double a = rng.uniform(0.0, 2.0 * Math.PI);
            double d = (parent[2] + r) * rng.uniform(0.5, 0.75);
            lobes.add(new double[]{parent[0] + StrictMath.cos(a) * d, parent[1] + StrictMath.sin(a) * d, r});
        }
        double soft = rng.uniform(1.05, 1.35);
        double warp = rng.uniform(0.15, 0.35) * radius;
        double wavelength = (rng.unit() < 0.5 ? 0.5 : 0.7) * radius;
        double level = rng.uniform(-0.05, 0.1);
        double nAngle = rng.uniform(0.0, 2.0 * Math.PI);
        double nSpread = rng.uniform(Math.PI * 0.55, Math.PI * 1.45);
        double gap = rng.uniform(0.0, 1.0);
        return new Draw(lobes.toArray(new double[0][]), soft, warp, wavelength, level, nAngle, nSpread, gap);
    }

    /** km2 of the union of the lobes' land discs, on a 200-block grid (no noise). */
    static double shapeArea(double[][] lobes, double soft) {
        double ext = 0.0;
        for (double[] l : lobes) ext = Math.max(ext, Math.sqrt(l[0] * l[0] + l[1] * l[1]) + l[2] * soft);
        int n = (int) Math.ceil((2.0 * ext + 1.0) / 200.0);
        long count = 0;
        for (int i = 0; i < n; i++) {
            double gz = -ext + i * 200.0;
            for (int j = 0; j < n; j++) {
                double gx = -ext + j * 200.0;
                for (double[] l : lobes) {
                    double lim = (LAND_FRAC * l[2] * soft) * (LAND_FRAC * l[2] * soft);
                    if ((gx - l[0]) * (gx - l[0]) + (gz - l[1]) * (gz - l[1]) < lim) {
                        count++;
                        break;
                    }
                }
            }
        }
        return count * 0.04;
    }

    /** Size rule: scale the layout so its own land area is the target, whatever the lobe draw. */
    static Draw scale(Draw d, double target) {
        double k = Math.sqrt(target / Math.max(shapeArea(d.lobes(), d.soft()), 1.0));
        double[][] lobes = new double[d.lobes().length][];
        for (int i = 0; i < lobes.length; i++) {
            double[] l = d.lobes()[i];
            lobes[i] = new double[]{l[0] * k, l[1] * k, l[2] * k};
        }
        return new Draw(lobes, d.soft(), d.warp() * k, d.wavelength(), d.level(), d.nAngle(), d.nSpread(), d.gap());
    }

    /** The seed's fixed inputs on the grid, row-major [z][x]. */
    static final class Grid {
        final double[] up;
        final NoiseHolderRef noise;
        private final Map<Double, double[][]> warps = new ConcurrentHashMap<>();

        Grid(double[] up, NoiseHolderRef noise) {
            this.up = up;
            this.noise = noise;
        }

        static double x(int j) {
            return -R + j * (double) STEP;
        }

        double[][] warp(double wavelength) {
            return warps.computeIfAbsent(wavelength, w -> {
                double f = 64.0 / w;
                double[] wx = new double[N * N], wz = new double[N * N];
                IntStream.range(0, N).parallel().forEach(i -> {
                    double z = x(i);
                    for (int j = 0; j < N; j++) {
                        double x = x(j);
                        wx[i * N + j] = clamp1(noise.get(x * f + 5.0e5, z * f) * 2.0);
                        wz[i * N + j] = clamp1(noise.get(x * f - 5.0e5, z * f + 3.0e5) * 2.0);
                    }
                });
                return new double[][]{wx, wz};
            });
        }
    }

    /** Fix a scaled draw to the seed, build its continent field and score it. */
    static Layout evaluate(int index, Draw d, Grid g, double[][] baseCenters, Params p, int round, int ox, int oz) {
        // Each lobe's height from the noise under it, so every lobe sees the same mean field on
        // every seed: one over a low patch isn't cut off, one over a high patch doesn't bloat.
        double[][] lobes = new double[d.lobes().length][];
        for (int k = 0; k < lobes.length; k++) {
            double[] l = d.lobes()[k];
            double sum = 0.0;
            int count = 0;
            for (int i = 0; i < N; i++) {
                double dz = Grid.x(i) - l[1];
                for (int j = 0; j < N; j++) {
                    double dx = Grid.x(j) - l[0];
                    if (Math.sqrt(dx * dx + dz * dz) < l[2]) {
                        sum += g.up[i * N + j];
                        count++;
                    }
                }
            }
            double mean = count > 0 ? sum / count : 0.0;
            lobes[k] = new double[]{l[0], l[1], l[2], p.centralBump() / d.soft() + d.level() - mean};
        }
        // Neighbours: the seed's two continents nearest the origin move so their coasts sit the
        // drawn gap beyond the spawn continent's reach in their direction; the rest stay put but
        // keep clear of it.
        int m = baseCenters.length;
        double[][] c = new double[m][];
        for (int i = 0; i < m; i++) c[i] = baseCenters[i].clone();
        Integer[] order = new Integer[m];
        for (int i = 0; i < m; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(Math.sqrt(c[a][0] * c[a][0] + c[a][1] * c[a][1]),
                Math.sqrt(c[b][0] * c[b][0] + c[b][1] * c[b][1])));
        double gap = (p.gapMin() + (p.gapMax() - p.gapMin()) * d.gap()) * 1000.0;
        double[] angles = {d.nAngle(), d.nAngle() + d.nSpread()};
        for (int slot = 0; slot < Math.min(2, m); slot++) {
            int i = order[slot];
            double ux = StrictMath.cos(angles[slot]), uz = StrictMath.sin(angles[slot]);
            double reach = Double.NEGATIVE_INFINITY;
            for (double[] l : lobes) reach = Math.max(reach, l[0] * ux + l[1] * uz + 0.7 * l[2] * d.soft());
            double dist = reach + gap + 0.8 * c[i][2];
            c[i][0] = ux * dist;
            c[i][1] = uz * dist;
        }
        double spawnReach = Double.NEGATIVE_INFINITY;
        for (double[] l : lobes) spawnReach = Math.max(spawnReach, Math.sqrt(l[0] * l[0] + l[1] * l[1]) + l[2] * d.soft());
        for (int s = 2; s < m; s++) {
            int i = order[s];
            double dd = Math.sqrt(c[i][0] * c[i][0] + c[i][1] * c[i][1]);
            double need = spawnReach + c[i][2] + 6000.0;
            if (dd < need) {
                c[i][0] = c[i][0] / dd * need;
                c[i][1] = c[i][1] / dd * need;
            }
        }
        double core = lobes[0][2];
        // The warp moves a point by at most warp * sqrt(2); past this the bump is 0.
        double reach = spawnReach + d.warp() * 1.5;
        Layout lay = new Layout(g.noise, lobes, d.soft(), d.warp(), d.wavelength(), core, c, reach,
                index, 0.0, 0.0, 0.0, Double.NaN, 0.0, round, ox, oz);

        double[][] w = g.warp(d.wavelength());
        boolean[] land = new boolean[N * N];
        for (int i = 0; i < N; i++) {
            double z = Grid.x(i);
            for (int j = 0; j < N; j++) {
                double x = Grid.x(j);
                int q = i * N + j;
                land[q] = falloffAt(lay, g.up[q], x, z, w[0][q], w[1][q], p) > COAST;
            }
        }
        double[] mres = measure(land, p);
        double sc = score(mres[0], mres[1], mres[2], mres[3], p);
        return new Layout(g.noise, lobes, d.soft(), d.warp(), d.wavelength(), core, c, reach,
                index, sc, mres[0], mres[1], mres[2], mres[3], round, ox, oz);
    }

    /** RadialFalloffFunction.compute with a layout, at a grid cell (warp given), then the floor. */
    static double falloffAt(Layout lay, double up, double x, double z, double wx, double wz, Params p) {
        double d = Math.sqrt(x * x + z * z);
        double t = Math.min(1.0, d / lay.core());
        double amp = lay.warp() * (t * t * (3.0 - 2.0 * t));
        double xw = x + amp * wx, zw = z + amp * wz;
        double spawn = 0.0;
        for (double[] l : lay.lobes()) {
            double dx = xw - l[0], dz = zw - l[1];
            spawn = Math.max(spawn, l[3] * smooth(Math.sqrt(dx * dx + dz * dz) / (l[2] * lay.soft())));
        }
        double bumped = up + spawn;
        for (double[] c : lay.centers()) {
            double dx = x - c[0], dz = z - c[1];
            bumped = bumped + p.continentBump() * smooth(Math.sqrt(dx * dx + dz * dz) / c[2]);
        }
        double biased = bumped - (p.oceanBias() + p.radialBias() * (d / p.coreRadius()));
        double blend = 1.0 / (1.0 + StrictMath.exp((d - p.borderRadius()) / p.transitionWidth()));
        return Math.max(blend * biased + (1.0 - blend) * p.oceanFloor(), FLOOR);
    }

    // -- measuring ----------------------------------------------------------------------------

    /** {area km2, roundness, gap km (NaN: no neighbour), other land % of the map} of the spawn landmass. */
    static double[] measure(boolean[] land, Params p) {
        int[] lab = new int[N * N];
        List<Integer> sizes = new ArrayList<>();
        sizes.add(0);
        int[] stack = new int[N * N];
        int nl = 0;
        for (int q = 0; q < N * N; q++) {
            if (!land[q] || lab[q] != 0) continue;
            nl++;
            int top = 0, size = 0;
            stack[top++] = q;
            lab[q] = nl;
            while (top > 0) {
                int v = stack[--top];
                size++;
                int i = v / N, j = v % N;
                if (i > 0 && land[v - N] && lab[v - N] == 0) { lab[v - N] = nl; stack[top++] = v - N; }
                if (i < N - 1 && land[v + N] && lab[v + N] == 0) { lab[v + N] = nl; stack[top++] = v + N; }
                if (j > 0 && land[v - 1] && lab[v - 1] == 0) { lab[v - 1] = nl; stack[top++] = v - 1; }
                if (j < N - 1 && land[v + 1] && lab[v + 1] == 0) { lab[v + 1] = nl; stack[top++] = v + 1; }
            }
            sizes.add(size);
        }
        int c = N / 2;
        int sp;
        if (land[c * N + c]) {
            sp = lab[c * N + c];
        } else {
            long bestD = Long.MAX_VALUE;
            int bestQ = -1;
            for (int q = 0; q < N * N; q++) {
                if (!land[q]) continue;
                long dy = q / N - c, dx = q % N - c;
                long d2 = dy * dy + dx * dx;
                if (d2 < bestD) {
                    bestD = d2;
                    bestQ = q;
                }
            }
            if (bestQ < 0) return new double[]{0.0, 1.0, Double.NaN, 0.0};
            sp = lab[bestQ];
        }
        int count = sizes.get(sp);
        double area = count * PX;
        double ys = 0.0, xs = 0.0;
        for (int q = 0; q < N * N; q++) {
            if (lab[q] == sp) {
                ys += q / N;
                xs += q % N;
            }
        }
        double ym = ys / count, xm = xs / count;
        double ss = 0.0;
        for (int q = 0; q < N * N; q++) {
            if (lab[q] == sp) {
                double dy = q / N - ym, dx = q % N - xm;
                ss += dy * dy + dx * dx;
            }
        }
        double rg2 = ss / count * (STEP / 1000.0) * (STEP / 1000.0);
        double roundness = rg2 > 0 ? area / (2.0 * Math.PI * rg2) : 1.0;

        boolean[] big = new boolean[nl + 1];
        boolean any = false;
        for (int i = 1; i <= nl; i++) {
            if (i != sp && sizes.get(i) * PX >= BIG) {
                big[i] = true;
                any = true;
            }
        }
        double gap = Double.NaN;
        if (any) {
            double[] f = new double[N * N];
            for (int q = 0; q < N * N; q++) f[q] = big[lab[q]] ? 0.0 : INF;
            edtSquared(f);
            double min = Double.POSITIVE_INFINITY;
            for (int q = 0; q < N * N; q++) if (lab[q] == sp) min = Math.min(min, f[q]);
            gap = Math.sqrt(min) * STEP / 1000.0;
        }
        int landCount = 0;
        for (boolean b : land) if (b) landCount++;
        double others = (landCount - count) * 100.0 / (N * N);
        return new double[]{area, roundness, gap, others};
    }

    static double score(double area, double roundness, double gap, double others, Params p) {
        double s = 0.0;
        double da = Math.abs(StrictMath.log(Math.max(area, 1.0) / p.targetArea())) / StrictMath.log(1.15);
        s += Math.max(0.0, da - 0.3) * 2.0;
        s += Math.max(Math.max(0.55 - roundness, roundness - 0.82), 0.0) / 0.1;
        if (Double.isNaN(gap)) {
            s += 5.0;
        } else {
            s += Math.max(Math.max(p.gapMin() - gap, gap - p.gapMax()), 0.0) / 1.5 * 1.5;
        }
        s += Math.max(0.0, others - p.maxOtherLand()) / 2.0;
        return s;
    }

    public static final double INF = 1e20;

    /** In place: squared Euclidean distance (cells) to the nearest 0 cell (Felzenszwalb-Huttenlocher). */
    public static void edtSquared(double[] f) {
        double[] col = new double[N], out = new double[N], zz = new double[N + 1];
        int[] v = new int[N];
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) col[i] = f[i * N + j];
            dt1(col, out, v, zz);
            for (int i = 0; i < N; i++) f[i * N + j] = out[i];
        }
        for (int i = 0; i < N; i++) {
            System.arraycopy(f, i * N, col, 0, N);
            dt1(col, out, v, zz);
            System.arraycopy(out, 0, f, i * N, N);
        }
    }

    private static void dt1(double[] f, double[] d, int[] v, double[] z) {
        int k = 0;
        v[0] = 0;
        z[0] = Double.NEGATIVE_INFINITY;
        z[1] = Double.POSITIVE_INFINITY;
        for (int q = 1; q < N; q++) {
            double s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
            while (s <= z[k]) {
                k--;
                s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = Double.POSITIVE_INFINITY;
        }
        k = 0;
        for (int q = 0; q < N; q++) {
            while (z[k + 1] < q) k++;
            double dq = q - v[k];
            d[q] = dq * dq + f[v[k]];
        }
    }

    // -- helpers ------------------------------------------------------------------------------

    static double smooth(double t) {
        if (t >= 1.0) return 0.0;
        return 1.0 - t * t * (3.0 - 2.0 * t);
    }

    static double clamp1(double v) {
        return Math.max(-1.0, Math.min(1.0, v));
    }

    /** The RNG seed: two raw jitter-noise samples (indices 50, 51), so it follows the world seed. */
    static long steerSeed(NoiseHolderRef noise) {
        double a = noise.get(50 * 9973.0 + 31.0, 50 * -6101.0 - 97.0);
        double b = noise.get(51 * 9973.0 + 31.0, 51 * -6101.0 - 97.0);
        return Double.doubleToRawLongBits(a) * GOLDEN ^ Double.doubleToRawLongBits(b);
    }

    static final class SplitMix {
        private long s;

        SplitMix(long seed) {
            s = seed;
        }

        long next() {
            s += GOLDEN;
            long z = s;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        double unit() {
            return (next() >>> 11) * 0x1.0p-53;
        }

        double uniform(double a, double b) {
            return a + (b - a) * unit();
        }

        int below(int n) {
            return (int) (n * unit());
        }
    }
}
