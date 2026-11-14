package dev.zalard.platform;

// inventory + item queries.
public interface McPlatformExt {

    default ItemHandle slot(int inventoryIndex)                   { throw unsupported(); }
    default ItemHandle hotbarSlot(int hotbarIdx)                  { throw unsupported(); }
    default ItemHandle mainHand()                                 { throw unsupported(); }
    default ItemHandle offHand()                                  { throw unsupported(); }

    default int findHotbar(String itemId)                         { throw unsupported(); }
    default int findInventory(String itemId)                      { throw unsupported(); }

    default void swapToHotbar(int invSlot, int hotbar)            { throw unsupported(); }
    /** True if the player has a container GUI open — AutoTotem must skip its swap in this state
     *  or it'll click into the open container's inventory instead of the player's. */
    default boolean hasOpenContainer()                            { throw unsupported(); }

    default boolean attackReady()                                 { throw unsupported(); }
    default float   attackStrength()                              { throw unsupported(); }
    default float   attackReach()                                 { throw unsupported(); }
    /** Player's current fall distance in blocks. Mace needs this. */
    default float   selfFallDistance()                            { throw unsupported(); }
    /** Current Options.sensitivity() value (0..1). Required for correct GCD snap. */
    default float   realSensitivity()                             { throw unsupported(); }
    /** Current motion vector of the local player (dx/dy/dz per tick). Mace slam needs it. */
    default dev.zalard.platform.McPlatform.Vec3d selfVelocity() { throw unsupported(); }
    /** Sends ServerboundPlayerActionPacket.SWAP_ITEM_WITH_OFFHAND — the F-key swap.
     *  Zero inventory interaction, works while sprinting, no ContainerClick trace.
     *  This is the AC-safe path AutoTotem relies on. */
    default void swapOffhand() { throw unsupported(); }
    /** Server-side move speed sq (per-tick). AutoTotem skips the F-swap if sprinting faster
     *  than Grim 2.x BadPacketsL's threshold during the tick we swap. */
    default double selfHorizontalSpeedSq() { throw unsupported(); }
    /** Shift-click (QUICK_MOVE) on an inventory slot via the player's own menu.
     *  Slots: 0-8 hotbar, 9-35 main inventory, 5 helmet, 6 chest, 7 legs,
     *  8 boots (via player.inventoryMenu addressing). */
    default void quickMoveSlot(int slot) { throw unsupported(); }
    /** Chest-armor item (slot 38 / inventoryMenu slot 6). */
    default ItemHandle armorChest() { throw unsupported(); }
    /** Wall-clock ms of the last attack packet we sent. Used by AutoElytra to
     *  detect "hit landed recently" and swap back. */
    default long lastAttackTickMs() { throw unsupported(); }
    /** Right-click use for the current main-hand item (throws splash potion,
     *  eats food, draws bow, etc.). Vanilla ServerboundUseItemPacket. */
    default void sendUseItem(boolean mainHand) { throw unsupported(); }
    /** Base potion type at a hotbar slot ("healing", "strong_healing",
     *  "regeneration", "long_regeneration", "fire_resistance", etc.), or null.
     *  AutoPot scores potions with this; returns null → scored as "generic". */
    default String potionTypeAt(int hotbarSlot) { throw unsupported(); }
    /** Trigger a vanilla jump via LivingEntity.jumpFromGround. Movement Reset / Jump Reset uses this. */
    default void selfJump() { throw unsupported(); }
    /** Set the client's forward-key held state. W-Tap toggles it off for one tick. */
    default void setForwardKey(boolean down) { throw unsupported(); }
    /** Puts the forward key back to whatever the real keyboard says (never presses W for you). */
    default void restoreForwardKey() { throw unsupported(); }
    /** Hold / release the sprint key (Sprint). */
    default void setSprintKey(boolean down) { throw unsupported(); }
    default void restoreSprintKey() { throw unsupported(); }
    /** Freecam on/off; speed in blocks per tick. */
    default void setFreecam(boolean on, double speed) { throw unsupported(); }
    /** Hold / release the sneak key (Safe Walk, Auto Bridge). */
    default void setSneakKey(boolean down) { throw unsupported(); }
    /** Sneak key back to what the real keyboard says. */
    default void restoreSneakKey() { throw unsupported(); }
    /** Set the client's back-key (S) held state. S-Tap holds it down briefly. */
    default void setBackKey(boolean down) { throw unsupported(); }
    /** Puts the back key back to whatever the real keyboard says. */
    default void restoreBackKey() { throw unsupported(); }
    /** True while you're physically holding the forward key (W), whatever Mako did to the binding. */
    default boolean forwardHeld() { return false; }
    /** Ticks left on your own hurt animation (10 right after being hit, 0 when not hurt); -1 if unknown. */
    default int selfHurtTime() { return -1; }
    /** Your ping as the tab list shows it to everyone (ms), or -1. */
    default int selfTabPing() { return -1; }
    /** An online player's already-loaded skin: {uuid, texture id, "slim"/"wide"}, or null. */
    default String[] onlineSkin(String name) { return null; }
    /** Takes raw PNG bytes, loads them as a NativeImage, registers under a
     *  stable ResourceLocation keyed by `uuid`, and caches the location in
     *  SpoofRegistry so platform mixins can resolve it at render time. */
    default void registerSkinTexture(java.util.UUID uuid, byte[] png) { throw unsupported(); }
    /** A spoofed skin's texture went missing: load it again from the PNG we downloaded (if it was ours). */
    default void repairSkinTexture(java.util.UUID skinOwner, String path) {}
    /** Cape Spoof: load a cape PNG as a texture and point your cape at it. */
    default void registerCapeTexture(String hash, byte[] png) {}
    /** Fake Elytra — set SharedFlag(7) locally without syncing to the server.
     *  Only your own client sees the pose change; nothing on the wire changes. */
    default void setLocalGliding(boolean on) { throw unsupported(); }
    /** True if the local player is currently fall-flying (gliding). */
    default boolean selfIsGliding() { throw unsupported(); }
    /** Are you sprinting (client state; the server learns changes with the next movement tick). */
    default boolean selfSprinting() { return false; }
    /** Stop sprinting now; vanilla tells the server with this tick's movement. */
    default void selfStopSprint() {}
    /** Your real jump key (whatever it's bound to) is held. */
    default boolean jumpKeyDown() { return false; }
    /** Entity id of the end crystal your crosshair is on, or -1. */
    default int crosshairCrystalId() { return -1; }
    /** Let go of the item you're using (bow: shoots now), like releasing right click. */
    default void releaseUseItem() {}
    /** Right click with the item in hand (in the air, not on a block). */
    default void useItemInHand(boolean mainHand) {}
    /** Ticks you've been using (drawing / eating) the current item. */
    default int usingTicks() { return 0; }
    /** Takes the right clicks vanilla hasn't handled yet, so vanilla won't act on them. Returns how many. */
    default int consumeUseClicks() { return 0; }
    /** Right clicks waiting for vanilla this tick (not consumed). */
    default int pendingUseClicks() { return 0; }
    /** Is the crossbow in this hotbar slot loaded? */
    default boolean crossbowCharged(int hotbarSlot) { return false; }
    /** Presses that hotbar number key once (vanilla switches the slot when it reads keys this tick). */
    default void pressHotbarKey(int slot) {}
    /** One right click, read by vanilla this tick (uses whatever your crosshair is on, like a real click). */
    default void clickUseKey() {}
    /** Presses your "swap item with offhand" key (F) once. */
    default void pressSwapOffhandKey() {}
    /** Holds / lets go of the use key (right mouse); restoreUseKey = back to your real mouse button. */
    default void setUseKey(boolean down) {}
    default void restoreUseKey() {}
    /** Vanilla would accept a left click with the item in hand right now (its minimum charge is met). */
    default boolean canAttackNow() { return true; }
    /** Your hunger bar (0-20). */
    default int selfFood() { return 20; }
    /** Sets your own motion (client prediction; the server sees it as your normal movement). */
    default void setSelfVelocity(double x, double y, double z) {}
    /** Your real movement keys: {forward, back, left, right, jump, sneak}. */
    default boolean[] moveKeys() { return new boolean[6]; }
    /** Vanilla's wait (ticks) before a held right click repeats; Fast Place lowers it. */
    default int rightClickDelay() { return 0; }
    default void setRightClickDelay(int ticks) {}
    /** The item in your main hand places a block. */
    default boolean holdingBlock() { return false; }
    /** Standing / swimming in water. */
    default boolean selfInWater() { return false; }
    /** Turns the real camera (what you see), unlike setSilentRotation which only changes packets. */
    default void setSelfRotation(float yaw, float pitch) { throw unsupported(); }
    /** End crystals within `radius` of the player's eyes: {entityId, x, y, z} each. */
    default java.util.List<double[]> crystalsNear(double radius) { throw unsupported(); }
    /** Attack any entity by network id (crystals aren't LivingEntity, so sendAttack can't reach them). */
    default void attackEntityId(int entityId) { throw unsupported(); }
    /** True while a GLFW key is held in-game (false while any screen is open). */
    default boolean isKeyDown(int glfwKey) { throw unsupported(); }
    /** Swimming, in water/lava, riding or gliding: movement that follows the look direction. */
    default boolean selfLookSteered() { return false; }
    /** Opens your own inventory screen, exactly like pressing E. */
    default void openInventory() { throw unsupported(); }
    /** Closes the open screen/container, exactly like pressing Esc. */
    default void closeScreen() { throw unsupported(); }
    /** True while the use (right-click) key is held in-game. */
    default boolean useKeyDown() { return false; }
    /** Block under the crosshair: {x, y, z, face (McPlatform.Direction ordinal), hitX, hitY, hitZ}, or null. */
    default double[] crosshairBlockHit() { return null; }
    /** Block hit by a ray from your eyes along yaw/pitch: same format as crosshairBlockHit, or null. */
    default double[] raycastBlock(float yaw, float pitch, double reach) { return null; }
    /** True if the hotbar item is a full, non-falling block you can bridge with. */
    default boolean isBridgeBlock(int hotbarSlot) { return false; }
    /** Charge (0-4) of the respawn anchor at x,y,z, or -1 if there's no anchor there. */
    default int anchorCharge(int x, int y, int z) { return -1; }
    /** Eats one pending attack-key click so vanilla doesn't also process it. */
    default void consumeAttackClick() { throw unsupported(); }

