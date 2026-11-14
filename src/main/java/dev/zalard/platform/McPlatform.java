package dev.zalard.platform;

import java.util.List;
import java.util.UUID;

// the one minecraft surface. combat/esp/etc talk through this.
public interface McPlatform extends McPlatformExt, McPlatformExt2, McPlatformXray {

    String minecraftVersion();
    boolean inGame();

    UUID    selfUuid();
    String  selfName();
    Vec3d   selfPosition();
    Vec3d   selfEyePosition();
    float   selfYaw();
    float   selfPitch();
    boolean selfOnGround();
    double  selfHealth();
    int     selfSelectedSlot();
    void    selfSelectSlot(int slot);

    List<EntityHandle> allPlayers();
    List<EntityHandle> allLiving();
    EntityHandle       byUuid(UUID id);
    EntityHandle       entityUnderCrosshair(double maxDistance);

    BlockHandle blockAt(int x, int y, int z);
    boolean     canPlaceAt(int x, int y, int z);
    boolean     lineOfSight(Vec3d from, Vec3d to);

    void setSilentRotation(float yaw, float pitch);
    void clearSilentRotation();
    boolean hasSilentRotation();
    float getSilentYaw();
    float getSilentPitch();

    void sendSwing(boolean mainHand);
    void sendUseItemOn(int x, int y, int z, Direction face, Vec3d hitVec, boolean mainHand);
    void sendAttack(EntityHandle target);

    void sendClientMessage(String msg);

    FrameRenderer frameRenderer();

    record Vec3d(double x, double y, double z) {
        public double distanceTo(Vec3d o)   { double dx = x-o.x, dy = y-o.y, dz = z-o.z; return Math.sqrt(dx*dx+dy*dy+dz*dz); }
        public double distanceSqTo(Vec3d o) { double dx = x-o.x, dy = y-o.y, dz = z-o.z; return dx*dx+dy*dy+dz*dz; }
        public Vec3d  add(double dx, double dy, double dz) { return new Vec3d(x+dx, y+dy, z+dz); }
        public Vec3d  add(Vec3d o)         { return new Vec3d(x+o.x, y+o.y, z+o.z); }
        public Vec3d  sub(Vec3d o)         { return new Vec3d(x-o.x, y-o.y, z-o.z); }
        public Vec3d  scale(double s)      { return new Vec3d(x*s, y*s, z*s); }
    }

    enum Direction { UP, DOWN, NORTH, SOUTH, EAST, WEST }
}
