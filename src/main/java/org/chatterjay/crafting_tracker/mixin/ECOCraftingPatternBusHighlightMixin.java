package org.chatterjay.crafting_tracker.mixin;

import java.util.UUID;

import org.chatterjay.crafting_tracker.server.CraftTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;

/**
 * Associates a worker execution with the exact FD pattern bus that dispatched it.
 *
 * <p>NeoECO accepts a job through several independent entry points - the AE2 provider
 * push, the extracted-pattern push, the verified fast-path batch and the virtual fast
 * path - and both their names and payload types changed between releases. Every
 * injection is therefore optional, so a release that no longer ships one of them still
 * loads instead of failing the whole mixin configuration.
 */
@Mixin(targets = "cn.dancingsnow.neoecoae.blocks.entity.crafting.ECOCraftingPatternBusBlockEntity", remap = false)
public abstract class ECOCraftingPatternBusHighlightMixin {
    private static final String EXTRACTED_PUSH =
            "pushPattern(Lcn/dancingsnow/neoecoae/crafting/execution/fastpath/"
                    + "ECOExtractedPatternExecution;Ljava/util/UUID;)Z";
    private static final String PROVIDER_PUSH =
            "pushPattern(Lappeng/api/crafting/IPatternDetails;"
                    + "[Lappeng/api/stacks/KeyCounter;Ljava/util/UUID;)Z";
    private static final String PROVIDER_PUSH_SLOW =
            "pushPatternSlow(Lappeng/api/crafting/IPatternDetails;"
                    + "[Lappeng/api/stacks/KeyCounter;Ljava/util/UUID;)Z";
    private static final String VERIFIED_BATCH =
            "acceptVerifiedBatch(Lcn/dancingsnow/neoecoae/crafting/execution/fastpath/"
                    + "ECOVerifiedFastPathExecution;Lcn/dancingsnow/neoecoae/blocks/entity/crafting/"
                    + "ECOCraftingPatternBusBlockEntity$BatchFastPathOffer;)Z";
    private static final String VIRTUAL_BATCH =
            "pushVirtualBatch(Lcn/dancingsnow/neoecoae/crafting/execution/fastpath/"
                    + "ECOVerifiedVirtualExecution;Lcn/dancingsnow/neoecoae/blocks/entity/crafting/"
                    + "ECOCraftingPatternBusBlockEntity$VirtualFastPathOffer;)Z";
    /** Current NeoECO keeps this entry point as a stub; older builds dispatched through it. */
    private static final String BATCH_REQUEST =
            "pushBatch(Lcn/dancingsnow/neoecoae/crafting/execution/fastpath/ECOBatchCraftingRequest;"
                    + "Lcn/dancingsnow/neoecoae/blocks/entity/crafting/"
                    + "ECOCraftingPatternBusBlockEntity$BatchFastPathOffer;)Z";
    private static final String PREPARE_FAST_PATH =
            "eco$prepareFastPath(Lcn/dancingsnow/neoecoae/api/me/provider/ECOBatchDispatchContext;)"
                    + "Lcn/dancingsnow/neoecoae/api/me/provider/ECOFastPathDispatchProvider$Preparation;";
    private static final String PREPARE_EXACT_FAST_PATH =
            "eco$prepareExactFastPath(Lcn/dancingsnow/neoecoae/api/me/provider/ECOBatchDispatchContext;"
                    + "Ljava/math/BigInteger;)Lcn/dancingsnow/neoecoae/api/me/provider/"
                    + "ECOFastPathDispatchProvider$ExactPreparation;";

    // --- Verified fast-path batch: NeoECO's primary dispatch path ---

    @Inject(method = VERIFIED_BATCH, at = @At("HEAD"), require = 0)
    private void craftingtracker$beginVerifiedBatch(
            @Coerce Object execution, @Coerce Object offer, CallbackInfoReturnable<Boolean> cir) {
        CraftTracker.beginEcoPatternBusPush(this, execution, CraftTracker.extractEcoJobId(execution));
    }

    @Inject(method = VERIFIED_BATCH, at = @At("RETURN"), require = 0)
    private void craftingtracker$endVerifiedBatch(
            @Coerce Object execution, @Coerce Object offer, CallbackInfoReturnable<Boolean> cir) {
        boolean accepted = Boolean.TRUE.equals(cir.getReturnValue());
        if (accepted) {
            CraftTracker.recordEcoPatternBusPush(this, execution,
                    CraftTracker.extractEcoJobId(execution));
        }
        CraftTracker.endEcoPatternBusPush(accepted);
    }

    // --- AE2 provider push, which hands the pattern over directly ---

    @Inject(method = PROVIDER_PUSH, at = @At("HEAD"), require = 0)
    private void craftingtracker$beginProviderPush(
            IPatternDetails details, KeyCounter[] container, UUID craftingJobId,
            CallbackInfoReturnable<Boolean> cir) {
        CraftTracker.beginEcoProviderPatternBusPush(this, details, craftingJobId);
    }

