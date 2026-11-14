package dev.zalard.gui;

import dev.zalard.nativegui.ZalardNative;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Thin shell: the GUI itself lives in zalard_gui.dll and is drawn by NativeRenderer.
 * This screen captures input and forwards it to the native side (which also owns every setting).
 */
public class ZalardScreen extends Screen {
    public ZalardScreen() {
        super(Component.literal("Zalard"));
        ZalardNative.onOpen();
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {}
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {}

    private static float px(double gui) {
        var w = Minecraft.getInstance().getWindow();
        return (float) (gui * w.getWidth() / (double) Math.max(1, w.getGuiScaledWidth()));
    }
    private static float py(double gui) {
        var w = Minecraft.getInstance().getWindow();
        return (float) (gui * w.getHeight() / (double) Math.max(1, w.getGuiScaledHeight()));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        ZalardNative.mouseDown(px(e.x()), py(e.y()), e.button());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        ZalardNative.mouseUp(px(e.x()), py(e.y()), e.button());
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        ZalardNative.mouseMove(px(e.x()), py(e.y()));
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        ZalardNative.wheel(py(mouseY), (float) (scrollY != 0 ? scrollY : scrollX));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int r = ZalardNative.keyDown(e.key(), e.modifiers());
        if (r == 2) { onClose(); return true; }
        return r == 1 || super.keyPressed(e);
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        return ZalardNative.charTyped(e.codepoint()) == 1 || super.charTyped(e);
    }

    @Override
    public void onClose() {
        ZalardNative.onClose();      // the native side saves the config
        super.onClose();
    }
}
