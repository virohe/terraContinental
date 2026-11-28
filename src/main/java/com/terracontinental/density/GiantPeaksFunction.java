package com.terracontinental.density;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.terracontinental.config.TerraConfig;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.Optional;

/**
 * One giant massif per continent, as a bump in Tectonic's height offset (offset units, before
 * vertical_scale), added to {@code terra_continental:terrain_spline/offset/continents} by the terrain pack.
 * Registered as {@code terra_continental:giant_peaks}.
 *
 * <p>Why: the user wants a peak over 1,000 blocks "almost hard to believe", about once per
 * continent (docs/decisions.md, 2026-10-23); Tectonic's own mountains top out near +600.
 *
 * <p>Each massif sits {@code reach} of its continent's radius from the centre, at a bearing drawn
 * from the seeded jitter noise at fixed coordinates (the same noise
 * {@link RadialFalloffFunction} draws its continents from, at different constants). Profile:
 * a broad dome ({@code radius}) plus a summit cone ({@code summit} of the height, {@code summit_width}
 * of the radius, default a third, and {@code summit_power} concave), so it reads as one mountain,
 * and optionally lesser summits round it ({@link Companions}); {@code variety} gives each massif its
 * own height, size, summit shape, companions and spurs.
 *
 * <p>Tour fixes (2026-10-24), all off by default and drawn from {@code detail_noise}: a perfectly
 * round, smooth dome showed its block steps as concentric rings on the flanks, so {@code warp}
 * roughens the outline, {@code spurs} carves ridges and gullies down the flanks (noise in polar
 * coordinates, so they converge on the summit; faded in below the summit cone, where at full
 * strength they met in a starburst of knife edges) and {@code texture} adds knolls ~250 blocks
 * across. {@code output: "mask"} returns the dome's 0-1 footprint (within {@code mask_reach} of
 * the radius) instead of a height; the seam uses it to flatten Tectonic's own relief under the
 * massif and to pull its biomes toward peaks.
 *
 * <p>Mirrors {@code preview/continental_terrain.py}'s {@code _giant_peaks}; keep the two in step.
 */
public final class GiantPeaksFunction implements DensityFunction {

    // Spur and texture scales in the detail noise's input units (terra_continental:tectonic,
    // wavelength ~1,024): ~10 spurs around a massif, meandering about once per 650 blocks and
    // bent by up to SPUR_BEND radians; knolls ~256 blocks across, grain ~64. Same constants as the
    // previewer.
    private static final double SPUR_ARC = 1630.0;
    private static final double SPUR_RADIAL = 1.6;
    private static final double SPUR_BEND = 0.6;
    private static final double SPUR_START = 0.12;
    private static final double SPUR_FADE = 0.35;
    private static final double TEXTURE_SCALE = 4.0;
    private static final double GRAIN_SCALE = 16.0;

    private final NoiseHolder jitterNoise;
    /** continent_base, to find the steered layout in (SpawnSteer); empty = the unsteered continents. */
    private final Optional<DensityFunction> continents;
    private final double height;
    private final double radius;
    private final double reach;
    private final String output;
    private final double maskReach;
    private final double warp;
    private final double spurs;
    private final double texture;
    private final double grain;
    private final double summit;
    private final double summitWidth;
    private final double summitPower;
    private final Companions companions;
    private final double variety;
    private final Optional<NoiseHolder> detailNoise;
    private final boolean mask;
    /** Range mode (place present): where each giant stands, and the range outputs. */
    private final GiantRange.Mode mode;
    private final int out;

    private volatile Massif[] peaks;
    private volatile GiantRange.Placed[] placed;
    private volatile GiantRange.World world;

    private GiantRange.World world() {
        GiantRange.World w = world;
        if (w == null) world = w = GiantRange.World.of(jitterNoise.noise());
        return w;
    }

