package dev.zalard.adapter;

import dev.zalard.platform.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PlatformImpl implements McPlatform {

    private final Minecraft mc = Minecraft.getInstance();
    private final FrameRendererImpl renderer = new FrameRendererImpl();
    private final PlatformExtImpl  ext      = new PlatformExtImpl();

    @Override public String minecraftVersion() { return "1.21.11"; }
    @Override public boolean inGame() { return mc.player != null && mc.level != null; }

    @Override public UUID selfUuid()         { return me().getUUID(); }
    @Override public String selfName()       { return me().getGameProfile().name(); }
    @Override public Vec3d selfPosition()    { Vec3 p = me().position();        return new Vec3d(p.x, p.y, p.z); }
    @Override public Vec3d selfEyePosition() { Vec3 p = me().getEyePosition();  return new Vec3d(p.x, p.y, p.z); }
    @Override public float selfYaw()         { return me().getYRot(); }
    @Override public float selfPitch()       { return me().getXRot(); }
    @Override public boolean selfOnGround()  { return me().onGround(); }
    @Override public double selfHealth()     { return me().getHealth(); }

    // If it was renamed, swap to the 26.2 accessor here.
    @Override public int selfSelectedSlot()  { return me().getInventory().getSelectedSlot(); }
    @Override public void selfSelectSlot(int slot) {
        if (slot < 0 || slot > 8) return;
        me().getInventory().setSelectedSlot(slot);
        // let vanilla send the slot packet (once, only if it changed) like a scroll-wheel switch
        if (mc.gameMode != null) ((dev.zalard.adapter.mixin.GameModeInvoker) mc.gameMode).zalard$syncSlot();
    }

    // Entity lists are built once per game tick and shared by every module that asks (several
    // modules used to each rebuild them, wrapping every entity in the world, every tick).
    private Object cacheLevel; private long cacheTime = Long.MIN_VALUE;
    private List<EntityHandle> cachePlayers, cacheLiving;
    private java.util.Map<UUID, EntityHandle> cacheByUuid;
    private void refreshEntityCache() {
        var lvl = world();
        long t = lvl.getGameTime();
        if (lvl == cacheLevel && t == cacheTime && cacheLiving != null) return;
        cacheLevel = lvl; cacheTime = t;
        List<EntityHandle> players = new ArrayList<>(), living = new ArrayList<>();
        java.util.Map<UUID, EntityHandle> byId = new java.util.HashMap<>();
        for (var e : lvl.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le)) continue;
            EntityHandle h = new EntityHandleImpl(le);
            living.add(h);
            byId.put(le.getUUID(), h);
            if (le instanceof Player) players.add(h);
        }
        cachePlayers = java.util.Collections.unmodifiableList(players);
        cacheLiving = java.util.Collections.unmodifiableList(living);
        cacheByUuid = byId;
    }
    @Override public List<EntityHandle> allPlayers() { refreshEntityCache(); return cachePlayers; }
    @Override public List<EntityHandle> allLiving()  { refreshEntityCache(); return cacheLiving; }
    @Override public EntityHandle byUuid(UUID id) {
        refreshEntityCache();
        return cacheByUuid.get(id);
    }
    @Override public EntityHandle entityUnderCrosshair(double maxDistance) {
        // Through Walls: the vanilla crosshair pick stops at terrain, so raycast entities ignoring blocks
        // from your real look instead.
        if (mc.player != null && dev.zalard.nat.Z.is(dev.zalard.nat.Z.Q_THRU_WALLS))
            return raycastEntity(mc.player.getYRot(), mc.player.getXRot(), maxDistance);
        var hit = mc.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.ENTITY) return null;
        var ehr = (net.minecraft.world.phys.EntityHitResult) hit;
        return (ehr.getEntity() instanceof LivingEntity le) ? new EntityHandleImpl(le) : null;
    }

    @Override public BlockHandle blockAt(int x, int y, int z) {
        BlockPos bp = new BlockPos(x, y, z);
        BlockState bs = world().getBlockState(bp);
        return new BlockHandleImpl(bp, bs);
    }
    @Override public boolean canPlaceAt(int x, int y, int z) {
        return world().getBlockState(new BlockPos(x, y, z)).canBeReplaced();
    }
    @Override public boolean lineOfSight(Vec3d a, Vec3d b) {
        var hit = world().clip(new ClipContext(
                new Vec3(a.x(), a.y(), a.z()),
                new Vec3(b.x(), b.y(), b.z()),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                me()));
        return hit.getType() == HitResult.Type.MISS;
    }

    @Override public void setSilentRotation(float yaw, float pitch)  { RotationBridgeImpl.set(yaw, pitch); }
    // fade the silent head back to your camera instead of snapping (from the rotation fix)
    @Override public void clearSilentRotation() {
        if (mc.player != null) RotationBridgeImpl.clear(mc.player.getYRot(), mc.player.getXRot());
        else RotationBridgeImpl.clear();
    }
    @Override public boolean hasSilentRotation()                     { return RotationBridgeImpl.active(); }
    @Override public float getSilentYaw()                            { return RotationBridgeImpl.yaw(); }
    @Override public float getSilentPitch()                          { return RotationBridgeImpl.pitch(); }

    @Override public void sendSwing(boolean mainHand) {
        if (mc.player == null) return;
        // swing() animates your arm AND sends the swing packet; the packet alone showed nothing
        mc.player.swing(mainHand ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
    }
    @Override public void sendUseItemOn(int x, int y, int z, Direction f, Vec3d hit, boolean mainHand) {
        var face = switch (f) {
            case UP -> net.minecraft.core.Direction.UP;
            case DOWN -> net.minecraft.core.Direction.DOWN;
            case NORTH -> net.minecraft.core.Direction.NORTH;
            case SOUTH -> net.minecraft.core.Direction.SOUTH;
            case EAST -> net.minecraft.core.Direction.EAST;
            case WEST -> net.minecraft.core.Direction.WEST;
        };
        var bhr = new net.minecraft.world.phys.BlockHitResult(
                new Vec3(hit.x(), hit.y(), hit.z()), face, new BlockPos(x, y, z), false);
        mc.gameMode.useItemOn(me(), mainHand ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND, bhr);
    }
    @Override public void sendAttack(EntityHandle target) {
        if (target instanceof EntityHandleImpl h) {
            mc.gameMode.attack(me(), h.raw());
            AttackLog.mark();
        }
    }

    @Override public void sendClientMessage(String msg) {
        // got a 26.x refactor. If so, try mc.gui.getChat().addMessage(Component.literal(msg))
        if (mc.gui != null) mc.gui.getChat().addMessage(Component.literal(msg));
    }

    @Override public FrameRenderer frameRenderer() { return renderer; }

    // ---- McPlatformExt delegates ----
    @Override public ItemHandle slot(int i)                      { return ext.slot(i); }
    @Override public ItemHandle hotbarSlot(int i)                { return ext.hotbarSlot(i); }
    @Override public ItemHandle mainHand()                       { return ext.mainHand(); }
    @Override public ItemHandle offHand()                        { return ext.offHand(); }
    @Override public int findHotbar(String id)                   { return ext.findHotbar(id); }
    @Override public int findInventory(String id)                { return ext.findInventory(id); }
    @Override public void swapToHotbar(int inv, int hot)         { ext.swapToHotbar(inv, hot); }
    @Override public boolean attackReady()                       { return ext.attackReady(); }
    @Override public float attackStrength()                      { return ext.attackStrength(); }
    @Override public float attackReach()                         { return ext.attackReach(); }
    @Override public float partialTick()                         { return ext.partialTick(); }
    @Override public long  worldTime()                           { return ext.worldTime(); }
    @Override public int   screenWidth()                         { return ext.screenWidth(); }
    @Override public int   screenHeight()                        { return ext.screenHeight(); }

    // ---- McPlatformExt delegates that were missing (these threw "unsupported" before) ----
    @Override public void swapOffhand()                          { ext.swapOffhand(); }
    @Override public double selfHorizontalSpeedSq()              { return ext.selfHorizontalSpeedSq(); }
    @Override public void quickMoveSlot(int slot)                { ext.quickMoveSlot(slot); }
    @Override public ItemHandle armorChest()                     { return ext.armorChest(); }
    @Override public long lastAttackTickMs()                     { return ext.lastAttackTickMs(); }
    @Override public void sendUseItem(boolean mainHand)          { ext.sendUseItem(mainHand); }
    @Override public String potionTypeAt(int hotbarSlot)         { return ext.potionTypeAt(hotbarSlot); }
    @Override public void selfJump()                             { ext.selfJump(); }
    @Override public void setForwardKey(boolean down)            { ext.setForwardKey(down); }
    @Override public void restoreForwardKey()                    { ext.restoreForwardKey(); }
    @Override public void setBackKey(boolean down)               { ext.setBackKey(down); }
    @Override public void setSneakKey(boolean down)              { ext.setSneakKey(down); }
    @Override public void setSprintKey(boolean down)             { ext.setSprintKey(down); }
    @Override public void restoreSprintKey()                     { ext.restoreSprintKey(); }
    @Override public void setFreecam(boolean on, double speed)   { Freecam.set(on, speed); }
    @Override public void restoreSneakKey()                      { ext.restoreSneakKey(); }
    @Override public void restoreBackKey()                       { ext.restoreBackKey(); }
    @Override public boolean forwardHeld()                       { return ext.forwardHeld(); }
    @Override public int selfHurtTime()                          { return ext.selfHurtTime(); }
    @Override public int selfTabPing()                           { return ext.selfTabPing(); }
    @Override public String[] onlineSkin(String name)            { return ext.onlineSkin(name); }
    @Override public void registerSkinTexture(UUID uuid, byte[] png) { ext.registerSkinTexture(uuid, png); }
    @Override public void repairSkinTexture(UUID owner, String path) { ext.repairSkinTexture(owner, path); }
    @Override public void registerCapeTexture(String hash, byte[] png) { ext.registerCapeTexture(hash, png); }
    @Override public void setLocalGliding(boolean on)            { ext.setLocalGliding(on); }
    @Override public boolean selfIsGliding()                     { return ext.selfIsGliding(); }
    @Override public void setSelfRotation(float yaw, float pitch) { ext.setSelfRotation(yaw, pitch); }
    @Override public java.util.List<double[]> crystalsNear(double radius) { return ext.crystalsNear(radius); }
    @Override public void attackEntityId(int entityId)          { ext.attackEntityId(entityId); }
    @Override public boolean isKeyDown(int glfwKey)             { return ext.isKeyDown(glfwKey); }

    @Override public EntityHandle raycastEntity(float yaw, float pitch, double reach) {
        var pl = mc.player;
        if (pl == null || mc.level == null) return null;
        Vec3 eye = pl.getEyePosition();                 // tick position = what the server checks against
        Vec3 dir = Vec3.directionFromRotation(pitch, yaw);
        Vec3 end = eye.add(dir.scale(reach));
        double bestSq;
        if (dev.zalard.nat.Z.is(dev.zalard.nat.Z.Q_THRU_WALLS)) {
            bestSq = reach * reach;                         // Through Walls: don't let terrain occlude the target
        } else {
            var block = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, pl));
            bestSq = block.getType() == HitResult.Type.MISS ? reach * reach : block.getLocation().distanceToSqr(eye);
        }
        LivingEntity best = null;
        var area = pl.getBoundingBox().expandTowards(dir.scale(reach)).inflate(1.0);
        for (var e : mc.level.getEntities(pl, area, x -> x instanceof LivingEntity && x.isPickable() && !x.isSpectator() && x.isAlive())) {
            var box = e.getBoundingBox().inflate(e.getPickRadius());
            double d;
            if (box.contains(eye)) d = 0;
            else {
                var hit = box.clip(eye, end);
                if (hit.isEmpty()) continue;
                d = eye.distanceToSqr(hit.get());
            }
            if (d < bestSq) { bestSq = d; best = (LivingEntity) e; }
        }
        return best == null ? null : new EntityHandleImpl(best);
    }

    @Override public boolean inventoryScreenOpen() {
        return mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen;
    }
    @Override public double[] inventorySlotCursorPos(int invIndex) {
        if (!(mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen scr) || mc.player == null) return null;
        int menuSlot = invIndex < 9 ? 36 + invIndex : invIndex;
        var slots = mc.player.inventoryMenu.slots;
        if (menuSlot < 0 || menuSlot >= slots.size()) return null;
        var s = slots.get(menuSlot);
        var acc = (dev.zalard.adapter.mixin.ContainerScreenAccessor) scr;
        double gx = acc.zalard$left() + s.x + 8, gy = acc.zalard$top() + s.y + 8;      // GUI pixels
        var w = mc.getWindow();
        double k = w.getScreenWidth() / (double) Math.max(1, w.getGuiScaledWidth());
        return new double[]{ gx * k, gy * k };
    }
    @Override public double[] cursorPos() { return new double[]{ mc.mouseHandler.xpos(), mc.mouseHandler.ypos() }; }
    @Override public void setCursorPos(double x, double y) {
        org.lwjgl.glfw.GLFW.glfwSetCursorPos(mc.getWindow().handle(), x, y);
    }
    @Override public void swapSlotWithOffhand(int invIndex) {
        if (mc.player == null || mc.gameMode == null) return;
        // Same rule as swapToHotbar: a container click with no inventory GUI open is an illegal packet
        // (2b2t's anti-dupe check kicks for it). Only click when the inventory is actually open, which
        // is the Totem Cursor Sim path; the no-GUI Silent Sim swap can't be made packet-legal.
        if (!(mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen)) return;
        int menuSlot = invIndex < 9 ? 36 + invIndex : invIndex;
        mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, menuSlot, 40,
                net.minecraft.world.inventory.ClickType.SWAP, mc.player);
    }

    @Override public boolean selfSprinting() { return mc.player != null && mc.player.isSprinting(); }
    @Override public boolean jumpKeyDown()   { return mc.options != null && PlatformExtImpl.physicalDown(mc, mc.options.keyJump); }
    @Override public void selfStopSprint()   { if (mc.player != null) mc.player.setSprinting(false); }
    @Override public void clickAttackKey() {
        var acc = (dev.zalard.adapter.mixin.KeyMappingAccessor) mc.options.keyAttack;
        acc.zalard$setClickCount(acc.zalard$getClickCount() + 1);
    }
    @Override public void sendSpearStab() {
        // Modded spear-lunge thrust = a STAB player-action packet (not vanilla). STAB is a modded enum
        // constant, so look it up by name: present only on a server/build that adds it, and a no-op
        // otherwise. BlockPos.ZERO + Direction.DOWN match the reference (water.wtf SpearLungeModule).
        var con = mc.getConnection();
        if (con == null) return;
        net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action stab = null;
        for (var act : net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.values())
            if (act.name().equals("STAB")) { stab = act; break; }
        if (stab == null) return;
        con.send(new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
            stab, net.minecraft.core.BlockPos.ZERO, net.minecraft.core.Direction.DOWN, 0));
    }
    @Override public boolean selfLookSteered() {
        var pl = mc.player;
        return pl != null && (pl.isInWater() || pl.isInLava() || pl.isPassenger() || pl.isFallFlying() || pl.isSwimming());
    }
    @Override public void openInventory() {
        if (mc.player == null || mc.gameMode == null || mc.screen != null) return;
        // same as vanilla's E key
        if (mc.gameMode.isServerControlledInventory()) mc.player.sendOpenInventory();
        else mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
    }
    @Override public void closeScreen() {
        if (mc.player == null || mc.screen == null) return;
        mc.player.closeContainer();                     // sends the close packet + closes, like Esc
    }
    @Override public boolean useKeyDown() {
        return mc.screen == null && mc.options.keyUse.isDown();
    }
    @Override public void releaseUseItem() { if (mc.player != null && mc.gameMode != null) mc.gameMode.releaseUsingItem(mc.player); }
    @Override public void useItemInHand(boolean mainHand) {
        if (mc.player == null || mc.gameMode == null) return;
        mc.gameMode.useItem(mc.player, mainHand ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
    }
    @Override public int usingTicks() { return mc.player == null ? 0 : mc.player.getTicksUsingItem(); }
    @Override public int consumeUseClicks() {
        if (mc.options == null) return 0;
        int n = 0;
        while (mc.options.keyUse.consumeClick()) n++;
        return n;
    }
    @Override public boolean crossbowCharged(int slot) {
        if (mc.player == null || slot < 0 || slot > 8) return false;
        return net.minecraft.world.item.CrossbowItem.isCharged(mc.player.getInventory().getItem(slot));
    }
    @Override public void pressHotbarKey(int slot) {
        if (mc.options == null || slot < 0 || slot > 8) return;
        var acc = (dev.zalard.adapter.mixin.KeyMappingAccessor) mc.options.keyHotbarSlots[slot];
        acc.zalard$setClickCount(acc.zalard$getClickCount() + 1);
    }
    @Override public void pressSwapOffhandKey() {
        if (mc.options == null) return;
        var acc = (dev.zalard.adapter.mixin.KeyMappingAccessor) mc.options.keySwapOffhand;
        acc.zalard$setClickCount(acc.zalard$getClickCount() + 1);
    }
    @Override public void clickUseKey() {
        if (mc.options == null) return;
        var acc = (dev.zalard.adapter.mixin.KeyMappingAccessor) mc.options.keyUse;
        acc.zalard$setClickCount(acc.zalard$getClickCount() + 1);
    }
    @Override public void setUseKey(boolean down) { if (mc.options != null) mc.options.keyUse.setDown(down); }
    @Override public void restoreUseKey() {
        if (mc.options != null) mc.options.keyUse.setDown(PlatformExtImpl.physicalDown(mc, mc.options.keyUse));
    }
    @Override public boolean canAttackNow() {
        return mc.player != null && !mc.player.cannotAttackWithItem(mc.player.getMainHandItem(), 0);
    }
    @Override public boolean[] moveKeys() {
        var o = mc.options;
        if (o == null || mc.screen != null) return new boolean[6];
        return new boolean[]{ PlatformExtImpl.physicalDown(mc, o.keyUp), PlatformExtImpl.physicalDown(mc, o.keyDown),
                PlatformExtImpl.physicalDown(mc, o.keyLeft), PlatformExtImpl.physicalDown(mc, o.keyRight),
                PlatformExtImpl.physicalDown(mc, o.keyJump), PlatformExtImpl.physicalDown(mc, o.keyShift) };
    }
    @Override public int rightClickDelay() {
        return ((dev.zalard.adapter.mixinopt.MinecraftAccessor) mc).zalard$getRightClickDelay();
    }
    @Override public void setRightClickDelay(int ticks) {
        ((dev.zalard.adapter.mixinopt.MinecraftAccessor) mc).zalard$setRightClickDelay(ticks);
    }
    @Override public boolean selfInWater() { return mc.player != null && mc.player.isInWater(); }
    @Override public boolean holdingBlock() {
        return mc.player != null && mc.player.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem;
    }
    @Override public int selfFood() { return mc.player == null ? 20 : mc.player.getFoodData().getFoodLevel(); }
    @Override public void setSelfVelocity(double x, double y, double z) { if (mc.player != null) mc.player.setDeltaMovement(x, y, z); }
    @Override public int crosshairCrystalId() {
        if (mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult eh && eh.getType() == HitResult.Type.ENTITY
                && eh.getEntity() instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal c && c.isAlive()) return c.getId();
        return -1;
    }
    @Override public double[] crosshairBlockHit() {
        if (!(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult b) || b.getType() != HitResult.Type.BLOCK) return null;
        return blockHitArray(b);
    }
    @Override public double[] raycastBlock(float yaw, float pitch, double reach) {
        var pl = mc.player;
        if (pl == null || mc.level == null) return null;
        Vec3 eye = pl.getEyePosition();
        Vec3 end = eye.add(Vec3.directionFromRotation(pitch, yaw).scale(reach));
        var hit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, pl));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        return blockHitArray(hit);
    }
    @Override public boolean isBridgeBlock(int slot) {
        if (mc.player == null || mc.level == null || slot < 0 || slot > 8) return false;
        var stack = mc.player.getInventory().getItem(slot);
        if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem bi)) return false;
        var block = bi.getBlock();
        if (block instanceof net.minecraft.world.level.block.FallingBlock) return false;     // sand, gravel...
        if (block instanceof net.minecraft.world.level.block.RespawnAnchorBlock) return false;
        return block.defaultBlockState().isCollisionShapeFullBlock(mc.level, mc.player.blockPosition());
    }
    private static double[] blockHitArray(net.minecraft.world.phys.BlockHitResult b) {
        var pos = b.getBlockPos();
        var loc = b.getLocation();
        int f = switch (b.getDirection()) {                     // same order as McPlatform.Direction
            case UP -> 0; case DOWN -> 1; case NORTH -> 2; case SOUTH -> 3; case EAST -> 4; case WEST -> 5;
        };
        return new double[]{ pos.getX(), pos.getY(), pos.getZ(), f, loc.x, loc.y, loc.z };
    }
    @Override public int anchorCharge(int x, int y, int z) {
        if (mc.level == null) return -1;
        var st = mc.level.getBlockState(new BlockPos(x, y, z));
        if (!(st.getBlock() instanceof net.minecraft.world.level.block.RespawnAnchorBlock)) return -1;
        return st.getValue(net.minecraft.world.level.block.RespawnAnchorBlock.CHARGE);
    }
    @Override public void consumeAttackClick() {
        var acc = (dev.zalard.adapter.mixin.KeyMappingAccessor) mc.options.keyAttack;
        acc.zalard$setClickCount(Math.max(0, acc.zalard$getClickCount() - 1));
    }
    @Override public int pendingUseClicks() {
        return ((dev.zalard.adapter.mixin.KeyMappingAccessor) mc.options.keyUse).zalard$getClickCount();
    }
    @Override public int pendingAttackClicks() {
        return ((dev.zalard.adapter.mixin.KeyMappingAccessor) mc.options.keyAttack).zalard$getClickCount();
    }

    @Override public boolean screenOpen()    { return mc.screen != null; }
    @Override public boolean selfUsingItem() { return mc.player != null && mc.player.isUsingItem(); }

    // ---- hardening-pass additions ----
    @Override public float selfFallDistance() {
        return mc.player == null ? 0f : (float) mc.player.fallDistance;
    }
    @Override public float realSensitivity() {
        try { return mc.options.sensitivity().get().floatValue(); }
        catch (Throwable t) { return 0.5f; }
    }
    @Override public Vec3d selfVelocity() {
        if (mc.player == null) return new Vec3d(0, 0, 0);
        Vec3 v = mc.player.getDeltaMovement();
        return new Vec3d(v.x, v.y, v.z);
    }
    @Override public boolean hasOpenContainer() {
        return mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
            && !(mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen);
    }

    // ---- McPlatformExt2 (ESP + HUD) delegates ----
    private final PlatformExt2Impl ext2 = new PlatformExt2Impl();
    @Override public java.util.List<dev.zalard.platform.BlockEntityHandle> blockEntitiesNear(double r) { return ext2.blockEntitiesNear(r); }
    @Override public java.util.List<double[]> droppedItemsNear(double r, String f) { return ext2.droppedItemsNear(r, f); }
    @Override public double[] thrownPearlPos() { return ext2.thrownPearlPos(); }
    @Override public String activeEffects() { return ext2.activeEffects(); }
    @Override public String  currentDimension()                           { return ext2.currentDimension(); }
    @Override public boolean isCreative()                                 { return ext2.isCreative(); }
    @Override public int     serverPing()                                 { return ext2.serverPing(); }
    @Override public int     currentFps()                                 { return ext2.currentFps(); }
    @Override public String  serverBrand()                                { return ext2.serverBrand(); }
    @Override public void    hudRect(int x, int y, int w, int h, int argb) { ext2.hudRect(x, y, w, h, argb); }
    @Override public void    hudText(String s, int x, int y, int argb)    { ext2.hudText(s, x, y, argb); }
    @Override public int     hudTextWidth(String s)                       { return ext2.hudTextWidth(s); }

    // ---- McPlatformXray (anti-xray leak detection) delegates ----
    private final PlatformXrayImpl xray = new PlatformXrayImpl();
    @Override public int blockLightAt(int x, int y, int z)                { return xray.blockLightAt(x, y, z); }
    @Override public int skyLightAt(int x, int y, int z)                  { return xray.skyLightAt(x, y, z); }
    @Override public boolean chunkLoaded(int bx, int bz)                  { return xray.chunkLoaded(bx, bz); }
    @Override public java.util.List<String> entityTypesInChunk(int cx, int cz) { return xray.entityTypesInChunk(cx, cz); }
    @Override public java.util.List<int[]> findBlocksInChunk(int cx, int cz, String id) { return xray.findBlocksInChunk(cx, cz, id); }
    @Override public java.util.List<int[]> scanOres(int cx, int cz, String[] ids, boolean exposed) { return xray.scanOres(cx, cz, ids, exposed); }

    private LocalPlayer me()    { return mc.player; }
    private ClientLevel world() { return mc.level;  }
}
