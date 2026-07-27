package org.chatterjay.crafting_tracker.client;

import java.util.HashMap;
import java.util.Map;

public final class AECpuPriorityClientState {
    private static final Map<Integer, Long> PRIORITIES_BY_SERIAL = new HashMap<>();

    private AECpuPriorityClientState() {
    }

    public static void setPriority(int serial, long priority) {
        if (priority <= 0) {
            PRIORITIES_BY_SERIAL.remove(serial);
        } else {
            PRIORITIES_BY_SERIAL.put(serial, priority);
        }
    }

    public static long getPriority(int serial) {
        return PRIORITIES_BY_SERIAL.getOrDefault(serial, 0L);
    }
}

