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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import net.neoforged.neoforge.network.PacketDistributor;

import org.chatterjay.crafting_tracker.api.CraftStatus;
import org.chatterjay.crafting_tracker.config.CTConfig;
import org.chatterjay.crafting_tracker.network.payloads.S2CCraftHighlightData;
import org.chatterjay.crafting_tracker.network.payloads.S2CCraftHighlightData.HighlightEntry;
import org.slf4j.Logger;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.config.LockCraftingMode;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingService;
import appeng.helpers.patternprovider.PatternProviderLogicHost;

import com.glodblock.github.extendedae.common.tileentities.matrix.TileAssemblerMatrixPattern;

import me.ramidzkh.mekae2.ae2.MekanismKey;

import net.pedroksl.advanced_ae.common.logic.AdvPatternProviderLogicHost;

public class CraftTracker {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_MISSED = 10;
    private static final long COOLDOWN_MS = 4000;

    private static final Set<UUID> disabledPlayers = new HashSet<>();
    private static final Map<BlockPos, TrackerEntry> entries = new HashMap<>();
    private static final Map<BlockPos, Boolean> prevProviderBusy = new HashMap<>();
    private static int scanCounter;

    static final int TYPE_ITEM = 0;
    static final int TYPE_FLUID = 1;
    static final int TYPE_OTHER = 2;
    static final int TYPE_CHEMICAL = 3;

    private record OutputInfo(@Nullable ResourceLocation id, int type) {}

    // --- Type abstractions for PatternProviderLogicHost / TileAssemblerMatrixPattern ---

    private static boolean isPatternSource(BlockEntity be) {
        return be instanceof PatternProviderLogicHost || be instanceof TileAssemblerMatrixPattern
                || be instanceof AdvPatternProviderLogicHost;
    }

    private static boolean isPatternBusy(BlockEntity be) {
        if (be instanceof PatternProviderLogicHost host) return host.getLogic().isBusy();
        if (be instanceof TileAssemblerMatrixPattern matrix) return matrix.isBusy();
        if (be instanceof AdvPatternProviderLogicHost host) return host.getLogic().isBusy();
        return false;
    }

    private static boolean isPatternLocked(BlockEntity be) {
        if (be instanceof PatternProviderLogicHost host)
            return host.getLogic().getCraftingLockedReason() != LockCraftingMode.NONE;
        if (be instanceof AdvPatternProviderLogicHost host)
            return host.getLogic().getCraftingLockedReason() != LockCraftingMode.NONE;
        return false;
    }

    private static List<IPatternDetails> getPatterns(BlockEntity be) {
        if (be instanceof PatternProviderLogicHost host) return host.getLogic().getAvailablePatterns();
        if (be instanceof TileAssemblerMatrixPattern matrix) return matrix.getAvailablePatterns();
        if (be instanceof AdvPatternProviderLogicHost host) return host.getLogic().getAvailablePatterns();
        return List.of();
    }

    @Nullable
    private static IGrid getGrid(BlockEntity be) {
        if (be instanceof TileAssemblerMatrixPattern matrix) {
            try { return matrix.getGrid(); } catch (Exception ignored) {}
        }
        if (be instanceof AdvPatternProviderLogicHost host) {
            try { return host.getGrid(); } catch (Exception ignored) {}
        }
        IGridNode node = getGridNode(be);
        return node != null ? node.getGrid() : null;
    }

    // --- end type abstractions ---

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

            // Keep entries still in cooldown or stuck
            for (Map.Entry<BlockPos, TrackerEntry> e : entries.entrySet()) {
                if (now < e.getValue().cooldownUntilMs || e.getValue().stuck) {
                    seen.add(e.getKey());
                }
            }

