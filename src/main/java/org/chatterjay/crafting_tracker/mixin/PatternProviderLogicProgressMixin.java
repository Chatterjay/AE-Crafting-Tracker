package org.chatterjay.crafting_tracker.mixin;

import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderLogicHost;

import org.chatterjay.crafting_tracker.server.CraftTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observes successful input transfers, which distinguish a working provider from a blocked queue. */
@Mixin(PatternProviderLogic.class)
public abstract class PatternProviderLogicProgressMixin {
    @Shadow(remap = false)
    @Final
    private PatternProviderLogicHost host;

    @Inject(method = "sendStacksOut", at = @At("RETURN"), remap = false)
    private void craftingtracker$recordPendingInputTransfer(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            CraftTracker.recordPatternProviderTransfer(host.getBlockEntity());
        }
    }
}
