package org.chatterjay.crafting_tracker.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;
import org.chatterjay.crafting_tracker.server.CraftTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Associates an ExtendedAE matrix worker with the physical pattern core that dispatched it. */
@Mixin(targets = "com.glodblock.github.extendedae.common.tileentities.matrix.TileAssemblerMatrixPattern",
        remap = false)
public abstract class ExtendedAeMatrixPatternHighlightMixin {
    @Inject(method = "pushPattern(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z",
            at = @At("HEAD"))
    private void craftingtracker$beginMatrixDispatch(
            IPatternDetails pattern, KeyCounter[] inputHolder, CallbackInfoReturnable<Boolean> cir) {
        CraftTracker.beginMatrixPatternPush(this, pattern);
    }

    @Inject(method = "pushPattern(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z",
            at = @At("RETURN"))
    private void craftingtracker$endMatrixDispatch(
            IPatternDetails pattern, KeyCounter[] inputHolder, CallbackInfoReturnable<Boolean> cir) {
        CraftTracker.endMatrixPatternPush();
    }
}
