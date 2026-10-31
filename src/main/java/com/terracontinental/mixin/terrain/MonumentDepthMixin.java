package com.terracontinental.mixin.terrain;

import com.terracontinental.terrain.TerrainBase;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Sinks ocean monuments to suit the deeper ocean floor. Absorbed from Tectonic's
 * oceans.monument_offset (MIT, (c) the Tectonic authors).
 */
@Mixin(StructurePiece.class)
public abstract class MonumentDepthMixin {

    @ModifyVariable(
            method = "<init>(Lnet/minecraft/world/level/levelgen/structure/pieces/StructurePieceType;ILnet/minecraft/world/level/levelgen/structure/BoundingBox;)V",
            at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private static BoundingBox terra_continental$sinkMonument(BoundingBox box, StructurePieceType type) {
        return type == StructurePieceType.OCEAN_MONUMENT_BUILDING ? box.moved(0, TerrainBase.MONUMENT_OFFSET, 0) : box;
    }
}
