package org.chatterjay.crafting_tracker.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.KeyCounter;
import appeng.me.service.CraftingService;
import org.chatterjay.crafting_tracker.util.AeCpuPrioritySelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bridges Crafting Tracker priority state into Neo ECO AE Extension without a hard dependency. */
@Mixin(targets = "cn.dancingsnow.neoecoae.api.me.ECOCraftingCPULogic", remap = false)
public abstract class ECOCraftingCPULogicCpuPriorityMixin {
    @Inject(method = "tickCraftingLogic", at = @At("HEAD"), cancellable = true)
    private void craftingtracker$deferLowerPriorityEcoCpu(
            IEnergyService energyService,
            CraftingService craftingService,
            CallbackInfo ci
    ) {
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
            method = {"executeCrafting", "collectAvailableProviders", "hasReadyProvider"},
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

    @Redirect(
            method = "executeCrafting",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/api/networking/crafting/ICraftingProvider;pushPattern(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z"
            )
    )
    private boolean craftingtracker$pushPatternWithCpuPriority(
            ICraftingProvider provider,
            IPatternDetails patternDetails,
            KeyCounter[] inputHolder
    ) {
        ICraftingCPU currentCpu = AeCpuPrioritySelector.cpuFromLogic(this);
        return currentCpu == null
                ? provider.pushPattern(patternDetails, inputHolder)
                : AeCpuPrioritySelector.pushPatternWithPriority(currentCpu, provider, patternDetails, inputHolder);
    }
}
