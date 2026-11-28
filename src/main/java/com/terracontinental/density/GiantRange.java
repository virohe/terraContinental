package com.terracontinental.density;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.function.IntBinaryOperator;
import java.util.stream.IntStream;

/**
 * Range mode for {@link GiantPeaksFunction}: where each giant stands on its continent's ranges, the
 * level of the range round it, the raised range on a continent without one, and the solver that
 * sets each giant's summit and sisters against the real terrain.
 *
 * <p>Why (the user's 8-seed review, 2026-11-27/28): a dome placed by bearing stood alone, with no
 * range of origin; on the ranges, its prominence came from five interacting terms, and nine
 * hand-tuned screens stalled at 4 of 8 seeds. The solver measures instead.
 *
 * <p>Mirrors {@code preview/continental_terrain.py}'s {@code _giant_place}, {@code _giant_axis} and
 * {@code _giant_solution}; keep them in step (same constants, same sample order).
 */
public final class GiantRange {

    private static final Logger LOGGER = LogUtils.getLogger();

    private GiantRange() {
    }

    // -- codec --------------------------------------------------------------------------------------

    /** The range-mode fields of a giant_peaks node; all optional ({@code place} absent = off). */
    public record Mode(Optional<DensityFunction> place, Optional<DensityFunction> range,
                      Optional<DensityFunction> crestLine, double placeReach, double placeRing, double crestMax,
                      double levelFloor, Optional<Double> levelQ, double gainRadius, double gainPower,
                      double rise, double targetCap, double raiseWidth, double raiseLength,
                      Optional<Solve> solve) {
        public static final Mode NONE = new Mode(Optional.empty(), Optional.empty(), Optional.empty(), 0.6, 0.0,
                0.08, -10.0, Optional.empty(), 6000.0, 2.0, 0.5, 1.74, 1800.0, 9000.0, Optional.empty());

        static final MapCodec<Mode> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                DensityFunction.HOLDER_HELPER_CODEC.optionalFieldOf("place").forGetter(Mode::place),
                DensityFunction.HOLDER_HELPER_CODEC.optionalFieldOf("range").forGetter(Mode::range),
                DensityFunction.HOLDER_HELPER_CODEC.optionalFieldOf("crest_line").forGetter(Mode::crestLine),
                Codec.DOUBLE.optionalFieldOf("place_reach", 0.6).forGetter(Mode::placeReach),
                Codec.DOUBLE.optionalFieldOf("place_ring", 0.0).forGetter(Mode::placeRing),
                Codec.DOUBLE.optionalFieldOf("crest_max", 0.08).forGetter(Mode::crestMax),
                Codec.DOUBLE.optionalFieldOf("level_floor", -10.0).forGetter(Mode::levelFloor),
                Codec.DOUBLE.optionalFieldOf("level_q").forGetter(Mode::levelQ),
                Codec.DOUBLE.optionalFieldOf("gain_radius", 6000.0).forGetter(Mode::gainRadius),
                Codec.DOUBLE.optionalFieldOf("gain_power", 2.0).forGetter(Mode::gainPower),
                Codec.DOUBLE.optionalFieldOf("rise", 0.5).forGetter(Mode::rise),
                Codec.DOUBLE.optionalFieldOf("target_cap", 1.74).forGetter(Mode::targetCap),
                Codec.DOUBLE.optionalFieldOf("raise_width", 1800.0).forGetter(Mode::raiseWidth),
                Codec.DOUBLE.optionalFieldOf("raise_length", 9000.0).forGetter(Mode::raiseLength),
                Solve.CODEC.optionalFieldOf("solve").forGetter(Mode::solve)
        ).apply(i, Mode::new));

        boolean on() {
            return place.isPresent();
        }

        /**
         * The placement settings without the density functions: every node of a pack carries the
         * same place/range/crest_line, and the functions themselves differ per NoiseChunk (each wraps
         * its own caches round them), so keys built on them never matched and every getBaseHeight
         * call placed every giant again (first rig run, 2026-11-28: the server never came up).
         */
        Sig sig() {
            return new Sig(on(), crestLine.isPresent(), placeReach, placeRing, crestMax, levelFloor, levelQ.orElse(-1.0));
        }

        DensityFunction rangeOrPlace() {
            return range.orElseGet(place::get);
        }

        Mode map(DensityFunction.Visitor v) {
            return new Mode(place.map(f -> f.mapAll(v)), range.map(f -> f.mapAll(v)), crestLine.map(f -> f.mapAll(v)),
                    placeReach, placeRing, crestMax, levelFloor, levelQ, gainRadius, gainPower, rise, targetCap,
                    raiseWidth, raiseLength, solve);
        }
    }

    record Sig(boolean on, boolean crest, double placeReach, double placeRing, double crestMax, double levelFloor,
               double levelQ) {
    }

    /** The solver's goal: the summit {@code prom} blocks over the highest point 600-3,000 blocks out. */
    public record Solve(double prom, double rise, double cap, double dropMin, double dropMax, double riseMin) {
        static final Codec<Solve> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.fieldOf("prom").forGetter(Solve::prom),
                Codec.DOUBLE.fieldOf("rise").forGetter(Solve::rise),
                Codec.DOUBLE.fieldOf("cap").forGetter(Solve::cap),
                Codec.DOUBLE.optionalFieldOf("drop_min", 0.05).forGetter(Solve::dropMin),
                Codec.DOUBLE.optionalFieldOf("drop_max", 1.5).forGetter(Solve::dropMax),
                Codec.DOUBLE.optionalFieldOf("rise_min", 0.3).forGetter(Solve::riseMin)
        ).apply(i, Solve::new));
    }

    // -- placement ----------------------------------------------------------------------------------

    /** Where giant k stands, its range's level (raised to the floor) and the shortfall. */
    public record Placed(double x, double z, double level, double deficit) {
    }

    /** Polar candidates 0.10, 0.15 ... of the radius, 36 bearings; the refine grid; the level rings. */
    private static final int PLACE_ANGLES = 36;
    private static final int PLACE_REFINE = 10;
    private static final double PLACE_STEP = 96.0;
    private static final double[] LEVEL_RADII = {600.0, 1000.0, 1500.0, 2000.0, 2500.0, 3000.0};
    private static final int LEVEL_ANGLES = 48;

    /**
     * A world, as its jitter noise's values at three fixed points: the same for every copy of a
     * seed's noise (each RandomState, each biome_map worker), so they share placements and
     * solutions. Different seeds' values differ in practice.
     */
    public record World(double a, double b, double c) {
        public static World of(NormalNoise n) {
            return new World(n.getValue(1234.5, 0.0, -987.25), n.getValue(-40213.0, 0.0, 77011.5),
                    n.getValue(9001.0, 0.0, 31337.0));
        }
    }

    private record PlaceKey(World world, int k, double cx, double cz, double cr, double ang, Sig sig) {
    }

    /** Placements and bearings, shared by every giant_peaks node of a world. */
    private static final Map<Object, Object> CACHE = new ConcurrentHashMap<>();

    private static double at(DensityFunction f, double x, double z) {
        // At whole blocks: a block context has integer x and z (the previewer floors too).
        return f.compute(new DensityFunction.SinglePointContext((int) Math.floor(x), 0, (int) Math.floor(z)));
    }

    /**
     * Giant k on continent (cx, cz, cr) in range mode. Candidates on a polar grid out to
     * place_reach of the radius are scored by the mean of {@code range} over the candidate and 8
     * points on each of two rings (place_ring and twice it), off the range's centre line excluded;
     * the best is refined onto the local top of {@code place}. level = the highest {@code range}
     * value 600-3,000 blocks out (or the level_q percentile).
     */
    static Placed place(World noise, int k, double cx, double cz, double cr, double ang0, Mode m) {
        PlaceKey key = new PlaceKey(noise, k, cx, cz, cr, ang0, m.sig());
        Map<Object, Object> c = CACHE;
        Object hit = c.get(key);
        if (hit != null) return (Placed) hit;
        DensityFunction place = m.place().orElseThrow();
        DensityFunction range = m.rangeOrPlace();
        int nr = Math.max(2, (int) Math.round((m.placeReach() - 0.1) / 0.05) + 1);
        int count = nr * PLACE_ANGLES;
        double[] xs = new double[count], zs = new double[count];
        for (int ir = 0; ir < nr; ir++) {
            double rr = (0.1 + 0.05 * ir) * cr;
            for (int ia = 0; ia < PLACE_ANGLES; ia++) {
                double a = ang0 + 2.0 * Math.PI * ia / PLACE_ANGLES;
                xs[ir * PLACE_ANGLES + ia] = cx + rr * Math.cos(a);
                zs[ir * PLACE_ANGLES + ia] = cz + rr * Math.sin(a);
            }
        }
        double[] score = new double[count];
        IntStream.range(0, count).parallel().forEach(i -> {
            double s = at(place, xs[i], zs[i]);
            if (m.placeRing() != 0.0) {
                double total = s;
                for (double rad : new double[]{m.placeRing(), 2.0 * m.placeRing()}) {
                    for (int j = 0; j < 8; j++) {
                        double a = 2.0 * Math.PI * j / 8.0;
                        total += at(range, xs[i] + rad * Math.cos(a), zs[i] + rad * Math.sin(a));
                    }
                }
                s = total / 17.0;
            }
            if (m.crestLine().isPresent() && at(m.crestLine().get(), xs[i], zs[i]) > m.crestMax()) {
                s = Double.NEGATIVE_INFINITY;
            }
            score[i] = s;
        });
        int best = argmax(score);
        double bx = xs[best], bz = zs[best];
        int side = 2 * PLACE_REFINE + 1;
        double[] gx = new double[side * side], gz = new double[side * side], gv = new double[side * side];
        for (int iz = 0; iz < side; iz++) {
            for (int ix = 0; ix < side; ix++) {
                gx[iz * side + ix] = bx + (ix - PLACE_REFINE) * PLACE_STEP;
                gz[iz * side + ix] = bz + (iz - PLACE_REFINE) * PLACE_STEP;
            }
        }
        IntStream.range(0, gv.length).parallel().forEach(i -> gv[i] = at(place, gx[i], gz[i]));
        int r = argmax(gv);
        double px = Math.floor(gx[r]), pz = Math.floor(gz[r]);
        double[] ring = new double[LEVEL_RADII.length * LEVEL_ANGLES];
        IntStream.range(0, ring.length).parallel().forEach(i -> {
            double rd = LEVEL_RADII[i / LEVEL_ANGLES];
            double a = 2.0 * Math.PI * (i % LEVEL_ANGLES) / LEVEL_ANGLES;
            ring[i] = at(range, px + rd * Math.cos(a), pz + rd * Math.sin(a));
        });
        double level = m.levelQ().isPresent() ? percentile(ring, m.levelQ().get())
                : Arrays.stream(ring).max().orElse(0.0);
        // A continent whose best range is too low gets one raised for its giant (the user,
        // 2026-11-28: "raise its own range" rather than a modest giant).
        double deficit = Math.max(0.0, m.levelFloor() - level);
        Placed p = new Placed(px, pz, Math.max(level, m.levelFloor()), deficit);
        c.put(key, p);
        return p;
    }

    /** numpy's argmax: the first of the largest. */
    private static int argmax(double[] v) {
        int best = 0;
        for (int i = 1; i < v.length; i++) if (v[i] > v[best]) best = i;
        return best;
    }

    /** numpy's default (linear) percentile. */
    private static double percentile(double[] v, double q) {
        double[] s = v.clone();
        Arrays.sort(s);
        double pos = q / 100.0 * (s.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(s.length - 1, lo + 1);
        return s[lo] + (s[hi] - s[lo]) * (pos - lo);
    }

    /**
     * The raised range's bearing: of 12, the one whose line through the summit has the most of
     * {@code range} along it (at a third and two thirds of half the length each way).
     */
    static double axis(World noise, int k, Placed p, Mode m) {
        double half = 0.5 * m.raiseLength();
        record AxisKey(World w, int k, Placed p, double half, Sig sig) {
        }
        AxisKey key = new AxisKey(noise, k, p, half, m.sig());
        Map<Object, Object> c = CACHE;
        Object hit = c.get(key);
        if (hit != null) return (Double) hit;
        DensityFunction range = m.rangeOrPlace();
        double[] mean = new double[12];
        double[] steps = {-2, -1, 1, 2};
        for (int i = 0; i < 12; i++) {
            double a = Math.PI * i / 12.0, sum = 0.0;
            for (double s : steps) {
                double d = s * half / 3.0;
                sum += at(range, p.x() + Math.cos(a) * d, p.z() + Math.sin(a) * d);
            }
            mean[i] = sum / steps.length;
        }
        double a = Math.PI * argmax(mean) / 12.0;
        c.put(key, a);
        return a;
    }

    // -- the solver ---------------------------------------------------------------------------------

    /** drop below zero: the solver left the ring alone (the sister term is off). */
    static final double NO_SISTERS = -1.0;
    private static final double RING_LO = 600.0, RING_HI = 3000.0, RING_STEP = 64.0;
    private static final int CANDIDATES = 256, REFINE = 12;
    private static final boolean DEBUG = Boolean.getBoolean("terracontinental.giantDebug");

    /** One giant's solution, keyed so every node wired to the same world shares it. */
    private record SolveKey(World world, int k, long x, long z, Solve solve) {
    }

    /** What the solver measures a world with: its heights, and the seeded final height offset. */
    public record Env(IntBinaryOperator heights, DensityFunction offset) {
    }

    private static final Map<World, Env> ENVS = new ConcurrentHashMap<>();
    private static final Map<SolveKey, CompletableFuture<double[]>> SOLVED = new ConcurrentHashMap<>();
    /** A trial's values, seen only by the threads measuring it (chunk generation never sees one). */
    private static final ThreadLocal<Map<SolveKey, double[]>> TRIAL = ThreadLocal.withInitial(HashMap::new);
    private static volatile boolean warned;

    private static final ForkJoinPool POOL = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
            pool -> {
                ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                t.setName("Terra Continental giant solver " + t.getPoolIndex());
                t.setDaemon(true);
                return t;
            }, null, false);

    private static final net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters> JITTER =
            net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.NOISE,
                    net.minecraft.resources.ResourceLocation.parse("terra_continental:continent_jitter"));
    private static final net.minecraft.resources.ResourceKey<DensityFunction> OFFSET =
            net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DENSITY_FUNCTION,
                    net.minecraft.resources.ResourceLocation.parse("terra_continental:terrain_spline/offset/continents"));

    /**
     * Registers the overworld's terrain for the solver, if it carries Terra Continental's: called
     * from the overworld's load event, before anything generates (giants solved before then would
     * have nothing to measure).
     */
    public static void register(net.minecraft.server.level.ServerLevel level) {
        if (!(level.getChunkSource().getGenerator() instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator gen)
                || !gen.generatorSettings().unwrapKey()
                        .map(k -> com.terracontinental.terrain.TerrainBase.OVERWORLD_SETTINGS.contains(k.location().toString()))
                        .orElse(false)) {
            return;
        }
        register(level, gen, level.getChunkSource().randomState(), true);
    }

    /**
     * Registers terrain made by {@code gen} with {@code rs} (the world's own, or a tool's for another
     * seed: biome_map); {@code replace} false keeps a world already registered for that seed.
     */
    public static void register(net.minecraft.server.level.ServerLevel level,
                                net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator gen,
                                net.minecraft.world.level.levelgen.RandomState rs, boolean replace) {
        var offset = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.DENSITY_FUNCTION)
                .getOptional(OFFSET);
        if (offset.isEmpty()) return;
        DensityFunction seeded = com.terracontinental.plan.WorldPlan.seeded(offset.get(), rs);
        Env env = new Env((x, z) -> gen.getBaseHeight(x, z,
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, level, rs), seeded);
        World w = world(rs);
        if (replace) {
            ENVS.put(w, env);
            java.nio.file.Path file = com.terracontinental.plan.WorldCache.file(level.getServer(), FILE);
            long fp = com.terracontinental.plan.WorldCache.fingerprint(level.getServer(), level.getSeed());
            int read = load(file, fp, w);
            if (read > 0) LOGGER.info("[giant] {} giants' solutions read from the world", read);
            placeAll(rs);
            presolve(rs, () -> save(file, fp, w));
        } else {
            ENVS.putIfAbsent(w, env);
        }
    }

    /**
     * Solves the world's giants in the background as it loads, so no chunk, biome lookup or the
     * server thread waits on a solve later (a solve runs ~30 getBaseHeight batches).
     */
    /**
     * Places every giant now, before anything generates: until a world's giants are placed, every
     * copy of the router carries their placement functions (see GiantPeaksFunction.placedKnown).
     */
    private static void placeAll(net.minecraft.world.level.levelgen.RandomState rs) {
        long t0 = System.nanoTime();
        java.util.List<GiantPeaksFunction> all = new java.util.ArrayList<>();
        // The whole router: the biome climate (ridges, vegetation) holds giant nodes of its own.
        rs.router().mapAll(new DensityFunction.Visitor() {
            @Override
            public DensityFunction apply(DensityFunction f) {
                if (f instanceof GiantPeaksFunction g) all.add(g);
                return f;
            }
        });
        for (GiantPeaksFunction g : all) g.place();
        LOGGER.info("[giant] {} giant nodes placed in {} ms", all.size(), (System.nanoTime() - t0) / 1_000_000L);
    }

    private static void presolve(net.minecraft.world.level.levelgen.RandomState rs, Runnable then) {
        GiantPeaksFunction[] found = new GiantPeaksFunction[1];
        rs.router().finalDensity().mapAll(new DensityFunction.Visitor() {
            @Override
            public DensityFunction apply(DensityFunction f) {
                if (found[0] == null && f instanceof GiantPeaksFunction g && g.solves()) found[0] = g;
                return f;
            }
        });
        if (found[0] == null) return;
        Thread t = new Thread(() -> {
            long t0 = System.nanoTime();
            try {
                found[0].presolve();
                LOGGER.info("[giant] every giant solved in {} ms", (System.nanoTime() - t0) / 1_000_000L);
                then.run();
            } catch (Throwable e) {
                LOGGER.error("[giant] solving failed", e);
            }
        }, "Terra Continental giant presolve");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Registers a world's terrain for the solver, under the jitter noise its giant_peaks nodes are
     * wired with. Must run before the world generates anything (the overworld's load event).
     */
    public static void register(World world, Env env) {
        ENVS.put(world, env);
    }

    /** The overworld's terrain, for {@link #register(World, Env)} from a tool (biome_map). */
    public static World world(net.minecraft.world.level.levelgen.RandomState rs) {
        return World.of(rs.getOrCreateNoise(JITTER));
    }

    /** Forgets every registered terrain (the overworld is closing); solutions stay, being values. */
    public static void unregisterAll() {
        ENVS.clear();
    }

    /**
     * The world's saved solutions (like the world plan, so a world solves its giants once, when it is
     * created: ~1-5 minutes of getBaseHeight). The file's fingerprint covers the seed, the worldgen
     * and Terra Continental's version and settings, so a solution is only read where the solver
     * would find it again.
     */
    private static final String FILE = "giants.dat";
    private static final int VERSION = 1;

    private static int load(java.nio.file.Path file, long fp, World w) {
        Integer n = com.terracontinental.plan.WorldCache.read(file, VERSION, fp, in -> {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int k = in.readInt();
                long x = in.readLong(), z = in.readLong();
                Solve s = new Solve(in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(),
                        in.readDouble(), in.readDouble());
                double[] r = {in.readDouble(), in.readDouble()};
                SOLVED.putIfAbsent(new SolveKey(w, k, x, z, s), CompletableFuture.completedFuture(r));
            }
            return count;
        });
        return n == null ? 0 : n;
    }

    private static void save(java.nio.file.Path file, long fp, World w) {
        java.util.List<Map.Entry<SolveKey, double[]>> done = new java.util.ArrayList<>();
        SOLVED.forEach((key, f) -> {
            if (key.world().equals(w) && f.isDone() && !f.isCompletedExceptionally()) done.add(Map.entry(key, f.join()));
        });
        com.terracontinental.plan.WorldCache.write(file, VERSION, fp, out -> {
            out.writeInt(done.size());
            for (Map.Entry<SolveKey, double[]> e : done) {
                SolveKey key = e.getKey();
                Solve s = key.solve();
                out.writeInt(key.k());
                out.writeLong(key.x());
                out.writeLong(key.z());
                out.writeDouble(s.prom());
                out.writeDouble(s.rise());
                out.writeDouble(s.cap());
                out.writeDouble(s.dropMin());
                out.writeDouble(s.dropMax());
                out.writeDouble(s.riseMin());
                out.writeDouble(e.getValue()[0]);
                out.writeDouble(e.getValue()[1]);
            }
        });
    }

    /**
     * Giant k's summit target and sister drop (offset units), solved against the real terrain so
     * the summit stands {@code prom} blocks over the highest point 600-3,000 blocks out. With no
     * sister lift it measures the prominence at the default target (level + rise, capped). Too
     * little: the target rises, up to the cap, and if still short there the ring's highs are
     * pressed down. Too much: the sisters rise, and if that isn't enough the summit comes down.
     * Each is a bisection on a quantity the prominence moves with.
     */
    static double[] solution(World noise, int k, Placed p, Solve s) {
        SolveKey key = new SolveKey(noise, k, Math.round(p.x()), Math.round(p.z()), s);
        Map<SolveKey, double[]> trials = TRIAL.get();
        double[] trial = trials.get(key);
        if (trial != null) return trial;
        if (!trials.isEmpty()) {
            // Another giant is being measured: this one stands at its unsolved default, so no
            // solve depends on the order giants are solved in (two giants 4.9 km apart each
            // started a solve of the other mid-trial, and which went first was down to thread
            // timing: 4151832666630476678, 2026-11-28).
            return new double[]{Math.min(s.cap(), p.level() + s.rise()), NO_SISTERS};
        }
        CompletableFuture<double[]> f = SOLVED.get(key);
        String forced = System.getProperty("terracontinental.giantForce");
        if (f == null && forced != null) {
            // Debug only: "k:target:drop,..." skips the solver for those giants (parity tests).
            for (String part : forced.split(",")) {
                String[] v = part.split(":");
                if (Integer.parseInt(v[0]) == k) {
                    double[] r = {Double.parseDouble(v[1]), Double.parseDouble(v[2])};
                    SOLVED.putIfAbsent(key, CompletableFuture.completedFuture(r));
                    return SOLVED.get(key).join();
                }
            }
        }
        if (f == null) {
            Env env = ENVS.get(noise);
            if (env == null) {
                // Not a registered world (a tool, or before the overworld loads): no solve, and
                // nothing cached, so a world that registers later still gets its own.
                if (!warned) {
                    warned = true;
                    LOGGER.warn("[giant] no terrain registered for the giant solver; using unsolved giants here");
                }
                return new double[]{Math.min(s.cap(), p.level() + s.rise()), NO_SISTERS};
            }
            CompletableFuture<double[]> mine = new CompletableFuture<>();
            f = SOLVED.putIfAbsent(key, mine);
            if (f == null) {
                try {
                    long t0 = System.nanoTime();
                    double[] r = solve(key, p, s, env);
                    mine.complete(r);
                    LOGGER.info("[giant] giant {} at {},{}: target {}, drop {} ({} ms)", k, (long) p.x(), (long) p.z(),
                            r[0], r[1], (System.nanoTime() - t0) / 1_000_000L);
                    return r;
                } catch (Throwable e) {
                    mine.completeExceptionally(e);
                    SOLVED.remove(key, mine);
                    throw e;
                }
            }
        }
        return f.join();
    }

    private static double[] solve(SolveKey key, Placed p, Solve s, Env env) {
        double px = p.x(), pz = p.z(), level = p.level();
        double goal = s.prom();
        double t0 = Math.min(s.cap(), level + s.rise());
        int[][] summit = new int[49][];
        for (int i = 0; i < 7; i++) {
            for (int j = 0; j < 7; j++) {
                // 16 apart: on a 32-block grid a jagged horn's top was missed by up to ~30 blocks.
                summit[i * 7 + j] = new int[]{(int) Math.rint(px + (j - 3) * 16.0), (int) Math.rint(pz + (i - 3) * 16.0)};
            }
        }
        int n = 0;
        int side = (int) Math.floor(2.0 * RING_HI / RING_STEP) + 1;
        double[] rx = new double[side * side], rz = new double[side * side];
        for (int i = 0; i < side; i++) {
            for (int j = 0; j < side; j++) {
                double x = px + (-RING_HI + j * RING_STEP), z = pz + (-RING_HI + i * RING_STEP);
                double d = Math.hypot(x - px, z - pz);
                if (d > RING_LO && d < RING_HI) {
                    rx[n] = x;
                    rz[n] = z;
                    n++;
                }
            }
        }
        double[] ringX = Arrays.copyOf(rx, n), ringZ = Arrays.copyOf(rz, n);
        Measure measure = (t, drop) -> measure(key, new double[]{t, drop}, env, summit, ringX, ringZ);

        double cap = s.cap(), dmin = s.dropMin(), dmax = s.dropMax();
        double p0 = measure.at(t0, NO_SISTERS);
        double[] result = {t0, NO_SISTERS};
        double t = t0;
        if (p0 < goal && t0 < cap) {
            // The ring stands too close under the summit: raise the summit, at most to the cap.
            double pcap = measure.at(cap, NO_SISTERS);
            if (pcap < goal) {
                t = cap;
            } else {
                double a = t0, b = cap, pa = p0, pb = pcap;
                for (int i = 0; i < 6; i++) {
                    double m = 0.5 * (a + b);
                    double pm = measure.at(m, NO_SISTERS);
                    if (pm < goal) {
                        a = m;
                        pa = pm;
                    } else {
                        b = m;
                        pb = pm;
                    }
                }
                result = new double[]{closer(a, pa, b, pb, goal), NO_SISTERS};
            }
        }
        if (p0 > goal || (p0 < goal && t == cap)) {
            // Proud (the sisters rise) or short at the cap (the ring's highs are pressed down):
            // the ring's top moves toward t - drop, so the prominence rises with the drop.
            double a = dmin, b = dmax;
            double pa = measure.at(t, a);
            if (pa >= goal) {
                if (t < cap) {
                    // Still proud with the sisters as high as they go: the summit comes down, at
                    // most to level + rise_min (a raised range's giant, 4151832666630476678 at 389).
                    double lo = level + s.riseMin(), hi = t;
                    double plo = measure.at(lo, a), phi = pa;
                    if (plo >= goal) {
                        result = new double[]{lo, a};
                    } else {
                        for (int i = 0; i < 6; i++) {
                            double m = 0.5 * (lo + hi);
                            double pm = measure.at(m, a);
                            if (pm > goal) {
                                hi = m;
                                phi = pm;
                            } else {
                                lo = m;
                                plo = pm;
                            }
                        }
                        result = new double[]{closer(lo, plo, hi, phi, goal), a};
                    }
                } else {
                    result = new double[]{t, a};
                }
            } else {
                double pb = measure.at(t, b);
                if (pb <= goal) {
                    result = new double[]{t, b};
                } else {
                    for (int i = 0; i < 6; i++) {
                        double m = 0.5 * (a + b);
                        double pm = measure.at(t, m);
                        if (pm < goal) {
                            a = m;
                            pa = pm;
                        } else {
                            b = m;
                            pb = pm;
                        }
                    }
                    result = new double[]{t, closer(a, pa, b, pb, goal)};
                }
            }
        }
        return result;
    }

    /**
     * A value that was measured, the nearer the goal (ties to the first): the prominence can jump
     * between two close trials as a ledge appears or goes, so the midpoint of the last bracket,
     * never measured, landed 50 under the goal in game (4151832666630476678, 2026-11-28).
     */
    private static double closer(double a, double pa, double b, double pb, double goal) {
        return Math.abs(pa - goal) <= Math.abs(pb - goal) ? a : b;
    }

    private interface Measure {
        double at(double t, double drop);
    }

    /**
     * The summit's height (the highest of a 7 x 7 grid 16 blocks apart) minus the ring's (the
     * highest of its CANDIDATES highest points by final offset), with the trial in force for
     * this giant on every thread that measures it.
     */
    private static double measure(SolveKey key, double[] trial, Env env, int[][] summit, double[] rx, double[] rz) {
        Map<SolveKey, double[]> outer = Map.of(key, trial);
        double[] off = new double[rx.length];
        run(() -> IntStream.range(0, rx.length).parallel().forEach(i ->
                off[i] = withTrial(outer, () -> at(env.offset(), rx[i], rz[i]))));
        // The CANDIDATES highest, ties in index order (numpy's stable argsort).
        Integer[] order = new Integer[off.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(off[a], off[b]));
        int from = Math.max(0, order.length - CANDIDATES);
        int[][] cols = new int[summit.length + order.length - from][];
        System.arraycopy(summit, 0, cols, 0, summit.length);
        for (int i = from; i < order.length; i++) {
            cols[summit.length + i - from] = new int[]{(int) Math.rint(rx[order[i]]), (int) Math.rint(rz[order[i]])};
        }
        int[] h = heights(env, outer, cols);
        int hs = Integer.MIN_VALUE, hr = Integer.MIN_VALUE;
        for (int i = 0; i < h.length; i++) {
            if (i < summit.length) hs = Math.max(hs, h[i]);
            else hr = Math.max(hr, h[i]);
        }
        // Round the tallest few on the 32-block grid the summit is measured on: the true top of a
        // ring peak sits between the 64-block samples, and missing it left giants 50-90 blocks
        // short of their goal (first game parity run, 4151832666630476678). Order as the
        // previewer's: candidates by height, ties in candidate order, then dz, then dx.
        int nc = order.length - from;
        Integer[] byH = new Integer[nc];
        for (int i = 0; i < nc; i++) byH[i] = i;
        Arrays.sort(byH, (a, b) -> Integer.compare(h[summit.length + a], h[summit.length + b]));
        java.util.List<int[]> fine = new java.util.ArrayList<>();
        double px = cx(summit), pz = cz(summit);
        for (int i = Math.max(0, nc - REFINE); i < nc; i++) {
            double bx = rx[order[from + byH[i]]], bz = rz[order[from + byH[i]]];
            for (int a = -2; a <= 2; a++) {
                for (int b = -2; b <= 2; b++) {
                    double fx = bx + b * 32.0, fz = bz + a * 32.0;
                    double d = Math.hypot(fx - px, fz - pz);
                    if (d > RING_LO && d < RING_HI) fine.add(new int[]{(int) Math.rint(fx), (int) Math.rint(fz)});
                }
            }
        }
        int[][] fineCols = fine.toArray(new int[0][]);
        int[] hf = heights(env, outer, fineCols);
        for (int v : hf) hr = Math.max(hr, v);
        if (DEBUG) {
            int[] at = null;
            for (int i = summit.length; i < h.length; i++) if (h[i] == hr) at = cols[i];
            for (int i = 0; i < hf.length; i++) if (hf[i] == hr) at = fineCols[i];
            LOGGER.info("[giant] measure t {} drop {}: summit {} ring {} at {} -> {}", trial[0], trial[1], hs, hr,
                    at == null ? "?" : at[0] + "," + at[1], hs - hr);
        }
        return hs - hr;
    }

    /** The summit grid's centre (its middle column is the summit). */
    private static double cx(int[][] summit) {
        return summit[24][0];
    }

    private static double cz(int[][] summit) {
        return summit[24][1];
    }

    private static int[] heights(Env env, Map<SolveKey, double[]> trials, int[][] cols) {
        int[] h = new int[cols.length];
        run(() -> IntStream.range(0, cols.length).parallel().forEach(i ->
                h[i] = (int) withTrial(trials, () -> env.heights().applyAsInt(cols[i][0], cols[i][1]))));
        return h;
    }

    private static void run(Runnable r) {
        if (inPool()) r.run();
        else POOL.submit(r).join();
    }

    private static boolean inPool() {
        return Thread.currentThread() instanceof ForkJoinWorkerThread w && w.getPool() == POOL;
    }

    private static double withTrial(Map<SolveKey, double[]> trials, java.util.function.DoubleSupplier f) {
        Map<SolveKey, double[]> before = TRIAL.get();
        TRIAL.set(trials);
        try {
            return f.getAsDouble();
        } finally {
            TRIAL.set(before);
        }
    }
}
