package com.terracontinental.terrain;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * The terrain noises renamed from tectonic: to terra_continental: in 0.10.0, and the id each is
 * seeded under so every seed keeps its land (see NoiseSeedMixin). The list is
 * terra_continental_renamed_noises.txt, which the previewer reads too.
 */
public final class RenamedNoises {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NS = "terra_continental";
    private static final Set<String> PATHS = load();

    private RenamedNoises() {}

    public static ResourceLocation seedId(ResourceLocation id) {
        if (!id.getNamespace().equals(NS) || !PATHS.contains(id.getPath())) return id;
        String path = id.getPath();
        // Tectonic seeded its climate parameters as vanilla's (parameter/erosion as minecraft:erosion).
        if (path.startsWith("parameter/")) return ResourceLocation.withDefaultNamespace(path.substring("parameter/".length()));
        return ResourceLocation.fromNamespaceAndPath("tectonic", path);
    }

    private static Set<String> load() {
        Set<String> out = new HashSet<>();
        try (InputStream in = RenamedNoises.class.getResourceAsStream("/terra_continental_renamed_noises.txt")) {
            if (in == null) throw new IllegalStateException("terra_continental_renamed_noises.txt missing from the jar");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                line = line.strip();
                if (!line.isEmpty() && !line.startsWith("#")) out.add(line);
            }
        } catch (Exception e) {
            // Without the list every seed would generate different land; fail loudly at startup.
            throw new IllegalStateException("Terra Continental can't read its renamed-noise list", e);
        }
        LOGGER.debug("{} renamed terrain noises keep their old seeds", out.size());
        return Set.copyOf(out);
    }
}
