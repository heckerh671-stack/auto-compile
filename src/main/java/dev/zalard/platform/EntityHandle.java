package dev.zalard.platform;

import java.util.UUID;

// entity wrapper. version-independent surface.
public interface EntityHandle {
    UUID uuid();
    String name();
    /** Network entity id (what entity packets refer to). */
    default int entityId() { return -1; }

    McPlatform.Vec3d position();          // interpolated, current frame
    McPlatform.Vec3d prevPosition();      // last-tick position (for backtrack / lead)
    McPlatform.Vec3d eyePosition();

    float yaw();
    float pitch();

    double health();
    /** True if this entity is currently using a shield (right-click held with shield in hand).
     *  Platform-side: LivingEntity#isBlocking() in modern MC. ShieldBreaker reads this. */
    default boolean isUsingShield() { throw new UnsupportedOperationException(); }
    double maxHealth();
    double armor();
    double armorToughness();

    boolean onGround();
    boolean isPlayer();
    boolean isDead();
    boolean isInvisible();
    boolean hasEffect(String effectId);   // "minecraft:regeneration"

    /** [minX,minY,minZ,maxX,maxY,maxZ] bounding box, interpolated this frame. */
    double[] boundingBox();
    /** Bounding box where the entity is DRAWN this frame (moved between ticks like its model). */
    default double[] renderBox() { return boundingBox(); }

    double distanceSqTo(McPlatform.Vec3d p);

    boolean canSee(EntityHandle other);   // raycast line-of-sight
}
