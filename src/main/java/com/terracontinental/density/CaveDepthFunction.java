package com.terracontinental.density;

import com.mojang.serialization.MapCodec;
import com.terracontinental.config.TerraConfig;
import com.terracontinental.terrain.TerrainBase;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * A position-independent constant: the factor that rescales the terrain-following
 * depth into vanilla's biome-depth semantics. Registered as
 * {@code terra_continental:cave_depth_scale}.
 *
 * <p>Vanilla selects underground biomes by NEAREST box on a depth axis where
 * {@code 0.2..0.9} is the underground-biome window (dripstone_caves, lush_caves), surface
 * biomes sit at both {@code point(0.0)} and {@code point(1.0)}, and deep_dark at
 * {@code point(1.1)}. Read raw, the terrain's depth runs far past that range in this deep world,
 * so whole columns sit nearer deep_dark's 1.1 than the surface biomes' 1.0 and the 0.2..0.9
 * window is unreachable. Rescaling linearly (vanilla's depth is linear in Y too) restores the
 * bands; {@code noise_router/depth.json} then caps the result at 0.8 above y=32 and 1.6 below.
 *
 * <p>Until 2026-11-10 that file multiplied by a literal 0.42 and nothing referenced this
 * function, so the {@code [caves]} settings did nothing; the floor default (1.88671875) is the
 * value that gives exactly 0.42, so wiring it in changed no world.
 *
 * <p>The factor is derived from the world's vertical extent ({@link TerrainBase#MIN_Y}, the
 * floor in the terrain pack; it was read from {@code config/tectonic.json} before Terra
 * Continental absorbed Tectonic), so a new floor rescales the banding without hand-edited
 * spline points:
 *
 * <pre>
 *   scale = floorValue * 128 / (referenceY - minY)
 * </pre>
 *
 * (128 because the depth gradient advances one unit per 128 blocks.) Knobs live in
 * {@code terra_continental-common.toml} under {@code [caves]}.
 *
 * <p>IMPORTANT — why this is a bare constant rather than a function that wraps the depth
 * input: wrapping {@code terra_continental:depth} and multiplying inside cost roughly 4x worldgen
 * throughput (70 cps -> 17 cps measured). {@code terra_continental:depth} is a {@code cache_once}
 * over an expensive terrain-spline chain that the terrain functions also read; wrapping it
 * evaluates that chain a second time instead of sharing the cached result. Emitting only
 * the scalar and letting vanilla's {@code minecraft:mul} do the multiply keeps
 * {@code terra_continental:depth} referenced directly, so its cache is shared as before.
 */
public class CaveDepthFunction implements DensityFunction.SimpleFunction {

    public static final CaveDepthFunction INSTANCE = new CaveDepthFunction();
    public static final MapCodec<CaveDepthFunction> CODEC = MapCodec.unit(INSTANCE);

    /** Tectonic's depth gradient advances 1 unit per 128 blocks (y_clamped_gradient -2048..2048, 17..-15). */
    private static final double BLOCKS_PER_DEPTH_UNIT = 128.0;

    private static volatile double cachedScale = Double.NaN;

    private static double scale() {
        double s = cachedScale;
        if (!Double.isNaN(s)) {
            return s;
        }
        double floorValue = 1.88671875;
        int referenceY = 63;
        try {
            floorValue = TerraConfig.CAVE_DEPTH_FLOOR_VALUE.get();
            referenceY = TerraConfig.CAVE_DEPTH_REFERENCE_Y.get();
        } catch (IllegalStateException notLoadedYet) {
            // Defaults above (scale 0.42); configs load long before worldgen in practice.
        }
        double span = referenceY - TerrainBase.MIN_Y;
        s = (span <= 1.0) ? 1.0 : floorValue * BLOCKS_PER_DEPTH_UNIT / span;
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
