package dev.zalard.adapter;

import dev.zalard.platform.BlockEntityHandle;
import dev.zalard.platform.McPlatformExt2;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;

public final class PlatformExt2Impl implements McPlatformExt2 {

    /** Singleton reference the InGameHudMixin reads to set the current GuiGraphics. */
    public static volatile PlatformExt2Impl INSTANCE;

    private final Minecraft mc = Minecraft.getInstance();
    private volatile GuiGraphics currentGui;

    public PlatformExt2Impl() { INSTANCE = this; }

    /** Called by InGameHudMixin on each HUD render frame. */
    public void setGui(GuiGraphics g) { this.currentGui = g; }

    @Override
    public List<BlockEntityHandle> blockEntitiesNear(double radius) {
        List<BlockEntityHandle> out = new ArrayList<>();
        if (mc.player == null || mc.level == null) return out;
        var self = mc.player.position();
        double rSq = radius * radius;
        int chunkR = (int) Math.ceil(radius / 16.0) + 1;
        int cx = mc.player.chunkPosition().x;
        int cz = mc.player.chunkPosition().z;

        for (int dx = -chunkR; dx <= chunkR; dx++) for (int dz = -chunkR; dz <= chunkR; dz++) {
            var chunk = mc.level.getChunk(cx + dx, cz + dz);
            if (chunk == null) continue;
            for (var entry : chunk.getBlockEntities().entrySet()) {
                BlockPos pos = entry.getKey();
                BlockEntity be = entry.getValue();
                if (be == null) continue;
                double dsq = pos.distToCenterSqr(self.x, self.y, self.z);
                if (dsq > rSq) continue;
                out.add(wrap(pos, be));
            }
        }
        return out;
    }

    @Override
    public List<double[]> droppedItemsNear(double radius, String filter) {
        List<double[]> out = new ArrayList<>();
        if (mc.player == null || mc.level == null) return out;
        var self = mc.player.position();
        double rSq = radius * radius;
        String f = filter == null ? "" : filter.toLowerCase();
        for (var e : mc.level.entitiesForRendering()) {
            if (!(e instanceof net.minecraft.world.entity.item.ItemEntity ie)) continue;
            if (e.distanceToSqr(self.x, self.y, self.z) > rSq) continue;
            if (!f.isEmpty()) {
                var id = BuiltInRegistries.ITEM.getKey(ie.getItem().getItem());
                if (id == null || !id.toString().toLowerCase().contains(f)) continue;
            }
            out.add(new double[]{ e.getX(), e.getY(), e.getZ() });
            if (out.size() >= 256) break;
        }
        return out;
    }

    @Override
    public double[] thrownPearlPos() {
        if (mc.player == null || mc.level == null) return null;
        net.minecraft.world.entity.Entity best = null;
        int youngest = Integer.MAX_VALUE;
        for (var e : mc.level.entitiesForRendering()) {
            if (!(e instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl p)) continue;
            if (p.getOwner() != mc.player) continue;        // only your own pearl
            if (p.tickCount < youngest) { youngest = p.tickCount; best = e; }   // most recently thrown
        }
        return best == null ? null : new double[]{ best.getX(), best.getY(), best.getZ() };
    }

    @Override
    public String activeEffects() {
        if (mc.player == null) return "";
        StringBuilder sb = new StringBuilder();
        for (var inst : mc.player.getActiveEffects()) {
            String name;
            try { name = inst.getEffect().value().getDisplayName().getString(); }
            catch (Throwable t) { name = "Effect"; }
            int amp = inst.getAmplifier();
            if (amp > 0) name += " " + roman(amp + 1);
            String time;
            if (inst.isInfiniteDuration()) time = "--";
            else { int s = inst.getDuration() / 20; time = (s / 60) + ":" + String.format("%02d", s % 60); }
            if (sb.length() > 0) sb.append('\n');
            sb.append(name).append(' ').append(time);
        }
        return sb.toString();
    }
    private static String roman(int n) {
        String[] r = { "", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X" };
        return n >= 0 && n < r.length ? r[n] : Integer.toString(n);
    }

    private BlockEntityHandle wrap(BlockPos pos, BlockEntity be) {
        var id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        String idStr = id == null ? "minecraft:air" : id.toString();
        return new BlockEntityHandle() {
            @Override public int x() { return pos.getX(); }
            @Override public int y() { return pos.getY(); }
            @Override public int z() { return pos.getZ(); }
            @Override public String blockId() { return idStr; }
            @Override public boolean isContainer() {
                return idStr.contains("chest") || idStr.contains("shulker")
                    || idStr.contains("barrel") || idStr.contains("hopper")
                    || idStr.contains("dropper") || idStr.contains("dispenser");
            }
            @Override public boolean isTrapped() { return idStr.contains("trapped"); }
        };
    }

    @Override
    public String currentDimension() {
        if (mc.level == null) return "unknown";
        return mc.level.dimension().identifier().toString();
    }
    @Override public boolean isCreative() {
        return mc.player != null && mc.player.getAbilities().instabuild;
    }
    @Override public int serverPing() {
        if (mc.getConnection() == null || mc.player == null) return -1;
        var info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info == null ? -1 : info.getLatency();
    }
    @Override public int currentFps() {
        try { return (int) mc.getFps(); } catch (Throwable t) { return 0; }
    }
    @Override public String serverBrand() {
        var brand = mc.getConnection() == null ? null : mc.getConnection().serverBrand();
        return brand == null ? "unknown" : brand;
    }

    @Override public void hudRect(int x, int y, int w, int h, int argb) {
        var g = currentGui;
        if (g != null) g.fill(x, y, x + w, y + h, argb);
    }
    @Override public void hudText(String s, int x, int y, int argb) {
        var g = currentGui;
        if (g != null && mc.font != null) g.drawString(mc.font, Component.literal(s), x, y, argb, false);
    }
    @Override public int hudTextWidth(String s) {
        return mc.font == null ? 0 : mc.font.width(s);
    }
}
