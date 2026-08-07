package org.chatterjay.crafting_tracker.mixin;

import org.chatterjay.crafting_tracker.server.CraftTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures accepted Trinity counted dispatches after ownership and accounting have both succeeded. */
@Mixin(targets = "com.fish_dan_.data_energistics.common.crafting.trinity.dispatch.commit.CraftingDispatchCommitterImpl",
        remap = false)
public abstract class TrinityCraftingDispatchCommitterHighlightMixin {
    @Inject(
            method = "commit(Lcom/fish_dan_/data_energistics/common/crafting/trinity/dispatch/commit/"
                    + "CraftingDispatchCommitRequest;)Lcom/fish_dan_/data_energistics/common/crafting/trinity/dispatch/model/"
                    + "CraftingDispatchResult;",
            at = @At("RETURN"))
    private void craftingtracker$recordAcceptedDispatch(
            @Coerce Object request, CallbackInfoReturnable<Object> cir) {
        CraftTracker.recordTrinityDispatch(request, cir.getReturnValue());
    }
}
