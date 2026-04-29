package com.wnir;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.PlayerEnchantItemEvent;

/**
 * When any warding-column post is enchanted at the enchanting table, transform it into
 * a random DIFFERENT post type. XP and lapis are spent normally.
 *
 * Full post pool: warding_post, repelling_post, teleporter_inhibitor,
 *                 lighting_post, hurt_post, reshaper_post, silencer_post
 */
public final class WardingPostEnchantHandler {

    private WardingPostEnchantHandler() {}

    public static void onEnchantItem(PlayerEnchantItemEvent event) {
        ItemStack enchanted = event.getEnchantedItem();
        Item input = enchanted.getItem();

        BlockItem[] allPosts = {
            WnirRegistries.WARDING_POST_ITEM.get(),
            WnirRegistries.REPELLING_POST_ITEM.get(),
            WnirRegistries.TELEPORTER_INHIBITOR_ITEM.get(),
            WnirRegistries.LIGHTING_POST_ITEM.get(),
            WnirRegistries.HURT_POST_ITEM.get(),
            WnirRegistries.RESHAPER_POST_ITEM.get(),
            WnirRegistries.SILENCER_POST_ITEM.get(),
        };

        // Check if the enchanted item is any post.
        boolean isPost = false;
        for (BlockItem p : allPosts) {
            if (input == p) { isPost = true; break; }
        }
        if (!isPost) return;

        // Build pool excluding the input post.
        List<BlockItem> pool = new ArrayList<>(allPosts.length - 1);
        for (BlockItem p : allPosts) {
            if (p != input) pool.add(p);
        }

        BlockItem chosen = pool.get(event.getEntity().getRandom().nextInt(pool.size()));
        enchanted.setCount(0); // clear slot
        event.getEntity().addItem(new ItemStack(chosen));
    }
}
