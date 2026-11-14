package dev.zalard.adapter;

import dev.zalard.nat.Op;
import dev.zalard.platform.ItemHandle;
import dev.zalard.platform.McPlatform;
import dev.zalard.platform.McPlatform.Vec3d;
import dev.zalard.platform.Platforms;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The only way the native logic (zalard_gui.dll) touches Minecraft. Every module, setting, config and
 * the GUI live in C++; they call these static methods (through JNI) to read the game and to press
 * keys / click, and read bulk state from three buffers the DLL owns:
 *   OUT  doubles      results of the last numeric op (self block, block hits, projections...)
 *   ENTS 256-byte records, one per living entity (see writeEntity)
 *   INV  96-byte records, inventory slots 0-35 + offhand (see writeItem)
 * Operation numbers come from tools/gen_ops.py (dev.zalard.nat.Op / native/logic/ops.h).
 */
public final class Bridge {
    private Bridge() {}

    private static DoubleBuffer OUT;
    private static ByteBuffer ENTS, INV;
    private static int entCap;
    static Path zalardDir;
    private static volatile String itemFilter = "";   // Item ESP id filter, set via T_SET_ITEM_FILTER

    /** Called once by the DLL with its buffers. */
    public static void setup(ByteBuffer out, ByteBuffer ents, ByteBuffer inv) {
        OUT = out.order(ByteOrder.nativeOrder()).asDoubleBuffer();
        ENTS = ents.order(ByteOrder.nativeOrder());
        INV = inv.order(ByteOrder.nativeOrder());
        entCap = ents.capacity() / 256;
    }

    private static McPlatform P() { return Platforms.get(); }
    private static final Minecraft mc = Minecraft.getInstance();
    private static void out(int i, double v) { OUT.put(i, v); }
    private static double b(boolean v) { return v ? 1 : 0; }

    // ------------------------------------------------------------------ numeric ops
    public static double d(int op, double a, double b, double c, double e, double f, double g, double h) {
        try { return d0(op, a, b, c, e, f, g, h); }
        catch (UnsupportedOperationException ex) { return -1; }
        catch (Throwable t) { report(op, t); return -1; }
    }

    private static final java.util.Set<Integer> REPORTED = new java.util.HashSet<>();
    private static void report(int op, Throwable t) {
        if (!REPORTED.add(op)) return;
        System.err.println("[Zalard] bridge op " + op + " failed: " + t);
        t.printStackTrace();
    }

