package org.chatterjay.crafting_tracker.client.handler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

import appeng.client.gui.me.crafting.CraftingStatusScreen;
import appeng.client.gui.widgets.AE2Button;

import org.chatterjay.crafting_tracker.network.payloads.C2SToggleRuntimeHighlight;

import net.neoforged.neoforge.network.PacketDistributor;

public class CraftingScreenHandler {
    private static final int BUTTON_X = 43;
    private static final int BUTTON_Y = 160;
    private static final int BUTTON_W = 50;
    private static final int BUTTON_H = 20;

    /** Local toggle state — instant, independent of server cache. */
    private static boolean runtimeActive = false;

    public static void register() {
        NeoForge.EVENT_BUS.addListener(CraftingScreenHandler::onScreenInit);
    }

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof CraftingStatusScreen aScreen))
            return;

        int guiLeft = aScreen.getGuiLeft();
        int guiTop = aScreen.getGuiTop();

        var button = new AE2Button(
                guiLeft + BUTTON_X,
                guiTop + BUTTON_Y,
                BUTTON_W,
                BUTTON_H,
                getButtonMessage(),
                btn -> onClick(aScreen)) {

            @Override
            public void renderWidget(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
                if (runtimeActive) {
                    this.setMessage(Component.translatable("button.crafting_tracker.runtime_highlight.on"));
                    guiGraphics.blitSprite(
                            ResourceLocation.parse("ae2:button_highlighted"),
                            this.getX(), this.getY(), this.width, this.height);
                    this.renderButtonText(guiGraphics, Minecraft.getInstance().font, 2, 15921906, 1);
                } else {
                    this.setMessage(Component.translatable("button.crafting_tracker.runtime_highlight.off"));
                    super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
                }
            }
        };

        event.addListener(button);
    }

    private static Component getButtonMessage() {
        return runtimeActive
                ? Component.translatable("button.crafting_tracker.runtime_highlight.on")
                : Component.translatable("button.crafting_tracker.runtime_highlight.off");
    }

    private static void onClick(CraftingStatusScreen screen) {
        runtimeActive = !runtimeActive;
        PacketDistributor.sendToServer(new C2SToggleRuntimeHighlight(runtimeActive));
        if (screen.getFocused() != null)
            screen.setFocused(null);
    }
}
