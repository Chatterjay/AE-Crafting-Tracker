package org.chatterjay.crafting_tracker.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import net.neoforged.neoforge.network.PacketDistributor;

import org.chatterjay.crafting_tracker.api.CraftStatus;
import org.chatterjay.crafting_tracker.config.CTConfig;
import org.chatterjay.crafting_tracker.network.payloads.S2CCraftHighlightData;
import org.chatterjay.crafting_tracker.network.payloads.S2CCraftHighlightData.HighlightEntry;
import org.slf4j.Logger;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.config.LockCraftingMode;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingService;
import appeng.helpers.patternprovider.PatternProviderLogicHost;

public class CraftTracker {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_MISSED = 10;
    private static final long COOLDOWN_MS = 4000;

    private static final Set<UUID> disabledPlayers = new HashSet<>();
    private static final Map<BlockPos, TrackerEntry> entries = new HashMap<>();
    private static final Map<BlockPos, Boolean> prevProviderBusy = new HashMap<>();
    private static int scanCounter;

    public static boolean isEnabledFor(UUID playerId) {
        return CTConfig.highlightEnabled && !disabledPlayers.contains(playerId);
    }

    public static void setEnabledFor(UUID playerId, boolean enabled) {
        if (enabled) {
            disabledPlayers.remove(playerId);
        } else {
            disabledPlayers.add(playerId);
        }
    }

