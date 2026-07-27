package org.chatterjay.crafting_tracker.mixin;

import appeng.client.Point;
import appeng.client.gui.Tooltip;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.chatterjay.crafting_tracker.client.AECpuPriorityClientState;
import org.chatterjay.crafting_tracker.network.payloads.AEPromoteCpuPriorityPacket;
import org.chatterjay.crafting_tracker.util.AeCpuPrioritySelector;
import org.chatterjay.crafting_tracker.util.ModLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;

@Mixin(targets = "net.pedroksl.advanced_ae.gui.quantumcomputer.AdvCpuSelectionList", remap = false)
public abstract class AdvCpuSelectionListMixin {
    @Inject(method = "onMouseUp", at = @At("HEAD"), cancellable = true)
    private void craftingtracker$setQuantumCpuPriorityOnRightClick(Point mousePos, int button,
                                                                    CallbackInfoReturnable<Boolean> cir) {
        if (button != 1 || !AeCpuPrioritySelector.isEnabled()) {
            return;
        }

        Object cpu = craftingtracker$hitTestCpu(mousePos);
        int serial = craftingtracker$serial(cpu);
        if (serial < 0) {
            return;
        }

        boolean lower = Screen.hasShiftDown();
        PacketDistributor.sendToServer(new AEPromoteCpuPriorityPacket(lower ? -serial : serial));
        ModLogger.debug("Sent AdvancedAE quantum CPU runtime priority {} request for serial {}",
                lower ? "lower" : "promote", serial);
        cir.setReturnValue(true);
    }

    @Inject(method = "getTooltip", at = @At("RETURN"), cancellable = true)
    private void craftingtracker$addQuantumCpuPriorityTooltip(int mouseX, int mouseY,
                                                               CallbackInfoReturnable<Tooltip> cir) {
        Tooltip tooltip = cir.getReturnValue();
        if (tooltip == null || !AeCpuPrioritySelector.isEnabled()) {
            return;
        }

        Object cpu = craftingtracker$hitTestCpu(new Point(mouseX, mouseY));
        int serial = craftingtracker$serial(cpu);
        if (serial < 0) {
            return;
        }

        long priority = AECpuPriorityClientState.getPriority(serial);
        if (priority > 0) {
            tooltip.getContent().add(Component.translatable("crafting_tracker.tooltip.ae_cpu_priority.level", priority)
                    .withStyle(ChatFormatting.GREEN));
        }
        tooltip.getContent().add(Component.translatable("crafting_tracker.tooltip.ae_cpu_priority.right_click")
                .withStyle(ChatFormatting.YELLOW));
        tooltip.getContent().add(Component.translatable("crafting_tracker.tooltip.ae_cpu_priority.shift_right_click")
                .withStyle(ChatFormatting.YELLOW));
    }

    private Object craftingtracker$hitTestCpu(Point mousePos) {
        try {
            Method method = craftingtracker$findMethod(getClass(), "hitTestCpu", Point.class);
            if (method == null) {
                return null;
            }
            method.setAccessible(true);
            return method.invoke(this, mousePos);
        } catch (ReflectiveOperationException exception) {
            ModLogger.debug("AdvancedAE quantum CPU hit test failed: {}", exception.toString());
            return null;
        }
    }

    private static int craftingtracker$serial(Object cpu) {
        if (cpu == null) {
            return -1;
        }
        try {
            Method method = cpu.getClass().getMethod("serial");
            Object value = method.invoke(cpu);
            return value instanceof Integer serial ? serial : -1;
        } catch (ReflectiveOperationException exception) {
            return -1;
        }
    }

    private static Method craftingtracker$findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
