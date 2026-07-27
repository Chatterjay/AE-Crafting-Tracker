package org.chatterjay.crafting_tracker.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.menu.me.crafting.CraftConfirmMenu;
import org.chatterjay.crafting_tracker.util.AeCpuPrioritySelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(CraftConfirmMenu.class)
public abstract class CraftConfirmMenuCpuPriorityMixin {
    @Shadow(remap = false)
    private IGrid getGrid() {
        throw new AssertionError();
    }

    @Redirect(
            method = "startJob",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/api/networking/crafting/ICraftingService;submitJob(Lappeng/api/networking/crafting/ICraftingPlan;Lappeng/api/networking/crafting/ICraftingRequester;Lappeng/api/networking/crafting/ICraftingCPU;ZLappeng/api/networking/security/IActionSource;)Lappeng/api/networking/crafting/ICraftingSubmitResult;"
            ),
            remap = false
    )
    private ICraftingSubmitResult craftingtracker$submitWithCpuPriority(
            ICraftingService service,
            ICraftingPlan job,
            ICraftingRequester requestingMachine,
            ICraftingCPU target,
            boolean prioritizePower,
            IActionSource source
    ) {
        if (target != null) {
            return service.submitJob(job, requestingMachine, target, prioritizePower, source);
        }

        ICraftingCPU prioritized = AeCpuPrioritySelector.select(getGrid(), job, source);
        return service.submitJob(job, requestingMachine, prioritized, prioritizePower, source);
    }
}

