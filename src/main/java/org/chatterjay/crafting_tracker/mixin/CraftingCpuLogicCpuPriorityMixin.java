package org.chatterjay.crafting_tracker.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import org.chatterjay.crafting_tracker.util.AeCpuPrioritySelector;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CraftingCpuLogic.class)
public abstract class CraftingCpuLogicCpuPriorityMixin {
    @Shadow(remap = false)
    @Final
    private CraftingCPUCluster cluster;

    @Inject(method = "finishJob", at = @At("HEAD"), remap = false)
    private void craftingtracker$clearRuntimePriorityOnJobFinished(boolean success, CallbackInfo ci) {
        AeCpuPrioritySelector.clearRuntimeAfterJobFinished(cluster);
    }

    @Redirect(
            method = "executeCrafting",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/api/networking/crafting/ICraftingProvider;isBusy()Z"
            ),
            remap = false
    )
    private boolean craftingtracker$checkProviderBusyWithCpuPriority(ICraftingProvider provider) {
        return AeCpuPrioritySelector.isProviderBusyForCpu(cluster, provider);
    }

    @Redirect(
            method = "executeCrafting",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/api/networking/crafting/ICraftingProvider;pushPattern(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z"
            ),
            remap = false
    )
    private boolean craftingtracker$pushPatternWithCpuPriority(
            ICraftingProvider provider,
            IPatternDetails patternDetails,
            KeyCounter[] inputHolder
    ) {
        return AeCpuPrioritySelector.pushPatternWithPriority(cluster, provider, patternDetails, inputHolder);
    }
}

