package org.chatterjay.crafting_tracker.mixin;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.energy.IEnergyService;
import appeng.me.service.CraftingService;
import org.chatterjay.crafting_tracker.server.CraftTracker;
import org.chatterjay.crafting_tracker.util.AeCpuPrioritySelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** CPU priority bridge for NeoECO 21.2, whose implementation moved out of the API shim. */
@Mixin(targets = "cn.dancingsnow.neoecoae.crafting.execution.ECOCraftingCPULogic", remap = false)
public abstract class ECOCraftingCPULogicModernCpuPriorityMixin {
    @Inject(method = "tickCraftingLogic", at = @At("HEAD"), cancellable = true)
    private void craftingtracker$deferLowerPriorityEcoCpu(
            IEnergyService energyService, CraftingService craftingService, CallbackInfo ci) {
        ICraftingCPU cpu = AeCpuPrioritySelector.cpuFromLogic(this);
        if (cpu != null && AeCpuPrioritySelector.shouldDeferCpuTick(cpu)) {
            ci.cancel();
            return;
        }
        AeCpuPrioritySelector.enterDispatchContext(cpu);
    }

    @Inject(method = "tickCraftingLogic", at = @At("RETURN"))
    private void craftingtracker$clearDispatchContext(
            IEnergyService energyService, CraftingService craftingService, CallbackInfo ci) {
        AeCpuPrioritySelector.exitDispatchContext();
    }

    @Inject(method = "finishJob", at = @At("HEAD"))
    private void craftingtracker$clearRuntimePriorityOnJobFinished(boolean success, CallbackInfo ci) {
        ICraftingCPU cpu = AeCpuPrioritySelector.cpuFromLogic(this);
        CraftTracker.clearCpuPattern(cpu);
        CraftTracker.clearProviderJob(this);
        AeCpuPrioritySelector.clearRuntimeAfterJobFinished(cpu);
    }
}
