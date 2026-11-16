package com.terracontinental.atlas;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The World Atlas (user, 2026-11-16): looks like a map, opens the whole world's simulated land with
 * its named continents, and from there an admin can pregenerate one. Admin-only: anyone else who
 * holds one is told so. Given by /continental world-map.
 */
public final class WorldAtlasItem extends Item {

    public WorldAtlasItem() {
        super(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(player instanceof ServerPlayer sp)) return InteractionResultHolder.success(stack);
        open(sp);
        return InteractionResultHolder.consume(stack);
    }

    /** Sends the atlas to an admin's screen (building it first if this world has none yet). */
    public static void open(ServerPlayer sp) {
        var server = sp.getServer();
        if (server == null) return;
        if (!sp.hasPermissions(2)) {
            sp.displayClientMessage(Component.literal("Only an admin can read the World Atlas")
                    .withStyle(ChatFormatting.GRAY), true);
            return;
        }
        if (ContinentAtlas.ifReady(server.overworld()) == null) {
            sp.displayClientMessage(Component.literal("Charting the world... (about 15 s)")
                    .withStyle(ChatFormatting.GRAY), true);
        }
        ContinentAtlas.get(server.overworld()).thenApplyAsync(atlas -> {
            PregenJob job = PregenJob.active();
            return WorldMapPayload.of(atlas, PregenJob.pregenerated(server), job == null ? "" : job.status());
        }, net.minecraft.Util.backgroundExecutor()).whenCompleteAsync((payload, err) -> {
            if (err != null) {
                sp.displayClientMessage(Component.literal("The World Atlas couldn't be drawn: " + err.getMessage())
                        .withStyle(ChatFormatting.RED), false);
                return;
            }
            if (!sp.hasDisconnected()) PacketDistributor.sendToPlayer(sp, payload);
        }, server);
    }
}
