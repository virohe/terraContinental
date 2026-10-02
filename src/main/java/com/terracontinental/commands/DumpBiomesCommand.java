package com.terracontinental.commands;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.datafixers.util.Pair;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class DumpBiomesCommand {

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("continental")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("dump_biomes")
                    .executes(DumpBiomesCommand::execute))
        );
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel overworld = source.getServer().getLevel(Level.OVERWORLD);

        if (overworld == null) {
            source.sendFailure(Component.literal("[continental] Overworld not loaded"));
            return 0;
        }

        if (!(overworld.getChunkSource().getGenerator().getBiomeSource()
                instanceof MultiNoiseBiomeSource biomeSource)) {
            source.sendFailure(Component.literal(
                "[continental] Overworld biome source is not MultiNoiseBiomeSource — cannot dump"));
            return 0;
        }

        // parameters() is private; reflect to call it
        Climate.ParameterList<Holder<Biome>> paramList;
        try {
            Method m = MultiNoiseBiomeSource.class.getDeclaredMethod("parameters");
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            Climate.ParameterList<Holder<Biome>> result =
                (Climate.ParameterList<Holder<Biome>>) m.invoke(biomeSource);
            paramList = result;
        } catch (Exception e) {
            source.sendFailure(Component.literal(
                "[continental] Failed to access biome parameters: " + e.getMessage()));
            return 0;
        }

        List<Pair<Climate.ParameterPoint, Holder<Biome>>> vanillaValues = paramList.values();

        JsonObject root = new JsonObject();
        root.addProperty("format", 2);

        // Top-level entries = vanilla / region-0 biomes (backward compat)
        JsonArray vanillaEntries = new JsonArray();
        for (Pair<Climate.ParameterPoint, Holder<Biome>> pair : vanillaValues) {
            vanillaEntries.add(serializeEntry(
                pair.getFirst(),
                pair.getSecond().unwrapKey()
                    .map(k -> k.location().toString())
                    .orElse("unknown")));
        }
        root.add("entries", vanillaEntries);

        // TerraBlender section: detect and reflect into terrablender.api.Regions
        JsonObject tb = buildTerraBlenderSection(source, overworld, paramList);
        if (tb != null) {
            root.add("terrablender", tb);
        }

        Path outputPath = source.getServer().getServerDirectory()
            .resolve("terra_biome_dump.json");
        try (Writer writer = Files.newBufferedWriter(outputPath)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(root, writer);
        } catch (IOException e) {
            source.sendFailure(Component.literal("[continental] Write failed: " + e.getMessage()));
            return 0;
        }

        int count = vanillaValues.size();
        source.sendSuccess(
            () -> Component.literal(
                "[terra] Wrote " + count + " biome entries to " + outputPath),
            false);
        return count;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static JsonObject buildTerraBlenderSection(
            CommandSourceStack source,
            ServerLevel overworld,
            Climate.ParameterList<Holder<Biome>> paramList) {

        Class<?> regionsClass;
        try {
            regionsClass = Class.forName("terrablender.api.Regions");
        } catch (ClassNotFoundException e) {
            return null; // TerraBlender not installed
        }

        try {
            Class<?> regionTypeClass = Class.forName("terrablender.api.RegionType");
            Object overworldType = regionTypeClass.getField("OVERWORLD").get(null);

            Method getMethod = regionsClass.getMethod("get", regionTypeClass);
            List<?> tbRegions = (List<?>) getMethod.invoke(null, overworldType);

            Class<?> regionClass = Class.forName("terrablender.api.Region");
            Method addBiomesMethod = regionClass.getMethod(
                "addBiomes", Registry.class, Consumer.class);
            Method getWeightMethod = regionClass.getMethod("getWeight");
            Method getNameMethod = regionClass.getMethod("getName");

            int regionSize = getTerraBlenderRegionSize();

            Registry<Biome> biomeRegistry =
                overworld.registryAccess().registryOrThrow(Registries.BIOME);

            JsonObject tb = new JsonObject();
            tb.addProperty("region_size", regionSize);
            JsonArray regionsArray = new JsonArray();

            for (int i = 0; i < tbRegions.size(); i++) {
                Object region = tbRegions.get(i);
                String name = getNameMethod.invoke(region).toString();
                int weight = (int) getWeightMethod.invoke(region);

                JsonObject regionObj = new JsonObject();
                regionObj.addProperty("name", name);
                regionObj.addProperty("weight", weight);

                if (i == 0) {
                    // Vanilla region: entries come from the MC parameter list,
                    // not from addBiomes() — reference the top-level entries
                    regionObj.add("entries", new JsonPrimitive("vanilla"));
                } else {
                    // Mod region: collect via addBiomes()
                    List<Pair<Climate.ParameterPoint, String>> modEntries = new ArrayList<>();
                    Consumer<Object> collector = obj -> {
                        Pair<?, ?> pair = (Pair<?, ?>) obj;
                        Climate.ParameterPoint pt = (Climate.ParameterPoint) pair.getFirst();
                        ResourceKey<?> key = (ResourceKey<?>) pair.getSecond();
                        modEntries.add(Pair.of(pt, key.location().toString()));
                    };
                    addBiomesMethod.invoke(region, biomeRegistry, collector);

                    JsonArray regionEntries = new JsonArray();
                    for (Pair<Climate.ParameterPoint, String> entry : modEntries) {
                        regionEntries.add(serializeEntry(entry.getFirst(), entry.getSecond()));
                    }
                    regionObj.add("entries", regionEntries);
                }
                regionsArray.add(regionObj);
            }

            tb.add("regions", regionsArray);
            return tb;

        } catch (Exception e) {
            source.sendFailure(Component.literal(
                "[continental] TerraBlender detected but reflection failed: " + e.getMessage()));
            return null;
        }
    }

    private static int getTerraBlenderRegionSize() {
        try {
            Class<?> tbClass = Class.forName("terrablender.core.TerraBlender");
            Object config = tbClass.getField("CONFIG").get(null);
            return config.getClass().getField("overworldRegionSize").getInt(config);
        } catch (Exception e) {
            return 2; // sensible default
        }
    }

    private static JsonObject serializeEntry(Climate.ParameterPoint pt, String biomeName) {
        JsonObject entry = new JsonObject();
        entry.addProperty("biome", biomeName);
        entry.add("t", paramJson(pt.temperature()));
        entry.add("h", paramJson(pt.humidity()));
        entry.add("c", paramJson(pt.continentalness()));
        entry.add("e", paramJson(pt.erosion()));
        entry.add("d", paramJson(pt.depth()));
        entry.add("w", paramJson(pt.weirdness()));
        entry.addProperty("offset", pt.offset());
        return entry;
    }

    private static JsonArray paramJson(Climate.Parameter p) {
        JsonArray arr = new JsonArray();
        arr.add(p.min());
        arr.add(p.max());
        return arr;
    }
}
