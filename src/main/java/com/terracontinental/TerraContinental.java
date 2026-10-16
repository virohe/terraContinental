package com.terracontinental;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import com.terracontinental.commands.BiomeMapCommand;
import com.terracontinental.commands.DumpBiomesCommand;
import com.terracontinental.config.TerraConfig;
import com.terracontinental.config.TerraStructureConfig;
import com.terracontinental.density.CaveDepthFunction;
import com.terracontinental.density.SpawnClimateMask;
import com.terracontinental.density.RadialFalloffFunction;
import com.terracontinental.density.ScaledWeirdnessFunction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

@Mod(TerraContinental.MOD_ID)
public class TerraContinental {

    public static final String MOD_ID = "terra_continental";
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<MapCodec<? extends DensityFunction>> DENSITY_FUNCTION_TYPES =
            DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, MOD_ID);

    public static final Supplier<MapCodec<RadialFalloffFunction>> RADIAL_FALLOFF =
            DENSITY_FUNCTION_TYPES.register("radial_falloff", () -> RadialFalloffFunction.CODEC);

    public static final Supplier<MapCodec<ScaledWeirdnessFunction>> SCALED_WEIRDNESS =
            DENSITY_FUNCTION_TYPES.register("scaled_weirdness", () -> ScaledWeirdnessFunction.CODEC);

    public static final Supplier<MapCodec<CaveDepthFunction>> CAVE_DEPTH =
            DENSITY_FUNCTION_TYPES.register("cave_depth_scale", () -> CaveDepthFunction.CODEC);

    public static final Supplier<MapCodec<SpawnClimateMask>> SPAWN_CLIMATE_MASK =
            DENSITY_FUNCTION_TYPES.register("spawn_climate_mask", () -> SpawnClimateMask.CODEC);

    public TerraContinental(IEventBus modEventBus, ModContainer modContainer) {
        DENSITY_FUNCTION_TYPES.register(modEventBus);
        adoptLegacyConfig("terra_incognita-common.toml", "terra_continental-common.toml");
        modContainer.registerConfig(ModConfig.Type.COMMON, TerraConfig.SPEC, "terra_continental-common.toml");
        modContainer.registerConfig(ModConfig.Type.SERVER, TerraStructureConfig.SPEC, "terra_continental-structures.toml");
        modEventBus.addListener(TerraContinental::addPackFinders);
        NeoForge.EVENT_BUS.addListener(DumpBiomesCommand::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(BiomeMapCommand::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(com.terracontinental.commands.VillagesCommand::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(com.terracontinental.commands.VillagesCommand::onServerTick);
    }

    /**
     * This mod was called terra_incognita until 0.8.0 (renamed so it no longer shares a name
     * with the modpack). Without this, an instance upgrading from 0.7.x would silently fall back
     * to default config values and lose its tuning. Copies, never moves: the old file stays as
     * a backup, and once the new file exists it wins. The structures config is per-world and
     * its one setting is inert (TI-102), so it is not carried over.
     */
    private static void adoptLegacyConfig(String legacyName, String name) {
        Path dir = FMLPaths.CONFIGDIR.get();
        Path legacy = dir.resolve(legacyName);
        Path current = dir.resolve(name);
        if (Files.exists(current) || !Files.exists(legacy)) return;
        try {
            Files.copy(legacy, current);
            LOGGER.info("Carried {} over to {} (mod renamed from terra_incognita)", legacyName, name);
        } catch (IOException e) {
            LOGGER.warn("Could not carry {} over to {}; using defaults", legacyName, name, e);
        }
    }

    /**
     * Registers the Tectonic seam as an always-active BUILT-IN datapack.
     *
     * Plain mod data ({@code data/tectonic/...} in this jar) cannot override Tectonic's
     * worldgen: Tectonic ships its datapack as a separately registered built-in pack that
     * stacks ABOVE all {@code mod/*} packs. Registering our seam the same way -- and loading
     * after Tectonic (see the AFTER ordering in neoforge.mods.toml) -- stacks ours above
     * Tectonic's, so {@code tectonic:noise/raw_continents} resolves to our radial field.
     * Always-active, so new worlds need no manual datapack selection. Harmless without
     * Tectonic (nothing references the overridden function).
     */
    private static void addPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() == PackType.SERVER_DATA) {
            event.addPackFinders(
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "resourcepacks/tectonic_seam"),
                    PackType.SERVER_DATA,
                    Component.literal("Terra Continental x Tectonic seam"),
                    PackSource.BUILT_IN,
                    true,
                    Pack.Position.TOP);
            // Raised tree canopies (horse clearance). This lived only as the world datapack
            // "terra_forests" in one Goofball Haven save, so every other world -- including
            // every new test world -- silently generated vanilla-height trees. Built in and
            // always active now, stacked above the biome mods' own data so its overrides of
            // minecraft: and biomeswevegone: tree features win. Source of truth is still
            // build-bigtrees-datapack.ps1; re-copy its output here when retuning.
            event.addPackFinders(
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "resourcepacks/big_trees"),
                    PackType.SERVER_DATA,
                    Component.literal("Terra Continental big trees"),
                    PackSource.BUILT_IN,
                    true,
                    Pack.Position.TOP);
            // Sparse villages: both village grids (vanilla's and BWG's) widened 3.8x, which
            // measured ~10 villages on the Goofball Haven spawn continent. This too existed only
            // as a world datapack ("terra_distant_towns") in that one save, so every other
            // world got vanilla density - denser, in fact, since this jar's own
            // structure_set/villages.json was 32/8 for the (dead) spacing mixin. Source of truth
            // is build-village-spacing-datapack.ps1; re-copy its output here when retuning.
            event.addPackFinders(
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "resourcepacks/distant_towns"),
                    PackType.SERVER_DATA,
                    Component.literal("Terra Continental distant towns"),
                    PackSource.BUILT_IN,
                    true,
                    Pack.Position.TOP);
            // End biome variety. A world made with a non-BetterX world type keeps vanilla's
            // TheEndBiomeSource, which WorldWeaver patches to pick BetterEnd biomes from the
            // climate sampler's TEMPERATURE -- a constant 0 in vanilla's End router, so every
            // pick landed mid-list: all highlands/midlands glowing_grasslands, all small
            // islands flower_islets (world 67, 2026-09-29). This pack gives the End router a
            // real 2D temperature noise; nothing else in the End reads temperature, so terrain
            // is unchanged. Harmless on BetterX worlds (WoverEndBiomeSource ignores it).
            event.addPackFinders(
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "resourcepacks/end_biomes"),
                    PackType.SERVER_DATA,
                    Component.literal("Terra Continental End biomes"),
                    PackSource.BUILT_IN,
                    true,
                    Pack.Position.TOP);
        }
    }
}
