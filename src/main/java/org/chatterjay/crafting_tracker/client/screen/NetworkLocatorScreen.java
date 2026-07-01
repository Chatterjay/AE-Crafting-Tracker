package org.chatterjay.crafting_tracker.client.screen;

import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import org.chatterjay.crafting_tracker.Crafting_tracker;
import org.chatterjay.crafting_tracker.item.NetworkLocatorMenu;
import org.chatterjay.crafting_tracker.network.payloads.C2SUpdateFilterSlot;
import org.slf4j.Logger;

import net.neoforged.neoforge.network.PacketDistributor;

public class NetworkLocatorScreen extends AbstractContainerScreen<NetworkLocatorMenu> {

    private static final Logger LOGGER = LogUtils.getLogger();
    // AE2's toolbox texture — same background used by Network Tool
    private static final ResourceLocation BACKGROUND =
            ResourceLocation.parse("ae2:textures/guis/toolbox.png");

    private static final int WINDOW_W = 176;
    private static final int WINDOW_H = 168;

    public NetworkLocatorScreen(NetworkLocatorMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = WINDOW_W;
        this.imageHeight = WINDOW_H;
        this.inventoryLabelY = 10000; // hide player inventory label (AE2 style)
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        guiGraphics.blit(BACKGROUND, x, y, 0, 0, WINDOW_W, WINDOW_H, 256, 256);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(this.font, this.title, 8, 6, 0x404040, false);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        Slot slot = getSlotUnderMouse();
        if (slot != null && isGhostSlot(slot)) {
            if (scrollY > 0) {
                LOGGER.info("[LocatorScreen] Scroll-wheel clear of slot {}", slot.index);
                slot.set(ItemStack.EMPTY);
                PacketDistributor.sendToServer(new C2SUpdateFilterSlot(slot.index, ItemStack.EMPTY));
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean isGhostSlot(Slot slot) {
        return slot.index >= 0 && slot.index < 9;
    }
}
