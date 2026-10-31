package com.terracontinental.mixin.terrain;

import com.terracontinental.config.TerraConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Raises where height starts cooling a biome (vanilla: above y 80, so snow covered every tall
 * mountain from its lower slopes). Absorbed from Tectonic's snow_start_offset (MIT, (c) the Tectonic
 * authors); now TerraConfig's snow_start_offset.
 */
@Mixin(Biome.class)
public abstract class BiomeSnowLineMixin {

    @ModifyVariable(method = "getHeightAdjustedTemperature", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private BlockPos terra_continental$raiseSnowLine(BlockPos pos) {
        return pos == null ? null : pos.below(TerraConfig.SNOW_START_OFFSET.get());
    }
}
