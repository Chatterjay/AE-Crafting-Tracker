package org.chatterjay.crafting_tracker.mixin;

import java.math.BigInteger;
import java.util.UUID;

import org.chatterjay.crafting_tracker.server.CraftTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional hook for NeoECO builds that expose the exact virtual fast path. */
@Mixin(targets = "cn.dancingsnow.neoecoae.blocks.entity.crafting.ECOCraftingPatternBusBlockEntity", remap = false)
public abstract class ECOCraftingExactVirtualPatternBusHighlightMixin {
    @Inject(
            method = "pushExactVirtualBatch(Lcn/dancingsnow/neoecoae/crafting/execution/fastpath/"
                    + "ECOVerifiedFastPathRecipe;Ljava/math/BigInteger;Ljava/util/UUID;)Z",
            at = @At("HEAD"))
    private void craftingtracker$beginExactVirtualBatchDispatch(
            @Coerce Object recipe, BigInteger count, UUID craftingJobId,
            CallbackInfoReturnable<Boolean> cir) {
        CraftTracker.beginEcoExactVirtualBatchPatternBusPush(this, recipe, craftingJobId);
    }

    @Inject(
            method = "pushExactVirtualBatch(Lcn/dancingsnow/neoecoae/crafting/execution/fastpath/"
                    + "ECOVerifiedFastPathRecipe;Ljava/math/BigInteger;Ljava/util/UUID;)Z",
            at = @At("RETURN"))
    private void craftingtracker$endExactVirtualBatchDispatch(
            @Coerce Object recipe, BigInteger count, UUID craftingJobId,
            CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            CraftTracker.recordEcoExactVirtualPatternBusPush(this, recipe, craftingJobId);
        }
        CraftTracker.endEcoPatternBusPush(cir.getReturnValue());
    }
}
