package com.terracontinental.density;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.terracontinental.config.TerraConfig;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Wraps any DensityFunction. Radial continent shaping with multiple deterministic
 * continent centers:
 *
 *   1. central_land_bump (smoothstep falloff): added to the upstream noise
 *      within central_land_radius. Guarantees a continent-scale landmass at
 *      the origin so the player spawns on land regardless of seed.
 *
 *   2. additional_continents: extra land bumps at seed-derived positions,
 *      distributed by angle around the origin within continent_spread blocks.
 *      Positions are derived from jitter_noise, which the game seeds from the
 *      world seed when the noise router is wired (mapAll/visitNoise) — density
 *      functions never see the raw seed directly.
 *
 *   3. ocean_bias (constant): subtracted from upstream noise at every point.
 *      Baseline spacing between continents.
 *
 *   4. radial_bias_strength × (distance / core_radius): linear additional
 *      bias growing with distance — density gradient from center to edge.
 *
 *   5. Sigmoid soft border centered at border_radius: final lerp to
 *      ocean_floor_value.
 *
 * All numeric parameters are optional in JSON. A missing field falls back to
 * the TOML config value (resolved at decode time, after configs have loaded),
 * then to a compiled-in default. The codec itself never touches the config
 * while the class is being constructed — orElseGet(config) here previously
 * caused ExceptionInInitializerError because Mojang's codec builder evaluates
 * defaults during class construction, before NeoForge configs load.
 *
 * Registered as: terra_continental:radial_falloff
 */
public final class RadialFalloffFunction implements DensityFunction {

    // Compiled-in fallbacks, used only when neither JSON nor config supplies a value.
    private static final double DEFAULT_CORE_RADIUS = 50000.0;
    private static final double DEFAULT_BORDER_RADIUS = 150000.0;
    private static final double DEFAULT_TRANSITION_WIDTH = 30000.0;
    private static final double DEFAULT_OCEAN_FLOOR_VALUE = -1.0;
    private static final double DEFAULT_OCEAN_BIAS = 0.35;
    private static final double DEFAULT_RADIAL_BIAS_STRENGTH = 0.15;
    private static final double DEFAULT_CENTRAL_LAND_BUMP = 1.2;
    private static final double DEFAULT_CENTRAL_LAND_RADIUS = 10000.0;
    private static final int DEFAULT_ADDITIONAL_CONTINENTS = 5;
    private static final double DEFAULT_CONTINENT_SPREAD = 40000.0;
    private static final double DEFAULT_CONTINENT_RADIUS = 9000.0;
    private static final double DEFAULT_CONTINENT_BUMP = 1.1;

    private final DensityFunction wrapped;
    private final NoiseHolder jitterNoise;
    private final double coreRadius;
    private final double borderRadius;
    private final double transitionWidth;
    private final double oceanFloorValue;
    private final double oceanBias;
    private final double radialBiasStrength;
    private final double centralLandBump;
    private final double centralLandRadius;
    private final int additionalContinents;
    private final double continentSpread;
    private final double continentRadius;
    private final double continentBump;

    // [i] = {x, z, radius}. Built lazily on first compute so the jitter noise is
    // already seeded; the benign race re-computes identical values.
    private volatile double[][] centers;

    public RadialFalloffFunction(DensityFunction wrapped, NoiseHolder jitterNoise,
                                 double coreRadius, double borderRadius, double transitionWidth,
                                 double oceanFloorValue, double oceanBias, double radialBiasStrength,
                                 double centralLandBump, double centralLandRadius,
                                 int additionalContinents, double continentSpread,
                                 double continentRadius, double continentBump) {
        this.wrapped = wrapped;
        this.jitterNoise = jitterNoise;
        this.coreRadius = coreRadius;
        this.borderRadius = borderRadius;
        this.transitionWidth = transitionWidth;
        this.oceanFloorValue = oceanFloorValue;
        this.oceanBias = oceanBias;
        this.radialBiasStrength = radialBiasStrength;
        this.centralLandBump = centralLandBump;
        this.centralLandRadius = centralLandRadius;
        this.additionalContinents = additionalContinents;
        this.continentSpread = continentSpread;
        this.continentRadius = continentRadius;
        this.continentBump = continentBump;
    }

