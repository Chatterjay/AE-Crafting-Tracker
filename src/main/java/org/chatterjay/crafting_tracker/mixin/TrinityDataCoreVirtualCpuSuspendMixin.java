package org.chatterjay.crafting_tracker.mixin;

import org.chatterjay.crafting_tracker.util.DirectCpuSuspendAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes DataEnergistics' native Trinity CPU pause control without reflection. */
@Mixin(targets = "com.fish_dan_.data_energistics.common.crafting.trinity.execution.cpu.TrinityDataCoreVirtualCpu",
        remap = false)
public abstract class TrinityDataCoreVirtualCpuSuspendMixin implements DirectCpuSuspendAccess {
    @Shadow(remap = false)
    public abstract boolean isJobSuspended();

    @Shadow(remap = false)
    public abstract void setJobSuspended(boolean suspended);

    @Override
    public boolean craftingtracker$isJobSuspended() {
        return isJobSuspended();
    }

    @Override
    public void craftingtracker$setJobSuspended(boolean suspended) {
        setJobSuspended(suspended);
    }
}
