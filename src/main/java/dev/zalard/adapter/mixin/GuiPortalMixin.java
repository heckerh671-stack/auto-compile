// Zalard - Copyright (c) 2026 weksom. All rights reserved. Proprietary; see LICENSE.
package dev.zalard.adapter.mixin;

import dev.zalard.nat.Z;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No Overlay (portal): cancels the purple nether-portal swirl drawn over your screen. Bit Z.NO_PORTAL.
 *
 * VERIFY TARGET per mappings: 1.21.11 Mojang = Gui.renderPortalOverlay(GuiGraphics, float). require = 0
 * keeps it safe if the name/args differ. 26.2: HUD split into Hud/GuiRenderer — the portal overlay may
 * live on a different class; re-point there.
 */
@Mixin(net.minecraft.client.gui.Gui.class)
public abstract class GuiPortalMixin {

    @Inject(method = "renderPortalOverlay", at = @At("HEAD"), cancellable = true, require = 0)
    private void zalard$noPortal(CallbackInfo ci) {
        if ((Z.qi(Z.Q_NO_OVERLAY) & Z.NO_PORTAL) != 0) ci.cancel();
    }
}
