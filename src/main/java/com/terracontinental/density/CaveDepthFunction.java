package com.terracontinental.density;

import com.mojang.serialization.MapCodec;
import com.terracontinental.config.TerraConfig;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A position-independent constant: the factor that rescales Tectonic's terrain-following
 * depth into vanilla's biome-depth semantics. Registered as
 * {@code terra_continental:cave_depth_scale}.
 *
 * <p>Vanilla selects underground biomes by NEAREST box on a depth axis where
 * {@code 0.2..0.9} is the underground-biome window (dripstone_caves, lush_caves), surface
 * biomes sit at both {@code point(0.0)} and {@code point(1.0)}, and deep_dark at
 * {@code point(1.1)}. Tectonic instead hard-caps depth at 1.3 below y=32, which pins the
 * value flat for hundreds of blocks in a deepened world — nearer deep_dark's 1.1 than the
 * surface biomes' 1.0, so deep_dark wins whole columns and the 0.2..0.9 window is
 * unreachable. Rescaling linearly (vanilla's depth is linear in Y too) restores the bands.
 *
 * <p>The factor is derived from the world's ACTUAL vertical extent, reading Tectonic's own
 * {@code min_y} out of {@code config/tectonic.json}, so changing {@code min_y} rescales the
 * banding automatically instead of needing hand-edited spline points:
 *
 * <pre>
 *   scale = floorValue * 128 / (referenceY - minY)
 * </pre>
 *
 * (128 because Tectonic's depth gradient advances one unit per 128 blocks.) Knobs live in
 * {@code terra_continental-common.toml} under {@code [caves]}.
 *
 * <p>IMPORTANT — why this is a bare constant rather than a function that wraps the depth
 * input: wrapping {@code tectonic:depth} and multiplying inside cost roughly 4x worldgen
 * throughput (70 cps -> 17 cps measured). {@code tectonic:depth} is a {@code cache_once}
 * over an expensive terrain-spline chain that the terrain functions also read; wrapping it
 * evaluates that chain a second time instead of sharing the cached result. Emitting only
 * the scalar and letting vanilla's {@code minecraft:mul} do the multiply keeps
 * {@code tectonic:depth} referenced directly, so its cache is shared as before.
 */
public class CaveDepthFunction implements DensityFunction.SimpleFunction {

    public static final CaveDepthFunction INSTANCE = new CaveDepthFunction();
    public static final MapCodec<CaveDepthFunction> CODEC = MapCodec.unit(INSTANCE);

    /** Tectonic's depth gradient advances 1 unit per 128 blocks (y_clamped_gradient -2048..2048, 17..-15). */
    private static final double BLOCKS_PER_DEPTH_UNIT = 128.0;

    private static final Pattern MIN_Y = Pattern.compile("\"min_y\"\\s*:\\s*(-?\\d+)");

    private static volatile double cachedScale = Double.NaN;

    /**
     * Reads Tectonic's configured world floor. The file is JSON-with-comments, so it is
     * matched rather than parsed. Falls back to the configured value if Tectonic is absent.
     */
    private static int minY() {
        try {
            Path p = FMLPaths.CONFIGDIR.get().resolve("tectonic.json");
            if (Files.isReadable(p)) {
                Matcher m = MIN_Y.matcher(Files.readString(p));
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return TerraConfig.CAVE_DEPTH_FALLBACK_MIN_Y.get();
    }

    private static double scale() {
        double s = cachedScale;
        if (!Double.isNaN(s)) {
            return s;
        }
        double span = TerraConfig.CAVE_DEPTH_REFERENCE_Y.get() - minY();
        s = (span <= 1.0) ? 1.0 : TerraConfig.CAVE_DEPTH_FLOOR_VALUE.get() * BLOCKS_PER_DEPTH_UNIT / span;
        cachedScale = s;
        return s;
    }

    @Override
    public double compute(FunctionContext context) {
        return scale();
    }

    @Override
    public double minValue() {
        return 0.0;
    }

    @Override
    public double maxValue() {
        return 4.0;
    }

    @Override
    public KeyDispatchDataCodec<CaveDepthFunction> codec() {
        return KeyDispatchDataCodec.of(CODEC);
    }
}
