package dev.zalard.adapter;

import dev.zalard.platform.EntityHandle;
import dev.zalard.platform.McPlatform.Vec3d;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

final class EntityHandleImpl implements EntityHandle {
    private final LivingEntity e;
    EntityHandleImpl(LivingEntity e) { this.e = e; }

    LivingEntity raw() { return e; }

    @Override public UUID uuid() { return e.getUUID(); }
    @Override public int entityId() { return e.getId(); }
    @Override public String name() {
        return e instanceof Player p ? p.getGameProfile().name() : e.getName().getString();
    }
    @Override public Vec3d position()     { Vec3 p = e.position();        return new Vec3d(p.x, p.y, p.z); }
    @Override public Vec3d prevPosition() { return new Vec3d(e.xo, e.yo, e.zo); }
    @Override public Vec3d eyePosition()  { Vec3 p = e.getEyePosition();  return new Vec3d(p.x, p.y, p.z); }
    @Override public float yaw()          { return e.getYRot(); }
    @Override public float pitch()        { return e.getXRot(); }
    @Override public double health()      { return e.getHealth(); }
    @Override public boolean isUsingShield() {
        if (!(e instanceof net.minecraft.world.entity.LivingEntity le)) return false;
        if (!le.isUsingItem()) return false;
        var used = le.getUseItem();
        if (used == null || used.isEmpty()) return false;
        var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(used.getItem());
        return id != null && "minecraft:shield".equals(id.toString());
    }
    @Override public double maxHealth()   { return e.getMaxHealth(); }
    @Override public double armor()       { return e.getAttributeValue(Attributes.ARMOR); }
    @Override public double armorToughness() { return e.getAttributeValue(Attributes.ARMOR_TOUGHNESS); }
    @Override public boolean onGround()   { return e.onGround(); }
    @Override public boolean isPlayer()   { return e instanceof Player; }
    @Override public boolean isDead()     { return !e.isAlive(); }
    @Override public boolean isInvisible(){ return e.isInvisible(); }
    @Override public boolean hasEffect(String id) {
        // shifted again, update this one block.
        Identifier rl = Identifier.tryParse(id);
        if (rl == null) return false;
        try {
            var reg = net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT;
            var want = reg.getValue(rl);
            if (want == null) return false;
            for (var inst : e.getActiveEffects()) {
                var held = inst.getEffect().value();
                if (held == want) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }
    @Override public double[] renderBox() {
        AABB b = e.getBoundingBox();
        float pt = net.minecraft.client.Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
        double ox = (e.xo + (e.getX() - e.xo) * pt) - e.getX();
        double oy = (e.yo + (e.getY() - e.yo) * pt) - e.getY();
        double oz = (e.zo + (e.getZ() - e.zo) * pt) - e.getZ();
        return new double[]{b.minX + ox, b.minY + oy, b.minZ + oz, b.maxX + ox, b.maxY + oy, b.maxZ + oz};
    }
    @Override public double[] boundingBox() {
        AABB b = e.getBoundingBox();
        return new double[]{b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ};
    }
    /** Distance to the nearest point of the hitbox — the way vanilla measures attack reach.
     *  (It used to measure to the feet, which made every 3-block reach check ~0.5-1 block short.) */
    @Override public double distanceSqTo(Vec3d p) {
        AABB b = e.getBoundingBox();
        double cx = Math.max(b.minX, Math.min(p.x(), b.maxX));
        double cy = Math.max(b.minY, Math.min(p.y(), b.maxY));
        double cz = Math.max(b.minZ, Math.min(p.z(), b.maxZ));
        double dx = p.x() - cx, dy = p.y() - cy, dz = p.z() - cz;
        return dx * dx + dy * dy + dz * dz;
    }
    @Override public boolean canSee(EntityHandle other) {
        if (!(other instanceof EntityHandleImpl o)) return false;
        return e.hasLineOfSight(o.e);
    }
}