    default float partialTick()                                   { throw unsupported(); }
    default long  worldTime()                                     { throw unsupported(); }

    /** Living entity hit by a ray from your eyes along yaw/pitch, within reach of its hitbox, not behind blocks. */
    default dev.zalard.platform.EntityHandle raycastEntity(float yaw, float pitch, double reach) { return null; }

    /** True while your own inventory screen (E) is open. */
    default boolean inventoryScreenOpen() { return false; }
    /** Window cursor position {x, y} of the centre of inventory slot `invIndex` (0-8 hotbar, 9-35 main), or null. */
    default double[] inventorySlotCursorPos(int invIndex) { return null; }
    default double[] cursorPos() { return null; }
    default void setCursorPos(double x, double y) {}
    /** Same as hovering a slot in the inventory and pressing the swap-offhand key. */
    default void swapSlotWithOffhand(int invIndex) {}

    /** Presses the attack key once, exactly like a real click (vanilla handles it this tick). */
    default void clickAttackKey() {}
    /** Sends the modded "spear lunge" STAB player-action packet (no-op on a build/server without it). */
    default void sendSpearStab() {}
    /** Attack-key clicks vanilla hasn't handled yet. */
    default int pendingAttackClicks() { return 0; }

    /** True while any screen (chat, inventory, GUI) is open. */
    default boolean screenOpen()                                  { return false; }
    /** True while eating, drinking, blocking or drawing a bow. */
    default boolean selfUsingItem()                               { return false; }

    default int screenWidth()                                     { throw unsupported(); }
    default int screenHeight()                                    { throw unsupported(); }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("McPlatformExt method not implemented in this adapter yet");
    }
}
