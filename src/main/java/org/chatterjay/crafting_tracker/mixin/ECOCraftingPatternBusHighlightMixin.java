package org.chatterjay.crafting_tracker.mixin;

import java.util.UUID;

import org.chatterjay.crafting_tracker.server.CraftTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Associates a worker execution with the exact FD pattern bus that dispatched it. */
@Mixin(targets = "cn.dancingsnow.neoecoae.blocks.entity.crafting.ECOCraftingPatternBusBlockEntity", remap = false)
public abstract class ECOCraftingPatternBusHighlightMixin {
    @Inject(
            method = "pushBatch(Lcn/dancingsnow/neoecoae/impl/crafting/fastpath/"
                    + "ECOBatchCraftingRequest;Lcn/dancingsnow/neoecoae/blocks/entity/crafting/"
                    + "ECOCraftingPatternBusBlockEntity$BatchFastPathOffer;)Z",
            at = @At("HEAD"))
    private void craftingtracker$beginBatchDispatch(
            @Coerce Object request, @Coerce Object offer, CallbackInfoReturnable<Boolean> cir) {
        CraftTracker.beginEcoBatchPatternBusPush(this, request);
    }

    @Inject(
            method = "pushBatch(Lcn/dancingsnow/neoecoae/impl/crafting/fastpath/"
                    + "ECOBatchCraftingRequest;Lcn/dancingsnow/neoecoae/blocks/entity/crafting/"
                    + "ECOCraftingPatternBusBlockEntity$BatchFastPathOffer;)Z",
            at = @At("RETURN"))
    private void craftingtracker$endBatchDispatch(
            @Coerce Object request, @Coerce Object offer, CallbackInfoReturnable<Boolean> cir) {
        CraftTracker.endEcoPatternBusPush();
    }

    @Inject(
            method = "pushPattern(Lcn/dancingsnow/neoecoae/impl/crafting/fastpath/"
                    + "ECOExtractedPatternExecution;Ljava/util/UUID;)Z",
            at = @At("HEAD"))
    private void craftingtracker$beginPatternDispatch(
            @Coerce Object execution, UUID craftingJobId, CallbackInfoReturnable<Boolean> cir) {
        CraftTracker.beginEcoPatternBusPush(this, execution, craftingJobId);
    }

    @Inject(
            method = "pushPattern(Lcn/dancingsnow/neoecoae/impl/crafting/fastpath/"
                    + "ECOExtractedPatternExecution;Ljava/util/UUID;)Z",
            at = @At("RETURN"))
    private void craftingtracker$endPatternDispatch(
            @Coerce Object execution, UUID craftingJobId, CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            CraftTracker.recordEcoPatternBusPush(this, execution, craftingJobId);
        }
        CraftTracker.endEcoPatternBusPush();
    }
}