    public static final MapCodec<GiantPeaksFunction> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    Source.CODEC.forGetter(f -> new Source(f.jitterNoise, f.continents, f.mode)),
                    Codec.DOUBLE.fieldOf("height").forGetter(f -> f.height),
                    Codec.DOUBLE.optionalFieldOf("radius", 2400.0).forGetter(f -> f.radius),
                    Codec.DOUBLE.optionalFieldOf("reach", 0.35).forGetter(f -> f.reach),
                    Codec.STRING.optionalFieldOf("output", "height").forGetter(f -> f.output),
                    Codec.DOUBLE.optionalFieldOf("mask_reach", 1.0).forGetter(f -> f.maskReach),
                    Codec.DOUBLE.optionalFieldOf("warp", 0.0).forGetter(f -> f.warp),
                    Codec.DOUBLE.optionalFieldOf("spurs", 0.0).forGetter(f -> f.spurs),
                    Codec.DOUBLE.optionalFieldOf("texture", 0.0).forGetter(f -> f.texture),
                    Codec.DOUBLE.optionalFieldOf("grain", 0.0).forGetter(f -> f.grain),
                    Codec.DOUBLE.optionalFieldOf("summit", 0.35).forGetter(f -> f.summit),
                    Codec.DOUBLE.optionalFieldOf("summit_width", 1.0 / 3.0).forGetter(f -> f.summitWidth),
                    Codec.DOUBLE.optionalFieldOf("summit_power", 1.5).forGetter(f -> f.summitPower),
                    Companions.CODEC.forGetter(f -> f.companions),
                    Codec.DOUBLE.optionalFieldOf("variety", 0.0).forGetter(f -> f.variety),
                    NoiseHolder.CODEC.optionalFieldOf("detail_noise").forGetter(f -> f.detailNoise)
            ).apply(instance, GiantPeaksFunction::new));

    public GiantPeaksFunction(Source source, double height, double radius, double reach, String output,
                              double maskReach, double warp, double spurs, double texture, double grain,
                              double summit,
                              double summitWidth, double summitPower, Companions companions, double variety,
                              Optional<NoiseHolder> detailNoise) {
        this.jitterNoise = source.jitterNoise();
        this.continents = source.continents();
        this.height = height;
        this.radius = radius;
        this.reach = reach;
        this.output = output;
        this.maskReach = maskReach;
        this.warp = warp;
        this.spurs = spurs;
        this.texture = texture;
        this.grain = grain;
        this.summit = summit;
        this.summitWidth = summitWidth;
        this.summitPower = summitPower;
        this.companions = companions;
        this.variety = variety;
        this.detailNoise = detailNoise;
        this.mask = "mask".equals(output);
        this.mode = source.mode();
        this.out = switch (output) {
            case "gain" -> GAIN;
            case "target" -> TARGET;
            case "drop" -> DROP;
            case "sisters" -> SISTERS;
            case "level" -> LEVEL;
            case "deficit" -> DEFICIT;
            case "ring" -> RING;
            case "raise" -> RAISE;
            case "raise_shape" -> RAISE_SHAPE;
            default -> HEIGHT;
        };
    }

    private static final int HEIGHT = 0, GAIN = 1, TARGET = 2, DROP = 3, SISTERS = 4, LEVEL = 5, DEFICIT = 6,
            RING = 7, RAISE = 8, RAISE_SHAPE = 9;

    /**
     * One massif: where it stands and its own character. {@code comps} holds {x, z, cone amplitude}
     * per lesser summit.
     */
    private record Massif(double x, double z, double height, double radius, double summit, double summitWidth,
                          double summitPower, double spurs, double stretch, double cos, double sin,
                          double[][] comps) {
    }

    /** Every continent's massif: the spawn continent first, then the extra ones. */
    /**
     * Every node's massifs, per world and per the settings they depend on: each NoiseChunk (one per
     * getBaseHeight call, one per chunk) maps the router into new instances, and range-mode
     * placement samples thousands of points, so it is worked out once per world, not per instance.
     */
    private record PeaksKey(GiantRange.World world, double height, double radius, double reach, double summit,
                            double summitWidth, double summitPower, Companions companions, double variety,
                            double spurs, boolean steered, GiantRange.Sig sig) {
    }

    private static final java.util.Map<PeaksKey, Object[]> PEAKS = new java.util.concurrent.ConcurrentHashMap<>();

    private PeaksKey peaksKey() {
        return new PeaksKey(world(), height, radius, reach, summit, summitWidth, summitPower, companions, variety,
                spurs, continents.isPresent(), mode.sig());
    }

    /**
     * True once this world's massifs for this node are worked out: the placement functions are not
     * needed any more, so copies stop wrapping them. Every NoiseChunk (one per getBaseHeight call)
     * maps the whole router, and 17 giant nodes each carrying three copies of the terrain's offset
     * made getBaseHeight about 10 times slower (rig, 2026-11-28: rare-site search 86 s, was 9 s).
     */
    private boolean placedKnown() {
        return mode.on() && jitterNoise.noise() != null && PEAKS.containsKey(peaksKey());
    }

    /** Works out this node's massifs now (GiantRange.register does it before anything generates). */
    void place() {
        peaks();
    }

    private Massif[] peaks() {
        Massif[] p = peaks;
        if (p == null) {
            PeaksKey key = peaksKey();
            Object[] hit = PEAKS.get(key);
            if (hit != null) {
                placed = (GiantRange.Placed[]) hit[1];
                peaks = (Massif[]) hit[0];
                return peaks;
            }
            double centralRadius = 14500.0, spread = 60000.0, contRadius = 14000.0;
            int extra = 5;
            try {
                centralRadius = TerraConfig.CENTRAL_LAND_RADIUS.get();
                extra = TerraConfig.ADDITIONAL_CONTINENTS.get();
                spread = TerraConfig.CONTINENT_SPREAD.get();
                contRadius = TerraConfig.CONTINENT_RADIUS.get();
            } catch (IllegalStateException notLoadedYet) {
                // Defaults above; configs load long before worldgen in practice.
            }
            double[][] centers = RadialFalloffFunction.buildCenters(jitterNoise, extra, spread, contRadius);
            // Steered (SpawnSteer): the moved continents, and the spawn massif within the core lobe.
            RadialFalloffFunction falloff = continents.map(GiantPeaksFunction::findFalloff).orElse(null);
            if (falloff != null && falloff.steered()) {
                double[][] all = falloff.continents();
                centralRadius = all[0][2];
                centers = java.util.Arrays.copyOfRange(all, 1, all.length);
            }
            p = new Massif[centers.length + 1];
            GiantRange.Placed[] where = mode.on() ? new GiantRange.Placed[p.length] : null;
            for (int k = 0; k <= centers.length; k++) {
                double cx = k == 0 ? 0.0 : centers[k - 1][0];
                double cz = k == 0 ? 0.0 : centers[k - 1][1];
                double cr = k == 0 ? centralRadius : centers[k - 1][2];
                // Unclamped: clamping (noise * 2) to +-1 sent many seeds' giants due west.
                double angle = jitterNoise.getValue(k * 7919.0 + 211.0, 0.0, k * -3571.0 - 59.0) * 2.0 * Math.PI;
                double px = cx + Math.cos(angle) * cr * reach;
                double pz = cz + Math.sin(angle) * cr * reach;
                if (where != null) {
                    // Range mode: on the continent's broadest range (the user's 8-seed review,
                    // 2026-11-27: placed by bearing, giants stood with no range of origin).
                    where[k] = GiantRange.place(world(), k, cx, cz, cr, angle, mode);
                    px = where[k].x();
                    pz = where[k].z();
                }

                // Each massif's own character (variety, 0-1): with one recipe, every giant on every
                // seed came out alike (the user, 2026-10-24: worlds and continents should have more
                // character). Height only goes down, so none reaches the world ceiling.
                double h = height * (1.0 - 0.03 * variety * (draw(k, 0) + 1.0));
                double rad = radius * (1.0 + 0.2 * variety * draw(k, 1));
                double sm = Math.min(0.6, Math.max(0.15, summit + 0.1 * variety * draw(k, 2)));
                double sw = summitWidth * (1.0 + 0.25 * variety * draw(k, 3));
                double sp = summitPower * (1.0 + 0.3 * variety * draw(k, 4));
                int n = (int) Math.floor(companions.count() + 2.0 * variety * draw(k, 5) + 0.5);
                n = Math.min(companions.count() + 2, Math.max(companions.count() > 0 ? 1 : 0, n));
                double ch = Math.min(0.92, companions.height() * (1.0 + 0.06 * variety * draw(k, 6)));
                double cd = companions.dist() * (1.0 + 0.25 * variety * draw(k, 7));
                double spk = spurs * (1.0 + 0.4 * variety * draw(k, 8));
                // Stretched along a seeded axis (1 to 1.6 times, the same area): some giants are
                // round cones, others long massifs with a summit ridge.
                double el = 1.0 + 0.3 * variety * (draw(k, 9) + 1.0);
                // Unclamped, as the massif's bearing: clamped draws pile up at +-1, which is one axis.
                double axis = 2.5 * Math.PI
                        * jitterNoise.getValue(k * 577.0 + 10 * 97.0 + 13.0, 0.0, k * -389.0 - 10 * 61.0 - 29.0);
                double ca = Math.cos(axis), sa = Math.sin(axis), se = Math.sqrt(el);

                double[][] comps = new double[n][3];
                for (int j = 0; j < n; j++) {
                    // Bearings and distances from the jitter noise at fixed coordinates, like the
                    // massif's own; the first is the tallest.
                    double a = 2.0 * Math.PI * j / n
                            + 1.2 * jitterNoise.getValue(k * 131.0 + j * 17.0 + 5.0, 0.0, j * -29.0 - 7.0);
                    double r = cd * rad
                            * (0.85 + 0.3 * jitterNoise.getValue(k * -83.0 + j * 41.0 - 3.0, 0.0, j * 23.0 + 11.0));
                    // Each tip is a set share of the main summit: the cone makes up the difference
                    // to the profile under it, so none outgrows the main peak (one did by 26 blocks
                    // in the previewer when the cones were simply added before the spurs).
                    double tj = Math.min(r / rad, 1.0);
                    double under = (1.0 - sm) * (1.0 - tj * tj * (3.0 - 2.0 * tj))
                            + sm * Math.pow(Math.max(0.0, 1.0 - tj / sw), sp);
                    double tip = ch * (1.0 - 0.15 * j / Math.max(1, n - 1));
                    // Placed in the massif's stretched frame, so they follow its long axis.
                    double la = Math.cos(a) * r * se, lc = Math.sin(a) * r / se;
                    comps[j][0] = px + la * ca - lc * sa;
                    comps[j][1] = pz + la * sa + lc * ca;
                    comps[j][2] = Math.max(0.0, tip - under);
                }
                p[k] = new Massif(px, pz, h, rad, sm, sw, sp, spk, el, ca, sa, comps);
            }
            placed = where;
            peaks = p;
            PEAKS.putIfAbsent(key, new Object[]{p, where});
        }
        return p;
    }

    /**
     * Massif k's draw i in -1..1: the jitter noise at fixed coordinates, stretched (its raw values
     * cluster within about +-0.4, which gave every giant a near-average character).
     */
    private double draw(int k, int i) {
        double v = 2.5 * jitterNoise.getValue(k * 577.0 + i * 97.0 + 13.0, 0.0, k * -389.0 - i * 61.0 - 29.0);
        return Math.min(1.0, Math.max(-1.0, v));
    }

    @Override
    public double compute(FunctionContext ctx) {
        double x = ctx.blockX(), z = ctx.blockZ();
        Massif[] all = peaks();
        if (out != HEIGHT && !mask) {
            return rangeOutput(x, z);
        }
        NoiseHolder detail = detailNoise.orElse(null);
        double warpReach = 1.0 + Math.abs(warp) * (detail == null ? 0.0 : detail.maxValue());
        double sum = 0.0;
        for (int k = 0; k < all.length; k++) {
            Massif m = all[k];
            double dx = x - m.x(), dz = z - m.z();
            double along = dx * m.cos() + dz * m.sin(), across = -dx * m.sin() + dz * m.cos();
            double d2 = along * along / m.stretch() + across * across * m.stretch();
            // Past this (stretched) distance the massif doesn't reach, whatever the warp noise does;
            // a mask can reach past the radius (the seam's terrace smoothing does).
            double outer = m.radius() * warpReach * (mask ? Math.max(1.0, maskReach) : 1.0);
            if (d2 >= outer * outer) {
                continue;
            }
            double d = Math.sqrt(d2);
            double r = m.radius();
            if (warp != 0.0 && detail != null) {
                r = m.radius() * (1.0 + warp * detail.getValue(x, 4111.0 + k, z));
            }
            double t = d / r;
            if (mask) {
                double tm = Math.min(Math.max(t / maskReach, 0.0), 1.0);
                sum = Math.max(sum, 1.0 - tm * tm * (3.0 - 2.0 * tm));
                continue;
            }
            if (t >= 1.0) {
                continue;
            }
            double dome = 1.0 - t * t * (3.0 - 2.0 * t);
            // summit: the cone's share of the height. A narrower, more concave cone gives a sharper
            // top (the user, 2026-10-24, after flattening left a rounded one).
            double cone = Math.pow(Math.max(0.0, 1.0 - t / m.summitWidth()), m.summitPower());
            double bump = (1.0 - m.summit()) * dome + m.summit() * cone;
            if (m.spurs() != 0.0 && detail != null) {
                // Bent by a low-frequency noise, so spurs curve and fork instead of running
                // ruler-straight (pie-slice facets in the previewer).
                double angle = Math.atan2(dz, dx) + SPUR_BEND * detail.getValue(x, 6007.0 + k, z);
                double n = detail.getValue(Math.cos(angle) * SPUR_ARC, d * SPUR_RADIAL + 977.0 * (k + 1),
                        Math.sin(angle) * SPUR_ARC);
                double ridge = 1.0 - 2.0 * Math.min(Math.abs(n), 1.0);
                double f = Math.min(Math.max((t - SPUR_START) / SPUR_FADE, 0.0), 1.0);
                bump *= 1.0 + m.spurs() * ridge * f * f * (3.0 - 2.0 * f);
            }
            // Lesser summits round the main one (the user liked the original's, which were Tectonic
            // hills riding the dome until the seam's flattening removed them).
            for (double[] c : m.comps()) {
                double cx = x - c[0], cz = z - c[1];
                double tc = Math.sqrt(cx * cx + cz * cz) / (companions.width() * m.radius());
                if (tc < 1.0) {
                    bump += c[2] * Math.pow(1.0 - tc, m.summitPower());
                }
            }
            if (texture != 0.0 && detail != null) {
                double n = detail.getValue(x * TEXTURE_SCALE, 9001.0 + k, z * TEXTURE_SCALE);
                bump += texture * n * Math.sin(Math.PI * t);
            }
            if (grain != 0.0 && detail != null) {
                // Finer bumps (~64 blocks): on the dome's smooth flanks each block step still ran
                // evenly for hundreds of blocks and read as topo-map lines (the user, 2026-10-24).
                double n = detail.getValue(x * GRAIN_SCALE, 12007.0 + k, z * GRAIN_SCALE);
                bump += grain * n * Math.sin(Math.PI * t);
            }
            sum += m.height() * bump;
        }
        return sum;
    }

    /** The range-mode outputs (previewer: _giant_peaks' outputs other than height and mask). */
    private double rangeOutput(double x, double z) {
        GiantRange.Placed[] where = placed;
        if (where == null) {
            return out == TARGET ? 10.0 : 0.0;
        }
        NoiseHolder detail = detailNoise.orElse(null);
        double gr = mode.gainRadius();
        double v = 0.0;
        for (int k = 0; k < where.length; k++) {
            GiantRange.Placed p = where[k];
            double dx = x - p.x(), dz = z - p.z();
            double d2 = dx * dx + dz * dz;
            boolean inside = d2 < gr * gr;
            switch (out) {
                case GAIN -> {
                    // The share of the range's own relief the giant adds, rising toward the summit
                    // (the user, 2026-11-27: the giant should be part of its range).
                    double t = Math.sqrt(d2) / gr;
                    v = Math.max(v, Math.pow(Math.max(0.0, 1.0 - t), mode.gainPower()));
                }
                case RING -> {
                    // Where the sisters stand: none within ~450 blocks, full ~800-2,500, gone by 3,500.
                    double d = Math.sqrt(d2);
                    double up = Math.min(Math.max((d - 450.0) / 350.0, 0.0), 1.0);
                    double down = Math.min(Math.max((3500.0 - d) / 1000.0, 0.0), 1.0);
                    v = Math.max(v, up * up * (3 - 2 * up) * down * down * (3 - 2 * down));
                }
                case LEVEL -> {
                    if (inside) v = p.level();
                }
                case DEFICIT -> {
                    if (inside) v = p.deficit();
                }
                case RAISE, RAISE_SHAPE -> {
                    // The raised range (the user chose "raise its own range", 2026-11-28): a ridge
                    // raise_length long through the summit, along the bearing with the most high
                    // ground, lifted by the shortfall; RAISE_SHAPE is its 0-1 footprint.
                    if (p.deficit() <= 0.0) continue;
                    double half = 0.5 * mode.raiseLength();
                    double w = mode.raiseWidth();
                    // Past this the ridge is zero whatever the wander noise does (|wander| <= ~600).
                    double reachR = half + w + 700.0;
                    if (d2 >= reachR * reachR) continue;
                    double axis = GiantRange.axis(world(), k, p, mode);
                    double u = dx * Math.cos(axis) + dz * Math.sin(axis);
                    double c = -dx * Math.sin(axis) + dz * Math.cos(axis);
                    if (detail != null) {
                        // The ridge line wanders, so it isn't a ruler-straight wedge.
                        c += 600.0 * detail.getValue(x * 0.25, 7307.0 + k, z * 0.25);
                    }
                    double tv = Math.min(Math.abs(c) / w, 1.0);
                    double across = 1.0 - tv * tv * (3 - 2 * tv);
                    double tu = Math.min(Math.abs(u) / half, 1.0);
                    double along = 1.0 - tu * tu * (3 - 2 * tu);
                    if (detail != null) {
                        double n = detail.getValue(x * TEXTURE_SCALE, 7411.0 + k, z * TEXTURE_SCALE);
                        across *= 0.75 + 0.5 * (1.0 - Math.min(Math.abs(n) * 2.0, 1.0));
                    }
                    v += (out == RAISE_SHAPE ? 1.0 : p.deficit()) * across * along;
                }
                default -> {
                    // TARGET, DROP, SISTERS. Outside every giant's reach the target is far
                    // overhead, so a cap at it changes nothing there.
                    if (!inside) {
                        if (out == TARGET && v == 0.0) v = 10.0;
                        continue;
                    }
                    double tgt, drop;
                    if (mode.solve().isPresent()) {
                        double[] s = GiantRange.solution(world(), k, p, mode.solve().get());
                        tgt = s[0];
                        drop = s[1];
                    } else {
                        // The summit as a rise over its own range (the user's 8-seed review).
                        tgt = Math.min(mode.targetCap(), p.level() + mode.rise());
                        drop = GiantRange.NO_SISTERS;
                    }
                    v = out == TARGET ? tgt : out == DROP ? drop : (drop >= 0.0 ? 1.0 : 0.0);
                }
            }
        }
        return v;
    }

    /** Solves every giant this node reaches, the spawn giant first (GiantRange.presolve). */
    void presolve() {
        if (mode.solve().isEmpty()) return;
        peaks();
        GiantRange.Placed[] where = placed;
        if (where == null) return;
        // The spawn giant first (the spawn area needs it), then the rest side by side: one after
        // another they took 94 s on the rig, and the world plan waits on all of them.
        GiantRange.solution(world(), 0, where[0], mode.solve().get());
        java.util.concurrent.CompletableFuture<?>[] rest = new java.util.concurrent.CompletableFuture<?>[where.length - 1];
        for (int k = 1; k < where.length; k++) {
            int kk = k;
            rest[k - 1] = java.util.concurrent.CompletableFuture.runAsync(
                    () -> GiantRange.solution(world(), kk, where[kk], mode.solve().get()),
                    r -> {
                        Thread t = new Thread(r, "Terra Continental giant presolve " + kk);
                        t.setDaemon(true);
                        t.start();
                    });
        }
        java.util.concurrent.CompletableFuture.allOf(rest).join();
    }

    boolean solves() {
        return mode.solve().isPresent();
    }

    @Override
    public void fillArray(double[] array, ContextProvider contextProvider) {
        contextProvider.fillAllDirectly(array, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new GiantPeaksFunction(
                new Source(visitor.visitNoise(jitterNoise), continents.map(df -> df.mapAll(visitor)),
                        placedKnown() ? mode : mode.map(visitor)),
                height, radius, reach, output,
                maskReach, warp, spurs, texture, grain, summit, summitWidth, summitPower, companions, variety,
                detailNoise.map(visitor::visitNoise)));
    }

    @Override
    public double minValue() {
        if (mask || out == GAIN || out == RING || out == SISTERS || out == RAISE || out == RAISE_SHAPE) {
            return 0.0;
        }
        if (out != HEIGHT) {
            return -1000.0;
        }
        double n = detailNoise.map(NoiseHolder::maxValue).orElse(0.0);
        return -2.0 * Math.abs(height) * (Math.abs(texture) + Math.abs(grain)) * n;
    }

    @Override
    public double maxValue() {
        if (mask || out == GAIN || out == RING || out == SISTERS) {
            return 1.0;
        }
        if (out != HEIGHT) {
            // Wide on purpose: vanilla's min/max shortcuts must never fire on these.
            return 1000.0;
        }
        double n = detailNoise.map(NoiseHolder::maxValue).orElse(0.0);
        // Two massifs can overlap only on touching continents.
        return 2.0 * height * (1.0 + Math.abs(companions.height()) + 1.4 * Math.abs(spurs) + (Math.abs(texture) + Math.abs(grain)) * n);
    }

    /**
     * Lesser summits round the main one: {@code companions} of them, the tallest's tip at
     * {@code companion_height} of the main summit and the rest a little lower,
     * {@code companion_width} of the massif's radius wide, {@code companion_dist} of it out. A
     * separate record because a codec group holds at most 16 fields.
     */
    /** The jitter noise, where to find the continent layout, and range mode; nested so the codec stays at 16 fields. */
    public record Source(NoiseHolder jitterNoise, Optional<DensityFunction> continents, GiantRange.Mode mode) {
        static final MapCodec<Source> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                NoiseHolder.CODEC.fieldOf("jitter_noise").forGetter(Source::jitterNoise),
                DensityFunction.HOLDER_HELPER_CODEC.optionalFieldOf("continents").forGetter(Source::continents),
                GiantRange.Mode.CODEC.forGetter(Source::mode)
        ).apply(instance, Source::new));
    }

    private static RadialFalloffFunction findFalloff(DensityFunction df) {
        RadialFalloffFunction[] found = new RadialFalloffFunction[1];
        df.mapAll(new Visitor() {
            @Override
            public DensityFunction apply(DensityFunction f) {
                if (found[0] == null && f instanceof RadialFalloffFunction r) found[0] = r;
                return f;
            }
        });
        return found[0];
    }

    public record Companions(int count, double height, double width, double dist) {
        static final MapCodec<Companions> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.INT.optionalFieldOf("companions", 0).forGetter(Companions::count),
                Codec.DOUBLE.optionalFieldOf("companion_height", 0.85).forGetter(Companions::height),
                Codec.DOUBLE.optionalFieldOf("companion_width", 0.12).forGetter(Companions::width),
                Codec.DOUBLE.optionalFieldOf("companion_dist", 0.22).forGetter(Companions::dist)
        ).apply(instance, Companions::new));
    }

    @Override
    public KeyDispatchDataCodec<GiantPeaksFunction> codec() {
        return KeyDispatchDataCodec.of(CODEC);
    }
}
