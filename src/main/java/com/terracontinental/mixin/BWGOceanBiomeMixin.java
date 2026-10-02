package com.terracontinental.mixin;

import com.mojang.datafixers.util.Pair;
import com.terracontinental.config.TerraConfig;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Makes BWG's oceanic "formation" biomes (lush_stacks, dead_sea) rare instead of
 * the default ocean floor.
 *
 * <p>BWG registers these two biomes with a full-range humidity box spanning the
 * entire ocean continentalness band, split only by temperature — so in two of its
 * three overworld regions they blanket every temperate/warm ocean tile (~12% of all
 * ocean, reading as constant scatter on a map). They are striking formations in game
 * but lose their significance when that common.
 *
 * <p>We wrap the {@code Consumer} that BWG's {@code addBiomes} feeds its
 * (ParameterPoint, biome) pairs into. For the two target biomes we keep only a narrow
 * central humidity slice and emit {@code terrablender:deferred_placeholder} for the
 * rest of the humidity range — TerraBlender resolves those placeholders to the vanilla
 * ocean biome at that climate point. MultiNoise uses <em>nearest-box</em> selection,
 * so simply shrinking the box would not help; the deferred fill is what actually frees
 * the vacated humidity range back to vanilla ocean.
 *
 * <p>The kept half-width is {@link TerraConfig#OCEAN_FORMATION_HUMIDITY_BAND} (0..1):
 * 0.08 ≈ 3% of ocean (rare), 0.0 removes them entirely, 1.0 is a no-op (vanilla BWG).
 *
 * <p>String-targeted + {@code remap = false} so this compiles and loads without BWG or
 * TerraBlender on the classpath; the surrounding mixin config is {@code required:false}
 * so absence of BWG is a clean no-op.
 */
@Mixin(targets = "net.potionstudios.biomeswevegone.world.level.levelgen.biome.BWGTerraBlenderRegion", remap = false)
public class BWGOceanBiomeMixin {

    private static final Set<String> TERRA_CONTINENTAL$OCEAN_FORMATIONS = Set.of(
            "biomeswevegone:lush_stacks",
            "biomeswevegone:dead_sea"
    );

    private static final ResourceKey<Biome> TERRA_CONTINENTAL$DEFERRED =
            ResourceKey.create(Registries.BIOME,
                    ResourceLocation.fromNamespaceAndPath("terrablender", "deferred_placeholder"));

    @ModifyVariable(method = "addBiomes", at = @At("HEAD"), argsOnly = true, remap = false)
    private Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> terraContinental$rarifyOceanFormations(
            Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> original) {

        long band = terraContinental$bandQuantized();
        if (band < 0) {
            return original; // disabled / config unavailable / full-range -> no-op
        }

        return pair -> {
            ResourceKey<Biome> biome = pair.getSecond();
            if (!TERRA_CONTINENTAL$OCEAN_FORMATIONS.contains(biome.location().toString())) {
                original.accept(pair);
                return;
            }

            Climate.ParameterPoint pt = pair.getFirst();
            Climate.Parameter h = pt.humidity();
            long hmin = h.min();
            long hmax = h.max();

            long centerLo = Math.max(hmin, -band);
            long centerHi = Math.min(hmax, band);

            // Narrow central slice keeps the formation biome.
            if (centerLo < centerHi) {
                original.accept(Pair.of(terraContinental$withHumidity(pt, centerLo, centerHi), biome));
            }
            // Vacated humidity on either side defers to the vanilla ocean biome.
            if (hmin < centerLo) {
                original.accept(Pair.of(
                        terraContinental$withHumidity(pt, hmin, Math.min(centerLo, hmax)),
                        TERRA_CONTINENTAL$DEFERRED));
            }
            if (centerHi < hmax) {
                original.accept(Pair.of(
                        terraContinental$withHumidity(pt, Math.max(centerHi, hmin), hmax),
                        TERRA_CONTINENTAL$DEFERRED));
            }
        };
    }

    private static Climate.ParameterPoint terraContinental$withHumidity(
            Climate.ParameterPoint pt, long lo, long hi) {
        return new Climate.ParameterPoint(
                pt.temperature(),
                new Climate.Parameter(lo, hi),
                pt.continentalness(),
                pt.erosion(),
                pt.depth(),
                pt.weirdness(),
                pt.offset());
    }

    /**
     * @return quantized humidity half-band, or a negative sentinel meaning "no-op"
     *         (config unavailable, or band == full range).
     */
    private static long terraContinental$bandQuantized() {
        try {
            if (TerraConfig.SPEC.isLoaded()) {
                double v = TerraConfig.OCEAN_FORMATION_HUMIDITY_BAND.get();
                if (v >= 1.0) {
                    return -1L; // full range -> identical to vanilla BWG, skip wrapping
                }
                // Climate humidity is quantized as (long)(value * 10000).
                return (long) (v * 10000.0);
            }
        } catch (Throwable ignored) {
            // config not ready (very early call) -> fall through to no-op
        }
        return -1L;
    }
}
