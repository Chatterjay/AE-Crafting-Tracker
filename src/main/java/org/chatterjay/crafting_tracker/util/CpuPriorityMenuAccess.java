package org.chatterjay.crafting_tracker.util;

import java.util.Map;

public interface CpuPriorityMenuAccess {
    long craftingtracker$setCpuPriority(int serial, boolean lower);

    Map<Integer, Long> craftingtracker$currentCpuPriorities();
}

