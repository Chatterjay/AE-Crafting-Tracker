package org.chatterjay.crafting_tracker.mixin;

import java.util.List;
import java.util.UUID;

import org.chatterjay.crafting_tracker.server.CraftTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Supports ECO releases before lane and cooling parameters were added. */
@Mixin(targets = "cn.dancingsnow.neoecoae.api.me.ECOCraftingThread", remap = false)
public abstract class ECOCraftingThreadLegacyHighlightMixin {
    @Inject(method = "startWork", at = @At("RETURN"))
    private void craftingtracker$attachPatternBus(
            List<?> outputs, List<?> inputs, List<?> remaining, UUID craftingJobId,
            int occupiedThreadSlots, CallbackInfo ci) {
        CraftTracker.attachEcoThreadExecution(this);
    }

    @Inject(method = "startBatchWork", at = @At("RETURN"))
    private void craftingtracker$attachBatchPatternBus(
            List<?> outputs, List<?> inputs, List<?> remaining, UUID craftingJobId,
            int occupiedThreadSlots, CallbackInfo ci) {
        CraftTracker.attachEcoThreadExecution(this);
    }

    @Inject(method = "clearWork", at = @At("HEAD"))
    private void craftingtracker$clearPatternBus(CallbackInfo ci) {
        CraftTracker.clearEcoThreadExecution(this);
    }
}
