package org.chatterjay.crafting_tracker.server;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

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
import org.chatterjay.crafting_tracker.util.ModLogger;
import org.slf4j.Logger;

import appeng.api.crafting.IPatternDetails;
import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.config.LockCraftingMode;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.blockentity.networking.CableBusBlockEntity;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingService;
import appeng.blockentity.crafting.MolecularAssemblerBlockEntity;
import appeng.helpers.externalstorage.GenericStackInv;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.helpers.patternprovider.PatternProviderReturnInventory;

public class CraftTracker {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_MISSED = 10;
    private static final long COOLDOWN_MS = 1000;
    private static final long OUTPUT_GRACE_MS = 2500;
    /** Allows a just-accepted ECO job to bridge the worker attachment callback. */
    private static final long ECO_JOB_RECOVERY_WINDOW_MS = 2000;
    private static final long ECO_JOB_ACTIVITY_TIMEOUT_MS = 6000;
    private static final long ECO_HANDOFF_GRACE_MS = 1500;
    private static final long ECO_PENDING_DISPATCH_MAX_MS = 10_000;
    /** Lifetime of a bus/job pair observed while an ECO fast path is being prepared. */
    private static final long ECO_PREPARED_JOB_TTL_MS = 4000;
    private static final long MATRIX_HANDOFF_GRACE_MS = 1500;
    /** How often the reflective cluster scan may run for one matrix pattern block. */
    private static final long MATRIX_RECOVERY_INTERVAL_MS = 1000;
    /** Idle time after which a scan timestamp is dropped again. */
    private static final long MATRIX_RECOVERY_IDLE_MS = 60_000;
    private static final long HIGHLIGHT_HEARTBEAT_TICKS = 20;
    private static final String ECO_CPU_CLASS = "cn.dancingsnow.neoecoae.api.me.ECOCraftingCPU";
    private static final String ECO_PATTERN_BUS_CLASS =
            "cn.dancingsnow.neoecoae.blocks.entity.crafting.ECOCraftingPatternBusBlockEntity";
    /**
     * NeoECO wraps a dispatch in a different type at every entry point and has renamed
     * the members describing it more than once, so they are probed by name instead of
     * being pinned to one release.
     */
    private static final String[] ECO_PATTERN_ACCESSORS = {"details", "pattern"};
    private static final String[] ECO_OUTPUT_ACCESSORS = {"outputTotal", "outputsPerCraft", "outputs"};
    private static final String TRINITY_CPU_CLASS =
            "com.fish_dan_.data_energistics.common.crafting.trinity.execution.cpu.TrinityDataCoreVirtualCpu";
    private static final String TRINITY_PATTERN_CORE_CLASS =
            "com.fish_dan_.data_energistics.blockentity.TrinityPatternCoreBlockEntity";
    private static final String MATRIX_PATTERN_CLASS =
            "com.glodblock.github.extendedae.common.tileentities.matrix.TileAssemblerMatrixPattern";
    private static final String ADV_PATTERN_PROVIDER_HOST_CLASS =
            "net.pedroksl.advanced_ae.common.logic.AdvPatternProviderLogicHost";
    private static final String MEKANISM_KEY_CLASS = "me.ramidzkh.mekae2.ae2.MekanismKey";

    private static final Set<UUID> enabledPlayers = new HashSet<>();
    private static final Map<UUID, Long> runtimeHighlightExpiry = new HashMap<>();
    /** Tracks players who have explicitly enabled runtime mode via the button. */
    private static final Set<UUID> runtimeActivePlayers = new HashSet<>();
    /** Tracks players who explicitly cancelled runtime via the button. */
    private static final Set<UUID> runtimeExplicitlyDisabled = new HashSet<>();
    private static final Map<BlockPos, TrackerEntry> entries = new HashMap<>();
    private static final Map<BlockPos, Boolean> prevProviderBusy = new HashMap<>();
    /** Last successful input transfer from an AE2 pattern provider's pending send queue. */
    private static final Map<BlockPos, Long> providerTransferProgress = new HashMap<>();
    private static final Map<UUID, List<HighlightEntry>> lastHighlightSnapshots = new HashMap<>();
    private static final Map<UUID, Integer> lastHighlightRuntimeStates = new HashMap<>();
    private static final Map<UUID, Long> lastHighlightPacketTicks = new HashMap<>();
    private static final Map<UUID, Long> lastHighlightSnapshotRevisions = new HashMap<>();
    private static final Map<ICraftingProvider, ProviderCraft> currentProviderCrafts =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<BlockPos, Map<Object, ProviderCraft>> currentEcoBusCrafts = new HashMap<>();
    /** Last accepted ECO job per physical bus; survives worker clear/reuse until completion. */
    private static final Map<BlockPos, ProviderCraft> currentEcoBusJobs = new HashMap<>();
    private static final Map<BlockPos, Long> ecoBusJobAcceptedAtMs = new HashMap<>();
    private static final Map<BlockPos, Long> ecoBusJobLastActivityMs = new HashMap<>();
    /**
     * Bus/job pairs observed while NeoECO prepares a fast path. They are only a fallback:
     * some entry points start the worker without ever calling back into the bus.
     */
    private static final Map<BlockPos, ProviderCraft> currentEcoPreparedJobs = new HashMap<>();
    private static final Map<BlockPos, Long> ecoPreparedJobAtMs = new HashMap<>();
    private static final Map<Object, BlockPos> ecoThreadBusPositions = new IdentityHashMap<>();
    private static final Map<Object, Integer> ecoThreadProgress = new IdentityHashMap<>();
    private static final Map<BlockPos, Long> ecoBusHandoffUntilMs = new HashMap<>();
    private static final ThreadLocal<Deque<EcoBusDispatch>> ecoBusDispatches =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final Map<UUID, Deque<EcoBusDispatch>> pendingEcoDispatches =
            new ConcurrentHashMap<>();
    private static final Map<BlockPos, Map<Object, ProviderCraft>> currentMatrixPatternCrafts = new HashMap<>();
    private static final Map<BlockPos, MatrixHandoff> matrixPatternHandoffs = new HashMap<>();
    private static final Map<BlockPos, Long> matrixRecoveryAtMs = new HashMap<>();
    private static final ThreadLocal<Deque<MatrixPatternDispatch>> matrixPatternDispatches =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final Map<UUID, ProviderCraft> currentTrinityCoreCrafts = new HashMap<>();
    private static final Map<ICraftingCPU, ResourceLocation> currentCpuCrafts =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private static final LocatorTrackingService locatorTracking = new LocatorTrackingService();
    @Nullable
    private static MinecraftServer trackingServer;
    private static int scanCounter;
    private static long highlightSnapshotRevision;

    static final int TYPE_ITEM = 0;
    static final int TYPE_FLUID = 1;
    static final int TYPE_OTHER = 2;
    static final int TYPE_CHEMICAL = 3;
    private static final int MAX_OUTPUTS = 3;

    private record OutputItem(ResourceLocation id, int type) {}
    private record ProviderCraft(ResourceLocation outputId, @Nullable UUID jobId) {}
    /** One ECO dispatch scope; the payload is null when the wrapper could not be read. */
    private record EcoBusDispatch(BlockPos busPosition, @Nullable ProviderCraft craft, long createdAtMs) {}
    /** Placeholder that bridges the gap between an accepted dispatch and its worker callback. */
    private record EcoDispatchMarker(UUID jobId, long createdAtMs) {}
    private record MatrixPatternDispatch(BlockPos patternPosition, ProviderCraft craft) {}
    private record MatrixHandoff(ProviderCraft craft, long untilMs) {}
    private record AdjacentActivity(boolean active, String detail) {
        private static final AdjacentActivity NONE = new AdjacentActivity(false, "none");
    }

    // --- Type abstractions for AE2 and optional provider implementations ---

    private static boolean isPatternSource(BlockEntity be) {
        return getPatternProviderHost(be) != null || isMatrixSource(be)
                || isAdvancedPatternProvider(be) || isEcoPatternBus(be)
                || isTrinityPatternCore(be);
    }

    @Nullable
    private static PatternProviderLogicHost getPatternProviderHost(@Nullable BlockEntity be) {
        if (be instanceof PatternProviderLogicHost host) return host;
        if (!(be instanceof CableBusBlockEntity cableBus)) return null;

        for (Direction direction : Direction.values()) {
            if (cableBus.getPart(direction) instanceof PatternProviderLogicHost host) return host;
        }
        return null;
    }

    private static boolean isMatrixSource(BlockEntity be) {
        return hasType(be, MATRIX_PATTERN_CLASS);
    }

    private static boolean isAdvancedPatternProvider(BlockEntity be) {
        return hasType(be, ADV_PATTERN_PROVIDER_HOST_CLASS);
    }

    private static boolean isEcoPatternBus(BlockEntity be) {
        return be != null && be.getClass().getName().equals(ECO_PATTERN_BUS_CLASS);
    }

    private static boolean isTrinityPatternCore(BlockEntity be) {
        return be != null && be.getClass().getName().equals(TRINITY_PATTERN_CORE_CLASS);
    }

    // --- Cached reflection ---------------------------------------------------------
    // Resolving members reflectively is very expensive when done per call: getDeclaredField
    // builds a fresh Field plus accessor every time, setAccessible re-runs the caller check,
    // and a miss throws an exception complete with a full stack trace. Everything is now
    // resolved once per (class, member) pair and misses are remembered, so the hot tick path
    // is a map lookup.

    /** Marks a member that does not exist; ConcurrentHashMap cannot store null values. */
    private static final Object ABSENT_MEMBER = new Object();

    private static final Map<Class<?>, Map<String, Object>> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Map<String, Object>> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Map<Class<?>, Boolean>> TYPE_CHECK_CACHE = new ConcurrentHashMap<>();

    /** Pre-allocated so that "field not found" never captures a stack trace. */
    private static final NoSuchFieldException FIELD_NOT_FOUND =
            new NoSuchFieldException("field is not present on the class hierarchy");

    @Nullable
    private static Field lookupField(Class<?> type, String fieldName) {
        Map<String, Object> perClass = FIELD_CACHE.computeIfAbsent(type, c -> new ConcurrentHashMap<>());
        Object cached = perClass.get(fieldName);
        if (cached == null) {
            cached = perClass.computeIfAbsent(fieldName, name -> resolveField(type, name));
        }
        return cached == ABSENT_MEMBER ? null : (Field) cached;
    }

