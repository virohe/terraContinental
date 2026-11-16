package com.terracontinental.spawn;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * Reads the village town-centre pools as the server actually loaded them and logs whether any
 * village can still roll abandoned (the user, 2026-11-10: a planned village came up as a zombie
 * village). The pool files are overrides, so another mod or datapack could put the zombie centres
 * back; this says so at every start instead of leaving it to a ~2% roll to show in game.
 */
public final class VillagePoolCheck {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String[] TYPES = {"plains", "desert", "savanna", "snowy", "taiga"};

    private VillagePoolCheck() {
    }

    public static void onServerStarted(ServerStartedEvent event) {
        var pools = event.getServer().registryAccess().registryOrThrow(Registries.TEMPLATE_POOL);
        int live = 0, livingZombie = 0, abandoned = 0, abandonedLiving = 0;
        for (String t : TYPES) {
            StructureTemplatePool village = pools.get(ResourceLocation.withDefaultNamespace("village/" + t + "/town_centers"));
            StructureTemplatePool ruin = pools.get(ResourceLocation.fromNamespaceAndPath("terra_continental", "abandoned_village/" + t + "/town_centers"));
            if (village != null) {
                for (StructurePoolElement e : village.getShuffledTemplates(RandomSource.create(0))) {
                    live++;
                    if (e.toString().contains("/zombie/")) {
                        livingZombie++;
                        LOGGER.warn("[village-pools] village/{}/town_centers can roll abandoned: {}", t, e);
                    }
                }
            }
            if (ruin != null) {
                for (StructurePoolElement e : ruin.getShuffledTemplates(RandomSource.create(0))) {
                    abandoned++;
                    if (!e.toString().contains("/zombie/")) abandonedLiving++;
                }
            }
        }
        LOGGER.info("[village-pools] village town centres: {} weighted entries, {} zombie; abandoned town centres: {}, {} not zombie",
                live, livingZombie, abandoned, abandonedLiving);
    }
}
