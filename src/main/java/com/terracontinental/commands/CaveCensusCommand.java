package com.terracontinental.commands;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
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
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

/**
 * {@code /continental cave_census <radius> <step> [seed] [noise settings]}: underground biome counts at fixed
 * heights, with the climate values that pick them.
 *
 * <p>Samples a square grid round 0,0 at y 48 down to -440 (the world floor is -512) through the generator's own
 * climate sampler (real depth, unlike biome_map's surface band) and writes
 * {@code seedscan/cave_census_<seed>_r<radius>_s<step>[_<settings>].json}: per height, the share of each
 * biome over all cells and over land cells (surface continentalness above -0.11), and
 * 0.05-wide histograms of continentalness, humidity and depth. Cave biomes key on those three:
 * dripstone on continentalness, lush on humidity, both on a depth band. Beside it, a {@code .bin} of the biome
 * grid at every height (little-endian uint16 palette indices, height-major, then z, then x; the palette is
 * the JSON's {@code palette}), so offline tools can measure how big and how far apart cave biome patches are.
 */
public final class CaveCensusCommand {
    private static final int[] HEIGHTS = {48, 0, -64, -160, -300, -440};
    private static final int BINS = 80;            // -2.0 .. 2.0 in 0.05 steps
    private static final int MAX_CELLS = 250_000;