    public static final MapCodec<RadialFalloffFunction> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    DensityFunction.HOLDER_HELPER_CODEC.fieldOf("wrapped")
                            .forGetter(f -> f.wrapped),
                    NoiseHolder.CODEC.fieldOf("jitter_noise")
                            .forGetter(f -> f.jitterNoise),
                    Codec.DOUBLE.optionalFieldOf("core_radius")
                            .forGetter(f -> Optional.of(f.coreRadius)),
                    Codec.DOUBLE.optionalFieldOf("border_radius")
                            .forGetter(f -> Optional.of(f.borderRadius)),
                    Codec.DOUBLE.optionalFieldOf("transition_width")
                            .forGetter(f -> Optional.of(f.transitionWidth)),
                    Codec.DOUBLE.optionalFieldOf("ocean_floor_value")
                            .forGetter(f -> Optional.of(f.oceanFloorValue)),
                    Codec.DOUBLE.optionalFieldOf("ocean_bias")
                            .forGetter(f -> Optional.of(f.oceanBias)),
                    Codec.DOUBLE.optionalFieldOf("radial_bias_strength")
                            .forGetter(f -> Optional.of(f.radialBiasStrength)),
                    Codec.DOUBLE.optionalFieldOf("central_land_bump")
                            .forGetter(f -> Optional.of(f.centralLandBump)),
                    Codec.DOUBLE.optionalFieldOf("central_land_radius")
                            .forGetter(f -> Optional.of(f.centralLandRadius)),
                    Codec.INT.optionalFieldOf("additional_continents")
                            .forGetter(f -> Optional.of(f.additionalContinents)),
                    Codec.DOUBLE.optionalFieldOf("continent_spread")
                            .forGetter(f -> Optional.of(f.continentSpread)),
                    Codec.DOUBLE.optionalFieldOf("continent_radius")
                            .forGetter(f -> Optional.of(f.continentRadius)),
                    Codec.DOUBLE.optionalFieldOf("continent_bump")
                            .forGetter(f -> Optional.of(f.continentBump))
            ).apply(instance, RadialFalloffFunction::fromJson)
    );

    /**
     * Decode-time resolution: JSON value > TOML config (if loaded) > compiled default.
     * Runs when the datapack is parsed at world load, long after configs load.
     */
    private static RadialFalloffFunction fromJson(DensityFunction wrapped, NoiseHolder jitterNoise,
                                                  Optional<Double> coreRadius, Optional<Double> borderRadius,
                                                  Optional<Double> transitionWidth, Optional<Double> oceanFloorValue,
                                                  Optional<Double> oceanBias, Optional<Double> radialBiasStrength,
                                                  Optional<Double> centralLandBump, Optional<Double> centralLandRadius,
                                                  Optional<Integer> additionalContinents, Optional<Double> continentSpread,
                                                  Optional<Double> continentRadius, Optional<Double> continentBump) {
        return new RadialFalloffFunction(
                wrapped, jitterNoise,
                resolve(coreRadius, () -> (double) TerraConfig.CORE_RADIUS.get(), DEFAULT_CORE_RADIUS),
                resolve(borderRadius, () -> (double) TerraConfig.BORDER_RADIUS.get(), DEFAULT_BORDER_RADIUS),
                resolve(transitionWidth, () -> (double) TerraConfig.TRANSITION_WIDTH.get(), DEFAULT_TRANSITION_WIDTH),
                resolve(oceanFloorValue, TerraConfig.OCEAN_FLOOR_VALUE::get, DEFAULT_OCEAN_FLOOR_VALUE),
                resolve(oceanBias, TerraConfig.OCEAN_BIAS::get, DEFAULT_OCEAN_BIAS),
                resolve(radialBiasStrength, TerraConfig.RADIAL_BIAS_STRENGTH::get, DEFAULT_RADIAL_BIAS_STRENGTH),
                resolve(centralLandBump, TerraConfig.CENTRAL_LAND_BUMP::get, DEFAULT_CENTRAL_LAND_BUMP),
                resolve(centralLandRadius, () -> (double) TerraConfig.CENTRAL_LAND_RADIUS.get(), DEFAULT_CENTRAL_LAND_RADIUS),
                additionalContinents.orElseGet(() -> configOrDefault(TerraConfig.ADDITIONAL_CONTINENTS::get, DEFAULT_ADDITIONAL_CONTINENTS)),
                resolve(continentSpread, () -> (double) TerraConfig.CONTINENT_SPREAD.get(), DEFAULT_CONTINENT_SPREAD),
                resolve(continentRadius, () -> (double) TerraConfig.CONTINENT_RADIUS.get(), DEFAULT_CONTINENT_RADIUS),
                resolve(continentBump, TerraConfig.CONTINENT_BUMP::get, DEFAULT_CONTINENT_BUMP)
        );
    }

    private static double resolve(Optional<Double> json, Supplier<Double> config, double fallback) {
        if (json.isPresent()) return json.get();
        return configOrDefault(config, fallback);
    }

    private static <T> T configOrDefault(Supplier<T> config, T fallback) {
        try {
            if (TerraConfig.SPEC.isLoaded()) {
                return config.get();
            }
        } catch (Throwable ignored) {
            // datagen / early-load contexts without a config
        }
        return fallback;
    }

    @Override
    public double compute(FunctionContext context) {
        double upstreamValue = wrapped.compute(context);
        double x = context.blockX();
        double z = context.blockZ();
        double distance = Math.sqrt(x * x + z * z);

        // 1. Central-continent bump (smoothstep falloff within central_land_radius)
        double bumped = upstreamValue + centralLandBump * smoothstepFalloff(distance / centralLandRadius);

        // 2. Additional seed-placed continents
        if (additionalContinents > 0) {
            for (double[] center : centers()) {
                double dx = x - center[0];
                double dz = z - center[1];
                double radius = center[2];
                double distSq = dx * dx + dz * dz;
                if (distSq < radius * radius) {
                    bumped += continentBump * smoothstepFalloff(Math.sqrt(distSq) / radius);
                }
            }
        }

        // 3 + 4. Bias = baseline + linear radial gradient
        double effectiveBias = oceanBias + radialBiasStrength * (distance / coreRadius);
        double biased = bumped - effectiveBias;

        // 5. Soft border sigmoid centered at border_radius
        double t = (distance - borderRadius) / transitionWidth;
        double blend = 1.0 / (1.0 + Math.exp(t));
        return blend * biased + (1.0 - blend) * oceanFloorValue;
    }

    private static double smoothstepFalloff(double t) {
        if (t >= 1.0) return 0.0;
        return 1.0 - t * t * (3.0 - 2.0 * t);
    }

    private double[][] centers() {
        double[][] result = centers;
        if (result == null) {
            result = buildCenters();
            centers = result;
        }
        return result;
    }

    /**
     * Continent centers: evenly distributed by angle with per-seed jitter in
     * angle, distance and radius, all sampled from the seeded jitter noise at
     * fixed coordinates. Same seed = same layout; different seed = different.
     */
    private double[][] buildCenters() {
        int n = additionalContinents;
        double[][] result = new double[n][3];
        for (int i = 0; i < n; i++) {
            double angleJitter = jitterSample(i, 0);
            double distJitter = jitterSample(i, 1);
            double radiusJitter = jitterSample(i, 2);

            double angle = (Math.PI * 2.0 * i) / n + angleJitter * (Math.PI / n) * 0.8;
            double dist = continentSpread * (0.40 + 0.275 * (distJitter + 1.0)); // 40%–95% of spread
            double radius = continentRadius * (1.0 + 0.30 * radiusJitter);       // ±30%

            result[i][0] = Math.cos(angle) * dist;
            result[i][1] = Math.sin(angle) * dist;
            result[i][2] = radius;
        }
        return result;
    }

    private double jitterSample(int index, int channel) {
        double raw = jitterNoise.getValue(index * 9973.0 + channel * 4451.0 + 31.0, 0.0,
                                          index * -6101.0 + channel * 2887.0 - 97.0);
        // widen typical normal-noise amplitude toward full [-1, 1] coverage
        return Math.max(-1.0, Math.min(1.0, raw * 2.0));
    }

    @Override
    public void fillArray(double[] array, ContextProvider contextProvider) {
        contextProvider.fillAllDirectly(array, this);
    }

    @Override
    public double minValue() {
        double maxBias = oceanBias + Math.max(radialBiasStrength, 0)
                * ((borderRadius + 10.0 * transitionWidth) / coreRadius);
        return Math.min(wrapped.minValue() - maxBias, oceanFloorValue);
    }

    @Override
    public double maxValue() {
        // conservative: central bump plus two possibly-overlapping continent bumps
        double maxBump = Math.max(centralLandBump, 0) + 2.0 * Math.max(continentBump, 0);
        return Math.max(wrapped.maxValue() + maxBump - oceanBias, oceanFloorValue);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new RadialFalloffFunction(
                wrapped.mapAll(visitor), visitor.visitNoise(jitterNoise),
                coreRadius, borderRadius, transitionWidth,
                oceanFloorValue, oceanBias, radialBiasStrength,
                centralLandBump, centralLandRadius,
                additionalContinents, continentSpread, continentRadius, continentBump
        ));
    }

    @Override
    public KeyDispatchDataCodec<RadialFalloffFunction> codec() {
        return KeyDispatchDataCodec.of(CODEC);
    }
}
