package com.terracontinental.mixin.alpine;

import com.terracontinental.alpine.Alpine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The tree line: worldgen trees don't grow above it (playtest 2026-10-31: trees covered 13-16% of
 * the ground at y ~900). Vanilla's tree feature, Oh The Trees You'll Grow's NBT trees (all of
 * BWG's) and Cobblemon's apricorn trees (playtest 2026-11-01: they stood on the snow); a random
 * selector of trees reaches here once per tree. Saplings grow anywhere: only
 * placements during world generation (a WorldGenRegion) are checked.
 */
@Mixin(ConfiguredFeature.class)
public abstract class TreeLineMixin {

    private static final ResourceLocation TERRA_CONTINENTAL$NBT_TREE =
            ResourceLocation.fromNamespaceAndPath("ohthetreesyoullgrow", "tree_from_nbt_v1");
    private static final ResourceLocation TERRA_CONTINENTAL$APRICORN =
            ResourceLocation.fromNamespaceAndPath("cobblemon", "apricorn_tree");

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void terra_continental$treeLine(WorldGenLevel level, ChunkGenerator generator, RandomSource random,
                                            BlockPos origin, CallbackInfoReturnable<Boolean> cir) {
        if (!(level instanceof WorldGenRegion) || !Alpine.active()) return;
        Feature<?> f = ((ConfiguredFeature<?, ?>) (Object) this).feature();
        if (!(f instanceof TreeFeature)) {
            ResourceLocation id = BuiltInRegistries.FEATURE.getKey(f);
            if (!TERRA_CONTINENTAL$NBT_TREE.equals(id) && !TERRA_CONTINENTAL$APRICORN.equals(id)) return;
        }
        if (!Alpine.treeAllowed(origin.getX(), origin.getY(), origin.getZ())) cir.setReturnValue(false);
    }
}
