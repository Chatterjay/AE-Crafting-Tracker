package org.chatterjay.crafting_tracker.compat.emi;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiIngredient;

import org.chatterjay.crafting_tracker.client.screen.NetworkLocatorScreen;

@EmiEntrypoint
public class NetworkLocatorEmiPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        registry.addDragDropHandler(NetworkLocatorScreen.class, new EmiDragDropHandler<NetworkLocatorScreen>() {

            @Override
            public boolean dropStack(NetworkLocatorScreen screen, EmiIngredient ingredient, int x, int y) {
                var slot = screen.getGhostSlotAt(x, y);
                if (slot == null) {
                    return false;
                }

                ItemStack stack = firstItemStack(ingredient);
                if (stack.isEmpty()) {
                    return false;
                }

                screen.applyGhostFilter(screen.getMenu().getFilterSlotIndex(slot), stack);
                return true;
            }

            @Override
            public void render(NetworkLocatorScreen screen, EmiIngredient ingredient,
                               GuiGraphics graphics, int mouseX, int mouseY, float delta) {
                if (firstItemStack(ingredient).isEmpty()) {
                    return;
                }
                screen.renderGhostDropTargets(graphics, mouseX, mouseY);
            }
        });
    }

    private static ItemStack firstItemStack(EmiIngredient ingredient) {
        var stacks = ingredient.getEmiStacks();
        return stacks.isEmpty() ? ItemStack.EMPTY : stacks.get(0).getItemStack();
    }
}
