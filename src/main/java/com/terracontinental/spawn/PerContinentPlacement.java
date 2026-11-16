package com.terracontinental.spawn;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.terracontinental.TerraContinental;
import com.terracontinental.density.RadialFalloffFunction;
import com.terracontinental.plan.WorldPlan;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * terra_continental:per_continent: {@code min_count}-{@code max_count} sites on every continent,
 * each on a biome in {@code biomes}, on fairly level ground and clear of the planned villages.
 * For the settlements nobody lives in (the user, 2026-11-10: abandoned villages, BWG's Forgotten
 * villages and Pumpkin Patches should still turn up, but rarely: one or two of each per continent).
 * A random-spread grid can't promise that: its count follows the continent's size and the biome's
 * share of it, so Pumpkin Patches were as common as villages.
 *
 * <p>Continents are those of the world's radial falloff ({@link RadialFalloffFunction#continents});
 * the spawn continent is the village planner's land, each other continent the ground proportionally
 * nearest its centre (as /continental villages divides them). Same seed, same sites.</p>
 */
public final class PerContinentPlacement extends StructurePlacement {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final MapCodec<PerContinentPlacement> CODEC = RecordCodecBuilder.mapCodec(instance ->
            placementCodec(instance)
                    .and(RegistryCodecs.homogeneousList(Registries.BIOME).fieldOf("biomes").forGetter(p -> p.biomes))
                    .and(Codec.intRange(0, 16).optionalFieldOf("min_count", 1).forGetter(p -> p.minCount))
                    .and(Codec.intRange(0, 16).optionalFieldOf("max_count", 2).forGetter(p -> p.maxCount))
                    .apply(instance, PerContinentPlacement::new));

    /** Kept this far from a planned village, so a ruin never reads as part of one. */
    private static final int VILLAGE_CLEARANCE = 1200;
    /** On the spawn continent, kept this far inland: its coast is where the spawn hamlets go. */
    private static final int COAST_CLEARANCE = 640;
    /** Two sites of one placement on a continent stay this far apart. */
    private static final int SITE_GAP = 2500;
    /** Random spots tried per continent before it settles for fewer sites. */
    private static final int ATTEMPTS = 4000;
    /** As /continental villages: land belongs to a continent out to this many of its radii. */
    private static final double MARGIN = 2.0;

    private final HolderSet<Biome> biomes;
    private final int minCount;
    private final int maxCount;

    private volatile Set<Long> sites;
    private volatile Object sitesFor;

    public PerContinentPlacement(Vec3i locateOffset, FrequencyReductionMethod method, float frequency, int salt,
                                 Optional<ExclusionZone> exclusionZone, HolderSet<Biome> biomes, int minCount, int maxCount) {
        super(locateOffset, method, frequency, salt, exclusionZone);
        this.biomes = biomes;
        this.minCount = minCount;
        this.maxCount = Math.max(minCount, maxCount);
    }

    @Override
    protected boolean isPlacementChunk(ChunkGeneratorStructureState state, int x, int z) {
        Set<Long> s = sitesFor(state);
        return s != null && s.contains(ChunkPos.asLong(x, z));
    }

    /** This placement's site chunks in the overworld, or null where it doesn't apply. */
    @Nullable
    public Set<Long> sitesFor(ChunkGeneratorStructureState state) {
        if (sitesFor == state) return sites;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        ServerLevel overworld = server.overworld();
        if (overworld == null || overworld.getChunkSource().getGeneratorState() != state) return null;
        synchronized (this) {
            if (sitesFor != state) {
                Set<Long> s;
                try {
                    s = compute(overworld, state);
                } catch (Throwable e) {
                    LOGGER.error("[rare-sites] placing salt {} failed; none of these will generate", salt(), e);
                    s = Set.of();
                }
                sites = s;
                sitesFor = state;
            }
            return sites;
        }
    }

