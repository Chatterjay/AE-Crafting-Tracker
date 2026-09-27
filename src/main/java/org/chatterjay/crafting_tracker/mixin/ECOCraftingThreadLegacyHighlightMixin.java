package org.chatterjay.crafting_tracker.mixin;

import java.util.List;
import java.util.UUID;

import org.chatterjay.crafting_tracker.server.CraftTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Thread-side hooks for NeoECO builds that start a worker per work kind instead of
 * installing it centrally. Only one of this class and
 * {@link ECOCraftingThreadHighlightMixin} is applied, so a worker is never attached
 * twice for the same execution.
 */
@Mixin(targets = "cn.dancingsnow.neoecoae.crafting.execution.worker.ECOCraftingThread", remap = false)
public abstract class ECOCraftingThreadLegacyHighlightMixin {
    private static final String START_WORK =
            "startWork(Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/UUID;I)V";
    private static final String START_BATCH_WORK =
            "startBatchWork(Lcn/dancingsnow/neoecoae/crafting/execution/fastpath/ECOBatchCraftingWork;)V";
    private static final String START_VIRTUAL_WORK =
            "startVirtualWork(Lcn/dancingsnow/neoecoae/crafting/execution/fastpath/ECOVirtualCraftingWork;)V";

    @Inject(method = START_WORK, at = @At("RETURN"), require = 0)
    private void craftingtracker$attachPatternBus(
            List<?> outputs, List<?> inputs, List<?> remaining, UUID craftingJobId,
            int occupiedThreadSlots, CallbackInfo ci) {
        CraftTracker.attachEcoThreadExecution(this, craftingJobId);
    }

    @Inject(method = START_BATCH_WORK, at = @At("RETURN"), require = 0)
    private void craftingtracker$attachBatchPatternBus(@Coerce Object work, CallbackInfo ci) {
        CraftTracker.attachEcoThreadExecution(this, CraftTracker.extractEcoJobId(work));
    }

    @Inject(method = START_VIRTUAL_WORK, at = @At("RETURN"), require = 0)
    private void craftingtracker$attachVirtualPatternBus(@Coerce Object work, CallbackInfo ci) {
        CraftTracker.attachEcoThreadExecution(this, CraftTracker.extractEcoJobId(work));
    }

    @Inject(method = "clearWork()V", at = @At("HEAD"), require = 0)
    private void craftingtracker$clearPatternBus(CallbackInfo ci) {
        CraftTracker.clearEcoThreadExecution(this);
    }
}
