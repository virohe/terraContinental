package com.terracontinental.spawn;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.terracontinental.TerraContinental;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;

import java.util.Optional;

/**
 * terra_continental:spawn_village: places its structure exactly where {@link SpawnVillagePlanner}
 * put a village of {@code village_type} on the spawn continent, and nowhere else. One structure
 * set per village type, so the planner, not a weighted roll, decides each village's type.
 */
public final class SpawnVillagePlacement extends StructurePlacement {

    public static final MapCodec<SpawnVillagePlacement> CODEC = RecordCodecBuilder.mapCodec(instance ->
            placementCodec(instance)
                    .and(Codec.STRING.fieldOf("village_type").forGetter(p -> p.villageType))
                    .apply(instance, SpawnVillagePlacement::new));

    private final String villageType;

    public SpawnVillagePlacement(Vec3i locateOffset, FrequencyReductionMethod method, float frequency, int salt,
                                 Optional<ExclusionZone> exclusionZone, String villageType) {
        super(locateOffset, method, frequency, salt, exclusionZone);
        this.villageType = villageType;
    }

    public String villageType() {
        return villageType;
    }

    @Override
    protected boolean isPlacementChunk(ChunkGeneratorStructureState state, int x, int z) {
        SpawnVillagePlanner.Plan plan = SpawnVillagePlanner.forState(state);
        if (plan == null) return false;
        SpawnVillagePlanner.Site site = plan.siteAt(x, z);
        return site != null && site.type().equals(villageType);
    }

    @Override
    public StructurePlacementType<?> type() {
        return TerraContinental.SPAWN_VILLAGE_PLACEMENT.get();
    }
}
