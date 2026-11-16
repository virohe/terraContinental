package com.terracontinental.spawn;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps cave and ravine carvers out of the ground under villages (user, 2026-11-11: in world 91 a
 * ravine 4-6 blocks wide and up to 13 deep opened along a planned village's street, and a pit 22
 * deep inside it). Carvers run after a village is laid out and don't look at it, unlike the noise
 * caves, which the terrain-fitting pass fills around each house. While a chunk is carved, every
 * village start touching it marks its whole area, {@link #MARGIN} blocks wider, from
 * {@link #DEPTH} blocks under its lowest piece upwards; carving stops there, so a ravine that runs
 * into a village ends at a wall short of it and deeper caves pass underneath.
 */
public final class VillageCarverGuard {
    static final int MARGIN = 8;
    static final int DEPTH = 16;

    private static final ThreadLocal<int[]> BOXES = new ThreadLocal<>();
    private static final int[] NONE = new int[0];

    private VillageCarverGuard() {
    }

    /** Start of a chunk's carving: note the village areas over it. */
    public static void begin(StructureManager structures, ChunkPos chunk) {
        List<int[]> boxes = new ArrayList<>();
        var registry = structures.registryAccess().registryOrThrow(Registries.STRUCTURE);
        try {
            for (StructureStart start : structures.startsForStructure(chunk, s -> isVillage(registry.wrapAsHolder(s)))) {
                BoundingBox b = start.getBoundingBox();
                boxes.add(new int[]{b.minX() - MARGIN, b.minZ() - MARGIN, b.maxX() + MARGIN, b.maxZ() + MARGIN, b.minY() - DEPTH});
            }
        } catch (RuntimeException e) {
            // Never let the guard break chunk generation: carve as usual.
            boxes.clear();
        }
        int[] flat = NONE;
        if (!boxes.isEmpty()) {
            flat = new int[boxes.size() * 5];
            for (int i = 0; i < boxes.size(); i++) System.arraycopy(boxes.get(i), 0, flat, i * 5, 5);
        }
        BOXES.set(flat);
    }

    public static void end() {
        BOXES.remove();
    }

    /** May a carver take this block? */
    public static boolean protects(BlockPos pos) {
        int[] b = BOXES.get();
        if (b == null || b.length == 0) return false;
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        for (int i = 0; i < b.length; i += 5) {
            if (x >= b[i] && z >= b[i + 1] && x <= b[i + 2] && z <= b[i + 3] && y >= b[i + 4]) return true;
        }
        return false;
    }

    /** Villages: the village tag, the abandoned villages, and BWG's Forgotten villages and Pumpkin Patches. */
    static boolean isVillage(Holder<Structure> s) {
        if (s.is(StructureTags.VILLAGE)) return true;
        return s.unwrapKey().map(k -> {
            String id = k.location().toString();
            return id.startsWith("terra_continental:abandoned_village/") || id.startsWith("biomeswevegone:village/");
        }).orElse(false);
    }
}
