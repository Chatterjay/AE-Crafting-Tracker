package org.chatterjay.crafting_tracker.client;

import net.minecraft.core.BlockPos;

import org.chatterjay.crafting_tracker.network.payloads.S2CLocatorHighlights;
import org.chatterjay.crafting_tracker.network.payloads.S2CLocatorHighlights.LocatorHit;
import org.chatterjay.crafting_tracker.config.CTConfig;
import org.chatterjay.crafting_tracker.util.ModLogger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public enum ClientLocatorCache {
    INSTANCE;

    private volatile Map<BlockPos, List<LocatorHit>> hits = Map.of();
    private volatile int runtimeRemainingTicks = 0;

    public void update(S2CLocatorHighlights data) {
        ModLogger.debugThrottled("client.locator.packet", CTConfig.debugLogIntervalTicks,
                "Client received locator highlight packet blocks={} hits={} runtimeRemainingTicks={}",
                data.hits().size(), data.hits().values().stream().mapToInt(List::size).sum(),
                data.runtimeRemainingTicks());
        Map<BlockPos, List<LocatorHit>> next = new HashMap<>();
        for (var entry : data.hits().entrySet()) {
            next.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        hits = Map.copyOf(next);
        for (var entry : data.hits().entrySet()) {
            ModLogger.debugThrottled("client.locator.pos." + entry.getKey().asLong(),
                    CTConfig.debugLogIntervalTicks,
                    "Client locator highlight cache pos={} hits={}", entry.getKey(), entry.getValue());
        }
        runtimeRemainingTicks = data.runtimeRemainingTicks();
    }

    public Map<BlockPos, List<LocatorHit>> getActiveHits() {
        return Map.copyOf(hits);
    }

    public int getRuntimeRemainingTicks() {
        return runtimeRemainingTicks;
    }

    public void clear() {
        hits = Map.of();
        runtimeRemainingTicks = 0;
    }
}
