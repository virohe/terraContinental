package com.terracontinental.commands;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

/**
 * /continental biome_map &lt;radius&gt; &lt;step&gt; [seed [centerX centerZ]]
 *
 * Sweeps the live overworld generator over a grid and writes a PNG overview plus a
 * land/ocean + top-biomes report in chat.
 *
 * <p>Two properties make this the pack's accurate map oracle:
 * <ul>
 *   <li><b>Arbitrary seed.</b> A RandomState is built from any seed against the CURRENT
 *       noise router, so seeds can be scouted from inside one loaded world without
 *       creating a world per seed. Whatever the pack generates (Tectonic + the radial
 *       seam + BWG/TerraBlender + every config value) is what gets sampled — nothing is
 *       modelled or ported, so it cannot drift out of sync the way the Python simulator did.</li>
 *   <li><b>Surface sampling.</b> Biome selection uses <i>depth</i> as a climate axis, and
 *       depth is a function of y (~1 depth unit per 128 blocks). Sampling every column at a
 *       fixed y therefore returns the biome INSIDE a mountain rather than the one on top of
 *       it — badly wrong on Tectonic terrain reaching y=1264. So each column's real surface
 *       height is resolved first (clamped up to sea level, so ocean columns report the ocean
 *       biome) and the biome is sampled there.</li>
 * </ul>
 *
 * <p>Surface resolution costs a column solve per sample, so this is far slower than a plain
 * climate lookup. Raise {@code step} if a sweep drags.
 */
public class BiomeMapCommand {

