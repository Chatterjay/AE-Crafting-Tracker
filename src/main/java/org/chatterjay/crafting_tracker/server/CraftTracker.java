package org.chatterjay.crafting_tracker.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
import net.minecraft.world.level.block.state.BlockState;
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
    private static final long COOLDOWN_MS = 1000;
    private static final long OUTPUT_GRACE_MS = 2500;

    private static final Set<UUID> enabledPlayers = new HashSet<>();
    private static final Map<UUID, Long> runtimeHighlightExpiry = new HashMap<>();
    /** Tracks players who have explicitly enabled runtime mode via the button. */
    private static final Set<UUID> runtimeActivePlayers = new HashSet<>();
    /** Tracks players who explicitly cancelled runtime via the button. */
    private static final Set<UUID> runtimeExplicitlyDisabled = new HashSet<>();
    private static final Map<BlockPos, TrackerEntry> entries = new HashMap<>();
    private static final Map<BlockPos, Boolean> prevProviderBusy = new HashMap<>();
    private static final Map<BlockPos, Long> debugLastLogMs = new HashMap<>();
    private static final LocatorTrackingService locatorTracking = new LocatorTrackingService();
    private static int scanCounter;

    static final int TYPE_ITEM = 0;
    static final int TYPE_FLUID = 1;
    static final int TYPE_OTHER = 2;
    static final int TYPE_CHEMICAL = 3;
    private static final int MAX_OUTPUTS = 3;

    private record OutputItem(ResourceLocation id, int type) {}
    private record AdjacentActivity(boolean active, String detail) {
        private static final AdjacentActivity NONE = new AdjacentActivity(false, "none");
    }

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
        if (runtimeActivePlayers.contains(playerId)) return true;
        if (runtimeExplicitlyDisabled.contains(playerId)) return false;
        return enabledPlayers.contains(playerId);
    }

    public static void setEnabledFor(UUID playerId, boolean enabled) {
        if (enabled) {
            enabledPlayers.add(playerId);
            runtimeExplicitlyDisabled.remove(playerId);
        } else {
            enabledPlayers.remove(playerId);
            runtimeExplicitlyDisabled.add(playerId);
        }
    }

    public static boolean isRuntimeActive(UUID playerId) {
        return runtimeHighlightExpiry.containsKey(playerId);
    }

    public static void enableRuntimeHighlight(UUID playerId, long gameTime) {
        runtimeHighlightExpiry.put(playerId, Long.MAX_VALUE);
        runtimeActivePlayers.add(playerId);
        runtimeExplicitlyDisabled.remove(playerId);
        LOGGER.info("[Highlight] Runtime enabled for player {}", playerId);
    }

    public static void disableRuntimeHighlight(UUID playerId) {
        runtimeHighlightExpiry.remove(playerId);
        runtimeActivePlayers.remove(playerId);
        runtimeExplicitlyDisabled.add(playerId);
        LOGGER.info("[Highlight] Runtime disabled for player {}", playerId);
    }

    static void clearRuntimeState(UUID playerId) {
        runtimeHighlightExpiry.remove(playerId);
        runtimeActivePlayers.remove(playerId);
    }

    public static int getRuntimeRemainingTicks(UUID playerId, long gameTime) {
        Long expiry = runtimeHighlightExpiry.get(playerId);
        return expiry != null ? (int) Math.max(0, expiry - gameTime) : 0;
    }

    public static void onServerTick(MinecraftServer server) {
        // Cleanup expired runtime highlights
        long gameTime = server.overworld().getGameTime();
        runtimeHighlightExpiry.entrySet().removeIf(e -> {
            if (gameTime >= e.getValue()) {
                runtimeActivePlayers.remove(e.getKey());
                LOGGER.info("[Highlight] Expired runtime for player {}", e.getKey());
                return true;
            }
            return false;
        });

        List<ServerPlayer> trackingPlayers = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isEnabledFor(player.getUUID())) {
                trackingPlayers.add(player);
            }
        }

        long now = System.currentTimeMillis();
        int radius = CTConfig.scanRadius;

        // ================================================================
        // Locator tracking runs every tick, independent of tracking state.
        // ================================================================
        locatorTracking.onServerTick(server, gameTime);

        // ================================================================
        // Above: always runs. Below: only when tracking is enabled.
        // ================================================================

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

            entries.entrySet().removeIf(e -> {
                if (!seen.contains(e.getKey())) {
                    e.getValue().missedCount++;
                    if (e.getValue().missedCount > MAX_MISSED) {
                        debugProviderEvent("entry.remove_missed", e.getKey(), e.getValue(), now,
                                "maxMissed=" + MAX_MISSED);
                        return true;
                    }
                }
                return false;
            });
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
                var outputs = e.getValue().outputs;
                boolean emptyOutputs = outputs == null || outputs.isEmpty();
                boolean sendWithoutOutputs = shouldSendWithoutOutputs(e.getValue(), now);
                if (emptyOutputs && !sendWithoutOutputs) {
                    debugProviderSample("send.skip_no_outputs", pos, e.getValue(), now,
                            "status=" + status + " player=" + player.getGameProfile().getName());
                    continue;
                }
                List<HighlightEntry.OutputItem> packetOutputs = new ArrayList<>();
                if (outputs != null) {
                    for (OutputItem out : outputs) {
                        packetOutputs.add(new HighlightEntry.OutputItem(out.id(), out.type()));
                    }
                }
                debugProviderSample("send.include", pos, e.getValue(), now,
                        "status=" + status
                                + " player=" + player.getGameProfile().getName()
                                + " emptyOutputs=" + emptyOutputs
                                + " sendWithoutOutputs=" + sendWithoutOutputs);
                highlightEntries.add(new HighlightEntry(pos, status.ordinal(), packetOutputs));
            }

            int runtimeRemaining = getRuntimeRemainingTicks(player.getUUID(), gameTime);
            PacketDistributor.sendToPlayer(player, new S2CCraftHighlightData(highlightEntries, runtimeRemaining));
        }
    }

    private static boolean hasAdjacentInventory(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (level.getBlockEntity(pos.relative(dir)) != null) return true;
        }
        return false;
    }

    private static AdjacentActivity getAdjacentActivity(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos adjacentPos = pos.relative(dir);
            BlockEntity adjacentBe = level.getBlockEntity(adjacentPos);
            if (adjacentBe == null) continue;

            BlockState state = level.getBlockState(adjacentPos);
            for (var property : state.getProperties()) {
                String propertyName = property.getName().toLowerCase(Locale.ROOT);
                if (!isActivityPropertyName(propertyName)) continue;

                String propertyValue = String.valueOf(state.getValue(property)).toLowerCase(Locale.ROOT);
                if (isActivePropertyValue(propertyValue)) {
                    ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    return new AdjacentActivity(true,
                            blockId + "@" + dir.getSerializedName() + "." + property.getName() + "=" + propertyValue);
                }
            }
        }
        return AdjacentActivity.NONE;
    }

    private static boolean isActivityPropertyName(String name) {
        return name.equals("active")
                || name.equals("lit")
                || name.equals("working")
                || name.equals("running")
                || name.equals("crafting")
                || name.equals("processing");
    }

    private static boolean isActivePropertyValue(String value) {
        return value.equals("true")
                || value.equals("on")
                || value.equals("active")
                || value.equals("working")
                || value.equals("running")
                || value.equals("lit")
                || value.equals("processing");
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
                            var info = getOutputInfo(be, null);
                            applyOutputInfo(entry, info, now);
                            entries.put(immPos, entry);
                            prevProviderBusy.put(immPos, true);
                            debugProviderEvent("quick.create_active", immPos, entry, now,
                                    "busy=" + busy + " locked=" + locked + " outputInfo=" + outputSummary(info));
                        } else {
                            var info = getOutputInfo(be, null);
                            if (info != null) {
                                boolean hasInv = hasAdjacentInventory(level, immPos);
                                if (hasInv) {
                                    TrackerEntry entry = new TrackerEntry(0);
                                    applyOutputInfo(entry, info, now);
                                    entry.tentative = true;
                                    entry.cooldownUntilMs = now + 1000;
                                    entries.put(immPos, entry);
                                    debugProviderEvent("quick.create_tentative", immPos, entry, now,
                                            "busy=false locked=false hasAdjacentInventory=true outputInfo=" + outputSummary(info));
                                } else {
                                    TrackerEntry entry = new TrackerEntry(now);
                                    applyOutputInfo(entry, info, now);
                                    entry.stuck = true;
                                    entry.lockStartMs = now;
                                    entries.put(immPos, entry);
                                    prevProviderBusy.put(immPos, false);
                                    debugProviderEvent("quick.create_stuck_no_adjacent", immPos, entry, now,
                                            "busy=false locked=false hasAdjacentInventory=false outputInfo=" + outputSummary(info));
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
                boolean locked = isPatternLocked(be);

                if (busy) {
                    boolean startedBusy = entry.busyStartMs == 0;
                    boolean wasMarkedStuck = entry.stuck;
                    boolean wasThresholdStuck = !entry.stuck && isDurationStuck(entry, now);

                    entry.cooldownUntilMs = 0;
                    entry.missedCount = 0;
                    entry.tentative = false;
                    prevProviderBusy.put(pos, true);

                    var info = getOutputInfo(be, entry.outputs);
                    applyOutputInfo(entry, info, now);

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
                    if (!locked && (startedBusy || wasMarkedStuck || wasThresholdStuck)) {
                        resetActiveTimer(entry, now);
                        if (wasMarkedStuck || wasThresholdStuck) {
                            debugProviderEvent("refresh.recover_busy", pos, entry, now,
                                    "busy=true locked=false startedBusy=" + startedBusy
                                            + " wasMarkedStuck=" + wasMarkedStuck
                                            + " wasThresholdStuck=" + wasThresholdStuck
                                            + " outputInfo=" + outputSummary(info));
                        }
                    }
                    debugProviderSample("refresh.busy", pos, entry, now,
                            "busy=true locked=" + locked + " outputInfo=" + outputSummary(info));
                } else {
                    entry.busyStartMs = 0;
                    if (entry.stuck) {
                        var info = getOutputInfo(be, entry.outputs);
                        applyOutputInfo(entry, info, now);
                        boolean hasRequest = info != null || hasRecentOutput(entry, now);
                        boolean hasInv = hasAdjacentInventory(level, pos);
                        AdjacentActivity adjacentActivity = getAdjacentActivity(level, pos);

                        if (!locked && adjacentActivity.active()) {
                            entry.stuck = false;
                            entry.lockStartMs = 0;
                            entry.missedCount = 0;
                            resetActiveTimer(entry, now);
                            entry.cooldownUntilMs = now + COOLDOWN_MS;
                            debugProviderEvent("refresh.stuck_clear_adjacent_active", pos, entry, now,
                                    "busy=false locked=false adjacentActive=" + adjacentActivity.detail()
                                            + " hasRequest=" + hasRequest
                                            + " hasAdjacentInventory=" + hasInv
                                            + " outputInfo=" + outputSummary(info));
                        } else if (locked || (hasRequest && !hasInv)) {
                            entry.missedCount = 0;
                            entry.stuck = true;
                            if (entry.lockStartMs == 0) {
                                entry.lockStartMs = now;
                            }
                            debugProviderSample("refresh.idle_stuck", pos, entry, now,
                                    "busy=false locked=" + locked
                                            + " adjacentActive=" + adjacentActivity.detail()
                                            + " hasRequest=" + hasRequest
                                            + " hasAdjacentInventory=" + hasInv
                                            + " outputInfo=" + outputSummary(info));
                        } else {
                            entry.stuck = false;
                            entry.lockStartMs = 0;
                            entry.missedCount = 0;
                            if (hasRequest) {
                                resetActiveTimer(entry, now);
                                entry.cooldownUntilMs = now + COOLDOWN_MS;
                            } else {
                                clearExpiredOutput(pos, entry, now);
                            }
                            debugProviderEvent("refresh.stuck_clear", pos, entry, now,
                                    "busy=false locked=false hasRequest=" + hasRequest
                                            + " hasAdjacentInventory=" + hasInv
                                            + " outputInfo=" + outputSummary(info));
                        }
                    } else if (!entry.tentative) {
                        if (locked) {
                            var info = getOutputInfo(be, entry.outputs);
                            applyOutputInfo(entry, info, now);
                            entry.stuck = true;
                            entry.lockStartMs = now;
                            entry.missedCount = 0;
                            debugProviderEvent("refresh.idle_locked", pos, entry, now,
                                    "busy=false locked=true outputInfo=" + outputSummary(info));
                            break;
                        }

                        boolean cpuBusy = isGridCpuBusy(be);

                        if (cpuBusy) {
                            entry.missedCount = 0;
                            var info = getOutputInfo(be, entry.outputs);
                            applyOutputInfo(entry, info, now);
                            AdjacentActivity adjacentActivity = getAdjacentActivity(level, pos);
                            if (adjacentActivity.active()) {
                                resetActiveTimer(entry, now);
                            }
                            if (entry.outputs != null) {
                                if (entry.cooldownUntilMs == 0 || entry.cooldownUntilMs - now < COOLDOWN_MS / 2) {
                                    entry.cooldownUntilMs = now + COOLDOWN_MS;
                                }
                            }
                            debugProviderSample("refresh.idle_cpu_busy", pos, entry, now,
                                    "busy=false cpuBusy=true adjacentActive=" + adjacentActivity.detail()
                                            + " outputInfo=" + outputSummary(info));
                        } else if (now < entry.cooldownUntilMs) {
                            entry.missedCount = 0;
                            // CPU may have started a new job even if isGridCpuBusy was false
                            var info = getOutputInfo(be, entry.outputs);
                            applyOutputInfo(entry, info, now);
                            debugProviderSample("refresh.cooldown", pos, entry, now,
                                    "busy=false cpuBusy=false outputInfo=" + outputSummary(info));
                        } else {
                            entry.lockStartMs = 0;
                            clearExpiredOutput(pos, entry, now);
                            debugProviderSample("refresh.idle_clear", pos, entry, now,
                                    "busy=false cpuBusy=false");
                        }
                    } else if (now < entry.cooldownUntilMs) {
                        entry.missedCount = 0;
                        var info = getOutputInfo(be, entry.outputs);
                        applyOutputInfo(entry, info, now);
                        debugProviderSample("refresh.tentative_cooldown", pos, entry, now,
                                "busy=false outputInfo=" + outputSummary(info));
                    } else {
                        var info = getOutputInfo(be, entry.outputs);
                        applyOutputInfo(entry, info, now);
                        if (info != null || entry.outputs != null) {
                            entry.tentative = false;
                            entry.missedCount = 0;
                            entry.cooldownUntilMs = now + COOLDOWN_MS;
                            debugProviderEvent("refresh.tentative_promote", pos, entry, now,
                                    "busy=false outputInfo=" + outputSummary(info));
                        } else {
                            entry.lockStartMs = 0;
                            clearExpiredOutput(pos, entry, now);
                            debugProviderSample("refresh.tentative_clear", pos, entry, now, "busy=false outputInfo=none");
                        }
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

                        var info = getOutputInfo(be, existing != null ? existing.outputs : null);

                        if (existing == null) {
                            TrackerEntry entry = new TrackerEntry(locked ? now : 0);
                            if (!locked) {
                                entry.busyStartMs = now;
                            }
                            entry.stuck = locked;
                            applyOutputInfo(entry, info, now);
                            entries.put(immPos, entry);
                            debugProviderEvent("scan.create_active", immPos, entry, now,
                                    "busy=" + busy + " locked=" + locked + " outputInfo=" + outputSummary(info));
                        } else {
                            boolean startedBusy = busy && existing.busyStartMs == 0;
                            boolean wasMarkedStuck = existing.stuck;
                            boolean wasThresholdStuck = !existing.stuck && isDurationStuck(existing, now);

                            existing.missedCount = 0;
                            existing.cooldownUntilMs = 0;
                            existing.tentative = false;
                            existing.stuck = locked;
                            applyOutputInfo(existing, info, now);
                            if (!locked && existing.busyStartMs == 0) {
                                existing.busyStartMs = now;
                            }
                            if (locked && existing.lockStartMs == 0) {
                                existing.lockStartMs = now;
                            } else if (!locked) {
                                existing.lockStartMs = 0;
                            }
                            if (!locked && (startedBusy || wasMarkedStuck || wasThresholdStuck)) {
                                resetActiveTimer(existing, now);
                                if (wasMarkedStuck || wasThresholdStuck) {
                                    debugProviderEvent("scan.recover_active", immPos, existing, now,
                                            "busy=" + busy
                                                    + " locked=false startedBusy=" + startedBusy
                                                    + " wasMarkedStuck=" + wasMarkedStuck
                                                    + " wasThresholdStuck=" + wasThresholdStuck
                                                    + " outputInfo=" + outputSummary(info));
                                }
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
                                var info = getOutputInfo(be, existing.outputs);
                                applyOutputInfo(existing, info, now);
                                seen.add(immPos);
                            }
                        } else if (wasActive) {
                            TrackerEntry entry = new TrackerEntry(0);
                            var info = getOutputInfo(be, null);
                            applyOutputInfo(entry, info, now);
                            entry.cooldownUntilMs = now + COOLDOWN_MS;
                            entries.put(immPos, entry);
                            seen.add(immPos);
                            debugProviderEvent("scan.create_cooldown_after_active", immPos, entry, now,
                                    "busy=false locked=false wasActive=true outputInfo=" + outputSummary(info));
                        } else {
                            // Idle provider, no existing entry — check if pattern matches a busy CPU or is requested
                            var info = getOutputInfo(be, null);
                            if (info != null) {
                                TrackerEntry entry;
                                if (hasAdjacentInventory(level, immPos)) {
                                    entry = new TrackerEntry(0);
                                    applyOutputInfo(entry, info, now);
                                    entry.cooldownUntilMs = now + COOLDOWN_MS;
                                    debugProviderEvent("scan.create_idle_requested", immPos, entry, now,
                                            "busy=false locked=false hasAdjacentInventory=true outputInfo=" + outputSummary(info));
                                } else {
                                    entry = new TrackerEntry(now);
                                    applyOutputInfo(entry, info, now);
                                    entry.stuck = true;
                                    entry.lockStartMs = now;
                                    debugProviderEvent("scan.create_stuck_no_adjacent", immPos, entry, now,
                                            "busy=false locked=false hasAdjacentInventory=false outputInfo=" + outputSummary(info));
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

    private static void applyOutputInfo(TrackerEntry entry, @Nullable List<OutputItem> info, long now) {
        if (info == null || info.isEmpty()) {
            return;
        }
        if (!samePrimaryOutput(entry.outputs, info) || entry.activeStartMs == 0) {
            entry.activeStartMs = now;
        }
        entry.outputs = List.copyOf(info);
        entry.lastOutputSeenMs = now;
    }

    private static void clearExpiredOutput(BlockPos pos, TrackerEntry entry, long now) {
        if (entry.lastOutputSeenMs != 0 && now - entry.lastOutputSeenMs <= OUTPUT_GRACE_MS) {
            return;
        }
        if (entry.outputs != null || entry.activeStartMs != 0 || entry.lastOutputSeenMs != 0) {
            debugProviderEvent("output.clear_expired", pos, entry, now,
                    "lastOutputAgeMs=" + (entry.lastOutputSeenMs == 0 ? -1 : now - entry.lastOutputSeenMs));
        }
        entry.outputs = null;
        entry.activeStartMs = 0;
        entry.lastOutputSeenMs = 0;
    }

    private static boolean hasRecentOutput(TrackerEntry entry, long now) {
        return entry.outputs != null
                && entry.lastOutputSeenMs != 0
                && now - entry.lastOutputSeenMs <= OUTPUT_GRACE_MS;
    }

    private static void resetActiveTimer(TrackerEntry entry, long now) {
        if (entry.outputs == null || entry.outputs.isEmpty()) {
            return;
        }
        entry.activeStartMs = now;
        entry.lastOutputSeenMs = now;
    }

    private static boolean isDurationStuck(TrackerEntry entry, long now) {
        long startMs;
        if (entry.lockStartMs != 0) {
            startMs = entry.lockStartMs;
        } else if (entry.busyStartMs != 0) {
            startMs = entry.busyStartMs;
        } else if (entry.activeStartMs != 0) {
            startMs = entry.activeStartMs;
        } else {
            return false;
        }
        return now - startMs >= CTConfig.stuckThresholdSeconds * 1000L;
    }

    private static boolean samePrimaryOutput(@Nullable List<OutputItem> current, List<OutputItem> next) {
        if (current == null || current.isEmpty() || next.isEmpty()) {
            return false;
        }
        return current.get(0).equals(next.get(0));
    }

    private static boolean shouldSendWithoutOutputs(TrackerEntry entry, long now) {
        return entry.stuck
                || entry.lockStartMs != 0
                || entry.busyStartMs != 0
                || entry.activeStartMs != 0
                || now < entry.cooldownUntilMs;
    }

    private static void debugProviderSample(String phase, BlockPos pos, TrackerEntry entry, long now, String detail) {
        if (!CTConfig.debugTracking) return;
        long intervalMs = Math.max(50L, CTConfig.debugLogIntervalTicks * 50L);
        long last = debugLastLogMs.getOrDefault(pos, 0L);
        if (now - last < intervalMs) return;
        debugLastLogMs.put(pos, now);
        debugProviderEvent(phase, pos, entry, now, detail);
    }

    private static void debugProviderEvent(String phase, BlockPos pos, TrackerEntry entry, long now, String detail) {
        if (!CTConfig.debugTracking) return;
        LOGGER.info("[CraftTrackerDebug] phase={} pos={} {} {}",
                phase, pos, entryDebugSummary(entry, now), detail == null ? "" : detail);
    }

    private static String entryDebugSummary(TrackerEntry entry, long now) {
        return "stuck=" + entry.stuck
                + " tentative=" + entry.tentative
                + " missed=" + entry.missedCount
                + " cooldownMs=" + Math.max(0, entry.cooldownUntilMs - now)
                + " busyAgeMs=" + age(now, entry.busyStartMs)
                + " lockAgeMs=" + age(now, entry.lockStartMs)
                + " activeAgeMs=" + age(now, entry.activeStartMs)
                + " lastOutputAgeMs=" + age(now, entry.lastOutputSeenMs)
                + " outputs=" + outputSummary(entry.outputs);
    }

    private static long age(long now, long startMs) {
        return startMs == 0 ? -1 : now - startMs;
    }

    private static String outputSummary(@Nullable List<OutputItem> outputs) {
        if (outputs == null || outputs.isEmpty()) return "none";
        StringBuilder summary = new StringBuilder();
        for (OutputItem output : outputs) {
            if (!summary.isEmpty()) summary.append(',');
            summary.append(output.id()).append('#').append(output.type());
        }
        return summary.toString();
    }

    private static @Nullable List<OutputItem> getOutputInfo(BlockEntity be, @Nullable List<OutputItem> prevOutputs) {
        try {
            IGrid grid = getGrid(be);
            if (grid == null) return null;
            ICraftingService cs = grid.getCraftingService();
            if (cs == null) return null;

            var patterns = getPatterns(be);

            // Collect up to MAX_OUTPUTS matching items in pattern order to form a queue.
            // Items that match isCpuCraftingOutput OR isRequesting are included.
            // The queue naturally advances: when item finishes (no longer matches),
            // it drops out and remaining items shift forward.
            List<OutputItem> results = new ArrayList<>();
            for (IPatternDetails pattern : patterns) {
                if (results.size() >= MAX_OUTPUTS) break;
                GenericStack output = pattern.getPrimaryOutput();
                if (output == null) continue;
                AEKey key = output.what();
                if (isCpuCraftingOutput(cs, key) || cs.isRequesting(key)) {
                    OutputItem item = buildOutputItem(key);
                    if (item != null) {
                        results.add(item);
                    }
                }
            }
            return results.isEmpty() ? null : results;
        } catch (Exception e) {
            LOGGER.info("getOutputInfo: exception at {}: {}", be.getBlockPos(), e.getMessage());
        }
        return null;
    }

    private static @Nullable OutputItem buildOutputItem(AEKey key) {
        ResourceLocation regKey = key.getId();
        if (key instanceof AEItemKey) {
            if (BuiltInRegistries.ITEM.containsKey(regKey)) {
                return new OutputItem(regKey, TYPE_ITEM);
            }
        } else if (key instanceof AEFluidKey) {
            if (BuiltInRegistries.FLUID.containsKey(regKey)) {
                return new OutputItem(regKey, TYPE_FLUID);
            }
        } else if (key instanceof MekanismKey) {
            return new OutputItem(regKey, TYPE_CHEMICAL);
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
        } else if (entry.activeStartMs != 0) {
            startMs = entry.activeStartMs;
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
        long activeStartMs;
        long lastOutputSeenMs;
        int missedCount;
        long cooldownUntilMs;
        boolean tentative;
        boolean stuck;
        @Nullable List<OutputItem> outputs;

        TrackerEntry(long lockStartMs) {
            this.lockStartMs = lockStartMs;
        }
    }
}