    private CaveCensusCommand() {}

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("continental")
            .requires(src -> src.hasPermission(2))
            .then(Commands.literal("cave_census")
                .then(Commands.argument("radius", IntegerArgumentType.integer(64, 4_000_000))
                    .then(Commands.argument("step", IntegerArgumentType.integer(4, 100_000))
                        .executes(ctx -> run(ctx, null, null))
                        .then(Commands.argument("seed", LongArgumentType.longArg())
                            .executes(ctx -> run(ctx, LongArgumentType.getLong(ctx, "seed"), null))
                            .then(Commands.argument("settings", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                .executes(ctx -> run(ctx, LongArgumentType.getLong(ctx, "seed"),
                                    net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "settings")))))))));
    }

    private static int bin(long quantized) {
        float v = Climate.unquantizeCoord(quantized);
        int b = (int) Math.floor((v + 2.0f) / 0.05f);
        return Math.max(0, Math.min(BINS - 1, b));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Long seedArg,
                           net.minecraft.resources.ResourceLocation settingsId) {
        CommandSourceStack source = ctx.getSource();
        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        int step = IntegerArgumentType.getInteger(ctx, "step");
        int n = (2 * radius) / step + 1;
        if ((long) n * n > MAX_CELLS) {
            source.sendFailure(Component.literal("[continental] Grid too large: " + n + "x" + n
                + " (max " + MAX_CELLS + " cells per height)."));
            return 0;
        }
        ServerLevel overworld = source.getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) {
            source.sendFailure(Component.literal("[continental] Overworld not loaded"));
            return 0;
        }
        ChunkGenerator generator = overworld.getChunkSource().getGenerator();
        if (!(generator instanceof NoiseBasedChunkGenerator noiseGen)) {
            source.sendFailure(Component.literal("[continental] Overworld generator is not noise-based."));
            return 0;
        }
        BiomeSource biomeSource = generator.getBiomeSource();
        NoiseGeneratorSettings settings;
        if (settingsId == null) {
            settings = noiseGen.generatorSettings().value();
        } else {
            var holder = overworld.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS).getHolder(
                net.minecraft.resources.ResourceKey.create(Registries.NOISE_SETTINGS, settingsId));
            if (holder.isEmpty()) {
                source.sendFailure(Component.literal("[continental] No noise settings " + settingsId));
                return 0;
            }
            settings = holder.get().value();
        }
        String suffix = settingsId == null ? "" : "_" + settingsId.toString().replace(':', '_').replace('/', '_');
        HolderGetter<NormalNoise.NoiseParameters> noiseGetter =
            overworld.registryAccess().lookupOrThrow(Registries.NOISE);
        long seedUsed = seedArg != null ? seedArg : overworld.getSeed();
        MinecraftServer server = source.getServer();
        ThreadLocal<RandomState> perThread =
            ThreadLocal.withInitial(() -> RandomState.create(settings, noiseGetter, seedUsed));

        int h = HEIGHTS.length;
        @SuppressWarnings("unchecked")
        Map<String, LongAdder>[] all = new Map[h];
        @SuppressWarnings("unchecked")
        Map<String, LongAdder>[] land = new Map[h];
        for (int i = 0; i < h; i++) { all[i] = new ConcurrentHashMap<>(); land[i] = new ConcurrentHashMap<>(); }
        AtomicLongArray contHist = new AtomicLongArray(BINS);
        AtomicLongArray humHist = new AtomicLongArray(BINS * h);
        AtomicLongArray humLandHist = new AtomicLongArray(BINS * h);
        AtomicLongArray depthHist = new AtomicLongArray(BINS * h);
        LongAdder landCells = new LongAdder();
        Map<String, Integer> palette = new ConcurrentHashMap<>();
        short[] grid = new short[h * n * n];

        source.sendSuccess(() -> Component.literal("[continental] Cave census: " + n + "x" + n
            + " at " + h + " heights, seed " + seedUsed + ". Running in the background."), false);

        CompletableFuture.runAsync(() -> {
            long t0 = System.currentTimeMillis();
            IntStream.range(0, n).parallel().forEach(iz -> {
                RandomState rs = perThread.get();
                Climate.Sampler sampler = rs.sampler();
                int qz = (-radius + iz * step) >> 2;
                for (int ix = 0; ix < n; ix++) {
                    int qx = (-radius + ix * step) >> 2;
                    boolean isLand = false;
                    for (int k = 0; k < h; k++) {
                        int qy = HEIGHTS[k] >> 2;
                        Climate.TargetPoint p = sampler.sample(qx, qy, qz);
                        if (k == 0) {
                            contHist.incrementAndGet(bin(p.continentalness()));
                            isLand = Climate.unquantizeCoord(p.continentalness()) > -0.11f;
                            if (isLand) landCells.increment();
                        }
                        humHist.incrementAndGet(k * BINS + bin(p.humidity()));
                        if (isLand) humLandHist.incrementAndGet(k * BINS + bin(p.humidity()));
                        depthHist.incrementAndGet(k * BINS + bin(p.depth()));
                        Holder<Biome> b = biomeSource.getNoiseBiome(qx, qy, qz, sampler);
                        String name = b.unwrapKey().map(key -> key.location().toString()).orElse("unknown");
                        int id;
                        synchronized (palette) { id = palette.computeIfAbsent(name, x -> palette.size()); }
                        grid[(k * n + iz) * n + ix] = (short) id;
                        all[k].computeIfAbsent(name, x -> new LongAdder()).increment();
                        if (isLand) land[k].computeIfAbsent(name, x -> new LongAdder()).increment();
                    }
                }
            });

            JsonObject root = new JsonObject();
            root.addProperty("seed", seedUsed);
            root.addProperty("radius", radius);
            root.addProperty("step", step);
            root.addProperty("cells", (long) n * n);
            root.addProperty("land_cells", landCells.sum());
            root.addProperty("hist_min", -2.0);
            root.addProperty("hist_bin", 0.05);
            root.add("continentalness", hist(contHist, 0));
            JsonObject levels = new JsonObject();
            for (int k = 0; k < h; k++) {
                JsonObject lv = new JsonObject();
                lv.add("biomes", shares(all[k]));
                lv.add("biomes_land", shares(land[k]));
                lv.add("humidity", hist(humHist, k * BINS));
                lv.add("humidity_land", hist(humLandHist, k * BINS));
                lv.add("depth", hist(depthHist, k * BINS));
                levels.add(Integer.toString(HEIGHTS[k]), lv);
            }
            root.add("levels", levels);
            com.google.gson.JsonArray pal = new com.google.gson.JsonArray();
            String[] names = new String[palette.size()];
            palette.forEach((k2, v) -> names[v] = k2);
            for (String nm : names) pal.add(nm);
            root.add("palette", pal);
            com.google.gson.JsonArray hs = new com.google.gson.JsonArray();
            for (int y : HEIGHTS) hs.add(y);
            root.add("grid_heights", hs);
            root.addProperty("n", n);

            Path out = server.getServerDirectory().resolve("seedscan")
                .resolve("cave_census_" + seedUsed + "_r" + radius + "_s" + step + suffix + ".json");
            String msg;
            try {
                Files.createDirectories(out.getParent());
                Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(root));
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(grid.length * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                for (short v : grid) buf.putShort(v);
                Files.write(out.resolveSibling(out.getFileName().toString().replace(".json", ".bin")), buf.array());
                msg = "[continental] Cave census written to " + out.getFileName() + " ("
                    + (System.currentTimeMillis() - t0) / 1000 + "s)";
            } catch (Exception e) {
                msg = "[continental] Cave census write failed: " + e.getMessage();
            }
            String m = msg;
            server.execute(() -> source.sendSuccess(() -> Component.literal(m), false));
        });
        return 1;
    }

    private static JsonObject shares(Map<String, LongAdder> counts) {
        long total = counts.values().stream().mapToLong(LongAdder::sum).sum();
        JsonObject o = new JsonObject();
        Map<String, Long> sorted = new TreeMap<>();
        counts.forEach((k, v) -> sorted.put(k, v.sum()));
        sorted.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
            .forEach(e -> o.addProperty(e.getKey(), total == 0 ? 0.0 : e.getValue() / (double) total));
        return o;
    }

    private static com.google.gson.JsonArray hist(AtomicLongArray a, int off) {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (int i = 0; i < BINS; i++) arr.add(a.get(off + i));
        return arr;
    }
}