            int beforeSize = entries.size();
            entries.entrySet().removeIf(e -> {
                if (!seen.contains(e.getKey())) {
                    e.getValue().missedCount++;
                    if (e.getValue().missedCount > MAX_MISSED) {
                        LOGGER.info("Cleaning up expired entry at {}", e.getKey());
                        return true;
                    }
                }
                return false;
            });
            int removed = beforeSize - entries.size();
            if (removed > 0) {
                LOGGER.info("Removed {} expired entries ({} remaining)", removed, entries.size());
            }
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
                var out = e.getValue().outputInfo;
                highlightEntries.add(new HighlightEntry(pos, status.ordinal(),
                        out != null ? out.id() : null,
                        out != null ? out.type() : 0));
            }

            if (scanCounter % 20 == 0) {
                LOGGER.info("Tracking {} entries, sending {} highlights to {}", entries.size(), highlightEntries.size(), player.getName().getString());
            }
            PacketDistributor.sendToPlayer(player, new S2CCraftHighlightData(highlightEntries));
        }
    }

    private static boolean hasAdjacentInventory(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (level.getBlockEntity(pos.relative(dir)) != null) return true;
        }
        return false;
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
                        if (!isPatternSource(be)) continue;
                        if (!pos.closerThan(ppos, quickRadius)) continue;

                        BlockPos immPos = pos.immutable();

                        // Skip already tracked entries — refreshEntries handles them per-tick
                        if (entries.containsKey(immPos)) continue;

                        boolean busy = isPatternBusy(be);
                        boolean locked = isPatternLocked(be);

                        if (busy || locked) {
                            TrackerEntry entry = new TrackerEntry(locked ? now : 0);
                            entry.stuck = locked;
                            entry.outputInfo = getOutputInfo(be);
                            entries.put(immPos, entry);
                            prevProviderBusy.put(immPos, true);
                        } else {
                            OutputInfo info = getOutputInfo(be);
                            if (info != null) {
                                boolean hasInv = hasAdjacentInventory(level, immPos);
                                LOGGER.info("quickScan: tracking idle provider at {} output={} hasInventory={}", immPos, info.id(), hasInv);
                                if (hasInv) {
                                    TrackerEntry entry = new TrackerEntry(0);
                                    entry.outputInfo = info;
                                    entry.tentative = true;
                                    entry.cooldownUntilMs = now + 1000;
                                    entries.put(immPos, entry);
                                } else {
                                    TrackerEntry entry = new TrackerEntry(now);
                                    entry.outputInfo = info;
                                    entry.stuck = true;
                                    entry.lockStartMs = now;
                                    entries.put(immPos, entry);
                                    prevProviderBusy.put(immPos, false);
                                }
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
                if (!isPatternSource(be)) continue;

                boolean busy = isPatternBusy(be);

                if (busy) {
                    entry.cooldownUntilMs = 0;
                    entry.missedCount = 0;
                    entry.tentative = false;
                    prevProviderBusy.put(pos, true);

                    boolean locked = isPatternLocked(be);

                    OutputInfo info = getOutputInfo(be);
                    if (info != null) {
                        entry.outputInfo = info;
                    }

                    entry.stuck = locked;

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
                    if (entry.stuck) {
                        entry.missedCount = 0;
                    } else if (!entry.tentative) {
                        boolean cpuBusy = isGridCpuBusy(be);

                        if (cpuBusy) {
                            entry.missedCount = 0;
                            OutputInfo info = getOutputInfo(be);
                            if (info != null) {
                                entry.outputInfo = info;
                            }
                            if (entry.cooldownUntilMs == 0 || entry.cooldownUntilMs - now < COOLDOWN_MS / 2) {
                                entry.cooldownUntilMs = now + COOLDOWN_MS;
                            }
                        } else if (now < entry.cooldownUntilMs) {
                            entry.missedCount = 0;
                            // CPU may have started a new job even if isGridCpuBusy was false
                            OutputInfo info = getOutputInfo(be);
                            if (info != null) {
                                entry.outputInfo = info;
                            }
                        } else {
                            entry.lockStartMs = 0;
                        }
                    } else if (now < entry.cooldownUntilMs) {
                        entry.missedCount = 0;
                        OutputInfo info = getOutputInfo(be);
                        if (info != null) {
                            entry.outputInfo = info;
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
                    if (!isPatternSource(be)) continue;

                    BlockPos immPos = pos.immutable();
                    seenProviders.add(immPos);
                    boolean busy = isPatternBusy(be);
                    boolean locked = isPatternLocked(be);
                    boolean active = busy || locked;

                    boolean wasActive = prevProviderBusy.getOrDefault(immPos, false);
                    prevProviderBusy.put(immPos, active);

                    TrackerEntry existing = entries.get(immPos);

                    if (active) {
                        seen.add(immPos);

                        OutputInfo info = getOutputInfo(be);

                        if (existing == null) {
                            TrackerEntry entry = new TrackerEntry(locked ? now : 0);
                            if (!locked) {
                                entry.busyStartMs = now;
                            }
                            entry.stuck = locked;
                            entry.outputInfo = info;
                            entries.put(immPos, entry);
                        } else {
                            existing.missedCount = 0;
                            existing.cooldownUntilMs = 0;
                            existing.tentative = false;
                            existing.stuck = locked;
                            if (info != null) {
                                existing.outputInfo = info;
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
                            if (existing.stuck) {
                                existing.missedCount = 0;
                                seen.add(immPos);
                            } else if (wasActive) {
                                existing.cooldownUntilMs = now + COOLDOWN_MS;
                                existing.missedCount = 0;
                                seen.add(immPos);
                            } else if (now < existing.cooldownUntilMs) {
                                // Still in cooldown — refresh item in case CPU switched jobs
                                OutputInfo info = getOutputInfo(be);
                                if (info != null) {
                                    existing.outputInfo = info;
                                }
                                seen.add(immPos);
                            }
                        } else if (wasActive) {
                            TrackerEntry entry = new TrackerEntry(0);
                            entry.outputInfo = getOutputInfo(be);
                            entry.cooldownUntilMs = now + COOLDOWN_MS;
                            entries.put(immPos, entry);
                            seen.add(immPos);
                        } else {
                            // Idle provider, no existing entry — check if pattern matches a busy CPU or is requested
                            OutputInfo info = getOutputInfo(be);
                            if (info != null) {
                                TrackerEntry entry;
                                if (hasAdjacentInventory(level, immPos)) {
                                    entry = new TrackerEntry(0);
                                    entry.outputInfo = info;
                                    entry.cooldownUntilMs = now + COOLDOWN_MS;
                                } else {
                                    entry = new TrackerEntry(now);
                                    entry.outputInfo = info;
                                    entry.stuck = true;
                                    entry.lockStartMs = now;
                                }
                                entries.put(immPos, entry);
                                seen.add(immPos);
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean isProviderNeededByCraftingSystem(BlockEntity be) {
        try {
            IGrid grid = getGrid(be);
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

            for (IPatternDetails pattern : getPatterns(be)) {
                GenericStack output = pattern.getPrimaryOutput();
                if (output != null && cs.isRequesting(output.what())) {
                    return true;
                }
            }
        } catch (Exception e) {
            LOGGER.info("isProviderNeededByCraftingSystem: exception at {}: {}", be.getBlockPos(), e.getMessage());
        }
        return false;
    }

    private static @Nullable OutputInfo getOutputInfo(BlockEntity be) {
        try {
            IGrid grid = getGrid(be);
            if (grid == null) {
                return null;
            }
            ICraftingService cs = grid.getCraftingService();
            if (cs == null) {
                return null;
            }

            var patterns = getPatterns(be);

            // Pass 1: prioritize what the CPU is actively crafting (fastest to switch)
            for (IPatternDetails pattern : patterns) {
                GenericStack output = pattern.getPrimaryOutput();
                if (output == null) continue;
                AEKey key = output.what();
                if (isCpuCraftingOutput(cs, key)) {
                    return buildOutputInfo(key);
                }
            }

            // Pass 2: fall back to cs.isRequesting (slower to update on item switch)
            for (IPatternDetails pattern : patterns) {
                GenericStack output = pattern.getPrimaryOutput();
                if (output == null) continue;
                AEKey key = output.what();
                if (cs.isRequesting(key)) {
                    return buildOutputInfo(key);
                }
            }
        } catch (Exception e) {
            LOGGER.info("getOutputInfo: exception at {}: {}", be.getBlockPos(), e.getMessage());
        }
        return null;
    }

    private static @Nullable OutputInfo buildOutputInfo(AEKey key) {
        ResourceLocation regKey = key.getId();
        if (key instanceof AEItemKey) {
            if (BuiltInRegistries.ITEM.containsKey(regKey)) {
                return new OutputInfo(regKey, TYPE_ITEM);
            }
        } else if (key instanceof AEFluidKey) {
            if (BuiltInRegistries.FLUID.containsKey(regKey)) {
                return new OutputInfo(regKey, TYPE_FLUID);
            }
        } else if (key instanceof MekanismKey) {
            return new OutputInfo(regKey, TYPE_CHEMICAL);
        }
        return null;
    }

    private static boolean isGridCpuBusy(BlockEntity be) {
        if (be == null || be.getLevel() == null) return false;
        try {
            IGrid grid = getGrid(be);
            if (grid == null) return false;
            ICraftingService cs = grid.getCraftingService();
            if (cs == null) return false;
            for (ICraftingCPU cpu : cs.getCpus()) {
                if (cpu.isBusy()) return true;
            }
        } catch (Exception e) {
            LOGGER.info("isGridCpuBusy: exception: {}", e.getMessage());
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

    private static boolean isCpuCraftingOutput(ICraftingService cs, AEKey key) {
        try {
            for (ICraftingCPU cpu : cs.getCpus()) {
                if (!cpu.isBusy()) continue;
                CraftingJobStatus status = cpu.getJobStatus();
                if (status == null || status.crafting() == null) continue;
                AEKey cpuKey = status.crafting().what();
                if (cpuKey.equals(key) || cpuKey.getId().equals(key.getId())) {
                    if (scanCounter % 20 == 0) {
                        LOGGER.info("CPU crafting {} matches provider pattern", key.getId());
                    }
                    return true;
                }
            }
        } catch (Exception e) {
            LOGGER.info("isCpuCraftingOutput: exception: {}", e.getMessage());
        }
        return false;
    }

    private static CraftStatus computeStatus(TrackerEntry entry, long now) {
        if (entry.stuck) return CraftStatus.STUCK;

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
        boolean stuck;
        @Nullable OutputInfo outputInfo;

        TrackerEntry(long lockStartMs) {
            this.lockStartMs = lockStartMs;
        }
    }
}