    private static double d0(int op, double a, double b, double c, double e, double f, double g, double h) {
        McPlatform p = P();
        switch (op) {
            case Op.D_SNAP_SELF: return snapSelf(p);
            case Op.D_SNAP_ENTS: return snapEnts(a);
            case Op.D_SNAP_INV:  snapInv(p); return 1;
            case Op.D_CROSSHAIR_ENT: { var x = p.entityUnderCrosshair(a); return x == null ? -1 : x.entityId(); }
            case Op.D_RAYCAST_ENT:   { var x = p.raycastEntity((float) a, (float) b, c); return x == null ? -1 : x.entityId(); }
            case Op.D_LOS: return b(p.lineOfSight(new Vec3d(a, b, c), new Vec3d(e, f, g)));
            case Op.D_BLOCK_FLAGS: {
                var bl = p.blockAt((int) a, (int) b, (int) c);
                int r = (bl.isAir() ? 1 : 0) | (bl.isReplaceable() ? 2 : 0) | (bl.isSolid() ? 4 : 0) | (bl.isLiquid() ? 8 : 0);
                if (bl.isLiquid() && bl.blockId().contains("water")) r |= 16;
                return r;
            }
            case Op.D_CROSSHAIR_BLOCK: return arr(p.crosshairBlockHit());
            case Op.D_RAYCAST_BLOCK:   return arr(p.raycastBlock((float) a, (float) b, c));
            case Op.D_ANCHOR_CHARGE: return p.anchorCharge((int) a, (int) b, (int) c);
            case Op.D_CROSSHAIR_CRYSTAL: return p.crosshairCrystalId();
            case Op.D_SET_SILENT: p.setSilentRotation((float) a, (float) b); return 1;
            case Op.D_CLEAR_SILENT: p.clearSilentRotation(); return 1;
            case Op.D_SWING: p.sendSwing(a != 0); return 1;
            case Op.D_USE_ITEM_ON: {
                int fm = (int) e;
                p.sendUseItemOn((int) a, (int) b, (int) c, McPlatform.Direction.values()[(fm >> 1) % 6], new Vec3d(f, g, h), (fm & 1) != 0);
                return 1;
            }
            case Op.D_ATTACK: {
                if (mc.level == null || mc.player == null || mc.gameMode == null) return 0;
                var en = mc.level.getEntity((int) a);
                if (en == null) return 0;
                mc.gameMode.attack(mc.player, en);
                AttackLog.mark();
                return 1;
            }
            case Op.D_SET_HIT_TARGET: {
                // Point the client crosshair at this entity for this tick so a simulated attack-click
                // (vanilla startAttack reads mc.hitResult) lands on it. Overwritten by next frame's pick().
                if (mc.level == null) return 0;
                var en = mc.level.getEntity((int) a);
                if (en == null) return 0;
                mc.hitResult = new net.minecraft.world.phys.EntityHitResult(en);
                return 1;
            }
            case Op.D_PRESS_HOTBAR: p.pressHotbarKey((int) a); return 1;
            case Op.D_CLICK_ATTACK: p.clickAttackKey(); return 1;
            case Op.D_CONSUME_ATTACK: p.consumeAttackClick(); return 1;
            case Op.D_CLICK_USE: p.clickUseKey(); return 1;
            case Op.D_CONSUME_USE: return p.consumeUseClicks();
            case Op.D_PRESS_SWAP_OFFHAND: p.pressSwapOffhandKey(); return 1;
            case Op.D_SET_USE_KEY: p.setUseKey(a != 0); return 1;
            case Op.D_RESTORE_USE_KEY: p.restoreUseKey(); return 1;
            case Op.D_SET_FWD: p.setForwardKey(a != 0); return 1;
            case Op.D_RESTORE_FWD: p.restoreForwardKey(); return 1;
            case Op.D_SET_BACK: p.setBackKey(a != 0); return 1;
            case Op.D_RESTORE_BACK: p.restoreBackKey(); return 1;
            case Op.D_SET_SPRINT: p.setSprintKey(a != 0); return 1;
            case Op.D_RESTORE_SPRINT: p.restoreSprintKey(); return 1;
            case Op.D_STOP_SPRINT: p.selfStopSprint(); return 1;
            case Op.D_JUMP: p.selfJump(); return 1;
            case Op.D_SET_VEL: p.setSelfVelocity(a, b, c); return 1;
            case Op.D_SET_ROT: p.setSelfRotation((float) a, (float) b); return 1;
            case Op.D_SET_RCD: p.setRightClickDelay((int) a); return 1;
            case Op.D_SET_FREECAM: p.setFreecam(a != 0, b); return 1;
            case Op.D_OPEN_INV: p.openInventory(); return 1;
            case Op.D_CLOSE_SCREEN: p.closeScreen(); return 1;
            case Op.D_SWAP_TO_HOTBAR: p.swapToHotbar((int) a, (int) b); return 1;
            case Op.D_SWAP_SLOT_OFFHAND: p.swapSlotWithOffhand((int) a); return 1;
            case Op.D_INV_SLOT_POS: return arr(p.inventorySlotCursorPos((int) a)) > 0 ? 1 : 0;
            case Op.D_CURSOR_POS: return arr(p.cursorPos()) > 0 ? 1 : 0;
            case Op.D_SET_CURSOR: p.setCursorPos(a, b); return 1;
            case Op.D_KEY_DOWN: return b(p.isKeyDown((int) a));
            case Op.D_BIND_HELD: return b(bindHeld((int) a));
            case Op.D_OPEN_GUI:
                if (mc.screen == null) mc.setScreen(new dev.zalard.gui.ZalardScreen());
                return 1;
            case Op.D_PROJECT_BOX: {
                float[] r = p.frameRenderer().project(new double[]{ a, b, c, e, f, g });
                if (r == null) return 0;
                for (int i = 0; i < 4; i++) out(i, r[i]);
                return 1;
            }
            case Op.D_PROJECT_POINT: {
                float[] r = p.frameRenderer().projectPoint(a, b, c);
                if (r == null) return 0;
                out(0, r[0]); out(1, r[1]);
                return 1;
            }
            case Op.D_ESP_LINE: p.frameRenderer().line(new Vec3d(a, b, c), new Vec3d(e, f, g), (int) (long) h); return 1;
            case Op.D_LINE_WIDTH: p.frameRenderer().setLineWidth((float) a); return 1;
            case Op.D_CRYSTALS_NEAR: {
                int n = 0;
                for (double[] cr : p.crystalsNear(a)) {
                    if (n >= 120) break;
                    for (int i = 0; i < 4; i++) out(n * 4 + i, cr[i]);
                    n++;
                }
                return n;
            }
            case Op.D_STORAGE_NEAR: {
                int n = 0;
                for (var be : p.blockEntitiesNear(a)) {
                    if (n >= 120) break;
                    String id = be.blockId();
                    int k = id.contains("shulker") ? 2 : id.contains("ender_chest") ? 3 : id.contains("chest") ? 1 : id.contains("barrel") ? 4 : 0;
                    if (k == 0) continue;
                    out(n * 4, be.x()); out(n * 4 + 1, be.y()); out(n * 4 + 2, be.z()); out(n * 4 + 3, k);
                    n++;
                }
                return n;
            }
            case Op.D_ITEMS_NEAR: {
                int n = 0;
                for (double[] pos : p.droppedItemsNear(a, itemFilter)) {   // Java applies the id filter
                    if (n >= 256) break;
                    out(n * 3, pos[0]); out(n * 3 + 1, pos[1]); out(n * 3 + 2, pos[2]);
                    n++;
                }
                return n;
            }
            case Op.D_PEARL_POS: {
                double[] pp = p.thrownPearlPos();
                if (pp == null) return 0;
                out(0, pp[0]); out(1, pp[1]); out(2, pp[2]);
                return 1;
            }
            case Op.D_CAM_GET: {
                var pl = mc.player;
                if (pl == null) return 0;
                out(0, pl.getYRot()); out(1, pl.getXRot()); out(2, b(mc.screen == null));
                out(3, mc.options.sensitivity().get());
                return 1;
            }
            case Op.D_CAM_TURN: {
                var pl = mc.player;
                if (pl == null) return 0;
                float qy = (float) a, cur = pl.getXRot();
                float np = Math.max(-90f, Math.min(90f, cur + (float) b));
                float qp = np - cur;
                pl.setYRot(pl.getYRot() + qy);
                pl.setXRot(np);
                pl.yRotO += qy;
                pl.xRotO += qp;
                return 1;
            }
            case Op.D_SPOOF_FLAGS: SpoofService.flags((int) a); return 1;
            case Op.D_SAVE_NOW: return 1;
            case Op.D_SELF_VEL: {
                var v = p.selfVelocity();
                out(0, v.x()); out(1, v.y()); out(2, v.z());
                return 1;
            }
            case Op.D_SPEAR_STAB: p.sendSpearStab(); return 1;
            default: return -1;
        }
    }