    public static void onServerTick(MinecraftServer server) {
        List<ServerPlayer> trackingPlayers = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isEnabledFor(player.getUUID())) {
                trackingPlayers.add(player);
            }
        }

        long now = System.currentTimeMillis();
        int radius = CTConfig.scanRadius;

        if (trackingPlayers.isEmpty()) {
            if (!entries.isEmpty()) {
                entries.clear();
            }
            return;
        }

        // Phase 1: every tick — refresh state for known entries + quick-check nearby for busy providers
        refreshEntries(server, now);
        quickScan(server, now, trackingPlayers);

        // Phase 2: periodic scan — discover providers and update state
        scanCounter++;
        boolean doScan = scanCounter % CTConfig.scanIntervalTicks == 0;

        if (doScan) {
            Set<BlockPos> seen = new HashSet<>();
            Set<BlockPos> seenProviders = new HashSet<>();

            for (ServerPlayer player : trackingPlayers) {
                ServerLevel level = player.serverLevel();
                BlockPos ppos = player.blockPosition();
                int chunkRadius = (int) Math.ceil(radius / 16.0);
                int cx0 = ppos.getX() >> 4;
                int cz0 = ppos.getZ() >> 4;

                scanChunks(level, ppos, cx0, cz0, chunkRadius, radius, now, seen, seenProviders);
            }

            // Clean up prevProviderBusy entries for providers no longer in range
            prevProviderBusy.keySet().removeIf(k -> !seenProviders.contains(k));

            // Keep entries still in cooldown (output targets or entries waiting to expire)
            for (Map.Entry<BlockPos, TrackerEntry> e : entries.entrySet()) {
                if (now < e.getValue().cooldownUntilMs) {
                    seen.add(e.getKey());
                }
            }

            int beforeSize = entries.size();
            entries.entrySet().removeIf(e -> {
                if (!seen.contains(e.getKey())) {
                    e.getValue().missedCount++;
                    if (e.getValue().missedCount > MAX_MISSED) {
                        return true;
                    }
                }
                return false;
            });
            int removed = beforeSize - entries.size();
        }

        // Phase 3: send highlights to each tracking player
        for (ServerPlayer player : trackingPlayers) {
            ServerLevel level = player.serverLevel();
            BlockPos ppos = player.blockPosition();
            List<HighlightEntry> highlightEntries = new ArrayList<>();

            for (Map.Entry<BlockPos, TrackerEntry> e : entries.entrySet()) {
                BlockPos pos = e.getKey();
                if (!pos.closerThan(ppos, radius)) continue;
                if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;

                CraftStatus status = computeStatus(e.getValue(), now);
                highlightEntries.add(new HighlightEntry(pos, status.ordinal(), e.getValue().outputItemId));
            }

            PacketDistributor.sendToPlayer(player, new S2CCraftHighlightData(highlightEntries));
        }
    }

    private static void quickScan(MinecraftServer server, long now, List<ServerPlayer> trackingPlayers) {
        int quickRadius = Math.min(CTConfig.scanRadius, 24);
        int chunkRadius = (int) Math.ceil(quickRadius / 16.0);

        for (ServerPlayer player : trackingPlayers) {
            ServerLevel level = player.serverLevel();
            BlockPos ppos = player.blockPosition();
            int cx0 = ppos.getX() >> 4;
            int cz0 = ppos.getZ() >> 4;

            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    int cx = cx0 + dx;
                    int cz = cz0 + dz;
                    if (!level.hasChunk(cx, cz)) continue;

                    LevelChunk chunk = level.getChunk(cx, cz);
                    for (Map.Entry<BlockPos, BlockEntity> beEntry : chunk.getBlockEntities().entrySet()) {
                        BlockPos pos = beEntry.getKey();
                        BlockEntity be = beEntry.getValue();
                        if (!(be instanceof PatternProviderLogicHost host)) continue;
                        if (!pos.closerThan(ppos, quickRadius)) continue;

                        BlockPos immPos = pos.immutable();

                        // Skip already tracked entries — refreshEntries handles them per-tick
                        if (entries.containsKey(immPos)) continue;

                        boolean busy = host.getLogic().isBusy();
                        boolean locked = host.getLogic().getCraftingLockedReason() != LockCraftingMode.NONE;

                        if (busy || locked) {
                            TrackerEntry entry = new TrackerEntry(locked ? now : 0);
                            entry.outputItemId = getProviderOutputItem(be, host);
                            entries.put(immPos, entry);
                            prevProviderBusy.put(immPos, true);
                        } else {
                            ResourceLocation itemId = getProviderOutputItem(be, host);
                            if (itemId != null) {
                                TrackerEntry entry = new TrackerEntry(0);
                                entry.outputItemId = itemId;
                                entry.tentative = true;
                                entry.cooldownUntilMs = now + 1000;
                                entries.put(immPos, entry);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void refreshEntries(MinecraftServer server, long now) {
        for (var e : entries.entrySet()) {
            BlockPos pos = e.getKey();
            TrackerEntry entry = e.getValue();

            for (ServerLevel level : server.getAllLevels()) {
                if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;
                BlockEntity be = level.getBlockEntity(pos);
                if (!(be instanceof PatternProviderLogicHost host)) continue;

                boolean busy = host.getLogic().isBusy();

                if (busy) {
                    entry.cooldownUntilMs = 0;
                    entry.missedCount = 0;
                    entry.tentative = false;
                    prevProviderBusy.put(pos, true);

                    ResourceLocation itemId = getProviderOutputItem(be, host);
                    if (itemId != null) {
                        entry.outputItemId = itemId;
                    }

                    LockCraftingMode lockReason = host.getLogic().getCraftingLockedReason();
                    boolean locked = lockReason != LockCraftingMode.NONE;

                    // Track continuous busy time for output-full detection (busy but not locked)
                    if (!locked && entry.busyStartMs == 0) {
                        entry.busyStartMs = now;
                    }
                    if (locked && entry.lockStartMs == 0) {
                        entry.lockStartMs = now;
                    } else if (!locked) {
                        entry.lockStartMs = 0;
                    }
                } else {
                    entry.busyStartMs = 0;
                    if (!entry.tentative) {
                        boolean cpuBusy = isGridCpuBusy(be);

                        if (cpuBusy) {
                            entry.missedCount = 0;
                            ResourceLocation itemId = getProviderOutputItem(be, host);
                            if (itemId != null) {
                                entry.outputItemId = itemId;
                            }
                            if (entry.cooldownUntilMs == 0 || entry.cooldownUntilMs - now < COOLDOWN_MS / 2) {
                                entry.cooldownUntilMs = now + COOLDOWN_MS;
                            }
                        } else if (now < entry.cooldownUntilMs) {
                            entry.missedCount = 0;
                            // CPU may have started a new job even if isGridCpuBusy was false
                            ResourceLocation itemId = getProviderOutputItem(be, host);
                            if (itemId != null) {
                                entry.outputItemId = itemId;
                            }
                        } else {
                            entry.lockStartMs = 0;
                        }
                    } else if (now < entry.cooldownUntilMs) {
                        entry.missedCount = 0;
                        ResourceLocation itemId = getProviderOutputItem(be, host);
                        if (itemId != null) {
                            entry.outputItemId = itemId;
                        }
                    } else {
                        entry.lockStartMs = 0;
                    }
                }
                break;
            }
        }
    }

    private static void scanChunks(ServerLevel level, BlockPos ppos,
                                    int cx0, int cz0, int chunkRadius,
                                    int radius, long now, Set<BlockPos> seen,
                                    Set<BlockPos> seenProviders) {
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                int cx = cx0 + dx;
                int cz = cz0 + dz;
                if (!level.hasChunk(cx, cz)) continue;

                LevelChunk chunk = level.getChunk(cx, cz);
                for (Map.Entry<BlockPos, BlockEntity> beEntry : chunk.getBlockEntities().entrySet()) {
                    BlockPos pos = beEntry.getKey();
                    BlockEntity be = beEntry.getValue();
                    if (!pos.closerThan(ppos, radius)) continue;
                    if (!(be instanceof PatternProviderLogicHost host)) continue;

                    BlockPos immPos = pos.immutable();
                    seenProviders.add(immPos);
                    boolean busy = host.getLogic().isBusy();
                    LockCraftingMode lockReason = host.getLogic().getCraftingLockedReason();
                    boolean locked = lockReason != LockCraftingMode.NONE;
                    boolean active = busy || locked;

                    boolean wasActive = prevProviderBusy.getOrDefault(immPos, false);
                    prevProviderBusy.put(immPos, active);

                    TrackerEntry existing = entries.get(immPos);

                    if (active) {
                        seen.add(immPos);

                        ResourceLocation itemId = getProviderOutputItem(be, host);

                        if (existing == null) {
                            TrackerEntry entry = new TrackerEntry(locked ? now : 0);
                            if (!locked) {
                                entry.busyStartMs = now;
                            }
                            entry.outputItemId = itemId;
                            entries.put(immPos, entry);
                        } else {
                            existing.missedCount = 0;
                            existing.cooldownUntilMs = 0;
                            existing.tentative = false;
                            if (itemId != null) {
                                existing.outputItemId = itemId;
                            }
                            if (!locked && existing.busyStartMs == 0) {
                                existing.busyStartMs = now;
                            }
                            if (locked && existing.lockStartMs == 0) {
                                existing.lockStartMs = now;
                            } else if (!locked) {
                                existing.lockStartMs = 0;
                            }
                        }
                    } else {
                        // Provider is idle now
                        if (existing != null) {
                            existing.busyStartMs = 0;
                            if (wasActive) {
                                existing.cooldownUntilMs = now + COOLDOWN_MS;
                                existing.missedCount = 0;
                                seen.add(immPos);
                            } else if (now < existing.cooldownUntilMs) {
                                // Still in cooldown — refresh item in case CPU switched jobs
                                ResourceLocation itemId = getProviderOutputItem(be, host);
                                if (itemId != null) {
                                    existing.outputItemId = itemId;
                                }
                                seen.add(immPos);
                            }
                        } else if (wasActive) {
                            TrackerEntry entry = new TrackerEntry(0);
                            entry.outputItemId = getProviderOutputItem(be, host);
                            entry.cooldownUntilMs = now + COOLDOWN_MS;
                            entries.put(immPos, entry);
                            seen.add(immPos);
                        }
                    }
                }
            }
        }
    }

    private static boolean isProviderNeededByCraftingSystem(BlockEntity be, PatternProviderLogicHost host) {
        try {
            IGridNode node = getGridNode(be);
            if (node == null) return false;
            IGrid grid = node.getGrid();
            if (grid == null) return false;
            ICraftingService cs = grid.getCraftingService();
            if (cs == null) return false;

            boolean cpuBusy = false;
            for (ICraftingCPU cpu : cs.getCpus()) {
                if (cpu.isBusy()) {
                    cpuBusy = true;
                    break;
                }
            }
            if (!cpuBusy) return false;

            for (IPatternDetails pattern : host.getLogic().getAvailablePatterns()) {
                GenericStack output = pattern.getPrimaryOutput();
                if (output != null && cs.isRequesting(output.what())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static @Nullable ResourceLocation getProviderOutputItem(BlockEntity be, PatternProviderLogicHost host) {
        try {
            IGridNode node = getGridNode(be);
            if (node == null) return null;
            IGrid grid = node.getGrid();
            if (grid == null) return null;
            ICraftingService cs = grid.getCraftingService();
            if (cs == null) return null;

            for (IPatternDetails pattern : host.getLogic().getAvailablePatterns()) {
                GenericStack output = pattern.getPrimaryOutput();
                if (output != null && cs.isRequesting(output.what())) {
                    ResourceLocation regKey = output.what().getId();
                    if (BuiltInRegistries.ITEM.containsKey(regKey)) {
                        return regKey;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isGridCpuBusy(BlockEntity be) {
        if (be == null || be.getLevel() == null) return false;
        try {
            IGridNode node = getGridNode(be);
            if (node == null) return false;
            IGrid grid = node.getGrid();
            if (grid == null) return false;
            ICraftingService cs = grid.getCraftingService();
            if (cs == null) return false;
            for (ICraftingCPU cpu : cs.getCpus()) {
                if (cpu.isBusy()) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static IGridNode getGridNode(BlockEntity be) {
        if (!(be instanceof IInWorldGridNodeHost host)) return null;
        IGridNode node = host.getGridNode(null);
        if (node != null) return node;
        for (var dir : Direction.values()) {
            node = host.getGridNode(dir);
            if (node != null) return node;
        }
        return null;
    }

    private static CraftStatus computeStatus(TrackerEntry entry, long now) {
        long startMs;
        if (entry.lockStartMs != 0) {
            startMs = entry.lockStartMs;
        } else if (entry.busyStartMs != 0) {
            startMs = entry.busyStartMs;
        } else {
            return CraftStatus.ACTIVE;
        }

        long durationMs = now - startMs;
        long stuckMs = CTConfig.stuckThresholdSeconds * 1000L;
        long stallMs = CTConfig.stallThresholdSeconds * 1000L;

        if (durationMs >= stuckMs) return CraftStatus.STUCK;
        if (durationMs >= stallMs) return CraftStatus.STALLED;
        return CraftStatus.ACTIVE;
    }

    private static class TrackerEntry {
        long lockStartMs;
        long busyStartMs; // when busy+!locked started (output full detection), 0 = not busy
        int missedCount;
        long cooldownUntilMs;
        boolean tentative;
        @Nullable ResourceLocation outputItemId;

        TrackerEntry(long lockStartMs) {
            this.lockStartMs = lockStartMs;
        }
    }
}
