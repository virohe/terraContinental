package com.terracontinental.terrain;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.terracontinental.TerraContinental;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Ore attempts scaled to the height between the world floor and max_y, so a 512-deep world
 * keeps vanilla's ore density per section instead of spreading vanilla's count thin.
 * Registered as terra_continental:height_stabilized_count; Tectonic's ore fix, absorbed
 * (MIT, (c) the Tectonic authors).
 */
public class HeightStabilizedCount extends PlacementModifier {

    public static final MapCodec<HeightStabilizedCount> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            VerticalAnchor.CODEC.fieldOf("max_y").forGetter(c -> c.maxY),
            ExtraCodecs.POSITIVE_FLOAT.fieldOf("count_per_section").forGetter(c -> c.countPerSection),
            Codec.BOOL.fieldOf("biased_to_bottom").forGetter(c -> c.biasedToBottom)
    ).apply(i, HeightStabilizedCount::new));

    private final VerticalAnchor maxY;
    private final float countPerSection;
    private final boolean biasedToBottom;

    public HeightStabilizedCount(VerticalAnchor maxY, float countPerSection, boolean biasedToBottom) {
        this.maxY = maxY;
        this.countPerSection = countPerSection;
        this.biasedToBottom = biasedToBottom;
    }

    @Override
    public Stream<BlockPos> getPositions(PlacementContext context, RandomSource random, BlockPos pos) {
        int top = maxY.resolveY(context);
        int bottom = context.getMinGenY();
        int span = top - bottom;
        int count = (int) Math.ceil((double) ((float) span / 16.0F * countPerSection));
        List<BlockPos> positions = new ArrayList<>(count);
        for (int n = 0; n < count; n++) {
            // Same draw order as Tectonic, so ores land where they did under it.
            int y = biasedToBottom
                    ? bottom + random.nextInt(random.nextIntBetweenInclusive(span / 2, span))
                    : random.nextIntBetweenInclusive(bottom, top);
            positions.add(pos.atY(y));
        }
        return positions.stream();
    }

    @Override
    public PlacementModifierType<?> type() {
        return TerraContinental.HEIGHT_STABILIZED_COUNT.get();
    }
}