    /** Column solves are expensive; keep a sweep to something that finishes in seconds-to-a-minute. */
    private static final int MAX_CELLS = 1_000_000;

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("continental")
                .requires(src -> src.hasPermission(2))
                .then(mapNode("biome_map", false))
                .then(mapNode("detail_map", true)));
    }

    /**
     * biome_map = climate only (fast: no terrain columns solved).
     * detail_map = climate + terrain height, for topography. Height needs a column solve per
     * cell, so it is far slower — intended for a tight radius at a fine step, e.g. reading a
     * valley's shape and where its rivers actually run.
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            mapNode(String name, boolean withHeight) {
        return Commands.literal(name)
            .then(Commands.argument("radius", IntegerArgumentType.integer(64, 4_000_000))
                .then(Commands.argument("step", IntegerArgumentType.integer(4, 100_000))
                    .executes(ctx -> run(ctx, null, 0, 0, withHeight))
                    .then(Commands.argument("seed", LongArgumentType.longArg())
                        .executes(ctx -> run(ctx, LongArgumentType.getLong(ctx, "seed"),
                                             0, 0, withHeight))
                        .then(Commands.argument("centerX", IntegerArgumentType.integer())
                            .then(Commands.argument("centerZ", IntegerArgumentType.integer())
                                .executes(ctx -> run(ctx,
                                    LongArgumentType.getLong(ctx, "seed"),
                                    IntegerArgumentType.getInteger(ctx, "centerX"),
                                    IntegerArgumentType.getInteger(ctx, "centerZ"),
                                    withHeight)))))));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Long seedArg,
                           int centerX, int centerZ, boolean withHeight) {
        CommandSourceStack source = ctx.getSource();
        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        int step = IntegerArgumentType.getInteger(ctx, "step");

        int n = (2 * radius) / step + 1;
        if ((long) n * n > MAX_CELLS) {
            source.sendFailure(Component.literal(
                "[continental] Grid too large: " + n + "x" + n + " cells (max " + MAX_CELLS
                    + "). Raise step or lower radius."));
            return 0;
        }

        ServerLevel overworld = source.getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) {
            source.sendFailure(Component.literal("[continental] Overworld not loaded"));
            return 0;
        }

        ChunkGenerator generator = overworld.getChunkSource().getGenerator();
        BiomeSource biomeSource = generator.getBiomeSource();

        // Guard against the silent world-type trap: the terrain applies to minecraft:overworld
        // and the TC world types' noise settings ONLY. World types like "BetterX - Large Biomes"
        // / "- Amplified" point the overworld at large_biomes / amplified instead, so the world
        // silently generates vanilla-style — land everywhere, no radial continents. Say so
        // loudly rather than letting a map get read as a real preview.
        if (generator instanceof NoiseBasedChunkGenerator ngc) {
            var key = ngc.generatorSettings().unwrapKey().orElse(null);
            if (key != null && !com.terracontinental.terrain.TerrainBase.OVERWORLD_SETTINGS.contains(key.location().toString())) {
                source.sendFailure(Component.literal(
                    "[continental] WARNING: this world uses '" + key.location()
                        + "' noise settings, not Terra Continental's. Its terrain applies only to"
                        + " minecraft:overworld and the TC world types, so this world is generating"
                        + " vanilla-style terrain with NO radial continents. Recreate it with a"
                        + " TC world type. Mapping anyway:"));
            }
        }

        if (!(generator instanceof NoiseBasedChunkGenerator noiseGen)) {
            source.sendFailure(Component.literal(
                "[continental] Overworld generator is not noise-based; cannot map it."));
            return 0;
        }

        // A RandomState is built per worker thread from this seed against the CURRENT noise
        // router, which both lets any seed be previewed without creating a world for it and
        // keeps threads off each other's density-function caches (the wrapper functions in a
        // Climate.Sampler hold mutable per-instance caches and must not be shared).
        NoiseGeneratorSettings settings = noiseGen.generatorSettings().value();
        HolderGetter<NormalNoise.NoiseParameters> noiseGetter =
            overworld.registryAccess().lookupOrThrow(Registries.NOISE);
        long seedUsed = seedArg != null ? seedArg.longValue() : overworld.getSeed();
        int seaLevel = generator.getSeaLevel();
        MinecraftServer server = source.getServer();

        source.sendSuccess(() -> Component.literal(
            "[continental] Sampling " + n + "x" + n + " (" + (n * n) + " columns) over "
                + (2 * radius) + " blocks, step " + step + ", seed " + seedUsed
                + ". Running in the background — the game stays playable; you'll get progress here."),
            false);

        ThreadLocal<RandomState> perThreadState =
            ThreadLocal.withInitial(() -> RandomState.create(settings, noiseGetter, seedUsed));

        int[] pixels = new int[n * n];
        // Parallel biome-ID grid + palette. Exported alongside the PNG so offline tools can
        // identify a cell (hover-to-inspect) and re-colour without re-sampling, and so the
        // Python parity harness can diff against the real generator cell by cell.
        short[] ids = new short[n * n];
        short[] heights = withHeight ? new short[n * n] : null;
        Map<String, Integer> nameToId = new ConcurrentHashMap<>();
        AtomicInteger nextId = new AtomicInteger();
        Map<String, LongAdder> counts = new ConcurrentHashMap<>();
        LongAdder oceanCells = new LongAdder();
        AtomicInteger rowsDone = new AtomicInteger();
        int progressEvery = Math.max(1, n / 10);

        CompletableFuture.runAsync(() -> {
            long t0 = System.currentTimeMillis();

            // One row per task: rows write disjoint slices of `pixels`, so no locking is
            // needed for the image itself.
            IntStream.range(0, n).parallel().forEach(iz -> {
                RandomState rs = perThreadState.get();
                Climate.Sampler sampler = surfaceSampler(rs);
                int worldZ = centerZ - radius + iz * step;   // iz=0 -> min Z at top (north up)

                for (int ix = 0; ix < n; ix++) {
                    int worldX = centerX - radius + ix * step;

                    // Depth is pinned to 0 (see surfaceSampler), so this IS the surface biome
                    // and y is irrelevant — no terrain column has to be solved.
                    Holder<Biome> holder = biomeSource.getNoiseBiome(
                        worldX >> 2, 0, worldZ >> 2, sampler);
                    String name = holder.unwrapKey().map(k -> k.location().toString()).orElse("unknown");

                    pixels[iz * n + ix] = colorFor(name);
                    ids[iz * n + ix] =
                        (short) (int) nameToId.computeIfAbsent(name, k -> nextId.getAndIncrement());
                    counts.computeIfAbsent(name, k -> new LongAdder()).increment();
                    if (isOcean(name)) oceanCells.increment();

                    if (heights != null) {
                        heights[iz * n + ix] = (short) generator.getBaseHeight(
                            worldX, worldZ, Heightmap.Types.WORLD_SURFACE_WG, overworld, rs);
                    }
                }

                int done = rowsDone.incrementAndGet();
                if (done % progressEvery == 0 && done < n) {
                    int pct = 100 * done / n;
                    long secs = (System.currentTimeMillis() - t0) / 1000;
                    server.execute(() -> source.sendSuccess(() -> Component.literal(
                        "[continental] " + pct + "% (" + secs + "s elapsed)"), false));
                }
            });

            long elapsed = System.currentTimeMillis() - t0;
            BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, n, n, pixels, 0, n);

            Path dir = server.getServerDirectory().resolve("seedscan");
            // Radius/step are part of the name: scanning the same seed at a different zoom
            // used to silently overwrite the earlier export.
            String stem = "biome_map_" + seedUsed + "_r" + radius + "_s" + step;
            Path out = dir.resolve(stem + ".png");
            try {
                Files.createDirectories(dir);
                ImageIO.write(img, "png", out.toFile());

                // Raw biome-ID grid, little-endian uint16, row-major (north-up, west-left).
                ByteBuffer buf = ByteBuffer.allocate(ids.length * 2).order(ByteOrder.LITTLE_ENDIAN);
                for (short id : ids) buf.putShort(id);
                Files.write(dir.resolve(stem + ".bin"), buf.array());

                // Sidecar: everything needed to interpret the grid without the game.
                String[] palette = new String[nameToId.size()];
                nameToId.forEach((nm, id) -> palette[id] = nm);
                JsonObject meta = new JsonObject();
                meta.addProperty("seed", seedUsed);
                meta.addProperty("n", n);
                meta.addProperty("radius", radius);
                meta.addProperty("step", step);
                meta.addProperty("centerX", centerX);
                meta.addProperty("centerZ", centerZ);
                JsonArray pal = new JsonArray();
                for (String nm : palette) pal.add(nm);
                meta.add("palette", pal);
                meta.addProperty("seaLevel", seaLevel);
                meta.addProperty("hasHeight", heights != null);
                if (heights != null) {
                    ByteBuffer hb = ByteBuffer.allocate(heights.length * 2)
                        .order(ByteOrder.LITTLE_ENDIAN);
                    for (short h : heights) hb.putShort(h);
                    Files.write(dir.resolve(stem + ".hgt"), hb.array());
                }
                Files.writeString(dir.resolve(stem + ".json"),
                    new GsonBuilder().create().toJson(meta));
            } catch (Exception e) {
                server.execute(() -> source.sendFailure(Component.literal(
                    "[continental] Export failed: " + e.getMessage())));
                return;
            }

            int total = n * n;
            double oceanPct = 100.0 * oceanCells.sum() / total;
            server.execute(() -> {
                source.sendSuccess(() -> Component.literal(
                    "[continental] Wrote " + n + "x" + n + " map to " + out.getFileName()
                        + " in " + (elapsed / 1000) + "s. Ocean ~" + String.format("%.1f", oceanPct)
                        + "%, land ~" + String.format("%.1f", 100.0 - oceanPct) + "%."), false);
                counts.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                    .limit(12)
                    .forEach(e -> {
                        double pct = 100.0 * e.getValue().sum() / total;
                        source.sendSuccess(() -> Component.literal(
                            "  " + String.format("%5.1f%%  ", pct) + e.getKey()), false);
                    });
                source.sendSuccess(() -> Component.literal(
                    "[continental] " + counts.size() + " distinct biomes in view."), false);
            });
        }, Util.backgroundExecutor());

        return n;
    }

    /**
     * A Climate.Sampler with <b>depth pinned to 0</b>, i.e. the surface climate band.
     *
     * <p>Biome selection takes six axes; five of them (temperature, humidity, continentalness,
     * erosion, weirdness) are 2D — {@code flat_cache}/{@code cache_2d} — so they don't depend on
     * y at all. Only depth does, and at the real terrain surface depth is ~0 by construction
     * (the surface is where density crosses zero, and depth is that expression's dominant term).
     *
     * <p>So instead of solving a terrain column per sample just to learn which y has depth≈0 —
     * which is what made the previous version take ~20 minutes for one map — we substitute a
     * constant-zero depth function and read the surface band directly. Same answer, no column
     * solve, and y becomes irrelevant to the caller.
     *
     * <p>This goes through {@code BiomeSource.getNoiseBiome} as normal, so TerraBlender's
     * per-region routing and BWG's biomes behave exactly as they do in game.
     */
    private static Climate.Sampler surfaceSampler(RandomState state) {
        Climate.Sampler s = state.sampler();
        return new Climate.Sampler(
            s.temperature(), s.humidity(), s.continentalness(), s.erosion(),
            DensityFunctions.zero(),
            s.weirdness(), s.spawnTarget());
    }

    /** Ocean classification for the land/ocean split (BWG's oceanic biomes included). */
    private static boolean isOcean(String name) {
        return name.contains("ocean")
            || name.equals("biomeswevegone:dead_sea")
            || name.equals("biomeswevegone:lush_stacks");
    }

    /** Readable palette for common biomes; deterministic land-toned hash for the rest (incl. BWG). */
    private static int colorFor(String name) {
        Integer c = PALETTE.get(name);
        if (c != null) return c;
        // Hash -> hue in the warm/green band so unknown LAND biomes never read as ocean-blue.
        int h = name.hashCode();
        float hue = 0.05f + (Math.floorMod(h, 1000) / 1000.0f) * 0.40f;
        float sat = 0.45f + (Math.floorMod(h >> 10, 1000) / 1000.0f) * 0.35f;
        float val = 0.55f + (Math.floorMod(h >> 20, 1000) / 1000.0f) * 0.30f;
        return java.awt.Color.HSBtoRGB(hue, sat, val);
    }

    private static final Map<String, Integer> PALETTE = new HashMap<>();
    private static void p(String n, int rgb) { PALETTE.put("minecraft:" + n, rgb); }
    static {
        // Oceans / water — blues so landmass reads instantly
        p("ocean", 0x3050B0);
        p("deep_ocean", 0x182C78);
        p("cold_ocean", 0x3560A8);
        p("deep_cold_ocean", 0x203A78);
        p("frozen_ocean", 0x7088C0);
        p("deep_frozen_ocean", 0x405890);
        p("lukewarm_ocean", 0x2C78B8);
        p("deep_lukewarm_ocean", 0x1C5090);
        p("warm_ocean", 0x2090B0);
        p("river", 0x3858C0);
        p("frozen_river", 0x88A0D8);
        // Coast / land
        p("beach", 0xF5DE73);
        p("snowy_beach", 0xF0E8C8);
        p("stony_shore", 0x888890);
        p("plains", 0x8DB360);
        p("sunflower_plains", 0xB5C84B);
        p("meadow", 0x7FB060);
        p("forest", 0x4F8A3A);
        p("flower_forest", 0x6FB048);
        p("birch_forest", 0x6FA85A);
        p("dark_forest", 0x2F5020);
        p("old_growth_birch_forest", 0x7AB070);
        p("taiga", 0x3F6B4A);
        p("snowy_taiga", 0x5A7868);
        p("old_growth_pine_taiga", 0x49684A);
        p("old_growth_spruce_taiga", 0x52704E);
        p("jungle", 0x337818);
        p("sparse_jungle", 0x5A8830);
        p("bamboo_jungle", 0x6B9820);
        p("savanna", 0xBFB755);
        p("savanna_plateau", 0xB0A84F);
        p("windswept_savanna", 0xA8A860);
        p("desert", 0xE8D17A);
        p("snowy_plains", 0xF0F0F5);
        p("ice_spikes", 0xD8E8F0);
        p("snowy_slopes", 0xD0DCE8);
        p("grove", 0x88A088);
        p("jagged_peaks", 0xC8D0DC);
        p("frozen_peaks", 0xBCCCE0);
        p("stony_peaks", 0xA89888);
        p("windswept_hills", 0x6A8870);
        p("windswept_forest", 0x5A7850);
        p("windswept_gravelly_hills", 0x808078);
        p("swamp", 0x4A6038);
        p("mangrove_swamp", 0x386038);
        p("badlands", 0xB85A2C);
        p("wooded_badlands", 0x9A6838);
        p("eroded_badlands", 0xC86838);
        p("cherry_grove", 0xF0A8C8);
        p("mushroom_fields", 0xC838C8);
        // Cave biomes — should now be RARE on a surface map; loud colors make leaks obvious.
        p("lush_caves", 0x00FF00);
        p("dripstone_caves", 0xFF00FF);
        p("deep_dark", 0xFF0000);

        // Oh The Biomes We've Gone — curated colors, kept in sync with BIOME_COLORS in
        // preview/mc_biomes.py so in-game maps and any Python-rendered view read alike.
        // Without these, all 54 fell through to the hash fallback and the map read as noise.
        PALETTE.put("biomeswevegone:allium_shrubland", 0x8880C0);
        PALETTE.put("biomeswevegone:amaranth_grassland", 0xC060A0);
        PALETTE.put("biomeswevegone:araucaria_savanna", 0xB89840);
        PALETTE.put("biomeswevegone:aspen_boreal", 0xA8C460);
        PALETTE.put("biomeswevegone:atacama_outback", 0xC8A068);
        PALETTE.put("biomeswevegone:baobab_savanna", 0xBF9A38);
        PALETTE.put("biomeswevegone:basalt_barrera", 0x3A3A3A);
        PALETTE.put("biomeswevegone:bayou", 0x1A4A30);
        PALETTE.put("biomeswevegone:black_forest", 0x1C3312);
        PALETTE.put("biomeswevegone:canadian_shield", 0x6A7060);
        PALETTE.put("biomeswevegone:cika_woods", 0x3E6830);
        PALETTE.put("biomeswevegone:coconino_meadow", 0xB8A870);
        PALETTE.put("biomeswevegone:coniferous_forest", 0x1E5035);
        PALETTE.put("biomeswevegone:crag_gardens", 0x5A7850);
        PALETTE.put("biomeswevegone:crimson_tundra", 0xA02828);
        PALETTE.put("biomeswevegone:cypress_swamplands", 0x1E4A28);
        PALETTE.put("biomeswevegone:cypress_wetlands", 0x286850);
        PALETTE.put("biomeswevegone:dacite_ridges", 0x787070);
        PALETTE.put("biomeswevegone:dacite_shore", 0x888080);
        PALETTE.put("biomeswevegone:dead_sea", 0xD0C8A8);
        PALETTE.put("biomeswevegone:ebony_woods", 0x283018);
        PALETTE.put("biomeswevegone:enchanted_tangle", 0x4A2870);
        PALETTE.put("biomeswevegone:firecracker_chaparral", 0xB84828);
        PALETTE.put("biomeswevegone:forgotten_forest", 0x4A5840);
        PALETTE.put("biomeswevegone:fragment_jungle", 0x3A7808);
        PALETTE.put("biomeswevegone:frosted_coniferous_forest", 0x3A6868);
        PALETTE.put("biomeswevegone:frosted_taiga", 0x3A5858);
        PALETTE.put("biomeswevegone:howling_peaks", 0xC0C8D8);
        PALETTE.put("biomeswevegone:ironwood_gour", 0x4A5868);
        PALETTE.put("biomeswevegone:jacaranda_jungle", 0x7840A8);
        PALETTE.put("biomeswevegone:lush_stacks", 0x187820);
        PALETTE.put("biomeswevegone:maple_taiga", 0xA83820);
        PALETTE.put("biomeswevegone:mojave_desert", 0xD0A868);
        PALETTE.put("biomeswevegone:orchard", 0x78B850);
        PALETTE.put("biomeswevegone:overgrowth_woodlands", 0x287828);
        PALETTE.put("biomeswevegone:pale_bog", 0x8AA888);
        PALETTE.put("biomeswevegone:prairie", 0xC8A840);
        PALETTE.put("biomeswevegone:pumpkin_valley", 0xD06020);
        PALETTE.put("biomeswevegone:rainbow_beach", 0x38D0B8);
        PALETTE.put("biomeswevegone:red_rock_peaks", 0xC03820);
        PALETTE.put("biomeswevegone:red_rock_valley", 0xB84830);
        PALETTE.put("biomeswevegone:redwood_thicket", 0x6A2218);
        PALETTE.put("biomeswevegone:rose_fields", 0xF07888);
        PALETTE.put("biomeswevegone:rugged_badlands", 0xC03028);
        PALETTE.put("biomeswevegone:sakura_grove", 0xF0A0C8);
        PALETTE.put("biomeswevegone:shattered_glacier", 0xB0C8E8);
        PALETTE.put("biomeswevegone:sierra_badlands", 0xB87840);
        PALETTE.put("biomeswevegone:skyris_vale", 0x5880B8);
        PALETTE.put("biomeswevegone:temperate_grove", 0x5A8840);
        PALETTE.put("biomeswevegone:tropical_rainforest", 0x186818);
        PALETTE.put("biomeswevegone:weeping_witch_forest", 0x382840);
        PALETTE.put("biomeswevegone:white_mangrove_marshes", 0x689878);
        PALETTE.put("biomeswevegone:windswept_desert", 0xC8A040);
        PALETTE.put("biomeswevegone:zelkova_forest", 0x88A840);
    }
}
