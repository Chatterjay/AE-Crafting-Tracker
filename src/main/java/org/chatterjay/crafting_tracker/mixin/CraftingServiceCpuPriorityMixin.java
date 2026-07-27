package org.chatterjay.crafting_tracker.mixin;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import org.chatterjay.crafting_tracker.util.AeCpuPrioritySelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;

@Mixin(CraftingService.class)
public abstract class CraftingServiceCpuPriorityMixin {
    @Redirect(
            method = "onServerEndTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Set;iterator()Ljava/util/Iterator;",
                    ordinal = 0
            )
    )
    private Iterator<CraftingCPUCluster> craftingtracker$prioritizeCpuTickOrder(Set<CraftingCPUCluster> cpus) {
        return AeCpuPrioritySelector.orderedTickIterator(cpus);
    }

    @Inject(method = "onServerEndTick", at = @At("TAIL"), remap = false)
    private void craftingtracker$reconcileCpuPriorityAfterTick(CallbackInfo ci) {
        Collection<ICraftingCPU> cpus = ((CraftingService) (Object) this).getCpus();
        AeCpuPrioritySelector.reconcileCpus(cpus);
    }
}