    private static int arr(double[] v) {
        if (v == null) return 0;
        for (int i = 0; i < v.length; i++) out(i, v[i]);
        return v.length;
    }

    private static boolean bindHeld(int key) {
        if (mc.screen != null) return false;
        long w = mc.getWindow().handle();
        if (key > 0) return org.lwjgl.glfw.GLFW.glfwGetKey(w, key) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        if (key >= -98 && key <= -96) return org.lwjgl.glfw.GLFW.glfwGetMouseButton(w, key + 100) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        return false;
    }

    // ------------------------------------------------------------------ snapshots
    private static double snapSelf(McPlatform p) {
        if (!p.inGame()) return 0;
        var pl = mc.player;
        Vec3d pos = p.selfPosition(), eye = p.selfEyePosition(), v = p.selfVelocity();
        boolean[] k = p.moveKeys();
        out(Op.SF_X, pos.x()); out(Op.SF_Y, pos.y()); out(Op.SF_Z, pos.z());
        out(Op.SF_EX, eye.x()); out(Op.SF_EY, eye.y()); out(Op.SF_EZ, eye.z());
        out(Op.SF_YAW, p.selfYaw()); out(Op.SF_PITCH, p.selfPitch());
        out(Op.SF_GROUND, b(p.selfOnGround())); out(Op.SF_HEALTH, p.selfHealth());
        out(Op.SF_SLOT, p.selfSelectedSlot());
        out(Op.SF_VX, v.x()); out(Op.SF_VY, v.y()); out(Op.SF_VZ, v.z());
        out(Op.SF_FALL, p.selfFallDistance()); out(Op.SF_STRENGTH, p.attackStrength());
        out(Op.SF_USING, b(p.selfUsingItem())); out(Op.SF_SCREEN, b(p.screenOpen()));
        out(Op.SF_INV_OPEN, b(p.inventoryScreenOpen())); out(Op.SF_SPRINTING, b(p.selfSprinting()));
        out(Op.SF_JUMP_KEY, b(p.jumpKeyDown())); out(Op.SF_USE_KEY, b(p.useKeyDown()));
        out(Op.SF_FOOD, p.selfFood()); out(Op.SF_IN_WATER, b(p.selfInWater()));
        out(Op.SF_LOOK_STEERED, b(p.selfLookSteered())); out(Op.SF_HURT, p.selfHurtTime());
        out(Op.SF_GLIDING, b(p.selfIsGliding()));
        out(Op.SF_PEND_ATK, p.pendingAttackClicks()); out(Op.SF_PEND_USE, p.pendingUseClicks());
        out(Op.SF_USING_TICKS, p.usingTicks()); out(Op.SF_CAN_ATTACK, b(p.canAttackNow()));
        out(Op.SF_HOLD_BLOCK, b(p.holdingBlock()));
        out(Op.SF_SILENT, b(p.hasSilentRotation())); out(Op.SF_SYAW, p.getSilentYaw()); out(Op.SF_SPITCH, p.getSilentPitch());
        out(Op.SF_SENS, p.realSensitivity()); out(Op.SF_FWD_HELD, b(p.forwardHeld()));
        out(Op.SF_K_FWD, b(k[0])); out(Op.SF_K_BACK, b(k[1])); out(Op.SF_K_LEFT, b(k[2])); out(Op.SF_K_RIGHT, b(k[3]));
        out(Op.SF_K_JUMP, b(k[4])); out(Op.SF_K_SNEAK, b(k[5]));
        out(Op.SF_RCD, p.rightClickDelay());
        int ping; try { ping = p.serverPing(); } catch (Throwable t) { ping = 0; }
        out(Op.SF_PING, ping);
        int fps; try { fps = p.currentFps(); } catch (Throwable t) { fps = 0; }
        out(Op.SF_FPS, fps); out(Op.SF_TAB_PING, p.selfTabPing());
        boolean cont; try { cont = p.hasOpenContainer(); } catch (Throwable t) { cont = false; }
        out(Op.SF_CONTAINER, b(cont));
        out(Op.SF_HSPEED_SQ, p.selfHorizontalSpeedSq());
        out(Op.SF_WORLD_TIME, p.worldTime());
        var win = mc.getWindow();
        out(Op.SF_SCREEN_W, win.getGuiScaledWidth()); out(Op.SF_SCREEN_H, win.getGuiScaledHeight());
        out(Op.SF_FB_W, win.getWidth()); out(Op.SF_FB_H, win.getHeight());
        String dim; try { dim = p.currentDimension(); } catch (Throwable t) { dim = ""; }
        out(Op.SF_NETHER, b(dim != null && dim.contains("the_nether")));
        out(Op.SF_SELF_ID, pl.getId());
        boolean cr; try { cr = p.isCreative(); } catch (Throwable t) { cr = false; }
        out(Op.SF_CREATIVE, b(cr));
        return 1;
    }

