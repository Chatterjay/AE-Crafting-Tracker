package org.chatterjay.crafting_tracker.mixin;

import org.chatterjay.crafting_tracker.server.CraftTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps an FD bus highlighted only while its own ECO worker execution is alive.
 *
 * <p>NeoECO installs every work kind - single, batch and virtual - through
 * {@code installWork}, so hooking that one method covers all of them. The worker work
 * record is package-private and changed shape between releases, so the job id is read
 * from the thread itself whenever the record cannot be inspected.
 */
@Mixin(targets = "cn.dancingsnow.neoecoae.crafting.execution.worker.ECOCraftingThread", remap = false)
public abstract class ECOCraftingThreadHighlightMixin {
    private static final String INSTALL_WORK =
            "installWork(Lcn/dancingsnow/neoecoae/crafting/execution/worker/ECOCraftingThreadWork;)V";

    @Inject(method = INSTALL_WORK, at = @At("RETURN"), require = 0)
    private void craftingtracker$attachPatternBus(@Coerce Object work, CallbackInfo ci) {
        CraftTracker.attachEcoThreadExecution(this, CraftTracker.extractEcoJobId(work));
    }

    @Inject(method = "clearWork()V", at = @At("HEAD"), require = 0)
    private void craftingtracker$clearPatternBus(CallbackInfo ci) {
        CraftTracker.clearEcoThreadExecution(this);
    }
}
