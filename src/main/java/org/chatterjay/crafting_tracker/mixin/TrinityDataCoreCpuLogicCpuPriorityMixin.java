package org.chatterjay.crafting_tracker.mixin;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingProvider;
import org.chatterjay.crafting_tracker.util.AeCpuPrioritySelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bridges Crafting Tracker priority state into DataEnergistics without a hard dependency. */
@Mixin(targets = "com.fish_dan_.data_energistics.common.crafting.trinity.execution.cpu.TrinityDataCoreCpuLogic",
        remap = false)
public abstract class TrinityDataCoreCpuLogicCpuPriorityMixin {
    @Inject(method = "tickCraftingLogic", at = @At("HEAD"), cancellable = true)
    private void craftingtracker$deferLowerPriorityTrinityCpu(CallbackInfo ci) {
        ICraftingCPU currentCpu = AeCpuPrioritySelector.cpuFromLogic(this);
        if (currentCpu != null && AeCpuPrioritySelector.shouldDeferCpuTick(currentCpu)) {
            ci.cancel();
        }
    }

    @Inject(method = "finishJob", at = @At("HEAD"))
    private void craftingtracker$clearRuntimePriorityOnJobFinished(boolean success, CallbackInfo ci) {
        AeCpuPrioritySelector.clearRuntimeAfterJobFinished(AeCpuPrioritySelector.cpuFromLogic(this));
    }

    @Redirect(
            method = "providerBusy",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/api/networking/crafting/ICraftingProvider;isBusy()Z"
            )
    )
    private boolean craftingtracker$checkProviderBusyWithCpuPriority(ICraftingProvider provider) {
        ICraftingCPU currentCpu = AeCpuPrioritySelector.cpuFromLogic(this);
        return currentCpu == null
                ? provider.isBusy()
                : AeCpuPrioritySelector.isProviderBusyForCpu(currentCpu, provider);
    }
}