    /** One 256-byte record per living entity within `radius` of you (you included, flagged 32). */
    private static double snapEnts(double radius) {
        if (mc.level == null || mc.player == null) return 0;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        double r2 = radius * radius;
        int n = 0;
        for (var en : mc.level.entitiesForRendering()) {
            if (!(en instanceof LivingEntity e)) continue;
            if (e != mc.player && e.distanceToSqr(mc.player) > r2) continue;
            if (n >= entCap) break;
            writeEntity(n * 256, e, pt);
            n++;
        }
        return n;
    }

    private static void writeEntity(int o, LivingEntity e, float pt) {
        ByteBuffer B = ENTS;
        boolean shield = false;
        if (e.isUsingItem()) {
            var used = e.getUseItem();
            if (used != null && !used.isEmpty()) {
                var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(used.getItem());
                shield = id != null && "minecraft:shield".equals(id.toString());
            }
        }
        int flags = (e instanceof Player ? 1 : 0) | (!e.isAlive() ? 2 : 0) | (e.isInvisible() ? 4 : 0)
                  | (e.onGround() ? 8 : 0) | (shield ? 16 : 0) | (e == mc.player ? 32 : 0);
        B.putInt(o, e.getId()); B.putInt(o + 4, flags);
        var u = e.getUUID();
        B.putLong(o + 8, u.getMostSignificantBits()); B.putLong(o + 16, u.getLeastSignificantBits());
        B.putDouble(o + 24, e.getX()); B.putDouble(o + 32, e.getY()); B.putDouble(o + 40, e.getZ());
        B.putDouble(o + 48, e.xo); B.putDouble(o + 56, e.yo); B.putDouble(o + 64, e.zo);
        var eye = e.getEyePosition();
        B.putDouble(o + 72, eye.x); B.putDouble(o + 80, eye.y); B.putDouble(o + 88, eye.z);
        B.putFloat(o + 96, e.getYRot()); B.putFloat(o + 100, e.getXRot());
        B.putFloat(o + 104, e.getHealth()); B.putFloat(o + 108, e.getMaxHealth());
        float armor;
        try { armor = (float) e.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR); } catch (Throwable t) { armor = 0; }
        B.putFloat(o + 112, armor); B.putInt(o + 116, e.hurtTime);
        AABB bb = e.getBoundingBox();
        B.putDouble(o + 120, bb.minX); B.putDouble(o + 128, bb.minY); B.putDouble(o + 136, bb.minZ);
        B.putDouble(o + 144, bb.maxX); B.putDouble(o + 152, bb.maxY); B.putDouble(o + 160, bb.maxZ);
        double ox = (e.xo + (e.getX() - e.xo) * pt) - e.getX();
        double oy = (e.yo + (e.getY() - e.yo) * pt) - e.getY();
        double oz = (e.zo + (e.getZ() - e.zo) * pt) - e.getZ();
        B.putDouble(o + 168, bb.minX + ox); B.putDouble(o + 176, bb.minY + oy); B.putDouble(o + 184, bb.minZ + oz);
        B.putDouble(o + 192, bb.maxX + ox); B.putDouble(o + 200, bb.maxY + oy); B.putDouble(o + 208, bb.maxZ + oz);
        String name = e instanceof Player pl ? pl.getGameProfile().name() : e.getName().getString();
        putStr(B, o + 216, 40, name);
    }

    private static void putStr(ByteBuffer B, int o, int cap, String s) {
        byte[] bytes = s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(bytes.length, cap - 1);
        for (int i = 0; i < n; i++) B.put(o + i, bytes[i]);
        B.put(o + n, (byte) 0);
    }

    private static final String[] ENCH = { "minecraft:lunge", "minecraft:density", "minecraft:breach", "minecraft:wind_burst", "minecraft:flame" };

    /** Inventory 0-35 (0-8 hotbar) and the offhand as slot 36, 96 bytes each. */
    private static void snapInv(McPlatform p) {
        for (int i = 0; i < 37; i++) {
            ItemHandle it = i == 36 ? p.offHand() : p.slot(i);
            writeItem(i * 96, it, i, p);
        }
    }

    private static void writeItem(int o, ItemHandle it, int slot, McPlatform p) {
        ByteBuffer B = INV;
        boolean empty = it == null || it.isEmpty();
        int flags = empty ? 1 : 0;
        if (!empty && slot < 9) {
            try { if (p.isBridgeBlock(slot)) flags |= 8; } catch (Throwable ignored) {}
            try { if (p.crossbowCharged(slot)) flags |= 4; } catch (Throwable ignored) {}
        }
        if (!empty && slot < 36) {
            var st = mc.player.getInventory().getItem(slot);
            if (st.getItem() instanceof net.minecraft.world.item.BlockItem) flags |= 2;
        }
        B.putInt(o, empty ? 0 : it.count()); B.putInt(o + 4, flags);
        B.putInt(o + 8, empty ? 0 : it.damage()); B.putInt(o + 12, empty ? 0 : it.maxDamage());
        for (int k = 0; k < 6; k++) {
            int lvl = 0;
            if (!empty && k < ENCH.length) try { lvl = it.enchantLevel(ENCH[k]); } catch (Throwable ignored) {}
            B.putInt(o + 16 + k * 4, lvl);
        }
        putStr(B, o + 40, 56, empty ? "minecraft:air" : it.itemId());
    }

    // ------------------------------------------------------------------ string ops
    public static double t(int op, String a, String b, String c) {
        try {
            switch (op) {
                case Op.T_CHAT: P().sendClientMessage(a); return 1;
                case Op.T_WRITE_FILE: {
                    Path f = file(a); if (f == null) return 0;
                    Files.createDirectories(f.getParent());
                    Files.writeString(f, b == null ? "" : b);
                    return 1;
                }
                case Op.T_DELETE_FILE: { Path f = file(a); return f != null && Files.deleteIfExists(f) ? 1 : 0; }
                case Op.T_SPOOF_NAME: SpoofService.name(a); return 1;
                case Op.T_SPOOF_SKIN: SpoofService.skin(a); return 1;
                case Op.T_SPOOF_OTHER: SpoofService.other(a, b, c); return 1;
                case Op.T_SPOOF_CAPE: SpoofService.cape(a); return 1;
                case Op.T_LOG: System.out.println("[Zalard] " + a); return 1;
                case Op.T_SET_ITEM_FILTER: itemFilter = a == null ? "" : a.trim(); return 1;
                case Op.T_SET_DRIP: DripService.set(Integer.parseInt(a), b); return 1;
                case Op.T_SPOTIFY_CONNECT: SpotifyService.connect(); return 1;
                case Op.T_SET_NATIVE_LANG: TranslationService.setNative(a); return 1;
                default: return -1;
            }
        } catch (Throwable t) { report(1000 + op, t); return -1; }
    }

    public static String r(int op, String a) {
        try {
            switch (op) {
                case Op.R_READ_FILE: { Path f = file(a); return f != null && Files.exists(f) ? Files.readString(f) : null; }
                case Op.R_LIST_CONFIGS: {
                    Path dir = zalardDir.resolve("configs");
                    if (!Files.isDirectory(dir)) return "";
                    try (var s = Files.list(dir)) {
                        return String.join("\n", s.map(x -> x.getFileName().toString()).filter(x -> x.endsWith(".json"))
                                .map(x -> x.substring(0, x.length() - 5)).sorted(String.CASE_INSENSITIVE_ORDER).limit(60).toList());
                    }
                }
                case Op.R_BLOCK_ID_AT: {
                    String[] q = a.split(" ");
                    return P().blockAt(Integer.parseInt(q[0]), Integer.parseInt(q[1]), Integer.parseInt(q[2])).blockId();
                }
                case Op.R_SPOTIFY_NOW: return SpotifyService.nowLine();
                case Op.R_SPOTIFY_STATE: return SpotifyService.state();
                case Op.R_EFFECTS: return P().activeEffects();
                default: return null;
            }
        } catch (Throwable t) { report(2000 + op, t); return null; }
    }

    /** A path inside .minecraft/mako only (no "..", no absolute paths). */
    private static Path file(String rel) {
        if (rel == null || rel.isEmpty() || rel.contains("..") || rel.startsWith("/") || rel.contains(":")) return null;
        return zalardDir.resolve(rel);
    }
}
