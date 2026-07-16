package org.chatterjay.crafting_tracker.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import net.neoforged.neoforge.network.PacketDistributor;

import org.chatterjay.crafting_tracker.item.NetworkLocatorMenu;
import org.chatterjay.crafting_tracker.network.payloads.C2SUpdateFilterSlot;

public class NetworkLocatorScreen extends AbstractContainerScreen<NetworkLocatorMenu> {

    private static final ResourceLocation BACKGROUND =
            ResourceLocation.parse("ae2:textures/guis/toolbox.png");

    private static final int WINDOW_W = 176;
    private static final int WINDOW_H = 168;

    public NetworkLocatorScreen(NetworkLocatorMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = WINDOW_W;
        this.imageHeight = WINDOW_H;
        this.inventoryLabelY = 10000;
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
                applyGhostFilter(this.menu.getFilterSlotIndex(slot), ItemStack.EMPTY);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    public void applyGhostFilter(int slotIndex, ItemStack stack) {
        if (!this.menu.isFilterSlot(slotIndex)) return;
        ItemStack ghostStack = NetworkLocatorMenu.toGhostStack(stack);
        this.menu.setFilterSlot(slotIndex, ghostStack);
        PacketDistributor.sendToServer(new C2SUpdateFilterSlot(slotIndex, ghostStack));
    }

    private boolean isGhostSlot(Slot slot) {
        return this.menu.isFilterMenuSlot(slot);
    }

    public Slot getGhostSlotAt(int mouseX, int mouseY) {
        for (Slot slot : this.menu.slots) {
            if (!isGhostSlot(slot)) continue;

            int x = this.leftPos + slot.x;
            int y = this.topPos + slot.y;
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                return slot;
            }
        }
        return null;
    }

    public void renderGhostDropTargets(GuiGraphics graphics, int mouseX, int mouseY) {
        Slot hovered = getGhostSlotAt(mouseX, mouseY);
        for (Slot slot : this.menu.slots) {
            if (!isGhostSlot(slot)) continue;

            int x = this.leftPos + slot.x;
            int y = this.topPos + slot.y;
            boolean isHovered = slot == hovered;
            int fill = isHovered ? 0x6048D7FF : 0x2448D7FF;
            int border = isHovered ? 0xE8F8FFFF : 0xAA48D7FF;
            graphics.fill(x, y, x + 16, y + 16, fill);
            graphics.fill(x - 1, y - 1, x + 17, y, border);
            graphics.fill(x - 1, y + 16, x + 17, y + 17, border);
            graphics.fill(x - 1, y, x, y + 16, border);
            graphics.fill(x + 16, y, x + 17, y + 16, border);
        }
    }
}
