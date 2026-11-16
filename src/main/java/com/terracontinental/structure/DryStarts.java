package com.terracontinental.structure;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import com.terracontinental.config.TerraConfig;
import com.terracontinental.plan.WorldPlan;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Land structures don't start under water. Biomes come from the smoothed continentalness while the
 * ground also takes the ocean noise, the wetland regions and the 3-D noise, so low land near a coast
 * can be a land biome over a few blocks of sea. A surface structure whose biomes are all land
 * (no ocean, beach, river or swamp biome) is refused where its start column's ground is more than
 * {@code dry_start_max_water} blocks under sea level. Water structures (shipwrecks, ocean ruins,
 * monuments, swamp huts) are untouched.
 *
 * <p>The ground comes from the plan's estimate (microseconds); only where that lands within a few
 * blocks of the water line does it ask the generator for the exact ocean-floor height.
 */
public final class DryStarts {

    private static final TagKey<Biome> C_SWAMP = TagKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath("c", "is_swamp"));
    private static final String[] WATER_WORDS = {"ocean", "beach", "river", "swamp", "marsh", "mangrove",
            "bog", "fen", "lake", "shore", "coast", "wetland", "stacks", "sea"};
    /** Estimates this far above the refusal depth are trusted without the exact check. */
    private static final int EXACT_MARGIN = 4;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int LOG_LIMIT = 40;
    private static final AtomicInteger LOGGED = new AtomicInteger();

    @Nullable
    private static volatile DensityFunction ground;
    private static final Map<Structure, Boolean> LAND_ONLY = new WeakHashMap<>();

    private DryStarts() {
    }

    /** Called as the overworld loads; worlds without the plan's terrain are left alone. */
    public static void load(ServerLevel level) {
        ground = WorldPlan.get(level) == null ? null : WorldPlan.groundFunction(level);
    }

    /** False when a land-only structure would start with its ground under water. */
    public static boolean allowed(Structure s, Structure.GenerationContext ctx, int x, int z) {
        DensityFunction g = ground;
        int maxWater = TerraConfig.DRY_START_MAX_WATER.get();
        if (g == null || maxWater < 0 || !landOnly(s)) return true;
        int sea = ctx.chunkGenerator().getSeaLevel();
        int limit = sea - 1 - maxWater; // lowest allowed top ground block (the top water block is sea - 1)
        int est = (int) Math.round(128.0 * g.compute(new DensityFunction.SinglePointContext(x, 0, z)));
        if (est >= limit + EXACT_MARGIN) return true;
        // getBaseHeight(OCEAN_FLOOR_WG) is the first free block above the ground.
        int floor = ctx.chunkGenerator().getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG,
                ctx.heightAccessor(), ctx.randomState()) - 1;
        if (floor >= limit) return true;
        if (LOGGED.getAndIncrement() < LOG_LIMIT) {
            String id = ctx.registryAccess().registryOrThrow(Registries.STRUCTURE).getResourceKey(s)
                    .map(k -> k.location().toString()).orElse("?");
            LOGGER.info("[dry-starts] refused {} at {} {}: ground y {}, sea {}", id, x, z, floor, sea);
        }
        return false;
    }

    private static boolean landOnly(Structure s) {
        synchronized (LAND_ONLY) {
            Boolean b = LAND_ONLY.get(s);
            if (b != null) return b;
        }
        boolean land = true;
        for (Holder<Biome> h : s.biomes()) {
            if (wet(h)) {
                land = false;
                break;
            }
        }
        synchronized (LAND_ONLY) {
            LAND_ONLY.put(s, land);
        }
        return land;
    }

    private static boolean wet(Holder<Biome> h) {
        if (h.is(BiomeTags.IS_OCEAN) || h.is(BiomeTags.IS_BEACH) || h.is(BiomeTags.IS_RIVER) || h.is(C_SWAMP)) {
            return true;
        }
        String path = h.unwrapKey().map(k -> k.location().getPath()).orElse("");
        for (String w : WATER_WORDS) {
            if (path.contains(w)) return true;
        }
        return false;
    }
}
