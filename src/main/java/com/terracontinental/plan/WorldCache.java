package com.terracontinental.plan;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Results worked out once per world and kept in {@code <world>/data/terra_continental/}, so opening
 * a world reads them back instead of working them out again: the spawn continent's layout, the
 * world plan and the spawn-village plan, about two minutes of work on every load otherwise.
 *
 * <p>Each file starts with its format version and a fingerprint of everything its contents depend
 * on: the seed, the worldgen content and the mods that supply it, the datapacks, and Terra
 * Continental's common and structure settings. When any of them differs the file is ignored and the result worked out again, exactly as
 * if there were no file, so a saved result never stands in for one the game would now make
 * differently.</p>
 */
public final class WorldCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DIR = "terra_continental";
    private static final String[] CONFIGS = {"terra_continental-common.toml", "terra_continental-structures.toml"};

    private static volatile Long installFingerprint;

    private WorldCache() {
    }

    @FunctionalInterface
    public interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    @FunctionalInterface
    public interface Reader<T> {
        T read(DataInputStream in) throws IOException;
    }

    /** The running server, or null (previews and tools run without one: nothing is saved then). */
    @Nullable
    public static MinecraftServer server() {
        try {
            return ServerLifecycleHooks.getCurrentServer();
        } catch (Throwable t) {
            return null;
        }
    }

    /** This world's file of that name. */
    public static Path file(MinecraftServer server, String name) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DIR).resolve(name);
    }

    /** Seed, worldgen and settings in one number: a saved result is used only where this matches. */
    public static long fingerprint(MinecraftServer server, long seed) {
        Long f = installFingerprint;
        if (f == null) {
            f = install(server);
            installFingerprint = f;
            LOGGER.info("[world-cache] worldgen fingerprint {}", Long.toHexString(f));
        }
        return mix(f, seed);
    }

    /** Settings can be edited between worlds: read them again for the next one. */
    public static void forgetInstall() {
        installFingerprint = null;
    }

    /**
     * Everything the saved results depend on besides the seed. Only what shapes worldgen counts, so a
     * world pregenerated on a dedicated server matches in a client that adds client-only mods (maps,
     * renderers): the ids in the worldgen registries, the version of each mod that owns some of them,
     * the datapacks in use, Terra Continental's version and its common and structure settings.
     */
    private static long install(MinecraftServer server) {
        long h = 0xCBF29CE484222325L;
        java.util.TreeSet<String> namespaces = new java.util.TreeSet<>();
        namespaces.add("terra_continental");
        try {
            var access = server.registryAccess();
            for (var key : WORLDGEN) {
                var reg = access.registry(key);
                if (reg.isEmpty()) {
                    h = fnv(h, ("-" + key.location()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    continue;
                }
                java.util.List<String> ids = new java.util.ArrayList<>();
                for (var id : reg.get().keySet()) {
                    ids.add(id.toString());
                    namespaces.add(id.getNamespace());
                }
                java.util.Collections.sort(ids);
                h = fnv(h, key.location().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                for (String id : ids) h = fnv(h, id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            // Each mod is also a datapack ("mod/<id>"), client-only ones included: those are covered by
            // the mods owning registry ids above.
            for (String pack : new java.util.TreeSet<>(server.getPackRepository().getSelectedIds())) {
                if (pack.startsWith("mod/") || pack.equals("mod_resources")) continue;
                h = fnv(h, pack.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            h = fnv(h, new byte[]{1});
        }
        for (String ns : namespaces) {
            String version = ModList.get().getModContainerById(ns)
                    .map(c -> c.getModInfo().getVersion().toString()).orElse("-");
            h = fnv(h, (ns + "@" + version).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        for (String name : CONFIGS) {
            Path p = FMLPaths.CONFIGDIR.get().resolve(name);
            // The settings, not the file: comments, blank lines and line endings don't count.
            try {
                if (!Files.isRegularFile(p)) {
                    h = fnv(h, new byte[]{0});
                    continue;
                }
                for (String line : Files.readAllLines(p)) {
                    String t = line.strip();
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    h = fnv(h, t.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                h = fnv(h, new byte[]{2});
            }
        }
        return h;
    }

    private static final java.util.List<net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<?>>> WORLDGEN =
            java.util.List.of(
                    net.minecraft.core.registries.Registries.BIOME,
                    net.minecraft.core.registries.Registries.DENSITY_FUNCTION,
                    net.minecraft.core.registries.Registries.NOISE,
                    net.minecraft.core.registries.Registries.NOISE_SETTINGS,
                    net.minecraft.core.registries.Registries.STRUCTURE,
                    net.minecraft.core.registries.Registries.STRUCTURE_SET,
                    net.minecraft.core.registries.Registries.TEMPLATE_POOL,
                    net.minecraft.core.registries.Registries.PROCESSOR_LIST,
                    net.minecraft.core.registries.Registries.CONFIGURED_FEATURE,
                    net.minecraft.core.registries.Registries.PLACED_FEATURE,
                    net.minecraft.core.registries.Registries.LEVEL_STEM);

    private static long fnv(long h, byte[] bytes) {
        for (byte b : bytes) {
            h ^= b & 0xFF;
            h *= 0x100000001B3L;
        }
        h ^= 0xFF;
        return h * 0x100000001B3L;
    }

    private static long mix(long a, long b) {
        long z = a * 0x9E3779B97F4A7C15L ^ b;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** The saved result, or null when there is none, it is from another version or fingerprint, or unreadable. */
    @Nullable
    public static <T> T read(Path file, int version, long fingerprint, Reader<T> reader) {
        if (!Files.isRegularFile(file)) return null;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != version || in.readLong() != fingerprint) {
                LOGGER.info("[world-cache] {} is from other settings or mods; working it out again", file.getFileName());
                return null;
            }
            return reader.read(in);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[world-cache] couldn't read {}, working it out again: {}", file.getFileName(), e.toString());
            return null;
        }
    }

    /** Saves a result: written beside the file and moved over it, so a crash never leaves half a file. */
    public static void write(Path file, int version, long fingerprint, Writer writer) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(version);
                out.writeLong(fingerprint);
                writer.write(out);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("[world-cache] couldn't save {}: {}", file.getFileName(), e.toString());
        }
    }
}
