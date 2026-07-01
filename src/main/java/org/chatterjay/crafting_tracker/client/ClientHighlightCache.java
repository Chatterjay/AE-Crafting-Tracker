package org.chatterjay.crafting_tracker.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;

import org.chatterjay.crafting_tracker.network.payloads.S2CCraftHighlightData;
import org.chatterjay.crafting_tracker.network.payloads.S2CCraftHighlightData.HighlightEntry;

public enum ClientHighlightCache {
    INSTANCE;

    private final Map<BlockPos, HighlightEntry> highlights = new ConcurrentHashMap<>();
    private volatile int runtimeRemainingTicks = 0;

    public void update(S2CCraftHighlightData data) {
        highlights.clear();
        for (HighlightEntry entry : data.entries()) {
            highlights.put(entry.pos(), entry);
        }
        runtimeRemainingTicks = data.runtimeRemainingTicks();
    }

    public List<HighlightEntry> getActiveHighlights() {
        return new ArrayList<>(highlights.values());
    }

    public int getRuntimeRemainingTicks() {
        return runtimeRemainingTicks;
    }

    public void clear() {
        highlights.clear();
        runtimeRemainingTicks = 0;
    }
}
