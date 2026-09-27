package org.chatterjay.crafting_tracker.mixin;

import appeng.api.networking.crafting.ICraftingProvider;
import org.chatterjay.crafting_tracker.util.AeCpuPrioritySelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Applies runtime priority while NeoECO builds its provider candidate list. */
@Mixin(targets = "cn.dancingsnow.neoecoae.crafting.execution.ECOProviderCursor", remap = false)
public abstract class ECOProviderCursorCpuPriorityMixin {
    @Redirect(
            method = "availableProviders(Lappeng/api/crafting/IPatternDetails;Ljava/util/function/Supplier;"
                    + "Ljava/util/function/Predicate;Ljava/util/function/BiConsumer;Ljava/util/function/BiPredicate;)Ljava/util/List;",
            at = @At(value = "INVOKE", target = "Lappeng/api/networking/crafting/ICraftingProvider;isBusy()Z"))
    private boolean craftingtracker$checkProviderBusyWithCpuPriority(ICraftingProvider provider) {
        var cpu = AeCpuPrioritySelector.currentDispatchCpu();
        return cpu == null ? provider.isBusy() : AeCpuPrioritySelector.isProviderBusyForCpu(cpu, provider);
    }
}