    private static Object resolveField(Class<?> type, String fieldName) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException missing) {
                // Not declared here, keep walking up the hierarchy.
            } catch (RuntimeException inaccessible) {
                // InaccessibleObjectException / SecurityException: skip this level.
            }
        }
        return ABSENT_MEMBER;
    }

    @Nullable
    private static Method lookupMethod(Class<?> type, String methodName, Class<?>... parameterTypes) {
        // The no-arg lookup is by far the hottest one; its name is already a unique key.
        String cacheKey = parameterTypes.length == 0
                ? methodName
                : methodSignature(methodName, parameterTypes);
        Map<String, Object> perClass = METHOD_CACHE.computeIfAbsent(type, c -> new ConcurrentHashMap<>());
        Object cached = perClass.get(cacheKey);
        if (cached == null) {
            cached = perClass.computeIfAbsent(cacheKey, key -> resolveMethod(type, methodName, parameterTypes));
        }
        return cached == ABSENT_MEMBER ? null : (Method) cached;
    }

    private static String methodSignature(String methodName, Class<?>[] parameterTypes) {
        StringBuilder key = new StringBuilder(methodName).append('(');
        for (Class<?> parameterType : parameterTypes) {
            key.append(parameterType.getName()).append(';');
        }
        return key.append(')').toString();
    }

    private static Object resolveMethod(Class<?> type, String methodName, Class<?>[] parameterTypes) {
        Method method;
        try {
            method = type.getMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException | RuntimeException missing) {
            return ABSENT_MEMBER;
        }
        try {
            // Optional integrations declare public members on package-private records and
            // wrappers; without this the lookup would succeed but every call would fail.
            method.setAccessible(true);
        } catch (RuntimeException ignored) {
            // A lookup that cannot be opened is still worth attempting.
        }
        return method;
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) return null;
        Method method = lookupMethod(target.getClass(), methodName);
        if (method == null) return null;
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Object invokeInt(Object target, String methodName, int value) {
        if (target == null) return null;
        Method method = lookupMethod(target.getClass(), methodName, int.class);
        if (method == null) return null;
        try {
            return method.invoke(target, value);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static boolean invokeBoolean(Object target, String methodName) {
        return Boolean.TRUE.equals(invokeNoArg(target, methodName));
    }

    private static boolean hasType(@Nullable Object value, String className) {
        return value != null && hasType(value.getClass(), className);
    }

    private static boolean hasType(@Nullable Class<?> type, String className) {
        if (type == null) return false;
        return TYPE_CHECK_CACHE
                .computeIfAbsent(className, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(type, t -> computeHasType(t, className));
    }

    private static boolean computeHasType(Class<?> type, String className) {
        while (type != null) {
            if (type.getName().equals(className)) return true;
            for (Class<?> iface : type.getInterfaces()) {
                if (computeHasType(iface, className)) return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static List<IPatternDetails> asPatternList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        List<IPatternDetails> result = new ArrayList<>();
        for (Object pattern : iterable) {
            if (pattern instanceof IPatternDetails details) result.add(details);
        }
        return result;
    }

    private static boolean isPatternBusy(BlockEntity be) {
        PatternProviderLogicHost host = getPatternProviderHost(be);
        if (host != null) return host.getLogic().isBusy();
        if (isMatrixSource(be)) {
            // ExtendedAE's isBusy() is cluster-wide and also reports true while a
            // pattern core has not yet joined a cluster. Track its real worker.
            return getMatrixPatternCraft(be) != null;
        }
        if (isAdvancedPatternProvider(be)) {
            return invokeBoolean(invokeNoArg(be, "getLogic"), "isBusy");
        }
        if (isEcoPatternBus(be)) {
            // NeoECO's isBusy() describes the whole shared cluster, not this physical bus.
            return getEcoBusCraft(be.getBlockPos()) != null;
        }
        if (isTrinityPatternCore(be)) return invokeBoolean(be, "hasWork");
        return false;
    }

    private static boolean isPatternLocked(BlockEntity be) {
        PatternProviderLogicHost host = getPatternProviderHost(be);
        if (host != null)
            return host.getLogic().getCraftingLockedReason() != LockCraftingMode.NONE;
        if (isAdvancedPatternProvider(be)) {
            Object reason = invokeNoArg(invokeNoArg(be, "getLogic"), "getCraftingLockedReason");
            return reason != null && reason != LockCraftingMode.NONE;
        }
        return false;
    }

    private static List<IPatternDetails> getPatterns(BlockEntity be) {
        PatternProviderLogicHost host = getPatternProviderHost(be);
        if (host != null) return host.getLogic().getAvailablePatterns();
        if (isMatrixSource(be)) return asPatternList(invokeNoArg(be, "getAvailablePatterns"));
        if (isAdvancedPatternProvider(be)) {
            return asPatternList(invokeNoArg(invokeNoArg(be, "getLogic"), "getAvailablePatterns"));
        }
        if (isEcoPatternBus(be)) {
            // NeoECO keeps one bus catalog per block entity, so getAvailablePatterns() is
            // already the list of patterns stored in this particular bus. Older builds
            // named the same list getLocalAvailablePatterns(), so both are probed.
            List<IPatternDetails> patterns = asPatternList(invokeNoArg(be, "getLocalAvailablePatterns"));
            if (patterns.isEmpty()) patterns = asPatternList(invokeNoArg(be, "getAvailablePatterns"));
            return patterns;
        }
        return List.of();
    }

    @Nullable
    private static ICraftingProvider getCraftingProvider(BlockEntity be) {
        Object candidate = null;
        PatternProviderLogicHost host = getPatternProviderHost(be);
        if (host != null) {
            candidate = host.getLogic();
        } else if (isMatrixSource(be)) {
            candidate = be;
        } else if (isAdvancedPatternProvider(be)) {
            candidate = invokeNoArg(be, "getLogic");
        } else if (isEcoPatternBus(be)) {
            candidate = be;
        }
        return candidate instanceof ICraftingProvider provider ? provider : null;
    }

    @Nullable
    private static IGrid getGrid(BlockEntity be) {
        PatternProviderLogicHost host = getPatternProviderHost(be);
        if (host != null) {
            IGrid grid = host.getGrid();
            if (grid != null) return grid;
        }
        if (isMatrixSource(be) || isAdvancedPatternProvider(be) || isEcoPatternBus(be)) {
            Object grid = invokeNoArg(be, "getGrid");
            if (grid instanceof IGrid result) return result;
        }
        IGridNode node = getGridNode(be);
        return node != null ? node.getGrid() : null;
    }

    private static boolean shouldTrackIdleProvider(
            BlockEntity be, @Nullable OutputInfo info, AdjacentActivity adjacentActivity) {
        if (info == null || info.isEmpty()) return false;
        // A normal AE provider can have a requested CPU output without exposing a
        // provider-local current ID or adjacent machine activity.
        if (!info.outputs().isEmpty()) return true;
        if (info.returnItems() || info.currentCraftingId() != null || adjacentActivity.active()) {
            return true;
        }
        return isMatrixSource(be) && hasExternalCpuBusy(be);
    }

    private static boolean hasExternalCpuBusy(BlockEntity be) {
        try {
            IGrid grid = getGrid(be);
            if (grid == null) return false;
            ICraftingService service = grid.getCraftingService();
            if (service == null) return false;
            for (ICraftingCPU cpu : service.getCpus()) {
                if (cpu.isBusy() && isExternalCpu(cpu)) return true;
            }
        } catch (Exception ignored) {
            // Optional CPU implementations must not affect normal provider scanning.
        }
        return false;
    }

    private static boolean isExternalCpu(ICraftingCPU cpu) {
        String className = cpu.getClass().getName();
        return className.equals(ECO_CPU_CLASS) || className.equals(TRINITY_CPU_CLASS);
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
        if (expiry == null) return 0;
        if (expiry == Long.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, expiry - gameTime));
    }

    /** Records the output of a pattern that a provider accepted for execution. */
    public static void recordProviderPatternPush(ICraftingProvider provider, IPatternDetails patternDetails) {
        recordProviderPatternPush(provider, patternDetails, null);
    }

    /** Records that a provider's pending input queue made real forward progress. */
    public static void recordPatternProviderTransfer(BlockEntity provider) {
        if (provider == null || provider.getLevel() == null || provider.getLevel().isClientSide()) return;

        BlockPos pos = provider.getBlockPos().immutable();
        long now = System.currentTimeMillis();
        providerTransferProgress.put(pos, now);

        TrackerEntry entry = entries.get(pos);
        if (entry != null && entry.busyStartMs != 0 && !entry.stuck) {
            entry.lastBusyProgressMs = now;
        }
    }

    private static void recordProviderPatternPush(
            ICraftingProvider provider, IPatternDetails patternDetails, @Nullable UUID jobId) {
        if (provider == null || patternDetails == null) {
            return;
        }
        GenericStack output = patternDetails.getPrimaryOutput();
        if (output != null) {
            ProviderCraft previous = currentProviderCrafts.get(provider);
            if (jobId == null && previous != null && previous.jobId() != null
                    && previous.outputId().equals(output.what().getId())) {
                return;
            }
            currentProviderCrafts.put(provider, new ProviderCraft(output.what().getId(), jobId));
            markHighlightStateChanged();
        }
    }

    /** Records an ECO bus dispatch with its job id when the bus accepted the execution. */
    public static void recordEcoPatternBusPush(Object patternBus, Object execution, @Nullable UUID jobId) {
        recordEcoBusCraft(patternBus, ecoDetails(execution), ecoCraft(execution, jobId), "push.accepted");
    }

    /** Records the AE2 provider push, which hands the pattern over directly. */
    public static void recordEcoProviderPatternBusPush(Object patternBus, IPatternDetails details,
                                                       @Nullable UUID jobId) {
        recordEcoBusCraft(patternBus, details, craftOf(details, jobId), "push.accepted.provider");
    }

    /**
     * Remembers that a physical bus owns a job. NeoECO reuses its worker lanes, and the
     * worker callback can arrive ticks after the dispatch, so the bus keeps the job until
     * the execution itself is gone instead of until the worker is released.
     */
    private static void recordEcoBusCraft(Object patternBus, @Nullable IPatternDetails details,
                                          @Nullable ProviderCraft craft, String phase) {
        if (!(patternBus instanceof BlockEntity be) || craft == null) return;
        if (patternBus instanceof ICraftingProvider provider && details != null) {
            recordProviderPatternPush(provider, details, craft.jobId());
        }

        BlockPos pos = be.getBlockPos();
        long now = System.currentTimeMillis();
        Map<Object, ProviderCraft> executions = currentEcoBusCrafts.get(pos);
        boolean alreadyAttached = executions != null
                && executions.values().stream().anyMatch(value -> sameCraft(value, craft));
        if (!alreadyAttached) {
            currentEcoBusCrafts
                    .computeIfAbsent(pos, ignored -> new IdentityHashMap<>())
                    .put(new EcoDispatchMarker(craft.jobId(), now), craft);
            markHighlightStateChanged();
        }
        currentEcoBusJobs.put(pos, craft);
        ecoBusJobAcceptedAtMs.put(pos, now);
        ecoBusJobLastActivityMs.put(pos, now);
        seedEcoHighlightEntry(pos, craft, phase);
        ModLogger.debug("ECO highlight push accepted phase={} pos={} jobId={} outputId={} mapEntries={}",
                phase, pos, craft.jobId(), craft.outputId(),
                currentEcoBusCrafts.getOrDefault(pos, Map.of()).size());
    }

    private static boolean sameCraft(@Nullable ProviderCraft first, ProviderCraft second) {
        return first != null
                && first.outputId().equals(second.outputId())
                && java.util.Objects.equals(first.jobId(), second.jobId());
    }

    /**
     * Resolves the dispatch an ECO wrapper describes. The wrapper type differs per entry
     * point and its members were renamed between releases, so both the pattern and the
     * produced stacks are probed by name and misses are cached.
     */
    @Nullable
    private static ProviderCraft ecoCraft(Object execution, @Nullable UUID jobId) {
        if (execution == null) return null;
        UUID resolvedJob = jobId != null ? jobId : extractEcoJobId(execution);
        IPatternDetails details = ecoDetails(execution);
        if (details != null) return craftOf(details, resolvedJob);
        for (String accessor : ECO_OUTPUT_ACCESSORS) {
            Object outputs = invokeNoArg(execution, accessor);
            if (!(outputs instanceof Iterable<?> iterable)) continue;
            for (Object value : iterable) {
                if (value instanceof GenericStack stack && stack.what() != null) {
                    return new ProviderCraft(stack.what().getId(), resolvedJob);
                }
            }
        }
        return null;
    }

    @Nullable
    private static IPatternDetails ecoDetails(Object execution) {
        if (execution instanceof IPatternDetails details) return details;
        if (execution == null) return null;
        for (String accessor : ECO_PATTERN_ACCESSORS) {
            Object value = invokeNoArg(execution, accessor);
            if (value instanceof IPatternDetails details) return details;
        }
        return null;
    }

    @Nullable
    private static ProviderCraft craftOf(@Nullable IPatternDetails details, @Nullable UUID jobId) {
        if (details == null) return null;
        GenericStack output = details.getPrimaryOutput();
        if (output == null || output.what() == null) return null;
        return new ProviderCraft(output.what().getId(), jobId);
    }

    /**
     * Remembers the bus a fast path was prepared from. Some NeoECO entry points start the
     * worker directly from the preparation, so the bus never appears in the accepting call.
     */
    public static void noteEcoFastPathPreparation(Object patternBus, Object context) {
        if (!(patternBus instanceof BlockEntity be) || context == null) return;
        UUID jobId = extractEcoJobId(context);
        if (jobId == null) return;
        ProviderCraft craft = ecoCraft(context, jobId);
        if (craft == null) return;
        BlockPos pos = be.getBlockPos();
        currentEcoPreparedJobs.put(pos, craft);
        ecoPreparedJobAtMs.put(pos, System.currentTimeMillis());
        ModLogger.debugThrottled("eco.fastpath.prepare." + pos.asLong(), CTConfig.debugLogIntervalTicks,
                "ECO highlight fastpath.prepare pos={} jobId={} outputId={}",
                pos, jobId, craft.outputId());
    }

    /** Opens a dispatch scope so the ECO worker thread can be tied to its source FD bus. */
    public static void beginEcoPatternBusPush(Object patternBus, Object execution, @Nullable UUID jobId) {
        beginEcoDispatch(patternBus, ecoCraft(execution, jobId));
    }

    /** Opens a dispatch scope for NeoECO's batch fast-path request. */
    public static void beginEcoBatchPatternBusPush(Object patternBus, Object request) {
        beginEcoDispatch(patternBus, ecoCraft(request, null));
    }

    public static void beginEcoVirtualBatchPatternBusPush(Object patternBus, Object execution) {
        beginEcoDispatch(patternBus, ecoCraft(execution, null));
    }

    public static void beginEcoExactVirtualBatchPatternBusPush(Object patternBus, Object recipe,
                                                                @Nullable UUID jobId) {
        beginEcoDispatch(patternBus, ecoCraft(recipe, jobId));
    }

    /** Opens a dispatch scope for the AE2 provider push, which hands the pattern over directly. */
    public static void beginEcoProviderPatternBusPush(Object patternBus, IPatternDetails details,
                                                      @Nullable UUID jobId) {
        beginEcoDispatch(patternBus, craftOf(details, jobId));
    }

    /**
     * Every begin pushes exactly one frame, including when the payload could not be read.
     * NeoECO keeps entry points that no longer carry a dispatch - its batch request is an
     * empty marker in current builds - and an end that popped unconditionally would throw
     * away the frame of the scope around it.
     */
    private static void beginEcoDispatch(Object patternBus, @Nullable ProviderCraft craft) {
        if (!(patternBus instanceof BlockEntity be)) return;
        EcoBusDispatch dispatch = new EcoBusDispatch(
                be.getBlockPos(), craft, System.currentTimeMillis());
        ecoBusDispatches.get().push(dispatch);
        if (craft != null) {
            rememberPendingEcoDispatch(dispatch);
            ModLogger.debug("ECO highlight push.begin pos={} jobId={} outputId={} thread={}",
                    be.getBlockPos(), craft.jobId(), craft.outputId(),
                    Thread.currentThread().getName());
        }
    }

    /** Closes the dispatch scope after ECO has accepted or rejected the pattern. */
    public static void endEcoPatternBusPush(boolean accepted) {
        Deque<EcoBusDispatch> dispatches = ecoBusDispatches.get();
        if (dispatches.isEmpty()) {
            ecoBusDispatches.remove();
            return;
        }

        EcoBusDispatch dispatch = dispatches.pop();
        ProviderCraft craft = dispatch.craft();
        if (craft != null) {
            ModLogger.debug("ECO highlight push.end accepted={} pos={} jobId={} outputId={} thread={}",
                    accepted, dispatch.busPosition(), craft.jobId(), craft.outputId(),
                    Thread.currentThread().getName());
            if (!accepted) {
                forgetPendingEcoDispatch(dispatch);
                removeEcoDispatchMarkers(dispatch.busPosition(), craft.jobId());
                ProviderCraft current = currentEcoBusJobs.get(dispatch.busPosition());
                Map<Object, ProviderCraft> executions = currentEcoBusCrafts.get(dispatch.busPosition());
                if (current != null && craft.jobId() != null
                        && craft.jobId().equals(current.jobId())
                        && (executions == null || executions.isEmpty())) {
                    currentEcoBusJobs.remove(dispatch.busPosition());
                    ecoBusJobAcceptedAtMs.remove(dispatch.busPosition());
                    ecoBusJobLastActivityMs.remove(dispatch.busPosition());
                    markHighlightStateChanged();
                    ModLogger.debug("ECO highlight job.clear pos={} jobId={} reason=push_rejected",
                            dispatch.busPosition(), current.jobId());
                }
            }
        }
        if (dispatches.isEmpty()) ecoBusDispatches.remove();
    }

    public static void recordEcoVirtualPatternBusPush(Object patternBus, Object execution) {
        recordEcoBusCraft(patternBus, ecoDetails(execution), ecoCraft(execution, null),
                "push.accepted.virtual");
    }

    public static void recordEcoExactVirtualPatternBusPush(Object patternBus, Object recipe,
                                                            @Nullable UUID jobId) {
        recordEcoBusCraft(patternBus, ecoDetails(recipe), ecoCraft(recipe, jobId),
                "push.accepted.exact");
    }

    private static void rememberPendingEcoDispatch(EcoBusDispatch dispatch) {
        ProviderCraft craft = dispatch.craft();
        UUID jobId = craft == null ? null : craft.jobId();
        if (jobId != null) {
            pendingEcoDispatches.computeIfAbsent(jobId, ignored -> new ConcurrentLinkedDeque<>())
                    .addLast(dispatch);
        }
    }

    private static void forgetPendingEcoDispatch(EcoBusDispatch dispatch) {
        ProviderCraft craft = dispatch.craft();
        UUID jobId = craft == null ? null : craft.jobId();
        if (jobId == null) return;
        Deque<EcoBusDispatch> pending = pendingEcoDispatches.get(jobId);
        if (pending != null) {
            pending.removeFirstOccurrence(dispatch);
            if (pending.isEmpty()) pendingEcoDispatches.remove(jobId, pending);
        }
    }

    /** Opens a scope that connects an ExtendedAE matrix worker to its physical pattern core. */
    public static void beginMatrixPatternPush(Object patternCore, IPatternDetails patternDetails) {
        if (!(patternCore instanceof BlockEntity be) || patternDetails == null) return;
        GenericStack output = patternDetails.getPrimaryOutput();
        if (output == null || output.what() == null) return;
        matrixPatternDispatches.get().push(new MatrixPatternDispatch(
                be.getBlockPos(), new ProviderCraft(output.what().getId(), null)));
    }

    /** Closes the ExtendedAE matrix dispatch scope after the provider call returns. */
    public static void endMatrixPatternPush() {
        Deque<MatrixPatternDispatch> dispatches = matrixPatternDispatches.get();
        if (!dispatches.isEmpty()) dispatches.pop();
        if (dispatches.isEmpty()) matrixPatternDispatches.remove();
    }

    /** Binds the matrix worker that accepted a job to the source pattern core. */
    public static void attachMatrixThreadExecution(Object matrixThread) {
        if (matrixThread == null) return;
        MatrixPatternDispatch dispatch = matrixPatternDispatches.get().peek();
        if (dispatch == null) return;

        attachMatrixThreadExecution(dispatch.patternPosition(), matrixThread, dispatch.craft());
    }

    /**
     * Captures a matrix job from the crafter that selected the worker. This is
     * deliberately independent of the outer provider call stack, because a
     * matrix can accept a follow-up job after the previous worker has been
     * released.
     */
    public static void attachMatrixCrafterExecution(Object matrixCrafter, IPatternDetails pattern) {
        if (matrixCrafter == null || pattern == null) return;

        BlockPos patternPosition = findMatrixPatternPosition(matrixCrafter, pattern);
        MatrixPatternDispatch dispatch = matrixPatternDispatches.get().peek();
        if (patternPosition == null && dispatch != null) {
            patternPosition = dispatch.patternPosition();
        }
        if (patternPosition == null) {
            ModLogger.debugThrottled("matrix.accept.unresolved", CTConfig.debugLogIntervalTicks,
                    "Matrix job accepted but source pattern was not resolved crafter={} pattern={}",
                    matrixCrafter.getClass().getName(), pattern.getClass().getName());
            return;
        }

        Object worker = findMatrixWorker(matrixCrafter, pattern);
        if (worker == null) {
            ModLogger.debugThrottled("matrix.accept.no_worker." + patternPosition.asLong(),
                    CTConfig.debugLogIntervalTicks,
                    "Matrix job accepted but worker was not resolved pos={} crafter={} pattern={}",
                    patternPosition, matrixCrafter.getClass().getName(), pattern.getClass().getName());
            return;
        }

        GenericStack output = pattern.getPrimaryOutput();
        if (output == null || output.what() == null) return;
        ModLogger.debugThrottled("matrix.accept." + patternPosition.asLong(),
                CTConfig.debugLogIntervalTicks,
                "Matrix job accepted pos={} outputId={} worker={} source={}",
                patternPosition, output.what().getId(), System.identityHashCode(worker),
                dispatch == null ? "cluster" : "dispatch");
        attachMatrixThreadExecution(
                patternPosition,
                worker,
                new ProviderCraft(output.what().getId(), dispatch == null ? null : dispatch.craft().jobId()));
    }

    private static void attachMatrixThreadExecution(
            BlockPos patternPosition, Object matrixThread, ProviderCraft craft) {
        Map<Object, ProviderCraft> executions = currentMatrixPatternCrafts
                .computeIfAbsent(patternPosition, ignored -> new IdentityHashMap<>());
        ProviderCraft previous = executions.put(matrixThread, craft);
        matrixPatternHandoffs.put(
                patternPosition,
                new MatrixHandoff(craft, System.currentTimeMillis() + MATRIX_HANDOFF_GRACE_MS));
        markHighlightStateChanged();

        // The worker can accept the job before the next provider scan creates an
        // entry. Create it here so a matrix craft is visible immediately.
        TrackerEntry entry = entries.computeIfAbsent(patternPosition, ignored -> new TrackerEntry(0));
        long now = System.currentTimeMillis();
        entry.busyStartMs = now;
        entry.lastBusyProgressMs = now;
        entry.activeStartMs = now;
        entry.lastOutputSeenMs = now;
        entry.lockStartMs = 0;
        entry.stuck = false;
        entry.tentative = false;
        entry.missedCount = 0;
        entry.cooldownUntilMs = 0;
        entry.currentCraftingId = craft.outputId();
        entry.outputs = List.of(buildOutputItem(craft.outputId()));
        debugProviderEvent("matrix.worker_start", patternPosition, entry, now,
                "outputId=" + craft.outputId()
                        + " worker=" + System.identityHashCode(matrixThread)
                        + (previous == null ? " new=true" : " new=false"));
    }

    @Nullable
    private static BlockPos findMatrixPatternPosition(Object matrixCrafter, IPatternDetails pattern) {
        Object cluster = invokeNoArg(matrixCrafter, "getCluster");
        Object matrixPatterns = invokeNoArg(cluster, "getPatterns");
        if (!(matrixPatterns instanceof Iterable<?> iterable)) return null;

        BlockPos equalMatch = null;
        for (Object value : iterable) {
            if (!(value instanceof BlockEntity matrix) || !isMatrixSource(matrix)) continue;
            for (IPatternDetails available : getPatterns(matrix)) {
                if (available == pattern) return matrix.getBlockPos();
                if (equalMatch == null && available.equals(pattern)) equalMatch = matrix.getBlockPos();
            }
        }
        return equalMatch;
    }

    @Nullable
    private static Object findMatrixWorker(Object matrixCrafter, IPatternDetails pattern) {
        Object workers;
        try {
            workers = readField(matrixCrafter, "threads");
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
        if (!(workers instanceof Object[] array)) return null;

        Object equalMatch = null;
        for (Object worker : array) {
            Object currentPattern = invokeNoArg(worker, "getCurrentPattern");
            if (currentPattern == pattern) return worker;
            if (equalMatch == null && currentPattern != null && currentPattern.equals(pattern)) {
                equalMatch = worker;
            }
        }
        return equalMatch;
    }

    /** Binds a newly started ECO worker thread to the FD bus that dispatched it. */
    public static void attachEcoThreadExecution(Object ecoThread, @Nullable UUID jobId) {
        if (ecoThread == null) return;
        UUID resolvedJob = jobId != null ? jobId : ecoThreadJobId(ecoThread);
        EcoBusDispatch dispatch = ecoBusDispatches.get().peek();
        if (dispatch != null && dispatch.craft() == null) dispatch = null;
        boolean fromPending = false;
        if (dispatch == null && resolvedJob != null) {
            Deque<EcoBusDispatch> pending = pendingEcoDispatches.get(resolvedJob);
            dispatch = pending == null ? null : pending.pollFirst();
            fromPending = dispatch != null;
            if (pending != null && pending.isEmpty()) pendingEcoDispatches.remove(resolvedJob, pending);
        }
        if (dispatch == null && resolvedJob != null) {
            BlockPos recoveredBus = findEcoBusForJob(ecoThread, resolvedJob);
            ProviderCraft recoveredCraft = recoveredBus == null ? null : currentEcoBusJobs.get(recoveredBus);
            if (recoveredCraft == null && recoveredBus != null) {
                recoveredCraft = currentEcoPreparedJobs.get(recoveredBus);
            }
            if (recoveredBus != null && recoveredCraft != null) {
                dispatch = new EcoBusDispatch(recoveredBus, recoveredCraft, System.currentTimeMillis());
                fromPending = true;
                ModLogger.debug("ECO highlight worker.attach_recovered pos={} jobId={} worker={} thread={}",
                        recoveredBus, resolvedJob, System.identityHashCode(ecoThread),
                        Thread.currentThread().getName());
            }
        }
        if (dispatch == null) {
            ModLogger.debug("ECO highlight worker.attach_miss jobId={} worker={} pendingJobs={} thread={}",
                    resolvedJob, System.identityHashCode(ecoThread), pendingEcoDispatches.keySet(),
                    Thread.currentThread().getName());
            return;
        }
        if (!fromPending) forgetPendingEcoDispatch(dispatch);
        ModLogger.debug("ECO highlight worker.attach pos={} jobId={} worker={} pending={} thread={}",
                dispatch.busPosition(), dispatch.craft().jobId(), System.identityHashCode(ecoThread),
                fromPending, Thread.currentThread().getName());
        currentEcoBusCrafts
                .computeIfAbsent(dispatch.busPosition(), ignored -> new IdentityHashMap<>())
                .put(ecoThread, dispatch.craft());
        ecoBusJobLastActivityMs.put(dispatch.busPosition(), System.currentTimeMillis());
        removeEcoDispatchMarkers(dispatch.busPosition(), dispatch.craft().jobId());
        ecoThreadBusPositions.put(ecoThread, dispatch.busPosition());
        ecoThreadProgress.remove(ecoThread);
        ecoBusHandoffUntilMs.remove(dispatch.busPosition());
        markHighlightStateChanged();

        seedEcoHighlightEntry(dispatch.busPosition(), dispatch.craft(), "worker.start");
    }

    public static void attachEcoThreadExecution(Object ecoThread) {
        attachEcoThreadExecution(ecoThread, null);
    }

    /**
     * Reads the job an ECO worker owns. NeoECO starts some work kinds without passing a
     * job id to the callback, and the worker work record is not always reachable, so the
     * live snapshot - which exposes the job for every work kind - is the source of truth.
     */
    @Nullable
    public static UUID ecoThreadJobId(Object ecoThread) {
        Object snapshot = invokeNoArg(ecoThread, "createSnapshot");
        Object value = snapshot == null ? null : invokeNoArg(snapshot, "craftingJobId");
        if (value instanceof UUID id) return id;
        return extractEcoJobId(ecoThread);
    }

    @Nullable
    public static UUID extractEcoJobId(Object work) {
        if (work == null) return null;
        Object value = invokeNoArg(work, "craftingJobId");
        if (!(value instanceof UUID)) value = invokeNoArg(work, "jobId");
        return value instanceof UUID id ? id : null;
    }

    private static void removeEcoDispatchMarkers(BlockPos busPosition, @Nullable UUID jobId) {
        if (jobId == null) return;
        Map<Object, ProviderCraft> executions = currentEcoBusCrafts.get(busPosition);
        if (executions == null) return;
        executions.entrySet().removeIf(entry ->
                entry.getKey() instanceof EcoDispatchMarker marker && jobId.equals(marker.jobId()));
        if (executions.isEmpty()) currentEcoBusCrafts.remove(busPosition);
    }

    @Nullable
    private static BlockPos findEcoBusForJob(Object ecoThread, UUID jobId) {
        ResourceLocation outputId = null;
        Object displayed = invokeNoArg(ecoThread, "getDisplayedOutputKey");
        if (displayed instanceof AEKey key) outputId = key.getId();

        BlockPos accepted = bestEcoBusForJob(currentEcoBusJobs, ecoBusJobLastActivityMs, jobId, outputId);
        if (accepted != null) return accepted;
        // The exact virtual fast path starts the worker straight from the prepared offer
        // without dispatching through the bus, so the preparation is the only owner link.
        return bestEcoBusForJob(currentEcoPreparedJobs, ecoPreparedJobAtMs, jobId, outputId);
    }

    /**
     * Picks the bus that owns a job, preferring an exact match on the worker's displayed
     * output and falling back to the most recently active candidate.
     */
    @Nullable
    private static BlockPos bestEcoBusForJob(Map<BlockPos, ProviderCraft> jobs,
                                             Map<BlockPos, Long> activityByPos,
                                             UUID jobId, @Nullable ResourceLocation outputId) {
        BlockPos fallback = null;
        long newest = Long.MIN_VALUE;
        for (Map.Entry<BlockPos, ProviderCraft> entry : jobs.entrySet()) {
            ProviderCraft craft = entry.getValue();
            if (!jobId.equals(craft.jobId())) continue;
            if (outputId != null && outputId.equals(craft.outputId())) return entry.getKey();
            long activity = activityByPos.getOrDefault(entry.getKey(), 0L);
            if (activity > newest) {
                newest = activity;
                fallback = entry.getKey();
            }
        }
        return fallback;
    }

    /** Removes a completed ECO execution as soon as its worker thread becomes free. */
    public static void clearEcoThreadExecution(Object ecoThread) {
        BlockPos busPosition = ecoThreadBusPositions.remove(ecoThread);
        ModLogger.debug("ECO highlight worker.clear worker={} pos={}",
                System.identityHashCode(ecoThread), busPosition);
        if (busPosition == null) return;
        ecoThreadProgress.remove(ecoThread);
        Map<Object, ProviderCraft> executions = currentEcoBusCrafts.get(busPosition);
        if (executions == null) return;
        executions.remove(ecoThread);
        if (executions.isEmpty()) {
            currentEcoBusCrafts.remove(busPosition);
            startEcoBusHandoffGrace(busPosition);
            ModLogger.debug("ECO highlight worker.idle pos={} jobId={} reason=worker_empty",
                    busPosition, currentEcoBusJobs.get(busPosition) == null
                            ? null : currentEcoBusJobs.get(busPosition).jobId());
        }
    }

    private static void seedEcoHighlightEntry(BlockPos pos, ProviderCraft craft, String phase) {
        TrackerEntry entry = entries.computeIfAbsent(pos, ignored -> new TrackerEntry(0));
        long now = System.currentTimeMillis();
        entry.sourceClass = ECO_PATTERN_BUS_CLASS;
        entry.busyStartMs = now;
        entry.lastBusyProgressMs = now;
        entry.activeStartMs = now;
        entry.lastOutputSeenMs = now;
        entry.lockStartMs = 0;
        entry.stuck = false;
        entry.tentative = false;
        entry.missedCount = 0;
        entry.cooldownUntilMs = 0;
        entry.currentCraftingId = craft.outputId();
        entry.outputs = List.of(buildOutputItem(craft.outputId()));
        markHighlightStateChanged();
        debugProviderEvent("eco." + phase, pos, entry, now,
                "jobId=" + craft.jobId() + " outputId=" + craft.outputId());
    }

    private static @Nullable ProviderCraft getEcoBusCraft(BlockPos busPosition) {
        Map<Object, ProviderCraft> executions = currentEcoBusCrafts.get(busPosition);
        if (executions == null) {
            return null;
        }
        if (executions.isEmpty()) {
            currentEcoBusCrafts.remove(busPosition);
            startEcoBusHandoffGrace(busPosition);
            ProviderCraft recovering = currentEcoBusJobs.get(busPosition);
            long lastActivity = ecoBusJobLastActivityMs.getOrDefault(busPosition, 0L);
            if (recovering != null && lastActivity != 0L
                    && System.currentTimeMillis() - lastActivity <= ECO_JOB_ACTIVITY_TIMEOUT_MS) {
                return recovering;
            }
            return null;
        }

        // clearWork is the authoritative lifecycle boundary. ECO can report a
        // worker as idle for a tick while it is handing the next batch to the
        // same thread; polling isBusy here caused intermittent highlight loss.
        long now = System.currentTimeMillis();
        // A marker only has to bridge acceptance and the worker callback. One that
        // outlived that window belongs to a job that never attached, and leaving it in
        // place would both keep the bus busy forever and let it shadow the next job.
        executions.entrySet().removeIf(entry -> entry.getKey() instanceof EcoDispatchMarker marker
                && now - marker.createdAtMs() > ECO_PENDING_DISPATCH_MAX_MS);
        if (executions.isEmpty()) {
            currentEcoBusCrafts.remove(busPosition);
            startEcoBusHandoffGrace(busPosition);
            return null;
        }

        ProviderCraft placeholder = null;
        for (Map.Entry<Object, ProviderCraft> entry : executions.entrySet()) {
            if (entry.getKey() instanceof EcoDispatchMarker) {
                if (placeholder == null) placeholder = entry.getValue();
                continue;
            }
            // A live worker always wins over a placeholder for another job.
            return entry.getValue();
        }
        return placeholder;
    }

    private static @Nullable ProviderCraft getRecoveringEcoBusJob(BlockPos busPosition, long now) {
        ProviderCraft craft = currentEcoBusJobs.get(busPosition);
        long acceptedAt = ecoBusJobAcceptedAtMs.getOrDefault(busPosition, 0L);
        long lastActivity = ecoBusJobLastActivityMs.getOrDefault(busPosition, acceptedAt);
        long timeout = lastActivity == acceptedAt ? ECO_JOB_RECOVERY_WINDOW_MS : ECO_JOB_ACTIVITY_TIMEOUT_MS;
        if (craft == null || lastActivity == 0L || now - lastActivity > timeout) {
            if (craft != null) {
                currentEcoBusJobs.remove(busPosition);
                ecoBusJobAcceptedAtMs.remove(busPosition);
                ecoBusJobLastActivityMs.remove(busPosition);
                ModLogger.debugThrottled("eco.job.expire." + busPosition.asLong(),
                        CTConfig.debugLogIntervalTicks,
                        "ECO highlight job.expire pos={} jobId={} ageMs={}",
                        busPosition, craft.jobId(), lastActivity == 0L ? -1 : now - lastActivity);
            }
            return null;
        }
        return craft;
    }

    private static @Nullable ProviderCraft getMatrixPatternCraft(BlockEntity matrix) {
        BlockPos patternPosition = matrix.getBlockPos();
        Map<Object, ProviderCraft> executions = currentMatrixPatternCrafts.get(patternPosition);
        if (executions == null) {
            executions = new IdentityHashMap<>();
            currentMatrixPatternCrafts.put(patternPosition, executions);
        }

        List<Object> completedThreads = new ArrayList<>();
        for (Object thread : executions.keySet()) {
            if (!isMatrixThreadBusy(thread)) completedThreads.add(thread);
        }
        for (Object thread : completedThreads) {
            executions.remove(thread);
        }

        // A matrix job can predate enabling the highlighter, or arrive through an
        // optional integration path that does not retain the dispatch call stack, so this
        // core may have no recorded execution even though its workers are running.
        // The scan walks every block entity of the cluster, which is far too expensive to
        // repeat on every tick of every idle matrix in range, so it only runs when nothing
        // is tracked and at most once per second. A dispatched job is already covered by
        // the call hooks, so this fallback is never on the hot path of a known execution.
        long nowMs = System.currentTimeMillis();
        if (executions.isEmpty()
                && nowMs - matrixRecoveryAtMs.getOrDefault(patternPosition, 0L)
                        >= MATRIX_RECOVERY_INTERVAL_MS) {
            matrixRecoveryAtMs.put(patternPosition, nowMs);
            recoverMatrixExecutions(matrix, executions);
        }
        if (executions.isEmpty()) {
            currentMatrixPatternCrafts.remove(patternPosition);
            if (!completedThreads.isEmpty()) {
                ModLogger.debugThrottled("matrix.thread_prune." + patternPosition.asLong(),
                        CTConfig.debugLogIntervalTicks,
                        "Released completed matrix worker mappings pos={} count={}",
                        patternPosition, completedThreads.size());
            }
            MatrixHandoff handoff = matrixPatternHandoffs.get(patternPosition);
            if (handoff != null && handoff.untilMs() > nowMs) {
                ModLogger.debugThrottled("matrix.handoff." + patternPosition.asLong(),
                        CTConfig.debugLogIntervalTicks,
                        "Preserving matrix highlight between batches pos={} outputId={} remainingMs={}",
                        patternPosition, handoff.craft().outputId(), handoff.untilMs() - nowMs);
                return handoff.craft();
            }
            matrixPatternHandoffs.remove(patternPosition);
            return null;
        }
        MatrixHandoff previousHandoff = matrixPatternHandoffs.get(patternPosition);
        ProviderCraft active = previousHandoff != null
                && executions.containsValue(previousHandoff.craft())
                ? previousHandoff.craft()
                : executions.values().iterator().next();
        matrixPatternHandoffs.put(
                patternPosition,
                new MatrixHandoff(active, System.currentTimeMillis() + MATRIX_HANDOFF_GRACE_MS));
        return active;
    }

    private static void recoverMatrixExecutions(
            BlockEntity matrix, Map<Object, ProviderCraft> executions) {
        Object cluster = invokeNoArg(matrix, "getCluster");
        Object blocks = invokeNoArg(cluster, "getBlockEntities");
        if (!(blocks instanceof java.util.Iterator<?> iterator)) return;

        int recovered = 0;
        while (iterator.hasNext()) {
            Object block = iterator.next();
            Object workers;
            try {
                workers = readField(block, "threads");
            } catch (ReflectiveOperationException ignored) {
                continue;
            }
            if (!(workers instanceof Object[] array)) continue;

            for (Object worker : array) {
                if (executions.containsKey(worker)) continue;
                Object current = invokeNoArg(worker, "getCurrentPattern");
                if (!(current instanceof IPatternDetails pattern)
                        || !isMatrixPatternOwnedBy(matrix, pattern)) {
                    continue;
                }
                GenericStack output = pattern.getPrimaryOutput();
                if (output == null || output.what() == null) continue;
                executions.put(worker, new ProviderCraft(output.what().getId(), null));
                recovered++;
            }
        }

        if (recovered > 0) {
            ModLogger.debugThrottled("matrix.recover." + matrix.getBlockPos().asLong(),
                    CTConfig.debugLogIntervalTicks,
                    "Recovered active matrix workers pos={} count={}",
                    matrix.getBlockPos(), recovered);
        }
    }

    private static boolean isMatrixPatternOwnedBy(
            BlockEntity matrix, IPatternDetails activePattern) {
        for (IPatternDetails available : getPatterns(matrix)) {
            if (available == activePattern || available.equals(activePattern)) return true;
        }
        return false;
    }

    private static boolean isMatrixThreadBusy(Object thread) {
        if (invokeNoArg(thread, "getCurrentPattern") != null) return true;
        Object inventory = invokeNoArg(thread, "getInternalInventory");
        return inventory instanceof InternalInventory internalInventory && !internalInventory.isEmpty();
    }

    private static void startEcoBusHandoffGrace(BlockPos busPosition) {
        ecoBusHandoffUntilMs.put(
                busPosition,
                System.currentTimeMillis() + ECO_HANDOFF_GRACE_MS);
    }

    private static long ecoBusHandoffRemaining(BlockPos busPosition, long now) {
        long until = ecoBusHandoffUntilMs.getOrDefault(busPosition, 0L);
        if (until <= now) {
            ecoBusHandoffUntilMs.remove(busPosition);
            return 0;
        }
        return until - now;
    }

    private static boolean preserveEcoHandoff(
            BlockEntity be, BlockPos busPosition, TrackerEntry entry, long now, String phase) {
        if (!isEcoPatternBus(be)) return false;
        long remainingMs = ecoBusHandoffRemaining(busPosition, now);
        if (remainingMs <= 0
                || ((entry.outputs == null || entry.outputs.isEmpty())
                        && entry.currentCraftingId == null)) {
            return false;
        }

        entry.busyStartMs = 0;
        entry.lockStartMs = 0;
        entry.stuck = false;
        entry.cooldownUntilMs = now + remainingMs;
        if (entry.outputs != null && !entry.outputs.isEmpty()) {
            resetActiveTimer(entry, now);
        } else {
            entry.activeStartMs = now;
        }
        debugProviderSample(phase, busPosition, entry, now,
                "busy=false remainingMs=" + remainingMs);
        return true;
    }

    /** Refreshes the timeout only when an ECO worker has made real progress. */
    private static void refreshEcoProgress(BlockPos busPosition, TrackerEntry entry, long now) {
        Map<Object, ProviderCraft> executions = currentEcoBusCrafts.get(busPosition);
        if (executions == null || executions.isEmpty()) return;

        boolean progressed = false;
        for (Object thread : executions.keySet()) {
            // NeoECO exposes lane progress through its snapshot, not a getter.
            Object snapshot = invokeNoArg(thread, "createSnapshot");
            Object value = snapshot == null ? null : invokeNoArg(snapshot, "progress");
            if (!(value instanceof Number)) value = invokeNoArg(thread, "getProgress");
            if (!(value instanceof Number number)) continue;
            int current = number.intValue();
            Integer previous = ecoThreadProgress.put(thread, current);
            if (previous != null && previous.intValue() != current) {
                progressed = true;
            }
        }
        if (progressed) {
            entry.busyStartMs = now;
            entry.lastBusyProgressMs = now;
            entry.activeStartMs = now;
            entry.stuck = false;
            entry.cooldownUntilMs = 0;
            debugProviderEvent("eco.progress", busPosition, entry, now,
                    "executionCount=" + executions.size());
        }
    }

    /** Records a Trinity dispatch only after its provider accepted ownership and CPU accounting completed. */
    public static void recordTrinityDispatch(Object request, Object result) {
        if (request == null || result == null) return;
        // Trinity's dispatch types are optional at runtime.
        Object dispatched = invokeNoArg(result, "dispatched");
        if (!Boolean.TRUE.equals(dispatched)) return;
        Object provider = invokeNoArg(request, "provider");
        Object patternDetails = invokeNoArg(request, "pattern");
        Object job = invokeNoArg(request, "jobId");
        if (provider instanceof ICraftingProvider craftingProvider
                && patternDetails instanceof IPatternDetails details) {
            UUID jobId = job instanceof UUID id ? id : null;
            recordProviderPatternPush(craftingProvider, details, jobId);
            recordTrinityCorePattern(details, jobId);
        }
    }

    public static void onServerTick(MinecraftServer server) {
        if (trackingServer != server) {
            clearTransientTrackingState();
            trackingServer = server;
        }

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
        pendingEcoDispatches.entrySet().removeIf(entry -> {
            entry.getValue().removeIf(dispatch -> now - dispatch.createdAtMs() > ECO_PENDING_DISPATCH_MAX_MS);
            return entry.getValue().isEmpty();
        });
        ecoPreparedJobAtMs.entrySet().removeIf(entry -> {
            if (now - entry.getValue() <= ECO_PREPARED_JOB_TTL_MS) return false;
            currentEcoPreparedJobs.remove(entry.getKey());
            return true;
        });
        int radius = CTConfig.scanRadius;
        matrixRecoveryAtMs.entrySet().removeIf(entry -> now - entry.getValue() > MATRIX_RECOVERY_IDLE_MS);

        ModLogger.debugThrottled("server.tick", CTConfig.debugLogIntervalTicks,
                "Tracker tick players={} entries={} radius={} scanInterval={} gameTime={}",
                trackingPlayers.size(), entries.size(), radius, CTConfig.scanIntervalTicks, gameTime);

        // ================================================================
        // Locator tracking runs every tick, independent of tracking state.
        // ================================================================
        locatorTracking.onServerTick(server, gameTime);

        // ================================================================
        // Above: always runs. Below: only when tracking is enabled.
        // ================================================================

        if (trackingPlayers.isEmpty()) {
            if (!entries.isEmpty()) {
                ModLogger.debugThrottled("server.disabled", CTConfig.debugLogIntervalTicks,
                        "Tracker disabled for all players; clearing {} server entries", entries.size());
                entries.clear();
            }
            lastHighlightSnapshots.clear();
            lastHighlightRuntimeStates.clear();
            lastHighlightPacketTicks.clear();
            lastHighlightSnapshotRevisions.clear();
            return;
        }

        Set<UUID> trackingPlayerIds = new HashSet<>();
        for (ServerPlayer player : trackingPlayers) trackingPlayerIds.add(player.getUUID());
        lastHighlightSnapshots.keySet().removeIf(playerId -> !trackingPlayerIds.contains(playerId));
        lastHighlightRuntimeStates.keySet().removeIf(playerId -> !trackingPlayerIds.contains(playerId));
        lastHighlightPacketTicks.keySet().removeIf(playerId -> !trackingPlayerIds.contains(playerId));
        lastHighlightSnapshotRevisions.keySet().removeIf(playerId -> !trackingPlayerIds.contains(playerId));

        // Phase 1: every tick — refresh state for known entries + quick-check nearby for busy providers
        clearDisconnectedEcoEntries(server, now);
        refreshEntries(server, now);
        quickScan(server, now, trackingPlayers);

        // Phase 2: periodic scan — discover providers and update state
        scanCounter++;
        boolean doScan = scanCounter % CTConfig.scanIntervalTicks == 0;

        ModLogger.debugThrottled("server.scan.phase", CTConfig.debugLogIntervalTicks,
                "Tracker phases refresh=true quick=true periodicScan={} scanCounter={}", doScan, scanCounter);

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

            ModLogger.debugThrottled("server.scan.result", CTConfig.debugLogIntervalTicks,
                    "Periodic provider scan complete seen={} seenProviders={} trackedEntries={}",
                    seen.size(), seenProviders.size(), entries.size());
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

                TrackerEntry trackerEntry = e.getValue();
                CraftStatus status = computeStatus(trackerEntry, now);
                if (trackerEntry.lastSentStatus != status) {
                    CraftStatus previousStatus = trackerEntry.lastSentStatus;
                    trackerEntry.lastSentStatus = status;
                    ModLogger.debugThrottled("provider.status." + pos.asLong(),
                            CTConfig.debugLogIntervalTicks,
                            "Provider status changed pos={} source={} from={} to={} currentId={} outputs={}",
                            pos, trackerEntry.sourceClass, previousStatus, status,
                            trackerEntry.currentCraftingId, outputSummary(trackerEntry.outputs));
                }
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
                highlightEntries.add(new HighlightEntry(
                        pos,
                        status.ordinal(),
                        packetOutputs,
                        e.getValue().currentCraftingId
                ));
            }

            highlightEntries.sort(java.util.Comparator.comparingLong(entry -> entry.pos().asLong()));
            int runtimeRemaining = getRuntimeRemainingTicks(player.getUUID(), gameTime);
            if (!shouldSendHighlightSnapshot(player.getUUID(), highlightEntries, runtimeRemaining, gameTime)) {
                continue;
            }
            ModLogger.debugThrottled("highlight.packet." + player.getUUID(), CTConfig.debugLogIntervalTicks,
                    "Sending craft highlight packet player={} entries={} runtimeRemainingTicks={}",
                    player.getGameProfile().getName(), highlightEntries.size(), runtimeRemaining);
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
            if (adjacentBe instanceof MolecularAssemblerBlockEntity assembler
                    && assembler.getCraftingProgress() > 0) {
                ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                return new AdjacentActivity(true,
                        blockId + "@" + dir.getSerializedName()
                                + ".craftingProgress=" + assembler.getCraftingProgress());
            }
            int extendedProgress = getExtendedAssemblerProgress(adjacentBe);
            if (extendedProgress > 0) {
                ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                return new AdjacentActivity(true,
                        blockId + "@" + dir.getSerializedName()
                                + ".craftingProgress=" + extendedProgress);
            }
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

    private static int getExtendedAssemblerProgress(BlockEntity be) {
        if (!be.getClass().getName().equals("com.glodblock.github.extendedae.common.tileentities.TileExMolecularAssembler")) {
            return 0;
        }
        Method method = lookupMethod(be.getClass(), "getCraftingProgress", int.class);
        if (method == null) return 0;
        try {
            for (int thread = 0; thread < 16; thread++) {
                Object value = method.invoke(be, thread);
                if (value instanceof Number progress && progress.intValue() > 0) {
                    return progress.intValue();
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // ExtendedAE is optional and its thread count can vary by version.
        }
        return 0;
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
                            entry.sourceClass = be.getClass().getName();
                            entry.stuck = locked;
                            if (busy && !locked) beginBusyMonitoring(immPos, entry, now);
                            var info = getOutputInfo(be, null);
                            applyOutputInfo(entry, info, now);
                            entries.put(immPos, entry);
                            prevProviderBusy.put(immPos, true);
                            debugProviderEvent("quick.create_active", immPos, entry, now,
                                    "busy=" + busy + " locked=" + locked + " outputInfo=" + outputSummary(info));
                        } else {
                            var info = getOutputInfo(be, null);
                            AdjacentActivity adjacentActivity = getAdjacentActivity(level, immPos);
                            if (shouldTrackIdleProvider(be, info, adjacentActivity)) {
                                TrackerEntry entry = new TrackerEntry(0);
                                entry.sourceClass = be.getClass().getName();
                                applyOutputInfo(entry, info, now);
                                entry.tentative = true;
                                entry.cooldownUntilMs = now + COOLDOWN_MS;
                                entries.put(immPos, entry);
                                debugProviderEvent("quick.create_active_machine", immPos, entry, now,
                                        "busy=false locked=false adjacentActive=" + adjacentActivity.detail()
                                                + " outputInfo=" + outputSummary(info));
                            }
                        }
                    }
                }
            }
        }
    }

    /** Removes stale ECO highlights immediately when their physical bus loses its ME grid. */
    private static void clearDisconnectedEcoEntries(MinecraftServer server, long now) {
        entries.entrySet().removeIf(entry -> {
            BlockPos pos = entry.getKey();
            if (!ECO_PATTERN_BUS_CLASS.equals(entry.getValue().sourceClass)) return false;
            BlockEntity bus = null;
            for (ServerLevel level : server.getAllLevels()) {
                if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;
                bus = level.getBlockEntity(pos);
                if (bus != null) break;
            }
            if (!isEcoPatternBus(bus) || getGrid(bus) != null) return false;
            currentEcoBusCrafts.remove(pos);
            currentEcoBusJobs.remove(pos);
            ecoBusJobAcceptedAtMs.remove(pos);
            ecoBusJobLastActivityMs.remove(pos);
            currentEcoPreparedJobs.remove(pos);
            ecoPreparedJobAtMs.remove(pos);
            currentProviderCrafts.remove(getCraftingProvider(bus));
            markHighlightStateChanged();
            ModLogger.debug("ECO highlight disconnected.clear pos={} now={} reason=grid_missing", pos, now);
            return true;
        });
    }

    private static void refreshEntries(MinecraftServer server, long now) {
        for (var e : entries.entrySet()) {
            BlockPos pos = e.getKey();
            TrackerEntry entry = e.getValue();

            for (ServerLevel level : server.getAllLevels()) {
                if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;
                BlockEntity be = level.getBlockEntity(pos);
                if (!isPatternSource(be)) continue;
                entry.sourceClass = be.getClass().getName();

                boolean busy = isPatternBusy(be);
                boolean locked = isPatternLocked(be);
                if (busy) {
                    boolean startedBusy = entry.busyStartMs == 0;
                    boolean wasMarkedStuck = entry.stuck;

                    entry.cooldownUntilMs = 0;
                    entry.missedCount = 0;
                    entry.tentative = false;
                    prevProviderBusy.put(pos, true);

                    var info = getOutputInfo(be, entry.outputs);
                    applyOutputInfo(entry, info, now);

                    entry.stuck = locked;

                    if (locked && entry.lockStartMs == 0) {
                        entry.lockStartMs = now;
                    } else if (!locked) {
                        entry.lockStartMs = 0;
                    }

                    boolean madeTransferProgress = false;
                    AdjacentActivity adjacentActivity = AdjacentActivity.NONE;
                    if (!locked) {
                        // A provider can remain busy while its target keeps consuming inputs.
                        // Only a lack of observed transfer or machine activity is a potential stall.
                        if (startedBusy || wasMarkedStuck) {
                            beginBusyMonitoring(pos, entry, now);
                        }
                        madeTransferProgress = refreshBusyTransferProgress(pos, entry);
                        adjacentActivity = getAdjacentActivity(level, pos);
                        if (adjacentActivity.active()) {
                            entry.lastBusyProgressMs = now;
                        }
                    } else {
                        entry.lastBusyProgressMs = 0;
                    }
                    if (isEcoPatternBus(be) && !locked) {
                        refreshEcoProgress(pos, entry, now);
                    }
                    if (!locked && (startedBusy || wasMarkedStuck)) {
                        resetActiveTimer(entry, now);
                        if (wasMarkedStuck) {
                            debugProviderEvent("refresh.recover_busy", pos, entry, now,
                                    "busy=true locked=false startedBusy=" + startedBusy
                                            + " wasMarkedStuck=" + wasMarkedStuck
                                            + " outputInfo=" + outputSummary(info));
                        }
                    }
                    debugProviderSample("refresh.busy", pos, entry, now,
                            "busy=true locked=" + locked
                                    + " transferProgress=" + madeTransferProgress
                                    + " adjacentActive=" + adjacentActivity.detail()
                                    + " outputInfo=" + outputSummary(info));
                } else {
                    if (!locked && preserveEcoHandoff(
                            be, pos, entry, now, "refresh.eco_handoff_grace")) {
                        break;
                    }
                    entry.busyStartMs = 0;
                    entry.lastBusyProgressMs = 0;
                    providerTransferProgress.remove(pos);
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
                        } else if (locked) {
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
                            if (adjacentActivity.active()) {
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
                            AdjacentActivity adjacentActivity = getAdjacentActivity(level, pos);
                            if (shouldTrackIdleProvider(be, info, adjacentActivity)) {
                                applyOutputInfo(entry, info, now);
                                resetActiveTimer(entry, now);
                                if (entry.cooldownUntilMs == 0 || entry.cooldownUntilMs - now < COOLDOWN_MS / 2) {
                                    entry.cooldownUntilMs = now + COOLDOWN_MS;
                                }
                                debugProviderSample("refresh.idle_cpu_busy", pos, entry, now,
                                        "busy=false cpuBusy=true adjacentActive=" + adjacentActivity.detail()
                                                + " outputInfo=" + outputSummary(info));
                            } else {
                                // AE's request index can briefly be empty while a repeated
                                // request is being re-queued. Keep the last output through
                                // the normal grace window instead of dropping the highlight.
                                clearExpiredOutput(pos, entry, now);
                                debugProviderSample("refresh.idle_cpu_busy_clear", pos, entry, now,
                                        "busy=false cpuBusy=true adjacentActive=" + adjacentActivity.detail()
                                                + " outputInfo=" + outputSummary(info));
                            }
                        } else if (now < entry.cooldownUntilMs) {
                            entry.missedCount = 0;
                            // CPU may have started a new job even if isGridCpuBusy was false
                            var info = getOutputInfo(be, entry.outputs);
                            if (info == null || info.isEmpty()) {
                                // A frequent automatic request may disappear for one tick
                                // between CPU status updates. Apply the same hysteresis used
                                // by the normal idle path to prevent highlight flicker.
                                clearExpiredOutput(pos, entry, now);
                            } else {
                                applyOutputInfo(entry, info, now);
                            }
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
                        AdjacentActivity adjacentActivity = getAdjacentActivity(level, pos);
                        if (shouldTrackIdleProvider(be, info, adjacentActivity)) {
                            applyOutputInfo(entry, info, now);
                            entry.tentative = false;
                            entry.missedCount = 0;
                            entry.cooldownUntilMs = now + COOLDOWN_MS;
                            debugProviderEvent("refresh.tentative_promote", pos, entry, now,
                                    "busy=false adjacentActive=" + adjacentActivity.detail()
                                            + " outputInfo=" + outputSummary(info));
                        } else {
                            entry.lockStartMs = 0;
                            clearExpiredOutput(pos, entry, now);
                            debugProviderSample("refresh.tentative_clear", pos, entry, now,
                                    "busy=false adjacentActive=" + adjacentActivity.detail()
                                            + " outputInfo=" + outputSummary(info));
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
                    if (existing != null) {
                        // refreshEntries owns all state transitions for existing
                        // entries. This scan only confirms the entry remains live.
                        existing.sourceClass = be.getClass().getName();
                        if (active || existing.stuck || now < existing.cooldownUntilMs
                                || hasRecentOutput(existing, now)) {
                            existing.missedCount = 0;
                            seen.add(immPos);
                        }
                        continue;
                    }

                    if (active) {
                        seen.add(immPos);

                        var info = getOutputInfo(be, null);

                        TrackerEntry entry = new TrackerEntry(locked ? now : 0);
                        entry.sourceClass = be.getClass().getName();
                        if (!locked) {
                            beginBusyMonitoring(immPos, entry, now);
                        }
                        entry.stuck = locked;
                        applyOutputInfo(entry, info, now);
                        entries.put(immPos, entry);
                        debugProviderEvent("scan.create_active", immPos, entry, now,
                                "busy=" + busy + " locked=" + locked + " outputInfo=" + outputSummary(info));
                    } else if (wasActive) {
                        TrackerEntry entry = new TrackerEntry(0);
                        entry.sourceClass = be.getClass().getName();
                        var info = getOutputInfo(be, null);
                        applyOutputInfo(entry, info, now);
                        entry.cooldownUntilMs = now + COOLDOWN_MS;
                        entries.put(immPos, entry);
                        seen.add(immPos);
                        debugProviderEvent("scan.create_cooldown_after_active", immPos, entry, now,
                                "busy=false locked=false wasActive=true outputInfo=" + outputSummary(info));
                    } else {
                        // Idle provider, no existing entry — check for an active requested output.
                        var info = getOutputInfo(be, null);
                        AdjacentActivity adjacentActivity = getAdjacentActivity(level, immPos);
                        if (shouldTrackIdleProvider(be, info, adjacentActivity)) {
                            TrackerEntry entry = new TrackerEntry(0);
                            entry.sourceClass = be.getClass().getName();
                            applyOutputInfo(entry, info, now);
                            entry.tentative = true;
                            entry.cooldownUntilMs = now + COOLDOWN_MS;
                            entries.put(immPos, entry);
                            seen.add(immPos);
                            debugProviderEvent("scan.create_active_machine", immPos, entry, now,
                                    "busy=false locked=false adjacentActive=" + adjacentActivity.detail()
                                            + " outputInfo=" + outputSummary(info));
                        }
                    }
                }
            }
        }
    }

    private static void applyOutputInfo(TrackerEntry entry, @Nullable OutputInfo info, long now) {
        if (info == null || info.isEmpty()) {
            // Do not erase the current id on a transient empty request result. It is cleared
            // together with the output after OUTPUT_GRACE_MS, keeping packet snapshots stable
            // while AE rebuilds a repeated automatic-crafting request.
            return;
        }
        if (!info.outputs().isEmpty()) {
            if (!samePrimaryOutput(entry.outputs, info.outputs()) || entry.activeStartMs == 0) {
                entry.activeStartMs = now;
            }
            entry.outputs = List.copyOf(info.outputs());
            entry.lastOutputSeenMs = now;
        }
        if (info.currentCraftingId() != null) {
            entry.currentCraftingId = info.currentCraftingId();
        } else if (!info.outputs().isEmpty()) {
            entry.currentCraftingId = null;
        }
    }

    private static void clearExpiredOutput(BlockPos pos, TrackerEntry entry, long now) {
        if (entry.lastOutputSeenMs != 0 && now - entry.lastOutputSeenMs <= OUTPUT_GRACE_MS) {
            return;
        }
        if (entry.outputs != null || entry.activeStartMs != 0 || entry.lastOutputSeenMs != 0) {
            debugProviderEvent("output.clear_expired", pos, entry, now,
                    "lastOutputAgeMs=" + (entry.lastOutputSeenMs == 0 ? -1 : now - entry.lastOutputSeenMs));
        }
        clearOutput(entry);
    }

    private static void clearOutput(TrackerEntry entry) {
        entry.outputs = null;
        entry.currentCraftingId = null;
        entry.activeStartMs = 0;
        entry.lastOutputSeenMs = 0;
    }

    private static void clearOutputImmediately(BlockPos pos, TrackerEntry entry, long now, String reason) {
        if (entry.outputs != null || entry.currentCraftingId != null) {
            debugProviderEvent("output.clear_immediate", pos, entry, now, reason);
        }
        clearOutput(entry);
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

    private static void beginBusyMonitoring(BlockPos pos, TrackerEntry entry, long now) {
        entry.busyStartMs = now;
        entry.lastBusyProgressMs = now;
        providerTransferProgress.remove(pos);
    }

    private static boolean refreshBusyTransferProgress(BlockPos pos, TrackerEntry entry) {
        long progressMs = providerTransferProgress.getOrDefault(pos, 0L);
        if (progressMs <= entry.lastBusyProgressMs || progressMs < entry.busyStartMs) {
            return false;
        }
        entry.lastBusyProgressMs = progressMs;
        return true;
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
                || entry.currentCraftingId != null;
    }

    private static void debugProviderSample(String phase, BlockPos pos, TrackerEntry entry, long now, String detail) {
        logProviderDiagnostic("provider.sample.", phase, pos, entry, now, detail);
    }

    private static void debugProviderEvent(String phase, BlockPos pos, TrackerEntry entry, long now, String detail) {
        logProviderDiagnostic("provider.event.", phase, pos, entry, now, detail);
    }

    private static void logProviderDiagnostic(
            String category, String phase, BlockPos pos, TrackerEntry entry, long now, String detail) {
        if (!CTConfig.debugTracking) return;
        ModLogger.debugThrottled(category + phase + "." + pos.asLong(), CTConfig.debugLogIntervalTicks,
                "Provider event phase={} pos={} {} {}",
                phase, pos, entryDebugSummary(entry, now), detail == null ? "" : detail);
    }

    private static String entryDebugSummary(TrackerEntry entry, long now) {
        return "stuck=" + entry.stuck
                + " source=" + entry.sourceClass
                + " tentative=" + entry.tentative
                + " missed=" + entry.missedCount
                + " cooldownMs=" + Math.max(0, entry.cooldownUntilMs - now)
                + " busyAgeMs=" + age(now, entry.busyStartMs)
                + " busyProgressAgeMs=" + age(now, entry.lastBusyProgressMs)
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

    private static String outputSummary(@Nullable OutputInfo info) {
        return outputSummary(info == null ? null : info.outputs());
    }

    private static @Nullable OutputInfo getOutputInfo(BlockEntity be, @Nullable List<OutputItem> prevOutputs) {
        try {
            if (isTrinityPatternCore(be)) {
                ProviderCraft recorded = getTrinityCoreCraft(be);
                if (recorded != null) {
                    return new OutputInfo(
                            List.of(buildOutputItem(recorded.outputId())),
                            recorded.outputId(), false);
                }
                return null;
            }
            IGrid grid = getGrid(be);
            if (grid == null) return null;
            ICraftingService cs = grid.getCraftingService();
            if (cs == null) return null;

            var patterns = getPatterns(be);

            // Collect up to MAX_OUTPUTS matching items in pattern order to form a queue.
            // Matrix providers must use the CPU's current item only: isRequesting can
            // remain true briefly after a matrix craft has already finished.
            List<OutputItem> results = new ArrayList<>();
            boolean returnItems = false;
            boolean providerBusy = isPatternBusy(be);
            GenericStackInv returnInv = getReturnInventory(be);
            if (returnInv != null) {
                for (int slot = 0; slot < returnInv.size() && results.size() < MAX_OUTPUTS; slot++) {
                    GenericStack returned = returnInv.getStack(slot);
                    if (returned == null || returned.what() == null || returned.amount() <= 0) continue;
                    OutputItem item = buildOutputItem(returned.what());
                    if (item != null) {
                        results.add(item);
                        returnItems = true;
                    }
                }
            }
            ICraftingProvider provider = getCraftingProvider(be);
            boolean exactPhysicalProvider = isEcoPatternBus(be) || isMatrixSource(be);
            ResourceLocation providerCraftingId = null;
            if (isEcoPatternBus(be)) {
                ProviderCraft recorded = getEcoBusCraft(be.getBlockPos());
                boolean liveExecution = recorded != null;
                if (recorded == null) {
                    recorded = getRecoveringEcoBusJob(be.getBlockPos(), System.currentTimeMillis());
                }
                // A running worker is authoritative. The bus pattern list only has to
                // confirm a remembered job that no worker is executing any more, which
                // keeps a completed craft from being attributed to the wrong bus.
                if (recorded != null && (liveExecution || patterns.isEmpty()
                        || containsAnyPatternOutput(patterns, recorded.outputId()))) {
                    providerCraftingId = recorded.outputId();
                }
                ModLogger.debugThrottled("eco.output." + be.getBlockPos().asLong(),
                        CTConfig.debugLogIntervalTicks,
                        "ECO highlight output.resolve pos={} providerBusy={} recorded={} currentId={} localPatterns={} mapEntries={}",
                        be.getBlockPos(), providerBusy,
                        recorded == null ? "none" : recorded.jobId() + "/" + recorded.outputId(),
                        providerCraftingId, patterns.size(),
                        currentEcoBusCrafts.getOrDefault(be.getBlockPos(), Map.of()).size());
            } else if (isMatrixSource(be)) {
                ProviderCraft recorded = getMatrixPatternCraft(be);
                if (recorded != null && containsPatternOutput(patterns, recorded.outputId())) {
                    providerCraftingId = recorded.outputId();
                }
                if (!providerBusy && provider != null) currentProviderCrafts.remove(provider);
            } else if (provider != null && providerBusy) {
                ProviderCraft recorded = currentProviderCrafts.get(provider);
                if (recorded != null
                        && containsPatternOutput(patterns, recorded.outputId())) {
                    providerCraftingId = recorded.outputId();
                }
            } else if (provider != null) {
                // A completed external job must not seed the next job with its old pattern.
                currentProviderCrafts.remove(provider);
            }
            for (IPatternDetails pattern : patterns) {
                if (results.size() >= MAX_OUTPUTS) break;
                GenericStack output = pattern.getPrimaryOutput();
                if (output == null) continue;
                AEKey key = output.what();
                boolean currentCpuOutput = (!isMatrixSource(be) || providerBusy)
                        && isCpuCraftingOutput(cs, key);
                boolean requestedOutput = !isMatrixSource(be) && cs.isRequesting(key);
                if ((providerCraftingId != null && providerCraftingId.equals(key.getId()))
                        || (!exactPhysicalProvider && (currentCpuOutput || requestedOutput))) {
                    OutputItem item = buildOutputItem(key);
                    if (item != null) {
                        results.add(item);
                    }
                }
            }
            if (isEcoPatternBus(be) && providerCraftingId != null && results.isEmpty()) {
                OutputItem fallback = buildOutputItem(providerCraftingId);
                if (fallback != null) results.add(fallback);
            }
            ResourceLocation currentCraftingId = null;
            boolean adjacentMachineActive = be.getLevel() != null
                    && getAdjacentActivity(be.getLevel(), be.getBlockPos()).active();
            if (providerCraftingId != null) {
                currentCraftingId = providerCraftingId;
            }
            if (currentCraftingId == null) {
                if (!exactPhysicalProvider && (providerBusy || adjacentMachineActive)) {
                    currentCraftingId = findCurrentCraftingId(cs, patterns);
                }
            }
            OutputInfo info = results.isEmpty() && currentCraftingId == null
                    ? null
                    : new OutputInfo(results, currentCraftingId, returnItems);
            if (info != null || providerBusy || adjacentMachineActive || returnItems) {
                ModLogger.debugThrottled("output.resolve." + be.getBlockPos().asLong(),
                        CTConfig.debugLogIntervalTicks,
                        "Output resolution pos={} source={} patterns={} returnItems={} providerBusy={} adjacentActive={} currentId={} outputs={}",
                        be.getBlockPos(), be.getClass().getName(), patterns.size(), returnItems,
                        providerBusy, adjacentMachineActive, currentCraftingId, outputSummary(info));
            }
            return info;
        } catch (Exception e) {
            ModLogger.debugThrottled("output.error." + be.getBlockPos().asLong(),
                    CTConfig.debugLogIntervalTicks,
                    "Output resolution failed pos={} source={} error={}",
                    be.getBlockPos(), be.getClass().getName(), e.toString());
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
        } else if (hasType(key, MEKANISM_KEY_CLASS)) {
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
                if (status != null && status.crafting() != null && sameKey(status.crafting().what(), key)) {
                    return true;
                }
            }
        } catch (Exception e) {
            LOGGER.info("isCpuCraftingOutput: exception: {}", e.getMessage());
        }
        return false;
    }

    private static @Nullable ResourceLocation findCurrentCraftingId(
            ICraftingService cs, List<IPatternDetails> patterns) {
        try {
            for (ICraftingCPU cpu : cs.getCpus()) {
                if (!cpu.isBusy()) continue;
                ResourceLocation trackedOutput = currentCpuCrafts.get(cpu);
                if (trackedOutput != null && containsPatternOutput(patterns, trackedOutput)) {
                    return trackedOutput;
                }
                CraftingJobStatus status = cpu.getJobStatus();
                if (status == null || status.crafting() == null) continue;
                AEKey currentKey = status.crafting().what();
                for (IPatternDetails pattern : patterns) {
                    GenericStack output = pattern.getPrimaryOutput();
                    if (output != null && sameKey(output.what(), currentKey)) {
                        return currentKey.getId();
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.info("findCurrentCraftingId: exception: {}", e.getMessage());
        }
        return null;
    }

    private static @Nullable ProviderCraft getTrinityCoreCraft(BlockEntity core) {
        Object coreId = invokeNoArg(core, "coreId");
        if (coreId instanceof UUID id) {
            ProviderCraft recorded = currentTrinityCoreCrafts.get(id);
            if (recorded != null) return recorded;
        }
        if (!invokeBoolean(core, "hasWork")) return null;

        Object slots = invokeNoArg(core, "occupiedPatternSlots");
        if (!(slots instanceof Iterable<?> iterable)) return null;
        for (Object value : iterable) {
            if (!(value instanceof Number number)) continue;
            Object details = invokeInt(core, "decodedPattern", number.intValue());
            if (details instanceof IPatternDetails pattern) {
                GenericStack output = pattern.getPrimaryOutput();
                if (output != null && output.what() != null) {
                    return new ProviderCraft(output.what().getId(), null);
                }
            }
        }
        return null;
    }

    private static OutputItem buildOutputItem(ResourceLocation id) {
        if (BuiltInRegistries.ITEM.containsKey(id)) return new OutputItem(id, TYPE_ITEM);
        if (BuiltInRegistries.FLUID.containsKey(id)) return new OutputItem(id, TYPE_FLUID);
        return new OutputItem(id, TYPE_OTHER);
    }

    private static boolean containsPatternOutput(List<IPatternDetails> patterns, ResourceLocation outputId) {
        for (IPatternDetails pattern : patterns) {
            GenericStack output = pattern.getPrimaryOutput();
            if (output != null && output.what() != null && output.what().getId().equals(outputId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Matches any output of a pattern, not only the primary one. NeoECO's virtual fast
     * path reports the produced stacks of the whole craft, and a bus can be the owner of
     * a pattern whose primary output is not the stack that is currently being counted.
     */
    private static boolean containsAnyPatternOutput(List<IPatternDetails> patterns, ResourceLocation outputId) {
        for (IPatternDetails pattern : patterns) {
            for (GenericStack output : pattern.getOutputs()) {
                if (output != null && output.what() != null && output.what().getId().equals(outputId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static @Nullable GenericStackInv getReturnInventory(BlockEntity be) {
        PatternProviderLogicHost host = getPatternProviderHost(be);
        if (host != null) {
            return host.getLogic().getReturnInv();
        }
        if (isAdvancedPatternProvider(be)) {
            Object returnInv = invokeNoArg(invokeNoArg(be, "getLogic"), "getReturnInv");
            if (returnInv instanceof GenericStackInv result) return result;
        }
        return null;
    }

    private static boolean sameKey(AEKey first, AEKey second) {
        return first.equals(second) || first.getId().equals(second.getId());
    }

    private static CraftStatus computeStatus(TrackerEntry entry, long now) {
        if (entry.stuck) return CraftStatus.STUCK;

        long startMs;
        if (entry.lockStartMs != 0) {
            startMs = entry.lockStartMs;
        } else if (entry.busyStartMs != 0) {
            startMs = entry.lastBusyProgressMs != 0
                    ? entry.lastBusyProgressMs
                    : entry.busyStartMs;
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
        long lastBusyProgressMs;
        long activeStartMs;
        long lastOutputSeenMs;
        int missedCount;
        long cooldownUntilMs;
        boolean tentative;
        boolean stuck;
        @Nullable String sourceClass;
        @Nullable CraftStatus lastSentStatus;
        @Nullable List<OutputItem> outputs;
        @Nullable ResourceLocation currentCraftingId;

        TrackerEntry(long lockStartMs) {
            this.lockStartMs = lockStartMs;
        }
    }

    private record OutputInfo(List<OutputItem> outputs, @Nullable ResourceLocation currentCraftingId,
                              boolean returnItems) {
        private boolean isEmpty() {
            return outputs.isEmpty() && currentCraftingId == null && !returnItems;
        }
    }

    private static void clearTransientTrackingState() {
        entries.clear();
        prevProviderBusy.clear();
        currentProviderCrafts.clear();
        providerTransferProgress.clear();
        currentEcoBusCrafts.clear();
        currentEcoBusJobs.clear();
        ecoBusJobAcceptedAtMs.clear();
        ecoBusJobLastActivityMs.clear();
        currentEcoPreparedJobs.clear();
        ecoPreparedJobAtMs.clear();
        ecoThreadBusPositions.clear();
        ecoThreadProgress.clear();
        ecoBusHandoffUntilMs.clear();
        currentMatrixPatternCrafts.clear();
        matrixPatternHandoffs.clear();
        matrixRecoveryAtMs.clear();
        currentTrinityCoreCrafts.clear();
        currentCpuCrafts.clear();
        ecoBusDispatches.remove();
        matrixPatternDispatches.remove();
        scanCounter = 0;
    }

    private static boolean shouldSendHighlightSnapshot(
            UUID playerId, List<HighlightEntry> snapshot, int runtimeRemaining, long gameTime) {
        List<HighlightEntry> previousSnapshot = lastHighlightSnapshots.get(playerId);
        Integer previousRuntime = lastHighlightRuntimeStates.get(playerId);
        long previousRevision = lastHighlightSnapshotRevisions.getOrDefault(playerId, Long.MIN_VALUE);
        long lastPacketTick = lastHighlightPacketTicks.getOrDefault(playerId, Long.MIN_VALUE);

        boolean snapshotChanged = !snapshot.equals(previousSnapshot);
        boolean executionChanged = previousRevision != highlightSnapshotRevision;
        boolean runtimeModeChanged = previousRuntime == null
                || (runtimeRemaining > 0) != (previousRuntime > 0)
                || (runtimeRemaining == Integer.MAX_VALUE) != (previousRuntime == Integer.MAX_VALUE);
        boolean heartbeatDue = lastPacketTick == Long.MIN_VALUE
                || gameTime - lastPacketTick >= HIGHLIGHT_HEARTBEAT_TICKS;
        if (!snapshotChanged && !executionChanged && !runtimeModeChanged && !heartbeatDue) return false;

        lastHighlightSnapshots.put(playerId, List.copyOf(snapshot));
        lastHighlightRuntimeStates.put(playerId, runtimeRemaining);
        lastHighlightPacketTicks.put(playerId, gameTime);
        lastHighlightSnapshotRevisions.put(playerId, highlightSnapshotRevision);
        return true;
    }

    private static void markHighlightStateChanged() {
        highlightSnapshotRevision++;
    }

    /** Records the pattern that ECO actually accepted, including its fast-path execution. */
    public static void recordCpuPatternPush(@Nullable ICraftingCPU cpu, Object execution) {
        if (cpu == null || execution == null) return;
        // ECO is optional and this method is intentionally accessed without a compile dependency.
        Object outputs = invokeNoArg(execution, "expectedOutputs");
        if (!(outputs instanceof List<?> list)) return;
        for (Object value : list) {
            if (value instanceof GenericStack stack && stack.what() != null) {
                currentCpuCrafts.put(cpu, stack.what().getId());
                return;
            }
        }
    }

    public static void clearCpuPattern(@Nullable ICraftingCPU cpu) {
        if (cpu != null) currentCpuCrafts.remove(cpu);
    }

    /** Clears provider records belonging to a Trinity job when its CPU reaches a terminal state. */
    public static void clearProviderJob(Object cpuLogic) {
        if (cpuLogic == null) return;
        try {
            Object job = readField(cpuLogic, "job");
            if (job == null) return;
            Object link = readField(job, "link");
            if (link == null) return;
            Object craftingId = invokeNoArg(link, "getCraftingID");
            if (!(craftingId instanceof UUID jobId)) return;
            synchronized (currentProviderCrafts) {
                currentProviderCrafts.entrySet().removeIf(entry -> jobId.equals(entry.getValue().jobId()));
            }
            currentEcoBusCrafts.values().forEach(executions ->
                    executions.entrySet().removeIf(entry -> jobId.equals(entry.getValue().jobId())));
            currentEcoBusCrafts.entrySet().removeIf(entry -> entry.getValue().isEmpty());
            currentEcoBusJobs.entrySet().removeIf(entry -> jobId.equals(entry.getValue().jobId()));
            ecoBusJobAcceptedAtMs.keySet().removeIf(pos -> !currentEcoBusJobs.containsKey(pos));
            ecoBusJobLastActivityMs.keySet().removeIf(pos -> !currentEcoBusJobs.containsKey(pos));
            markHighlightStateChanged();
            ecoThreadBusPositions.entrySet().removeIf(entry -> {
                Map<Object, ProviderCraft> executions = currentEcoBusCrafts.get(entry.getValue());
                return executions == null || !executions.containsKey(entry.getKey());
            });
            currentTrinityCoreCrafts.entrySet().removeIf(entry -> jobId.equals(entry.getValue().jobId()));
        } catch (ReflectiveOperationException ignored) {
            // DataEnergistics is optional and its job internals are intentionally accessed reflectively.
        }
    }

    public static void clearTrinityJob(Object cpuLogic) {
        clearProviderJob(cpuLogic);
    }

    private static Object readField(Object target, String fieldName) throws ReflectiveOperationException {
        if (target == null) return null;
        Field field = lookupField(target.getClass(), fieldName);
        // Pre-allocated instance: signalling a miss must not capture a stack trace.
        if (field == null) throw FIELD_NOT_FOUND;
        return field.get(target);
    }

    private static void recordTrinityCorePattern(IPatternDetails details, @Nullable UUID jobId) {
        // Only DataEnergistics routed patterns expose a physical Trinity pattern-core identity.
        Object route = invokeNoArg(details, "route");
        if (route == null) return;
        Object coreId = invokeNoArg(route, "coreId");
        GenericStack output = details.getPrimaryOutput();
        if (coreId instanceof UUID id && output != null && output.what() != null) {
            currentTrinityCoreCrafts.put(id, new ProviderCraft(output.what().getId(), jobId));
            markHighlightStateChanged();
        }
    }
}
