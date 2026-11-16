package com.terracontinental.alpine;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * The alpine surface, once per chunk after vanilla's snowfall (top-layer step, every overworld
 * biome through a Lithostitched modifier, so modded biomes' own surface rules are covered too).
 * Playtest 2026-10-31: snow was a biome palette (whole ranges white, cliffs included) and the
 * peaks carried ice everywhere. Around each column's own snow line (the shared field, lower on
 * north faces and higher on south faces):
 * <ul>
 *   <li>above it: snow pack on ground gentler than {@link #CLIFF}, bare stone on steeper faces,
 *       and glacier ice (packed, blue at the core) in hollows and on high flats;</li>
 *   <li>below it, down to {@link #TONGUE_REACH}: glacier tongues in valley floors;</li>
 *   <li>below it otherwise: no snow, except in biomes cold enough to snow at sea level.</li>
 * </ul>
 * Slope and hollows come from the real surface, 2 and 12 blocks around (inside the 3x3 chunks a
 * feature may read).
 */
public class AlpineSurfaceFeature extends Feature<NoneFeatureConfiguration> {

    /** Rise per block above which a face stays bare rock (~50 degrees). */
    static final double CLIFF = 1.2;
    /** How far below the snow line glacier tongues reach down valleys. */
    static final int TONGUE_REACH = 120;
    /** Snow line shift on a full north (lower) or south (higher) face. */
    static final double ASPECT = 25.0;
    private static final int R = 12;

    public AlpineSurfaceFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        if (!Alpine.active()) return false;
        WorldGenLevel level = ctx.level();
        int x0 = ctx.origin().getX() & ~15, z0 = ctx.origin().getZ() & ~15;
        int n = 16 + 2 * R;
        int[] h = new int[n * n];
        int max = Integer.MIN_VALUE;
        for (int iz = 0; iz < n; iz++) {
            for (int ix = 0; ix < n; ix++) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0 - R + ix, z0 - R + iz) - 1;
                h[iz * n + ix] = y;
                if (ix >= R && ix < R + 16 && iz >= R && iz < R + 16) max = Math.max(max, y);
            }
        }
        // Most chunks are nowhere near a snow line.
        if (max < Alpine.snowLine(x0 + 8, z0 + 8) - 40 - ASPECT - TONGUE_REACH) return false;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        boolean changed = false;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int ix = lx + R, iz = lz + R;
                int y = h[iz * n + ix];
                int x = x0 + lx, z = z0 + lz;
                double slope = Math.max(Math.abs(h[iz * n + ix + 2] - h[iz * n + ix - 2]),
                        Math.abs(h[(iz + 2) * n + ix] - h[(iz - 2) * n + ix])) / 4.0;
                // Ground rising southwards faces north: shade, so the snow comes lower.
                double north = (h[(iz + 2) * n + ix] - h[(iz - 2) * n + ix]) / 4.0;
                double line = Alpine.snowLine(x, z) - ASPECT * Math.max(-1.0, Math.min(1.0, north / 0.6));
                double hollow = (h[(iz - R) * n + ix] + h[(iz + R) * n + ix] + h[iz * n + ix - R] + h[iz * n + ix + R]
                        + h[(iz - 9) * n + ix - 9] + h[(iz - 9) * n + ix + 9] + h[(iz + 9) * n + ix - 9]
                        + h[(iz + 9) * n + ix + 9]) / 8.0 - y;
                p.set(x, y, z);
                BlockState top = level.getBlockState(p);
                // Leaf piles, bushes and berry plants have collision, so the heightmap stops on them.
                for (int k = 0; k < 3 && debris(level, p, top); k++) {
                    p.setY(--y);
                    top = level.getBlockState(p);
                }
                if (!top.getFluidState().isEmpty()) continue;   // lakes freeze (or not) as vanilla decides
                boolean underTrees = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1 > y;
                if (y >= line) {
                    if ((slope < 0.7 && hollow > 3) || (slope < 0.25 && y >= line + 40)) {
                        ice(level, p, y, hollow > 8 ? 5 : 3, hollow > 8);
                    } else if (slope > CLIFF) {
                        bare(level, p, y);
                    } else if (alpineFlower(level, p, x, y, z, line, slope)) {
                        continue;
                    } else {
                        snowPack(level, p, y, top, slope < 0.5 ? 2 : 1, y >= line + 80 ? 2 : 1);
                    }
                    changed = true;
                } else if (y >= line - TONGUE_REACH && hollow > 6 && slope < 0.9 && !underTrees) {
                    ice(level, p, y, 2, false);
                    changed = true;
                } else if (!coldBiome(level.getBiome(p))) {
                    changed |= clearSnow(level, p, y);
                }
            }
        }
        return changed;
    }

    /**
     * A few flowers keep their patch of grass on gentle ground in the lowest 40 blocks above the
     * line: about a quarter of the columns a flower already grows on (playtest 2026-11-01, "I like a
     * few alpine flowers"). Everything else that grew there goes with the snow.
     */
    private static boolean alpineFlower(WorldGenLevel level, BlockPos.MutableBlockPos p, int x, int y, int z,
                                        double line, double slope) {
        if (slope >= 0.5 || y >= line + 40) return false;
        p.setY(y);
        if (!soil(level, p, level.getBlockState(p))) return false;
        p.setY(y + 1);
        if (!level.getBlockState(p).is(BlockTags.SMALL_FLOWERS)) return false;
        long hash = (x * 73428767L) ^ (z * 912931L);
        hash = hash * hash * 4987142L + hash * 5947611L;
        return ((hash >> 24) & 3) == 0;
    }

    private static boolean coldBiome(Holder<Biome> b) {
        return b.value().getBaseTemperature() < 0.15f;
    }

    /**
     * Natural ground the pass may turn to snow, rock or ice. Beyond vanilla's tags this takes any
     * solid shovel block and any solid, natural-looking pickaxe block, which covers BWG's own ground
     * (overgrown and podzol dacite, lush grass, mossy and rocky stone): playtest 2026-11-01, those
     * stayed green above the line. Structures don't start up here (the structure line).
     */
    private static boolean ground(WorldGenLevel level, BlockPos p, BlockState s) {
        return snowy(s) || soil(level, p, s) || rock(level, p, s) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL);
    }

    private static boolean soil(WorldGenLevel level, BlockPos p, BlockState s) {
        return s.is(BlockTags.DIRT) || (s.is(BlockTags.MINEABLE_WITH_SHOVEL) && !snowy(s) && s.isSolidRender(level, p));
    }

    private static boolean rock(WorldGenLevel level, BlockPos p, BlockState s) {
        if (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.CALCITE) || s.is(Blocks.COBBLESTONE)
                || s.is(Blocks.MOSSY_COBBLESTONE)) return true;
        if (!s.is(BlockTags.MINEABLE_WITH_PICKAXE) || !s.isSolidRender(level, p) || snowy(s)) return false;
        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        for (String made : MADE) if (id.contains(made)) return false;
        return true;
    }

    /** Pickaxe blocks that are built, not natural. */
    private static final String[] MADE = {"brick", "plank", "tile", "polished", "chiseled", "smooth", "cut_", "glass",
            "block", "pillar", "lamp", "ore"};

    /**
     * Small things lying on the ground: grass, flowers, mushrooms, leaf piles, clover, bushes, berry
     * and mint plants (playtest 2026-11-01: they sat on snow). Not trees, fences or walls.
     */
    private static boolean debris(WorldGenLevel level, BlockPos p, BlockState s) {
        if (s.isAir() || !s.getFluidState().isEmpty() || s.is(Blocks.SNOW)) return false;
        if (s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(BlockTags.FENCES) || s.is(BlockTags.WALLS)
                || s.is(BlockTags.SLABS) || s.is(BlockTags.STAIRS)) return false;
        return s.canBeReplaced() || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS)
                || !s.isCollisionShapeFullBlock(level, p);
    }

    private static boolean snowy(BlockState s) {
        return s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.PACKED_ICE) || s.is(Blocks.BLUE_ICE)
                || s.is(Blocks.ICE);
    }

    private static void ice(WorldGenLevel level, BlockPos.MutableBlockPos p, int y, int depth, boolean blueCore) {
        clearAbove(level, p, y);
        clearPlants(level, p, y);
        for (int d = 0; d < depth; d++) {
            p.setY(y - d);
            if (!ground(level, p, level.getBlockState(p))) break;
            level.setBlock(p, (blueCore && d >= 2 ? Blocks.BLUE_ICE : Blocks.PACKED_ICE).defaultBlockState(), 2);
        }
    }

    private static void bare(WorldGenLevel level, BlockPos.MutableBlockPos p, int y) {
        clearAbove(level, p, y);
        clearPlants(level, p, y);
        for (int d = 0; d < 4; d++) {
            p.setY(y - d);
            BlockState s = level.getBlockState(p);
            if (s.is(BlockTags.BASE_STONE_OVERWORLD) || !ground(level, p, s)) break;
            level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
        }
    }

    private static void snowPack(WorldGenLevel level, BlockPos.MutableBlockPos p, int y, BlockState top, int depth, int layers) {
        p.setY(y);
        if (!ground(level, p, top)) return;
        clearPlants(level, p, y);
        for (int d = 0; d < depth; d++) {
            p.setY(y - d);
            if (!ground(level, p, level.getBlockState(p))) break;
            level.setBlock(p, Blocks.SNOW_BLOCK.defaultBlockState(), 2);
        }
        p.setY(y + 1);
        BlockState above = level.getBlockState(p);
        if (above.isAir() || above.is(Blocks.SNOW)) {
            level.setBlock(p, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers), 2);
        }
    }

    /** Below the line in a biome that isn't snowy at sea level: no snow, no ice on the ground. */
    private static boolean clearSnow(WorldGenLevel level, BlockPos.MutableBlockPos p, int y) {
        boolean changed = clearAbove(level, p, y);
        p.setY(y);
        BlockState s = level.getBlockState(p);
        if (s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.PACKED_ICE) || s.is(Blocks.BLUE_ICE)) {
            p.setY(y - 1);
            boolean soil = level.getBlockState(p).is(BlockTags.DIRT);
            p.setY(y);
            level.setBlock(p, (soil ? Blocks.GRASS_BLOCK : Blocks.STONE).defaultBlockState(), 2);
            changed = true;
        }
        return changed;
    }

    private static boolean clearAbove(WorldGenLevel level, BlockPos.MutableBlockPos p, int y) {
        p.setY(y + 1);
        if (level.getBlockState(p).is(Blocks.SNOW)) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            return true;
        }
        return false;
    }

    /**
     * Plants and debris ({@link #debris}) off ground the pass turns to snow, rock or ice. This step
     * runs after vegetation, so they otherwise stood on snow, stone and ice (playtest 2026-11-01).
     * Tall plants go whole; trees (logs, leaves) stay.
     */
    private static void clearPlants(WorldGenLevel level, BlockPos.MutableBlockPos p, int y) {
        for (int d = 1; d <= 3; d++) {
            p.setY(y + d);
            if (!debris(level, p, level.getBlockState(p))) return;
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        }
    }
}
