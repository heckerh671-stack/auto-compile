package dev.zalard.adapter.mixin;

import dev.zalard.adapter.RotationBridgeImpl;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ServerboundMovePlayerPacket.class, remap = true)
public abstract class MovePlayerPacketMixin {

    @Mutable @Accessor("yRot")   abstract void setYRot(float v);
    @Mutable @Accessor("xRot")   abstract void setXRot(float v);

    // Base constructor (all four subclasses call it):
    // x, y, z, yRot, xRot, onGround, horizontalCollision, hasPos, hasRot
    @Inject(method = "<init>(DDDFFZZZZ)V", at = @At("TAIL"))
    void mako_rewriteRotation(double x, double y, double z, float yRot, float xRot,
                              boolean onGround, boolean horizontalCollision, boolean hasPos, boolean hasRot,
                              CallbackInfo ci) {
        if (!hasRot) return;
        if (!RotationBridgeImpl.active()) {
            RotationBridgeImpl.noteSent(yRot, xRot);
            RotationBridgeImpl.noteRawSent(yRot);
            return;
        }
        // Always write the silent angle. (Flipping hasRot off doesn't help: a Rot/PosRot packet
        // still writes its yaw/pitch on the wire, so it would leak your camera angle for a tick.
        // Duplicate looks are prevented earlier, in LocalPlayerMixin, so vanilla builds a
        // position-only packet instead.)
        float ny = RotationBridgeImpl.continuous(RotationBridgeImpl.yaw(), yRot);   // no 360s on the wire
        float np = RotationBridgeImpl.pitch();
        setYRot(ny);
        setXRot(np);
        RotationBridgeImpl.noteSent(ny, np);
        RotationBridgeImpl.noteRawSent(ny);
    }
}
