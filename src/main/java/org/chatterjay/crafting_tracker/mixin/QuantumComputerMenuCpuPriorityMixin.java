package org.chatterjay.crafting_tracker.mixin;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.menu.me.crafting.CraftingCPUMenu;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.chatterjay.crafting_tracker.network.payloads.AECpuPrioritySyncPacket;
import org.chatterjay.crafting_tracker.util.AeCpuPrioritySelector;
import org.chatterjay.crafting_tracker.util.CpuPriorityMenuAccess;
import org.chatterjay.crafting_tracker.util.ModLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

@Mixin(targets = "net.pedroksl.advanced_ae.gui.quantumcomputer.QuantumComputerMenu", remap = false)
public abstract class QuantumComputerMenuCpuPriorityMixin implements CpuPriorityMenuAccess {
    @Shadow
    private List<?> lastCpuSet;

    @Shadow
    private WeakHashMap<ICraftingCPU, Integer> cpuSerialMap;

    @Unique
    private final Map<Integer, Long> craftingtracker$lastSentCpuPriorities = new HashMap<>();

    @Inject(method = "broadcastChanges", at = @At("TAIL"))
    private void craftingtracker$reconcileQuantumCpuPriorityHolds(CallbackInfo ci) {
        AeCpuPrioritySelector.reconcileCpus(craftingtracker$craftingCpus());
        craftingtracker$syncCpuPriorityChanges();
    }

    @Override
    public long craftingtracker$setCpuPriority(int serial, boolean lower) {
        for (ICraftingCPU cpu : craftingtracker$craftingCpus()) {
            if (cpuSerialMap.getOrDefault(cpu, -1) == serial) {
                return lower
                        ? AeCpuPrioritySelector.lowerRuntime(cpu, craftingtracker$craftingCpus())
                        : AeCpuPrioritySelector.promoteRuntime(cpu, craftingtracker$craftingCpus());
            }
        }
        ModLogger.debug("AdvancedAE quantum CPU runtime priority change skipped: serial {} was not found", serial);
        return 0;
    }

    @Override
    public Map<Integer, Long> craftingtracker$currentCpuPriorities() {
        Map<Integer, Long> priorities = new HashMap<>();
        for (ICraftingCPU cpu : craftingtracker$craftingCpus()) {
            int serial = cpuSerialMap.getOrDefault(cpu, -1);
            if (serial >= 0) {
                priorities.put(serial, AeCpuPrioritySelector.runtimePriorityForDisplay(cpu));
            }
        }
        return priorities;
    }

    private List<ICraftingCPU> craftingtracker$craftingCpus() {
        return lastCpuSet.stream()
                .filter(ICraftingCPU.class::isInstance)
                .map(ICraftingCPU.class::cast)
                .toList();
    }

    @Unique
    private void craftingtracker$syncCpuPriorityChanges() {
        if (!AeCpuPrioritySelector.isEnabled()
                || !(((CraftingCPUMenu) (Object) this).getPlayer() instanceof ServerPlayer player)) {
            return;
        }

        Map<Integer, Long> current = craftingtracker$currentCpuPriorities();
        HashSet<Integer> serials = new HashSet<>(craftingtracker$lastSentCpuPriorities.keySet());
        serials.addAll(current.keySet());
        for (int serial : serials) {
            long priority = current.getOrDefault(serial, 0L);
            if (!Objects.equals(craftingtracker$lastSentCpuPriorities.get(serial), priority)) {
                PacketDistributor.sendToPlayer(player, new AECpuPrioritySyncPacket(serial, priority));
            }
        }
        craftingtracker$lastSentCpuPriorities.clear();
        craftingtracker$lastSentCpuPriorities.putAll(current);
    }
}

