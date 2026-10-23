package com.terracontinental.worldtype;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.Reader;
import java.util.Optional;

/**
 * The TC world types' noise settings are copies of Tectonic's minecraft:overworld that differ only in
 * the climate entries (tools/make_world_types.py). After a Tectonic update the copies go stale and
 * those worlds would quietly lose the new terrain; this says so in the log at every server start.
 *
 * <p>It compares the data FILES (the winning overworld.json against each copy), not the loaded
 * settings: TerraBlender attaches BWG's surface rules only to the settings a world actually uses,
 * so in a TC world the loaded copy and the unused minecraft:overworld always differ (release
 * test, 2026-10-23: a false warning on every boot).</p>
 */
public final class WorldTypeCheck {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String[] COPIES = {"overworld_large", "overworld_climates"};

    private WorldTypeCheck() {
    }

    public static void run(MinecraftServer server) {
        ResourceManager rm = server.getResourceManager();
        JsonElement base = read(rm, ResourceLocation.withDefaultNamespace("worldgen/noise_settings/overworld.json"));
        if (base == null) return;
        for (String name : COPIES) {
            JsonElement copy = read(rm, ResourceLocation.fromNamespaceAndPath("terra_continental", "worldgen/noise_settings/" + name + ".json"));
            if (copy == null) continue;
            if (!base.equals(copy)) {
                LOGGER.warn("[world types] terra_continental:{} no longer matches minecraft:overworld outside its climate "
                        + "entries (Tectonic updated?). Re-run tools/make_world_types.py and rebuild.", name);
            }
        }
    }

    /** The winning copy of a data file, with the two climate entries removed; null if absent or unreadable. */
    private static JsonElement read(ResourceManager rm, ResourceLocation file) {
        Optional<Resource> res = rm.getResource(file);
        if (res.isEmpty()) return null;
        try (Reader r = res.get().openAsReader()) {
            JsonElement e = JsonParser.parseReader(r);
            if (e instanceof JsonObject o && o.get("noise_router") instanceof JsonObject router) {
                router.remove("temperature");
                router.remove("vegetation");
            }
            return e;
        } catch (Exception ex) {
            LOGGER.warn("[world types] could not read {}", file, ex);
            return null;
        }
    }
}
