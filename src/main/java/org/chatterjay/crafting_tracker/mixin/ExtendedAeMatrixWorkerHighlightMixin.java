package org.chatterjay.crafting_tracker.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;
import net.minecraft.core.Direction;
import org.chatterjay.crafting_tracker.server.CraftTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures the exact ExtendedAE matrix worker that accepted a dispatched pattern. */
@Mixin(targets = "com.glodblock.github.extendedae.common.me.CraftingThread", remap = false)
public abstract class ExtendedAeMatrixWorkerHighlightMixin {
    @Inject(method = "acceptJob(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;"
            + "Lnet/minecraft/core/Direction;)Z", at = @At("RETURN"))
    private void craftingtracker$attachMatrixPattern(
            IPatternDetails pattern, KeyCounter[] inputHolder, Direction direction,
            CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            CraftTracker.attachMatrixThreadExecution(this);
        }
    }
}
