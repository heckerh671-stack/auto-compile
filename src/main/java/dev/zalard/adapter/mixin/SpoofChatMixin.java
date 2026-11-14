package dev.zalard.adapter.mixin;

import dev.zalard.adapter.SpoofText;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Name Spoof in chat: every chat line goes through this addMessage overload. */
@Mixin(ChatComponent.class)
public abstract class SpoofChatMixin {
    @ModifyVariable(
            method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Component zalard$chat(Component msg) {
        if (msg == null) return msg;
        try { return SpoofText.replaceNames(msg); } catch (Throwable t) { return msg; }
    }
}