    private Set<Long> compute(ServerLevel level, ChunkGeneratorStructureState state) {
        long t0 = System.nanoTime();
        RadialFalloffFunction falloff = falloff(level);
        if (falloff == null) return Set.of();
        double[][] continents = falloff.continents();
        SpawnVillagePlanner.Plan plan = SpawnVillagePlanner.forState(state);
        DensityFunction ground = WorldPlan.get(level) != null ? WorldPlan.groundFunction(level) : null;
        var gen = level.getChunkSource().getGenerator();
        BiomeSource bs = gen.getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        int sea = gen.getSeaLevel();
        double spawnReach = 2.2 * Math.max(continents[0][2], centralRadius());

        List<Structure> structures = structuresOf(level);
        int tried = 0;
        Random rng = new Random(level.getSeed() * 0x9E3779B97F4A7C15L + salt());
        Set<Long> out = new HashSet<>();
        StringBuilder log = new StringBuilder();
        for (int ci = 0; ci < continents.length; ci++) {
            double[] c = continents[ci];
            boolean spawn = ci == 0;
            int want = minCount + rng.nextInt(maxCount - minCount + 1);
            List<int[]> placed = new ArrayList<>();
            for (int a = 0; a < ATTEMPTS && placed.size() < want; a++) {
                int x, z;
                if (spawn) {
                    x = (int) ((rng.nextDouble() * 2 - 1) * spawnReach);
                    z = (int) ((rng.nextDouble() * 2 - 1) * spawnReach);
                } else {
                    double r = c[2] * MARGIN * Math.sqrt(rng.nextDouble()), ang = rng.nextDouble() * 2 * Math.PI;
                    x = (int) (c[0] + r * Math.cos(ang));
                    z = (int) (c[1] + r * Math.sin(ang));
                }
                if (!onContinent(ci, x, z, continents, plan)) continue;
                if (plan != null && nearPlanned(plan, x, z)) continue;
                if (tooClose(placed, x, z)) continue;
                int y = siteHeight(ground, sea, x, z);
                if (y == Integer.MIN_VALUE) continue;
                if (!biomes.contains(bs.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), sampler))) continue;
                if (!startsHere(level, structures, new ChunkPos(x >> 4, z >> 4))) { tried++; continue; }
                placed.add(new int[]{x, z});
            }
            for (int[] p : placed) {
                ChunkPos cp = new ChunkPos(p[0] >> 4, p[1] >> 4);
                out.add(cp.toLong());
                log.append(String.format(" [%s %d: %d, %d]", spawn ? "spawn" : "continent", ci, cp.getMiddleBlockX(), cp.getMiddleBlockZ()));
            }
        }
        LOGGER.info("[rare-sites] salt {}: {} sites on {} continents ({} spots refused by the structure) in {} ms:{}", salt(),
                out.size(), continents.length, tried, (System.nanoTime() - t0) / 1_000_000L, log);
        return out;
    }

    /** The structures of the set this placement belongs to. */
    private List<Structure> structuresOf(ServerLevel level) {
        List<Structure> out = new ArrayList<>();
        for (StructureSet set : level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET)) {
            if (set.placement() != this) continue;
            for (StructureSet.StructureSelectionEntry e : set.structures()) out.add(e.structure().value());
        }
        return out;
    }

    /**
     * Would one of the set's structures start in this chunk? The game's own check, as the village
     * planner does: the biome under the real surface can differ from the one read at the estimated
     * height (rig 2026-11-10: the spawn continent's Pumpkin Patch site passed the estimate and /locate
     * then refused it), and a site that starts nothing would leave the continent short.
     */
    private static boolean startsHere(ServerLevel level, List<Structure> structures, ChunkPos chunk) {
        if (structures.isEmpty()) return true;
        var src = level.getChunkSource();
        for (Structure s : structures) {
            Structure.GenerationContext ctx = new Structure.GenerationContext(level.registryAccess(), src.getGenerator(),
                    src.getGenerator().getBiomeSource(), src.randomState(), level.getServer().getStructureManager(),
                    level.getSeed(), chunk, level, s.biomes()::contains);
            if (s.findValidGenerationPoint(ctx).isPresent()) return true;
        }
        return false;
    }

    /** Is the spot on continent {@code ci}? The planner's land for the spawn continent, else nearest by radii. */
    private static boolean onContinent(int ci, int x, int z, double[][] continents, @Nullable SpawnVillagePlanner.Plan plan) {
        if (plan != null) {
            boolean spawnLand = plan.onContinent(x, z);
            if (ci == 0) return spawnLand && inland(plan, x, z);
            if (spawnLand) return false;
        }
        int best = -1;
        double bestRatio = MARGIN;
        for (int i = plan != null ? 1 : 0; i < continents.length; i++) {
            double ratio = Math.hypot(x - continents[i][0], z - continents[i][1]) / continents[i][2];
            if (ratio < bestRatio) {
                bestRatio = ratio;
                best = i;
            }
        }
        return best == ci;
    }

    /**
     * Land all round out to {@link #COAST_CLEARANCE}: Terra Towns puts the spawn hamlets on this
     * continent's coast after these sites are fixed (rig 2026-11-10: a Pumpkin Patch 200 blocks from
     * a spawn hamlet).
     */
    private static boolean inland(SpawnVillagePlanner.Plan plan, int x, int z) {
        for (int i = 0; i < 16; i++) {
            double a = Math.PI * i / 8;
            if (!plan.onContinent(x + (int) (COAST_CLEARANCE * Math.cos(a)), z + (int) (COAST_CLEARANCE * Math.sin(a)))) return false;
        }
        return true;
    }

    private static boolean nearPlanned(SpawnVillagePlanner.Plan plan, int x, int z) {
        for (SpawnVillagePlanner.Site s : plan.sites.values()) {
            if (Math.hypot(s.chunk().getMiddleBlockX() - x, s.chunk().getMiddleBlockZ() - z) < VILLAGE_CLEARANCE) return true;
        }
        return false;
    }

    private static boolean tooClose(List<int[]> placed, int x, int z) {
        for (int[] p : placed) if (Math.hypot(p[0] - x, p[1] - z) < SITE_GAP) return true;
        return false;
    }

    /**
     * The ground height at the site's middle, or MIN_VALUE where the ground doesn't suit a village
     * ({@link VillageFootprint}: these are villages too). Without the world plan's ground function
     * (not Terra Continental's terrain) the biome is read at sea level and the ground isn't checked.
     */
    private static int siteHeight(@Nullable DensityFunction ground, int sea, int x, int z) {
        if (ground == null) return sea + 1;
        var height = VillageFootprint.groundHeight(ground, sea);
        int mx = (x & ~15) + 8, mz = (z & ~15) + 8;
        if (VillageFootprint.check(height, sea, mx, mz, VillageFootprint.maxRelief()) != VillageFootprint.OK) return Integer.MIN_VALUE;
        return height.applyAsInt(mx, mz);
    }

    @Nullable
    private static RadialFalloffFunction falloff(ServerLevel level) {
        RadialFalloffFunction[] found = new RadialFalloffFunction[1];
        level.getChunkSource().randomState().router().mapAll(new DensityFunction.Visitor() {
            @Override
            public DensityFunction apply(DensityFunction df) {
                if (found[0] == null && df instanceof RadialFalloffFunction r) found[0] = r;
                return df;
            }
        });
        return found[0];
    }

    private static int centralRadius() {
        try {
            if (com.terracontinental.config.TerraConfig.SPEC.isLoaded()) return com.terracontinental.config.TerraConfig.CENTRAL_LAND_RADIUS.get();
        } catch (Throwable ignored) {
        }
        return 14500;
    }

    @Override
    public StructurePlacementType<?> type() {
        return TerraContinental.PER_CONTINENT_PLACEMENT.get();
    }
}
