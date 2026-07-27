package org.chatterjay.crafting_tracker.mixin;

import appeng.client.Point;
import appeng.client.gui.Tooltip;
import appeng.client.gui.widgets.CPUSelectionList;
import appeng.menu.me.crafting.CraftingStatusMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.chatterjay.crafting_tracker.client.AECpuPriorityClientState;
import org.chatterjay.crafting_tracker.network.payloads.AEPromoteCpuPriorityPacket;
import org.chatterjay.crafting_tracker.util.AeCpuPrioritySelector;
import org.chatterjay.crafting_tracker.util.ModLogger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CPUSelectionList.class)
public abstract class CPUSelectionListMixin {
    @Shadow(remap = false)
    @Final
    private CraftingStatusMenu menu;

    @Shadow(remap = false)
    private CraftingStatusMenu.CraftingCpuListEntry hitTestCpu(Point mousePos) {
        throw new AssertionError();
    }

    @Inject(method = "onMouseUp", at = @At("HEAD"), cancellable = true, remap = false)
    private void craftingtracker$promoteCpuOnRightClick(Point mousePos, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button != 1 || !AeCpuPrioritySelector.isEnabled()) {
            return;
        }

        var cpu = hitTestCpu(mousePos);
        if (cpu == null) {
            return;
        }

        boolean lower = Screen.hasShiftDown();
        int serial = cpu.serial();
        menu.selectCpu(serial);
        PacketDistributor.sendToServer(new AEPromoteCpuPriorityPacket(lower ? -serial : serial));
        ModLogger.debug("Sent AE CPU runtime priority {} request for serial {}",
                lower ? "lower" : "promote", serial);
        cir.setReturnValue(true);
    }

    @Inject(method = "getTooltip", at = @At("RETURN"), cancellable = true, remap = false)
    private void craftingtracker$addPriorityTooltip(int mouseX, int mouseY, CallbackInfoReturnable<Tooltip> cir) {
        var tooltip = cir.getReturnValue();
        if (tooltip != null && AeCpuPrioritySelector.isEnabled()) {
            var cpu = hitTestCpu(new Point(mouseX, mouseY));
            if (cpu != null) {
                long priority = AECpuPriorityClientState.getPriority(cpu.serial());
                if (priority > 0) {
                    tooltip.getContent().add(Component.translatable("crafting_tracker.tooltip.ae_cpu_priority.level", priority)
                            .withStyle(ChatFormatting.GREEN));
                }
            }
            tooltip.getContent().add(Component.translatable("crafting_tracker.tooltip.ae_cpu_priority.right_click")
                    .withStyle(ChatFormatting.YELLOW));
            tooltip.getContent().add(Component.translatable("crafting_tracker.tooltip.ae_cpu_priority.shift_right_click")
                    .withStyle(ChatFormatting.YELLOW));
        }
    }
}
