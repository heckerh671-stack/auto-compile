// Zalard - Copyright (c) 2026 weksom. All rights reserved. Proprietary; see LICENSE.
package dev.zalard.adapter;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Self-cosmetic "drip": holds the fake ItemStack to render for each of your own equipment slots. Set
 * from native via Bridge (T_SET_DRIP); read by the drip render mixins. Blank id = render the real item.
 * Slots: 0 helm, 1 chest, 2 legs, 3 boots, 4 main hand, 5 off hand.
 */
public final class DripService {
    private DripService() {}

    private static final String[] ids = { "", "", "", "", "", "" };
    private static final ItemStack[] cache = {
        ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY };

    public static void set(int slot, String itemId) {
        if (slot < 0 || slot > 5) return;
        String id = itemId == null ? "" : itemId.trim();
        if (id.equals(ids[slot])) return;
        ids[slot] = id;
        cache[slot] = build(id);
    }

    /** Build a 1-count stack for an item id. Resolves by iterating the registry and matching the key,
     *  which avoids the registry get()/getValue() method rename churn across versions (compile-stable). */
    private static ItemStack build(String id) {
        if (id.isEmpty()) return ItemStack.EMPTY;
        try {
            Identifier want = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
            if (want == null) return ItemStack.EMPTY;
            for (Item it : BuiltInRegistries.ITEM) {
                Identifier key = BuiltInRegistries.ITEM.getKey(it);
                if (key != null && key.equals(want)) return new ItemStack(it);
            }
        } catch (Throwable t) { /* bad id -> real item */ }
        return ItemStack.EMPTY;
    }

    /** Fake stack for a slot, or EMPTY to render the real item. */
    public static ItemStack fake(int slot) {
        return slot >= 0 && slot <= 5 ? cache[slot] : ItemStack.EMPTY;
    }
}
