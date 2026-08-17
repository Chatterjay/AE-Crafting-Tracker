package org.chatterjay.crafting_tracker.util;

/** Optional CPU implementations expose their native job-suspension control through this bridge. */
public interface DirectCpuSuspendAccess {
    boolean craftingtracker$isJobSuspended();

    void craftingtracker$setJobSuspended(boolean suspended);
}
