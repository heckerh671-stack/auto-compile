// Zalard - Copyright (c) 2026 weksom. All rights reserved. Proprietary; see LICENSE.
package dev.zalard.adapter.mixin;

import dev.zalard.adapter.TranslationService;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds every incoming chat line to the Translation service (which adds a translated line below when it's
 * in another language). Skips our own added line via the suppress flag so it can't translate itself.
 *
 * VERIFY TARGET per mappings: 1.21.11 Mojang = ChatComponent.addMessage(Component). require = 0 → no-op
 * if the signature differs (then point it at whichever addMessage overload player chat flows through).
 */
@Mixin(net.minecraft.client.gui.components.ChatComponent.class)
public abstract class ChatTranslateMixin {

    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), require = 0)
    private void zalard$translate(Component message, CallbackInfo ci) {
        try {
            if (!TranslationService.suppress && TranslationService.active())
                TranslationService.onChat(message.getString());
        } catch (Throwable ignored) {}
    }
}