    @Inject(method = PROVIDER_PUSH, at = @At("RETURN"), require = 0)
    private void craftingtracker$endProviderPush(
            IPatternDetails details, KeyCounter[] container, UUID craftingJobId,
            CallbackInfoReturnable<Boolean> cir) {
        boolean accepted = Boolean.TRUE.equals(cir.getReturnValue());
        if (accepted) {
            CraftTracker.recordEcoProviderPatternBusPush(this, details, craftingJobId);
        }
        CraftTracker.endEcoPatternBusPush(accepted);
    }

    @Inject(method = PROVIDER_PUSH_SLOW, at = @At("HEAD"), require = 0)
    private void craftingtracker$beginProviderPushSlow(
            IPatternDetails details, KeyCounter[] container, UUID craftingJobId,
            CallbackInfoReturnable<Boolean> cir) {
        CraftTracker.beginEcoProviderPatternBusPush(this, details, craftingJobId);
    }

    @Inject(method = PROVIDER_PUSH_SLOW, at = @At("RETURN"), require = 0)
    private void craftingtracker$endProviderPushSlow(
            IPatternDetails details, KeyCounter[] container, UUID craftingJobId,
            CallbackInfoReturnable<Boolean> cir) {
        boolean accepted = Boolean.TRUE.equals(cir.getReturnValue());
        if (accepted) {
            CraftTracker.recordEcoProviderPatternBusPush(this, details, craftingJobId);
        }
        CraftTracker.endEcoPatternBusPush(accepted);
    }

    // --- Extracted-pattern push, used by NeoECO's own dispatch loop ---

    @Inject(method = EXTRACTED_PUSH, at = @At("HEAD"), require = 0)
    private void craftingtracker$beginPatternDispatch(
            @Coerce Object execution, UUID craftingJobId, CallbackInfoReturnable<Boolean> cir) {
        CraftTracker.beginEcoPatternBusPush(this, execution, craftingJobId);
    }

    @Inject(method = EXTRACTED_PUSH, at = @At("RETURN"), require = 0)
    private void craftingtracker$endPatternDispatch(
            @Coerce Object execution, UUID craftingJobId, CallbackInfoReturnable<Boolean> cir) {
        boolean accepted = Boolean.TRUE.equals(cir.getReturnValue());
        if (accepted) {
            CraftTracker.recordEcoPatternBusPush(this, execution, craftingJobId);
        }
        CraftTracker.endEcoPatternBusPush(accepted);
    }

    // --- Virtual fast path ---

    @Inject(method = VIRTUAL_BATCH, at = @At("HEAD"), require = 0)
    private void craftingtracker$beginVirtualBatch(
            @Coerce Object execution, @Coerce Object offer, CallbackInfoReturnable<Boolean> cir) {
        CraftTracker.beginEcoVirtualBatchPatternBusPush(this, execution);
    }

    @Inject(method = VIRTUAL_BATCH, at = @At("RETURN"), require = 0)
    private void craftingtracker$endVirtualBatch(
            @Coerce Object execution, @Coerce Object offer, CallbackInfoReturnable<Boolean> cir) {
        boolean accepted = Boolean.TRUE.equals(cir.getReturnValue());
        if (accepted) {
            CraftTracker.recordEcoVirtualPatternBusPush(this, execution);
        }
        CraftTracker.endEcoPatternBusPush(accepted);
    }

    // --- Legacy batch request, kept so the dispatch scope stays balanced ---

    @Inject(method = BATCH_REQUEST, at = @At("HEAD"), require = 0)
    private void craftingtracker$beginBatchDispatch(
            @Coerce Object request, @Coerce Object offer, CallbackInfoReturnable<Boolean> cir) {
        CraftTracker.beginEcoBatchPatternBusPush(this, request);
    }

    @Inject(method = BATCH_REQUEST, at = @At("RETURN"), require = 0)
    private void craftingtracker$endBatchDispatch(
            @Coerce Object request, @Coerce Object offer, CallbackInfoReturnable<Boolean> cir) {
        boolean accepted = Boolean.TRUE.equals(cir.getReturnValue());
        if (accepted) {
            CraftTracker.recordEcoPatternBusPush(this, request,
                    CraftTracker.extractEcoJobId(request));
        }
        CraftTracker.endEcoPatternBusPush(accepted);
    }

    // --- Fast-path preparation ---
    // The exact virtual fast path prepares its offer on the bus but then starts the
    // worker straight from the preparation lambda, so the bus never appears in the call
    // that accepts the job. Recording the prepared pair is what keeps that job attached.

    @Inject(method = PREPARE_FAST_PATH, at = @At("HEAD"), require = 0)
    private void craftingtracker$noteFastPathPreparation(
            @Coerce Object context, CallbackInfoReturnable<?> cir) {
        CraftTracker.noteEcoFastPathPreparation(this, context);
    }

    @Inject(method = PREPARE_EXACT_FAST_PATH, at = @At("HEAD"), require = 0)
    private void craftingtracker$noteExactFastPathPreparation(
            @Coerce Object context, @Coerce Object craftCount, CallbackInfoReturnable<?> cir) {
        CraftTracker.noteEcoFastPathPreparation(this, context);
    }
}
