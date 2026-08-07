package org.chatterjay.crafting_tracker.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import net.minecraft.core.BlockPos;

import org.chatterjay.crafting_tracker.network.payloads.S2CCraftHighlightData;
import org.chatterjay.crafting_tracker.network.payloads.S2CCraftHighlightData.HighlightEntry;
import org.chatterjay.crafting_tracker.config.CTConfig;
import org.chatterjay.crafting_tracker.util.ModLogger;

public enum ClientHighlightCache {
    INSTANCE;

    private volatile Map<BlockPos, HighlightEntry> highlights = Map.of();
    private volatile int runtimeRemainingTicks = 0;

    public void update(S2CCraftHighlightData data) {
        ModLogger.debugThrottled("client.craft.packet", CTConfig.debugLogIntervalTicks,
                "Client received craft highlight packet entries={} runtimeRemainingTicks={}",
                data.entries().size(), data.runtimeRemainingTicks());

        // State changes arrive immediately and stable state has a periodic
        // heartbeat. Replace the complete snapshot atomically on every packet.
        Map<BlockPos, HighlightEntry> next = new HashMap<>();
        for (HighlightEntry entry : data.entries()) {
            next.put(entry.pos(), entry);
            ModLogger.debugThrottled("client.craft.pos." + entry.pos().asLong(),
                    CTConfig.debugLogIntervalTicks,
                    "Client craft highlight cache pos={} status={} outputs={} currentId={}",
                    entry.pos(), entry.statusOrdinal(), entry.outputs().size(), entry.currentCraftingId());
        }
        int removed = 0;
        Map<BlockPos, HighlightEntry> previous = highlights;
        for (BlockPos pos : previous.keySet()) {
            if (!next.containsKey(pos)) removed++;
        }
        highlights = Map.copyOf(next);
        if (removed > 0) ModLogger.debug("Client removed {} stale craft highlight entries", removed);
        runtimeRemainingTicks = data.runtimeRemainingTicks();
    }

    public List<HighlightEntry> getActiveHighlights() {
        return new ArrayList<>(highlights.values());
    }

    public int getRuntimeRemainingTicks() {
        return runtimeRemainingTicks;
    }

    public void clear() {
        highlights = Map.of();
        runtimeRemainingTicks = 0;
    }
}
