package dev.zalard.adapter;

import dev.zalard.platform.ItemHandle;
import dev.zalard.platform.McPlatformExt;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

public final class PlatformExtImpl implements McPlatformExt {

    private final Minecraft mc = Minecraft.getInstance();

    @Override public ItemHandle slot(int inventoryIndex) {
        if (mc.player == null) return EMPTY;
        return wrap(mc.player.getInventory().getItem(inventoryIndex));
    }
    @Override public ItemHandle hotbarSlot(int hotbarIdx) {
        if (mc.player == null || hotbarIdx < 0 || hotbarIdx > 8) return EMPTY;
        return wrap(mc.player.getInventory().getItem(hotbarIdx));
    }
    @Override public ItemHandle mainHand() { return mc.player == null ? EMPTY : wrap(mc.player.getMainHandItem()); }
    @Override public ItemHandle offHand()  { return mc.player == null ? EMPTY : wrap(mc.player.getOffhandItem()); }

    @Override public int findHotbar(String itemId) {
        if (mc.player == null) return -1;
        var want = Identifier.tryParse(itemId);
        if (want == null) return -1;
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            var s = inv.getItem(i);
            if (s.isEmpty()) continue;
            if (BuiltInRegistries.ITEM.getKey(s.getItem()).equals(want)) return i;
        }
        return -1;
    }
    @Override public int findInventory(String itemId) {
        if (mc.player == null) return -1;
        var want = Identifier.tryParse(itemId);
        if (want == null) return -1;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var s = inv.getItem(i);
            if (s.isEmpty()) continue;
            if (BuiltInRegistries.ITEM.getKey(s.getItem()).equals(want)) return i;
        }
        return -1;
    }
    @Override public void swapToHotbar(int invSlot, int hotbar) {
        if (mc.player == null || mc.gameMode == null) return;
        // A ServerboundContainerClickPacket is only legal while the matching container GUI is open —
        // a vanilla client never sends one with no inventory open. Strict servers (2b2t's anti-dupe
        // check) kick for exactly that, so skip rather than emit the illegal click. To move items,
        // open the inventory first (Totem's Cursor Sim / Open Inventory mode); a silent no-GUI swap
        // can't be done without a bad packet.
        if (!(mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen)) return;
        int containerId = mc.player.inventoryMenu.containerId;
        int containerSlot = (invSlot == 40) ? 45
                : (invSlot >= 0 && invSlot <= 8) ? 36 + invSlot
                : invSlot;
        mc.gameMode.handleInventoryMouseClick(containerId, containerSlot, hotbar, ClickType.SWAP, mc.player);
    }

    @Override public void swapOffhand() {
        // a press of your F key: vanilla sends the swap itself when it reads the key
        if (mc.options == null) return;
        var acc = (dev.zalard.adapter.mixin.KeyMappingAccessor) mc.options.keySwapOffhand;
        acc.zalard$setClickCount(acc.zalard$getClickCount() + 1);
    }
    @Override public boolean forwardHeld() {
        return mc.options != null && physicalDown(mc, mc.options.keyUp);
    }

    @Override public int selfTabPing() {
        var conn = mc.getConnection();
        if (conn == null || mc.player == null) return -1;
        var info = conn.getPlayerInfo(mc.player.getUUID());
        return info == null ? -1 : info.getLatency();
    }

    @Override public String[] onlineSkin(String name) {
        var conn = mc.getConnection();
        if (conn == null || name == null || name.isBlank()) return null;
        for (var info : conn.getOnlinePlayers()) {
            var gp = info.getProfile();
            if (gp == null || !name.equalsIgnoreCase(gp.name())) continue;
            var skin = info.getSkin();
            if (skin == null || skin.body() == null) return null;
            String path = skin.body().texturePath().toString();
            // still the default Steve/Alex: their real skin isn't loaded (or they have none)
            if (path.startsWith("minecraft:textures/entity/player/")) return null;
            // only if that texture is really loaded (on plugin servers tab entries are often fake
            // profiles whose skin never loads -> it used to point at nothing = black head)
            try {
                var t = ((dev.zalard.adapter.mixinopt.TextureManagerAccessor) mc.getTextureManager()).zalard$byPath()
                        .get(skin.body().texturePath());
                if (t == null || t.getTexture() == null || t.getTexture().isClosed()) return null;
            } catch (Throwable ignored) {}
            boolean slim = String.valueOf(skin.model()).toUpperCase().contains("SLIM");
            return new String[]{ gp.id().toString(), path, slim ? "slim" : "wide" };
        }
        return null;
    }

    @Override public int selfHurtTime() {
        return mc.player == null ? -1 : mc.player.hurtTime;
    }

    /** What the real keyboard/mouse says for this key binding. */
    static boolean physicalDown(Minecraft mc, net.minecraft.client.KeyMapping km) {
        if (mc.screen != null) return false;
        try {
            var key = ((dev.zalard.adapter.mixin.KeyMappingAccessor) km).zalard$getKey();
            long h = mc.getWindow().handle();
            if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM)
                return org.lwjgl.glfw.GLFW.glfwGetKey(h, key.getValue()) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.MOUSE)
                return org.lwjgl.glfw.GLFW.glfwGetMouseButton(h, key.getValue()) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        } catch (Throwable ignored) {}
        return false;
    }

    @Override public void setLocalGliding(boolean on) {
        if (mc.player == null) return;
        // SharedFlag(7) is the fall-flying bit. We flip it on our local entity
        // data without sending any packet to the server — the server never
        // asked, we never told.
        var data = mc.player.getEntityData();
        try {
            byte flags = data.get(dev.zalard.adapter.mixin.EntityAccessor.zalard$sharedFlags());
            byte modified = on ? (byte)(flags | (1 << 7)) : (byte)(flags & ~(1 << 7));
            data.set(dev.zalard.adapter.mixin.EntityAccessor.zalard$sharedFlags(), modified);
        } catch (Throwable ignored) {}
    }

    @Override public java.util.List<double[]> crystalsNear(double radius) {
        var out = new java.util.ArrayList<double[]>();
        if (mc.player == null || mc.level == null) return out;
        var eye = mc.player.getEyePosition();
        double r2 = radius * radius;
        for (var e : mc.level.entitiesForRendering()) {
            if (!(e instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal) || !e.isAlive()) continue;
            if (e.position().distanceToSqr(eye) > r2) continue;
            out.add(new double[]{ e.getId(), e.getX(), e.getY(), e.getZ() });
        }
        return out;
    }

    @Override public boolean isKeyDown(int glfwKey) {
        if (glfwKey == -100) return mc.screen == null                 // left mouse button
                && org.lwjgl.glfw.GLFW.glfwGetMouseButton(mc.getWindow().handle(), 0) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        if (glfwKey <= 0 || mc.screen != null) return false;
        return org.lwjgl.glfw.GLFW.glfwGetKey(mc.getWindow().handle(), glfwKey) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
    }

    @Override public void attackEntityId(int entityId) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        var e = mc.level.getEntity(entityId);
        if (e == null) return;
        mc.gameMode.attack(mc.player, e);
        mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        dev.zalard.adapter.AttackLog.mark();
    }

    @Override public void setSelfRotation(float yaw, float pitch) {
        if (mc.player == null) return;
        pitch = Math.max(-90f, Math.min(90f, pitch));
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.setYHeadRot(yaw);
    }

    @Override public boolean selfIsGliding() {
        return mc.player != null && mc.player.isFallFlying();
    }


    private static final java.util.Map<java.util.UUID, byte[]> SKIN_PNG = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Long> REPAIRED = new java.util.concurrent.ConcurrentHashMap<>();

    @Override public void repairSkinTexture(java.util.UUID owner, String path) {
        long now = System.currentTimeMillis();
        Long last = REPAIRED.get(owner);
        if (last != null && now - last < 2000) return;
        REPAIRED.put(owner, now);
        byte[] png = SKIN_PNG.get(owner);
        if (png != null && path != null && path.startsWith("mako:")) mc.execute(() -> registerSkinNow(owner, png));
    }

    private static final java.util.Map<String, byte[]> CAPE_PNG = new java.util.concurrent.ConcurrentHashMap<>();
    @Override public void registerCapeTexture(String hash, byte[] png) {
        if (png == null) return;
        CAPE_PNG.put(hash, png);
        mc.execute(() -> {
            try {
                var img = com.mojang.blaze3d.platform.NativeImage.read(new java.io.ByteArrayInputStream(png));
                var tex = new net.minecraft.client.renderer.texture.DynamicTexture(() -> "mako_cape", img);
                var path = net.minecraft.resources.Identifier.fromNamespaceAndPath("mako", "cape/" + hash.substring(0, Math.min(32, hash.length())));
                mc.getTextureManager().register(path, tex);
                dev.zalard.spoof.SpoofRegistry.setCapePath(path.toString());
            } catch (Throwable t) {
                System.err.println("[Mako] Cape Spoof: couldn't load cape texture: " + t);
            }
        });
    }
    /** Cape texture went missing (reload / closed): load it again. */
    static void repairCape(String path) {
        if (path == null) return;
        for (var e : CAPE_PNG.entrySet())
            if (path.endsWith(e.getKey().substring(0, Math.min(32, e.getKey().length()))))
                dev.zalard.platform.Platforms.get().registerCapeTexture(e.getKey(), e.getValue());
    }

    @Override public void registerSkinTexture(java.util.UUID uuid, byte[] png) {
        if (png == null) return;
        SKIN_PNG.put(uuid, png);
        // fetch callbacks arrive on a worker thread; GL work must run on the render thread
        mc.execute(() -> registerSkinNow(uuid, png));
    }

    private void registerSkinNow(java.util.UUID uuid, byte[] png) {
        if (mc.getTextureManager() == null) return;
        try {
            var img = com.mojang.blaze3d.platform.NativeImage.read(
                    new java.io.ByteArrayInputStream(png));
            img = legacySkinTo64x64(img);
            var tex = new net.minecraft.client.renderer.texture.DynamicTexture(() -> "mako_skin", img);
            var path = net.minecraft.resources.Identifier
                    .fromNamespaceAndPath("mako", "spoofed/" + uuid.toString().replace("-", ""));
            mc.getTextureManager().register(path, tex);
            dev.zalard.spoof.SpoofRegistry.cacheSkinPath(uuid, path.toString());
        } catch (Throwable t) {
            System.err.println("[Mako] Client Spoofer: couldn't load skin texture: " + t);
        }
    }

    /** Old 64x32 skins (like Notch's) get the same 64x64 conversion vanilla does, so legs/arms map right. */
    private static com.mojang.blaze3d.platform.NativeImage legacySkinTo64x64(com.mojang.blaze3d.platform.NativeImage img) {
        if (img.getWidth() != 64 || img.getHeight() != 32) return img;
        try {
            var out = new com.mojang.blaze3d.platform.NativeImage(64, 64, true);
            out.copyFrom(img);
            out.fillRect(0, 32, 64, 32, 0);
            out.copyRect(4, 16, 16, 32, 4, 4, true, false);
            out.copyRect(8, 16, 16, 32, 4, 4, true, false);
            out.copyRect(0, 20, 24, 32, 4, 12, true, false);
            out.copyRect(4, 20, 16, 32, 4, 12, true, false);
            out.copyRect(8, 20, 8, 32, 4, 12, true, false);
            out.copyRect(12, 20, 16, 32, 4, 12, true, false);
            out.copyRect(44, 16, -8, 32, 4, 4, true, false);
            out.copyRect(48, 16, -8, 32, 4, 4, true, false);
            out.copyRect(40, 20, 0, 32, 4, 12, true, false);
            out.copyRect(44, 20, -8, 32, 4, 12, true, false);
            out.copyRect(48, 20, -16, 32, 4, 12, true, false);
            out.copyRect(52, 20, -8, 32, 4, 12, true, false);
            img.close();
            return out;
        } catch (Throwable t) {
            return img;
        }
    }

    @Override public String potionTypeAt(int hotbarSlot) {
        if (mc.player == null) return null;
        if (hotbarSlot < 0 || hotbarSlot > 8) return null;
        var stack = mc.player.getInventory().getItem(hotbarSlot);
        if (stack == null || stack.isEmpty()) return null;
        var contents = stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
        if (contents == null) return null;
        var potion = contents.potion();
        if (potion.isEmpty()) return null;
        var key = potion.get().unwrapKey();
        if (key.isEmpty()) return null;
        return key.get().identifier().getPath();   // e.g. "healing", "strong_healing"
    }

    @Override public boolean attackReady()  { return mc.player != null && mc.player.getAttackStrengthScale(mc.getDeltaTracker().getGameTimeDeltaPartialTick(true)) >= 1f; }
    @Override public float attackStrength() { return mc.player == null ? 1f : mc.player.getAttackStrengthScale(0f); }
    @Override public float attackReach()    { return 3.0f; }

    @Override public float partialTick() {
        try { return mc.getDeltaTracker().getRealtimeDeltaTicks(); }
        catch (Throwable t) { return 0.5f; }
    }
    @Override public long  worldTime()   { return mc.level == null ? 0L : mc.level.getGameTime(); }
    @Override public int   screenWidth() { return mc.getWindow().getGuiScaledWidth(); }
    @Override public int   screenHeight(){ return mc.getWindow().getGuiScaledHeight(); }

    private static ItemHandle wrap(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return EMPTY;
        return new ItemHandle() {
            @Override public String  itemId() {
                var k = BuiltInRegistries.ITEM.getKey(stack.getItem());
                return k == null ? "minecraft:air" : k.toString();
            }
            @Override public int     count()     { return stack.getCount(); }
            @Override public boolean isEmpty()   { return stack.isEmpty(); }
            @Override public int     maxDamage() { return stack.getMaxDamage(); }
            @Override public int     damage()    { return stack.getDamageValue(); }
            @Override public int     enchantLevel(String enchantId) {
                var want = Identifier.tryParse(enchantId);
                if (want == null) return 0;
                var comp = stack.get(DataComponents.ENCHANTMENTS);
                if (comp == null) return 0;
                for (var h : comp.entrySet()) {
                    var key = h.getKey().unwrapKey();
                    if (key.isPresent() && key.get().identifier().equals(want)) return h.getIntValue();
                }
                return 0;
            }
        };
    }

    private static final ItemHandle EMPTY = new ItemHandle() {
        @Override public String  itemId()        { return "minecraft:air"; }
        @Override public int     count()         { return 0; }
        @Override public boolean isEmpty()       { return true; }
        @Override public int     maxDamage()     { return 0; }
        @Override public int     damage()        { return 0; }
        @Override public int     enchantLevel(String e) { return 0; }
    };
}
