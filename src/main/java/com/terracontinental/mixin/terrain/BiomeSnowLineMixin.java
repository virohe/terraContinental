package com.terracontinental.mixin.terrain;

import com.terracontinental.alpine.Alpine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Height starts cooling a biome at the snow line (vanilla: above y 80, so snow covered every tall
 * mountain from its lower slopes; Tectonic, absorbed, raised that to a fixed y 336). The line is the
 * alpine field ({@link Alpine}: y ~500 and wandering), so snowfall, the peak biomes and the surface
 * pass agree. Outside a TC overworld it stays at y 336.
 */
@Mixin(Biome.class)
public abstract class BiomeSnowLineMixin {

    @ModifyVariable(method = "getHeightAdjustedTemperature", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private BlockPos terra_continental$raiseSnowLine(BlockPos pos) {
        if (pos == null) return null;
        double line = Alpine.active() ? Alpine.snowLine(pos.getX(), pos.getZ()) : 336.0;
        return pos.below((int) Math.round(line) - 80);
    }
}
