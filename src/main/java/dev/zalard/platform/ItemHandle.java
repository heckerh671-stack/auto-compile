package dev.zalard.platform;

/**
 * Version-abstract inventory item. Enough surface for combat modules
 * to make swap decisions without touching Minecraft classes.
 */
public interface ItemHandle {
    String  itemId();           // "minecraft:totem_of_undying"
    int     count();
    boolean isEmpty();
    int     maxDamage();        // 0 if non-damageable
    int     damage();           // current wear
    int     enchantLevel(String enchantId);   // 0 if absent — "minecraft:breach", "minecraft:density"
}
