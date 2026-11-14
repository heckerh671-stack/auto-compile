package dev.zalard.adapter.mixin;

import dev.zalard.adapter.RotationBridgeImpl;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Throwing a pearl / wind charge sends a ServerboundUseItemPacket whose rotation vanilla fills from
 * player.getYRot()/getXRot() — your real camera angle, WRAPPED to [-180,180). But while Silent Aim is
 * active the MOVEMENT packet carries its angle in a CONTINUOUS (unwrapped) winding to avoid a 360° snap
 * on the wire (see {@link MovePlayerPacketMixin}). The two then disagree — frequently by a full 360° —
 * so the server sees an impossible instant spin between your last movement and your interaction and
 * kicks it as a bad packet (2b2t's anti-dupe/illegal-packet filter).
 *
 * Make the use packet carry EXACTLY what this tick's movement packet carries, computed the same way.
 * When you throw a pearl/wind charge the combat code has already yielded the silent angle to your real
 * look (combat.h: throwingNow / yieldTicks), so this throws where you're actually aiming AND keeps the
 * two packets identical on the wire. Rotation state is only read here; the movement packet still owns
 * the sent-tracking, so this must not call noteSent/noteRawSent.
 */
@Mixin(value = ServerboundUseItemPacket.class, remap = true)
public abstract class UseItemPacketMixin {

    @Mutable @Accessor("yRot") abstract void zalard$setYRot(float v);
    @Mutable @Accessor("xRot") abstract void zalard$setXRot(float v);

    @Inject(method = "<init>(Lnet/minecraft/world/InteractionHand;IFF)V", at = @At("TAIL"))
    void zalard$matchMovementRotation(InteractionHand hand, int sequence, float yRot, float xRot, CallbackInfo ci) {
        if (!RotationBridgeImpl.active()) return;
        // identical to the movement-packet rewrite: continuous silent yaw (no 360 jump) + silent pitch
        zalard$setYRot(RotationBridgeImpl.continuous(RotationBridgeImpl.yaw(), yRot));
        zalard$setXRot(RotationBridgeImpl.pitch());
    }
}
