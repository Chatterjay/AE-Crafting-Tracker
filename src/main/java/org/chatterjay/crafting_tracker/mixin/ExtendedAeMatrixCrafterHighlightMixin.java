package org.chatterjay.crafting_tracker.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;
import org.chatterjay.crafting_tracker.server.CraftTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures matrix jobs at the crafter boundary where the worker is selected. */
@Mixin(targets = "com.glodblock.github.extendedae.common.tileentities.matrix.TileAssemblerMatrixCrafter",
        remap = false)
public abstract class ExtendedAeMatrixCrafterHighlightMixin {
    @Inject(method = "pushJob(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z",
            at = @At("RETURN"))
    private void craftingtracker$recordMatrixJob(
            IPatternDetails pattern, KeyCounter[] inputHolder, CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            CraftTracker.attachMatrixCrafterExecution(this, pattern);
        }
    }
}
