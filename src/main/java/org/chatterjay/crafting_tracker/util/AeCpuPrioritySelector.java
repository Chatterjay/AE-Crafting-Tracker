package org.chatterjay.crafting_tracker.util;

import appeng.api.config.CpuSelectionMode;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.KeyCounter;
import appeng.hooks.ticking.TickHandler;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import org.chatterjay.crafting_tracker.config.CTConfig;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public final class AeCpuPrioritySelector {
    @FunctionalInterface
    public interface PatternPusher {
        boolean push(ICraftingProvider provider, IPatternDetails patternDetails, KeyCounter[] inputHolder);
    }

    private static final String ADVANCED_AE_CPU_CLASS = "net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU";
    private static final String ADVANCED_AE_PENDING_KEY_PREFIX = "advanced_ae:pending:";
    private static final String NEO_ECO_CPU_CLASS = "cn.dancingsnow.neoecoae.api.me.ECOCraftingCPU";
    private static final long PROVIDER_RESERVATION_TICKS = 100;
    private static final long PROVIDER_DEFER_LOG_TICKS = 40;
    private static final long CPU_DEFER_LOG_TICKS = 40;
    private static final long MIN_PRIORITY_HOLD_TICKS = 100;
    private static final long STALE_ADVANCED_PRIORITY_GRACE_TICKS = 40;
    private static final Map<ICraftingCPU, Long> RUNTIME_PRIORITIES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<String, Long> RUNTIME_PRIORITIES_BY_KEY = Collections.synchronizedMap(new java.util.HashMap<>());
    private static final Map<String, Long> RUNTIME_PRIORITY_LAST_SEEN_TICKS =
            Collections.synchronizedMap(new java.util.HashMap<>());
    private static final Set<String> RUNTIME_PRIORITY_BUSY_KEYS = Collections.synchronizedSet(new java.util.HashSet<>());
    private static final Map<ICraftingProvider, ProviderReservation> PROVIDER_RESERVATIONS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ICraftingProvider, Long> PROVIDER_DEFER_LOG_TICKS_BY_PROVIDER =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ICraftingCPU, SuspendedCpuHold> CRAFTING_TRACKER_SUSPENDED_CPUS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ICraftingCPU, Long> CPU_DEFER_LOG_TICKS_BY_CPU =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<ICraftingCPU> ACTIVE_DISPATCH_CPU = new ThreadLocal<>();

    private AeCpuPrioritySelector() {}

    public static boolean isEnabled() {
        return CTConfig.ENABLE_AE_CPU_PRIORITY.get();
    }

    @Nullable
    public static ICraftingCPU select(@Nullable IGrid grid, ICraftingPlan plan, IActionSource source) {
        if (grid == null || !isEnabled()) {
            return null;
        }

        var priorityNames = CTConfig.AE_CPU_PRIORITY_NAMES.get();
        if (priorityNames == null || priorityNames.isEmpty()) {
            return null;
        }

        long requiredBytes = plan.bytes();
        for (String configuredName : priorityNames) {
            String wantedName = normalize(configuredName);
            if (wantedName.isEmpty()) {
                continue;
            }

            for (ICraftingCPU cpu : grid.getCraftingService().getCpus()) {
                if (!isUsable(cpu, requiredBytes, source)) {
                    continue;
                }

                if (matches(cpu, wantedName)) {
                    ModLogger.debug("Selected AE crafting CPU '{}' by Crafting Tracker priority for {} bytes", configuredName, requiredBytes);
                    return cpu;
                }
            }
        }

        ModLogger.debug("No configured AE crafting CPU priority matched for {} bytes", requiredBytes);
        return null;
    }

    public static Iterator<CraftingCPUCluster> orderedTickIterator(Collection<CraftingCPUCluster> cpus) {
        boolean configPriorityEnabled = isEnabled();
        var priorityNames = CTConfig.AE_CPU_PRIORITY_NAMES.get();
        boolean hasConfiguredPriorities = configPriorityEnabled && priorityNames != null && !priorityNames.isEmpty();
        boolean hasRuntimePriorities = hasRuntimePriorities();
        reconcileCpus(cpus);
        if (!hasConfiguredPriorities && !hasRuntimePriorities) {
            return cpus.iterator();
        }

        var ordered = new ArrayList<>(cpus);
        ordered.sort(Comparator
                .comparingLong(AeCpuPrioritySelector::runtimePriorityIndex)
                .thenComparingInt(cpu -> hasConfiguredPriorities ? priorityIndex(cpu) : Integer.MAX_VALUE));
        return ordered.iterator();
    }

    public static boolean isProviderBusyForCpu(ICraftingCPU currentCpu, ICraftingProvider provider) {
        boolean busy = provider.isBusy();
        if (busy) {
            reserveBusyProvider(currentCpu, provider);
            return true;
        }

        return shouldDeferProviderDispatch(currentCpu, provider);
    }

    public static boolean pushPatternWithPriority(
            ICraftingCPU currentCpu,
            ICraftingProvider provider,
            IPatternDetails patternDetails,
            KeyCounter[] inputHolder
    ) {
        return pushPatternWithPriority(currentCpu, provider, patternDetails, inputHolder,
                ICraftingProvider::pushPattern);
    }

    /**
     * Applies CPU priority checks and delegates the actual push to the caller. Using a delegate
     * keeps this hook composable with other mods that wrap AE2's pushPattern invocation.
     */
    public static boolean pushPatternWithPriority(
            ICraftingCPU currentCpu,
            ICraftingProvider provider,
            IPatternDetails patternDetails,
            KeyCounter[] inputHolder,
            PatternPusher pusher
    ) {
        if (shouldDeferCpuDispatch(currentCpu)) {
            logDeferredCpuDispatch(currentCpu);
            return false;
        }

        return pusher.push(provider, patternDetails, inputHolder);
    }

    public static boolean shouldDeferCpuTick(ICraftingCPU currentCpu) {
        if (shouldDeferCpuDispatch(currentCpu)) {
            logDeferredCpuDispatch(currentCpu);
            return true;
        }
        return false;
    }

    /** Binds the CPU whose NeoECO dispatch callback is currently executing. */
    public static void enterDispatchContext(@Nullable ICraftingCPU cpu) {
        if (cpu == null) {
            ACTIVE_DISPATCH_CPU.remove();
        } else {
            ACTIVE_DISPATCH_CPU.set(cpu);
        }
    }

    public static void exitDispatchContext() {
        ACTIVE_DISPATCH_CPU.remove();
    }

    @Nullable
    public static ICraftingCPU currentDispatchCpu() {
        return ACTIVE_DISPATCH_CPU.get();
    }

    @Nullable
    public static ICraftingCPU cpuFromLogic(Object logic) {
        Object cpu = readFieldValue(logic, "cpu");
        return cpu instanceof ICraftingCPU craftingCpu ? craftingCpu : null;
    }

    public static long promoteRuntime(ICraftingCPU cpu) {
        if (cpu == null || !isEnabled()) {
            return 0;
        }

        String key = stableCpuKey(cpu);
        Long currentPriority = runtimePriority(cpu);
        long priority = currentPriority == null ? 1 : currentPriority + 1;
        RUNTIME_PRIORITIES.put(cpu, priority);
        RUNTIME_PRIORITIES_BY_KEY.put(key, priority);
        touchRuntimePriorityKey(key);
        if (cpu.isBusy()) {
            markRuntimePriorityBusy(cpu);
        }
        ModLogger.debug("Promoted AE crafting CPU '{}' [{}] to runtime priority {}",
                displayName(cpu), key, priority);
        return priority;
    }

    public static long promoteRuntime(ICraftingCPU cpu, Collection<? extends ICraftingCPU> cpus) {
        long priority = promoteRuntime(cpu);
        if (cpu != null && cpu.isBusy()) {
            suspendOtherBusyCpus(cpu, cpus);
        }
        return priority;
    }

    public static long lowerRuntime(ICraftingCPU cpu, Collection<? extends ICraftingCPU> cpus) {
        if (cpu == null) {
            return 0;
        }

        String key = stableCpuKey(cpu);
        Long currentPriority = runtimePriority(cpu);
        if (currentPriority == null || currentPriority <= 1) {
            clearRuntimeKey(key);
            ModLogger.debug("Cleared AE crafting CPU runtime priority for '{}' [{}]",
                    displayName(cpu), key);
            return 0;
        }

        long priority = currentPriority - 1;
        RUNTIME_PRIORITIES.put(cpu, priority);
        RUNTIME_PRIORITIES_BY_KEY.put(key, priority);
        touchRuntimePriorityKey(key);
        reconcileCpus(cpus);
        ModLogger.debug("Lowered AE crafting CPU '{}' [{}] runtime priority to {}",
                displayName(cpu), key, priority);
        return priority;
    }

    public static long runtimePriorityForDisplay(ICraftingCPU cpu) {
        Long priority = runtimePriority(cpu);
        return priority == null ? 0 : priority;
    }

    public static void clearRuntimeAfterJobFinished(@Nullable ICraftingCPU cpu) {
        if (cpu == null || runtimePriority(cpu) == null) {
            return;
        }

        String key = stableCpuKey(cpu);
        clearRuntimeKey(key);
        reconcileSuspendedCpus(peerCpus(cpu));
        ModLogger.debug("Cleared AE crafting CPU '{}' [{}] runtime priority because its job finished",
                displayName(cpu), key);
    }

    public static void reconcileCpus(Collection<? extends ICraftingCPU> cpus) {
        reconcileRuntimePriorityLifetime(cpus);
        pruneStaleAdvancedRuntimePriorities(cpus);
        reconcileSuspendedCpus(cpus);
    }

    private static void reconcileRuntimePriorityLifetime(Collection<? extends ICraftingCPU> cpus) {
        if (!hasRuntimePriorities()) {
            return;
        }

        java.util.ArrayList<String> completedKeys = new java.util.ArrayList<>();
        for (ICraftingCPU cpu : cpus) {
            Long priority = runtimePriority(cpu);
            if (priority == null) {
                continue;
            }
            String key = stableCpuKey(cpu);
            if (cpu.isBusy()) {
                markRuntimePriorityBusy(cpu);
            } else if (RUNTIME_PRIORITY_BUSY_KEYS.contains(key)) {
                completedKeys.add(key);
            }
        }

        for (String key : completedKeys) {
            clearRuntimeKey(key);
            ModLogger.debug("Cleared AE crafting CPU runtime priority after job completed [{}]", key);
        }
    }

    private static void clearRuntimeKey(String key) {
        RUNTIME_PRIORITIES_BY_KEY.remove(key);
        RUNTIME_PRIORITY_LAST_SEEN_TICKS.remove(key);
        RUNTIME_PRIORITY_BUSY_KEYS.remove(key);
        synchronized (RUNTIME_PRIORITIES) {
            RUNTIME_PRIORITIES.entrySet().removeIf(entry -> stableCpuKey(entry.getKey()).equals(key));
        }
        synchronized (PROVIDER_RESERVATIONS) {
            PROVIDER_RESERVATIONS.entrySet().removeIf(entry -> stableCpuKey(entry.getValue().cpu()).equals(key));
        }
        synchronized (CRAFTING_TRACKER_SUSPENDED_CPUS) {
            var iterator = CRAFTING_TRACKER_SUSPENDED_CPUS.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.getValue().promotedCpuKey().equals(key)) {
                    ICraftingCPU suspendedCpu = entry.getKey();
                    if (isJobSuspended(suspendedCpu)) {
                        setJobSuspended(suspendedCpu, false);
                        ModLogger.debug("Resumed AE crafting CPU '{}' after clearing runtime priority",
                                displayName(suspendedCpu));
                    }
                    iterator.remove();
                }
            }
        }
    }

    private static void suspendOtherBusyCpus(ICraftingCPU promotedCpu, Collection<? extends ICraftingCPU> cpus) {
        if (!isEnabled()) {
            return;
        }

        Long promotedPriority = RUNTIME_PRIORITIES.get(promotedCpu);
        if (promotedPriority == null) {
            return;
        }

        if (isJobSuspended(promotedCpu)) {
            setJobSuspended(promotedCpu, false);
            CRAFTING_TRACKER_SUSPENDED_CPUS.remove(promotedCpu);
            ModLogger.debug("Resumed promoted AE crafting CPU '{}' before applying priority hold",
                    displayName(promotedCpu));
        }

        int seen = 0;
        int busy = 0;
        int suspended = 0;
        int alreadySuspended = 0;
        int unsupported = 0;
        for (ICraftingCPU cpu : cpus) {
            seen++;
            if (cpu == promotedCpu || !cpu.isBusy()) {
                continue;
            }
            busy++;

            if (!canSetJobSuspended(cpu)) {
                unsupported++;
                ModLogger.debug("Cannot suspend AE crafting CPU '{}' because implementation {} has no compatible craftingLogic suspend API",
                        displayName(cpu), cpu.getClass().getName());
                continue;
            }

            if (isJobSuspended(cpu)) {
                alreadySuspended++;
                continue;
            }

            boolean verified = setJobSuspended(cpu, true) && isJobSuspended(cpu);
            if (verified) {
                CRAFTING_TRACKER_SUSPENDED_CPUS.put(cpu, new SuspendedCpuHold(
                        promotedPriority,
                        stableCpuKey(promotedCpu),
                        TickHandler.instance().getCurrentTick()));
                suspended++;
            } else {
                unsupported++;
            }
            ModLogger.debug("Suspended AE crafting CPU '{}' for promoted CPU '{}' verified={}",
                    displayName(cpu), displayName(promotedCpu), verified);
        }

        ModLogger.debug("AE CPU priority suspend pass: promoted='{}', seen={}, busyOthers={}, suspended={}, alreadySuspended={}, unsupported={}",
                displayName(promotedCpu), seen, busy, suspended, alreadySuspended, unsupported);
    }

    private static void reconcileSuspendedCpus(Collection<? extends ICraftingCPU> cpus) {
        if (CRAFTING_TRACKER_SUSPENDED_CPUS.isEmpty()) {
            return;
        }

        long highestBusyPriority = highestBusyRuntimePriority(cpus);
        synchronized (CRAFTING_TRACKER_SUSPENDED_CPUS) {
            var iterator = CRAFTING_TRACKER_SUSPENDED_CPUS.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                ICraftingCPU cpu = entry.getKey();
                SuspendedCpuHold hold = entry.getValue();
                Long cpuPriority = runtimePriority(cpu);
                boolean minHoldElapsed = TickHandler.instance().getCurrentTick() - hold.createdTick() >= MIN_PRIORITY_HOLD_TICKS;
                boolean promotedStillBusy = isRuntimePriorityBusy(cpus, hold.promotedCpuKey(), hold.priority());
                boolean cpuPresentInSnapshot = containsStableCpu(cpus, cpu);
                boolean cpuBusy = cpu.isBusy();
                boolean shouldResume = !cpuBusy
                        || (minHoldElapsed && !promotedStillBusy)
                        || (minHoldElapsed && highestBusyPriority == Long.MIN_VALUE)
                        || (minHoldElapsed && cpuPriority != null && cpuPriority >= highestBusyPriority);
                if (shouldResume) {
                    ModLogger.debug("Priority hold resume check for '{}' presentInSnapshot={}, busy={}, "
                                    + "minHoldElapsed={}, promotedStillBusy={}, highestBusyPriority={}, cpuPriority={}, "
                                    + "reason={}",
                            displayName(cpu), cpuPresentInSnapshot, cpuBusy, minHoldElapsed, promotedStillBusy,
                            highestBusyPriority, cpuPriority, resumeReason(cpuBusy, minHoldElapsed, promotedStillBusy,
                                    highestBusyPriority, cpuPriority));
                }
                if (shouldResume) {
                    if (isJobSuspended(cpu)) {
                        setJobSuspended(cpu, false);
                        ModLogger.debug("Resumed AE crafting CPU '{}' after Crafting Tracker priority hold", displayName(cpu));
                    }
                    iterator.remove();
                }
            }
        }
    }

    private static boolean canSetJobSuspended(ICraftingCPU cpu) {
        if (cpu instanceof CraftingCPUCluster) {
            return true;
        }
        if (cpu instanceof DirectCpuSuspendAccess) {
            return true;
        }
        if (findMethod(cpu.getClass(), "setJobSuspended", boolean.class) != null
                && findMethod(cpu.getClass(), "isJobSuspended") != null) {
            return true;
        }
        Object logic = getCraftingLogic(cpu);
        return logic != null && findMethod(logic.getClass(), "setJobSuspended", boolean.class) != null
                && findMethod(logic.getClass(), "isJobSuspended") != null;
    }

    private static boolean isJobSuspended(ICraftingCPU cpu) {
        if (cpu instanceof CraftingCPUCluster cluster) {
            return cluster.craftingLogic.isJobSuspended();
        }
        if (cpu instanceof DirectCpuSuspendAccess access) {
            return access.craftingtracker$isJobSuspended();
        }
        try {
            var method = findMethod(cpu.getClass(), "isJobSuspended");
            if (method != null) {
                return (boolean) method.invoke(cpu);
            }
        } catch (ReflectiveOperationException | ClassCastException exception) {
            ModLogger.debug("Failed to read AE crafting CPU suspend state from {}", cpu.getClass().getName());
            return false;
        }
        Object logic = getCraftingLogic(cpu);
        if (logic == null) {
            return false;
        }

        try {
            var method = findMethod(logic.getClass(), "isJobSuspended");
            return method != null && (boolean) method.invoke(logic);
        } catch (ReflectiveOperationException | ClassCastException exception) {
            ModLogger.debug("Failed to read AE crafting CPU suspend state from {}", cpu.getClass().getName());
            return false;
        }
    }

    private static boolean setJobSuspended(ICraftingCPU cpu, boolean suspended) {
        if (cpu instanceof CraftingCPUCluster cluster) {
            cluster.craftingLogic.setJobSuspended(suspended);
            return true;
        }
        if (cpu instanceof DirectCpuSuspendAccess access) {
            access.craftingtracker$setJobSuspended(suspended);
            return true;
        }
        try {
            var method = findMethod(cpu.getClass(), "setJobSuspended", boolean.class);
            if (method != null) {
                method.invoke(cpu, suspended);
                return true;
            }
        } catch (ReflectiveOperationException exception) {
            ModLogger.debug("Failed to set AE crafting CPU suspend state on {} directly: {}",
                    cpu.getClass().getName(), exception.toString());
            return false;
        }
        Object logic = getCraftingLogic(cpu);
        if (logic == null) {
            ModLogger.debug("Failed to set AE crafting CPU suspend state on {}: missing craftingLogic field",
                    cpu.getClass().getName());
            return false;
        }

        try {
            var method = findMethod(logic.getClass(), "setJobSuspended", boolean.class);
            if (method != null) {
                method.invoke(logic, suspended);
                return true;
            }
        } catch (ReflectiveOperationException exception) {
            ModLogger.debug("Failed to set AE crafting CPU suspend state on {} via {}: {}",
                    cpu.getClass().getName(), logic.getClass().getName(), exception.toString());
        }
        return false;
    }

    @Nullable
    private static Object getCraftingLogic(ICraftingCPU cpu) {
        for (String fieldName : new String[]{"craftingLogic", "logic"}) {
            try {
                var field = findField(cpu.getClass(), fieldName);
                if (field != null) {
                    Object logic = field.get(cpu);
                    if (logic != null) {
                        return logic;
                    }
                }
            } catch (ReflectiveOperationException exception) {
                // Try the alternate field name used by optional CPU implementations.
            }
        }
        return null;
    }

    // --- Cached reflection ---------------------------------------------------------
    // Same reasoning as CraftTracker: resolving members on every call rebuilds Field/Method
    // objects, repeats the access check and throws stack-trace-carrying exceptions on misses.

    /** Marks a member that does not exist; ConcurrentHashMap cannot store null values. */
    private static final Object ABSENT_MEMBER = new Object();
    private static final Map<Class<?>, Map<String, Object>> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Map<String, Object>> METHOD_CACHE = new ConcurrentHashMap<>();

    @Nullable
    private static java.lang.reflect.Field findField(Class<?> type, String name) {
        Object cached = FIELD_CACHE
                .computeIfAbsent(type, c -> new ConcurrentHashMap<>())
                .computeIfAbsent(name, n -> {
                    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                        try {
                            var field = current.getDeclaredField(n);
                            field.setAccessible(true);
                            return field;
                        } catch (NoSuchFieldException ignored) {
                            // Not declared here, keep walking up the hierarchy.
                        } catch (RuntimeException inaccessible) {
                            // InaccessibleObjectException / SecurityException: skip this level.
                        }
                    }
                    return ABSENT_MEMBER;
                });
        return cached == ABSENT_MEMBER ? null : (java.lang.reflect.Field) cached;
    }

    @Nullable
    private static java.lang.reflect.Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        StringBuilder key = new StringBuilder(name).append('(');
        for (Class<?> parameterType : parameterTypes) {
            key.append(parameterType.getName()).append(';');
        }
        String cacheKey = key.append(')').toString();
        Object cached = METHOD_CACHE
                .computeIfAbsent(type, c -> new ConcurrentHashMap<>())
                .computeIfAbsent(cacheKey, k -> {
                    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                        try {
                            var method = current.getDeclaredMethod(name, parameterTypes);
                            method.setAccessible(true);
                            return method;
                        } catch (NoSuchMethodException ignored) {
                            // Not declared here, keep walking up the hierarchy.
                        } catch (RuntimeException inaccessible) {
                            // InaccessibleObjectException / SecurityException: skip this level.
                        }
                    }
                    return ABSENT_MEMBER;
                });
        return cached == ABSENT_MEMBER ? null : (java.lang.reflect.Method) cached;
    }

    private static long highestBusyRuntimePriority(Collection<? extends ICraftingCPU> cpus) {
        long highestBusyPriority = Long.MIN_VALUE;
        synchronized (RUNTIME_PRIORITIES) {
            for (ICraftingCPU cpu : cpus) {
                Long priority = runtimePriority(cpu);
                if (priority != null && cpu.isBusy() && priority > highestBusyPriority) {
                    markRuntimePriorityBusy(cpu);
                    highestBusyPriority = priority;
                }
            }
            for (var entry : RUNTIME_PRIORITIES.entrySet()) {
                ICraftingCPU cpu = entry.getKey();
                Long priority = entry.getValue();
                if (cpu != null && priority != null && cpu.isBusy() && priority > highestBusyPriority) {
                    markRuntimePriorityBusy(cpu);
                    highestBusyPriority = priority;
                }
            }
        }
        return highestBusyPriority;
    }

    private static boolean isRuntimePriorityBusy(
            Collection<? extends ICraftingCPU> cpus,
            String promotedCpuKey,
            long priority
    ) {
        for (ICraftingCPU cpu : cpus) {
            Long cpuPriority = runtimePriority(cpu);
            if (cpu.isBusy()
                    && (stableCpuKey(cpu).equals(promotedCpuKey)
                    || (cpuPriority != null && cpuPriority >= priority))) {
                markRuntimePriorityBusy(cpu);
                return true;
            }
        }
        synchronized (RUNTIME_PRIORITIES) {
            for (var entry : RUNTIME_PRIORITIES.entrySet()) {
                ICraftingCPU cpu = entry.getKey();
                Long cpuPriority = entry.getValue();
                if (cpu != null && cpu.isBusy()
                        && (stableCpuKey(cpu).equals(promotedCpuKey)
                        || (cpuPriority != null && cpuPriority >= priority))) {
                    markRuntimePriorityBusy(cpu);
                    return true;
                }
            }
        }
        return false;
    }

    private static String resumeReason(
            boolean cpuBusy,
            boolean minHoldElapsed,
            boolean promotedStillBusy,
            long highestBusyPriority,
            Long cpuPriority
    ) {
        if (!cpuBusy) {
            return "suspended CPU no longer owns a job";
        }
        if (minHoldElapsed && !promotedStillBusy) {
            return "promoted CPU no longer busy";
        }
        if (minHoldElapsed && highestBusyPriority == Long.MIN_VALUE) {
            return "no busy runtime-priority CPU remains";
        }
        if (minHoldElapsed && cpuPriority != null && cpuPriority >= highestBusyPriority) {
            return "suspended CPU priority is no longer lower";
        }
        return "priority hold ended";
    }

    private static boolean shouldDeferCpuDispatch(ICraftingCPU currentCpu) {
        if (currentCpu == null || !isEnabled() || !hasRuntimePriorities()) {
            return false;
        }

        Collection<? extends ICraftingCPU> cpus = peerCpus(currentCpu);
        if (cpus.isEmpty()) {
            return false;
        }

        reconcileCpus(cpus);
        long highestBusyPriority = highestBusyRuntimePriority(cpus);
        if (highestBusyPriority == Long.MIN_VALUE) {
            return false;
        }

        Long currentPriority = runtimePriority(currentCpu);
        return currentPriority == null || currentPriority < highestBusyPriority;
    }

    private static boolean containsStableCpu(Collection<? extends ICraftingCPU> cpus, ICraftingCPU wantedCpu) {
        if (cpus.contains(wantedCpu)) {
            return true;
        }

        String wantedKey = stableCpuKey(wantedCpu);
        for (ICraftingCPU cpu : cpus) {
            if (stableCpuKey(cpu).equals(wantedKey)) {
                return true;
            }
        }
        return false;
    }

    private static void logDeferredCpuDispatch(ICraftingCPU currentCpu) {
        long now = TickHandler.instance().getCurrentTick();
        long lastLogTick = CPU_DEFER_LOG_TICKS_BY_CPU.getOrDefault(currentCpu, Long.MIN_VALUE);
        if (now - lastLogTick >= CPU_DEFER_LOG_TICKS) {
            CPU_DEFER_LOG_TICKS_BY_CPU.put(currentCpu, now);
            ModLogger.debug("Deferred AE pattern push for lower priority CPU '{}'", displayName(currentCpu));
        }
    }

    private static void reserveBusyProvider(ICraftingCPU currentCpu, ICraftingProvider provider) {
        Long priority = runtimePriority(currentCpu);
        if (priority == null) {
            return;
        }
        if (currentCpu.isBusy()) {
            markRuntimePriorityBusy(currentCpu);
        }

        long expiresAt = TickHandler.instance().getCurrentTick() + PROVIDER_RESERVATION_TICKS;
        synchronized (PROVIDER_RESERVATIONS) {
            ProviderReservation existing = PROVIDER_RESERVATIONS.get(provider);
            if (existing == null || existing.isExpired() || priority >= existing.priority()) {
                PROVIDER_RESERVATIONS.put(provider, new ProviderReservation(currentCpu, priority, expiresAt));
                if (existing == null || existing.cpu() != currentCpu || existing.priority() != priority) {
                    ModLogger.debug("Reserved busy AE crafting provider for CPU '{}' priority {}",
                            displayName(currentCpu), priority);
                }
            }
        }
    }

    private static boolean shouldDeferProviderDispatch(ICraftingCPU currentCpu, ICraftingProvider provider) {
        synchronized (PROVIDER_RESERVATIONS) {
            ProviderReservation reservation = PROVIDER_RESERVATIONS.get(provider);
            if (reservation == null) {
                return false;
            }

            if (reservation.isExpired() || !reservation.cpu().isBusy()) {
                PROVIDER_RESERVATIONS.remove(provider);
                return false;
            }

            Long currentPriority = runtimePriority(currentCpu);
            boolean defer = reservation.cpu() != currentCpu
                    && (currentPriority == null || currentPriority < reservation.priority());
            if (defer) {
                long now = TickHandler.instance().getCurrentTick();
                long lastLogTick = PROVIDER_DEFER_LOG_TICKS_BY_PROVIDER.getOrDefault(provider, Long.MIN_VALUE);
                if (now - lastLogTick >= PROVIDER_DEFER_LOG_TICKS) {
                    PROVIDER_DEFER_LOG_TICKS_BY_PROVIDER.put(provider, now);
                    ModLogger.debug("Deferred AE provider dispatch for CPU '{}' because '{}' has priority {}",
                            displayName(currentCpu), displayName(reservation.cpu()), reservation.priority());
                }
            }
            return defer;
        }
    }

    private static boolean isUsable(ICraftingCPU cpu, long requiredBytes, IActionSource source) {
        if (cpu == null || cpu.isBusy() || cpu.getAvailableStorage() < requiredBytes) {
            return false;
        }

        CpuSelectionMode mode = cpu.getSelectionMode();
        return switch (mode) {
            case ANY -> true;
            case PLAYER_ONLY -> source.player().isPresent();
            case MACHINE_ONLY -> source.player().isEmpty();
        };
    }

    private static int priorityIndex(ICraftingCPU cpu) {
        var priorityNames = CTConfig.AE_CPU_PRIORITY_NAMES.get();
        if (priorityNames == null || priorityNames.isEmpty()) {
            return Integer.MAX_VALUE;
        }

        for (int index = 0; index < priorityNames.size(); index++) {
            String wantedName = normalize(priorityNames.get(index));
            if (!wantedName.isEmpty() && matches(cpu, wantedName)) {
                return index;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static long runtimePriorityIndex(ICraftingCPU cpu) {
        Long priority = runtimePriority(cpu);
        return priority == null ? Long.MAX_VALUE : -priority;
    }

    @Nullable
    private static Long runtimePriority(ICraftingCPU cpu) {
        Long priority = RUNTIME_PRIORITIES.get(cpu);
        if (priority != null) {
            touchRuntimePriorityKey(stableCpuKey(cpu));
            return priority;
        }
        String stableKey = stableCpuKey(cpu);
        priority = RUNTIME_PRIORITIES_BY_KEY.get(stableKey);
        if (priority != null) {
            RUNTIME_PRIORITIES.put(cpu, priority);
            touchRuntimePriorityKey(stableKey);
            if (cpu.isBusy()) {
                markRuntimePriorityBusy(cpu);
            }
            return priority;
        }

        String pendingAdvancedKey = advancedPendingClusterKey(cpu);
        if (pendingAdvancedKey != null && isAdvancedActiveCpu(cpu)) {
            priority = RUNTIME_PRIORITIES_BY_KEY.remove(pendingAdvancedKey);
            if (priority != null) {
                RUNTIME_PRIORITIES_BY_KEY.put(stableKey, priority);
                RUNTIME_PRIORITY_LAST_SEEN_TICKS.remove(pendingAdvancedKey);
                touchRuntimePriorityKey(stableKey);
                RUNTIME_PRIORITIES.put(cpu, priority);
                if (cpu.isBusy()) {
                    markRuntimePriorityBusy(cpu);
                }
                ModLogger.debug("Transferred AdvancedAE quantum CPU pending runtime priority {} to '{}' [{}]",
                        priority, displayName(cpu), stableKey);
            }
        }
        return priority;
    }

    private static boolean hasRuntimePriorities() {
        return !RUNTIME_PRIORITIES.isEmpty() || !RUNTIME_PRIORITIES_BY_KEY.isEmpty();
    }

    private static void markRuntimePriorityBusy(ICraftingCPU cpu) {
        String key = stableCpuKey(cpu);
        touchRuntimePriorityKey(key);
        RUNTIME_PRIORITY_BUSY_KEYS.add(key);
    }

    private static void touchRuntimePriorityKey(String key) {
        RUNTIME_PRIORITY_LAST_SEEN_TICKS.put(key, TickHandler.instance().getCurrentTick());
    }

    private static boolean matches(ICraftingCPU cpu, String wantedName) {
        String cpuName = cpu.getName() == null ? "" : normalize(cpu.getName().getString());
        if (wantedName.endsWith("*")) {
            String prefix = wantedName.substring(0, wantedName.length() - 1);
            return !prefix.isEmpty() && cpuName.startsWith(prefix);
        }
        return cpuName.equals(wantedName);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String displayName(ICraftingCPU cpu) {
        if (cpu.getName() != null) {
            return cpu.getName().getString();
        }
        if (cpu.getClass().getName().equals(NEO_ECO_CPU_CLASS)) {
            return "ECO CPU [" + stableCpuKey(cpu) + "]";
        }
        return "<unnamed>";
    }

    private static String stableCpuKey(ICraftingCPU cpu) {
        String dispatchIdentity = invokeStringMethod(cpu, "getStableDispatchIdentity");
        if (dispatchIdentity != null && !dispatchIdentity.isBlank()) {
            return "dispatch:" + dispatchIdentity;
        }
        if (cpu.getClass().getName().equals(ADVANCED_AE_CPU_CLASS)) {
            Object uniqueId = readFieldValue(cpu, "uniqueId");
            if (uniqueId != null) {
                return cpu.getClass().getName() + "#" + uniqueId;
            }
            String pendingKey = advancedPendingClusterKey(cpu);
            if (pendingKey != null) {
                return pendingKey;
            }
        }
        if (cpu.getClass().getName().equals(NEO_ECO_CPU_CLASS)) {
            String ecoKey = neoEcoCpuKey(cpu);
            if (ecoKey != null) {
                return ecoKey;
            }
        }
        Object uniqueId = readFieldValue(cpu, "uniqueId");
        if (uniqueId != null) {
            return cpu.getClass().getName() + "#" + uniqueId;
        }
        return cpu.getClass().getName() + "@" + System.identityHashCode(cpu);
    }

    @Nullable
    private static String neoEcoCpuKey(ICraftingCPU cpu) {
        Object owner = readFieldValue(cpu, "owner");
        if (owner == null) {
            return null;
        }

        int slot = -1;
        Object cpuArray = readFieldValue(owner, "cpus");
        if (cpuArray instanceof Object[] cpus) {
            for (int index = 0; index < cpus.length; index++) {
                if (cpus[index] == cpu) {
                    slot = index;
                    break;
                }
            }
        }

        return "neoecoae:threading-core@" + System.identityHashCode(owner) + "#" + slot;
    }

    @Nullable
    private static String invokeStringMethod(Object object, String methodName) {
        try {
            var method = findMethod(object.getClass(), methodName);
            Object value = method == null ? null : method.invoke(object);
            return value instanceof String string ? string : null;
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    @Nullable
    private static String advancedPendingClusterKey(ICraftingCPU cpu) {
        if (!cpu.getClass().getName().equals(ADVANCED_AE_CPU_CLASS)) {
            return null;
        }
        Object advancedCluster = readFieldValue(cpu, "cluster");
        if (advancedCluster == null) {
            return null;
        }
        return ADVANCED_AE_PENDING_KEY_PREFIX + advancedCluster.getClass().getName()
                + "@" + System.identityHashCode(advancedCluster);
    }

    private static boolean isAdvancedActiveCpu(ICraftingCPU cpu) {
        return cpu.getClass().getName().equals(ADVANCED_AE_CPU_CLASS)
                && readFieldValue(cpu, "uniqueId") != null;
    }

    private static void pruneStaleAdvancedRuntimePriorities(Collection<? extends ICraftingCPU> cpus) {
        if (RUNTIME_PRIORITIES_BY_KEY.isEmpty()) {
            return;
        }

        java.util.HashSet<String> activeAdvancedKeys = new java.util.HashSet<>();
        for (ICraftingCPU cpu : cpus) {
            if (cpu != null && cpu.getClass().getName().equals(ADVANCED_AE_CPU_CLASS)) {
                String key = stableCpuKey(cpu);
                if (!key.startsWith(ADVANCED_AE_PENDING_KEY_PREFIX)) {
                    activeAdvancedKeys.add(key);
                }
            }
        }

        java.util.ArrayList<String> staleKeys = new java.util.ArrayList<>();
        long now = TickHandler.instance().getCurrentTick();
        synchronized (RUNTIME_PRIORITIES_BY_KEY) {
            for (String key : RUNTIME_PRIORITIES_BY_KEY.keySet()) {
                long lastSeen = RUNTIME_PRIORITY_LAST_SEEN_TICKS.getOrDefault(key, Long.MIN_VALUE);
                boolean graceElapsed = now - lastSeen >= STALE_ADVANCED_PRIORITY_GRACE_TICKS;
                if (key.startsWith(ADVANCED_AE_CPU_CLASS + "#") && !activeAdvancedKeys.contains(key) && graceElapsed) {
                    staleKeys.add(key);
                }
            }
        }
        for (String key : staleKeys) {
            clearRuntimeKey(key);
            ModLogger.debug("Cleared stale AdvancedAE quantum CPU runtime priority [{}]", key);
        }
    }

    @Nullable
    private static IGrid getGrid(ICraftingCPU cpu) {
        if (cpu instanceof CraftingCPUCluster cluster) {
            return cluster.getGrid();
        }
        try {
            var method = findMethod(cpu.getClass(), "getGrid");
            if (method == null) {
                method = findMethod(cpu.getClass(), "grid");
            }
            Object grid = method == null ? null : method.invoke(cpu);
            return grid instanceof IGrid iGrid ? iGrid : null;
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private static Collection<? extends ICraftingCPU> peerCpus(ICraftingCPU cpu) {
        Collection<? extends ICraftingCPU> advancedCpus = advancedClusterCpus(cpu);
        if (advancedCpus != null) {
            return advancedCpus;
        }

        IGrid grid = getGrid(cpu);
        if (grid == null) {
            return java.util.List.of(cpu);
        }
        return grid.getCraftingService().getCpus();
    }

    @Nullable
    private static Collection<? extends ICraftingCPU> advancedClusterCpus(ICraftingCPU cpu) {
        if (!cpu.getClass().getName().equals(ADVANCED_AE_CPU_CLASS)) {
            return null;
        }

        Object cluster = readFieldValue(cpu, "cluster");
        if (cluster == null) {
            return null;
        }

        java.util.ArrayList<ICraftingCPU> cpus = new java.util.ArrayList<>();
        try {
            var activeMethod = findMethod(cluster.getClass(), "getActiveCPUs");
            Object active = activeMethod == null ? null : activeMethod.invoke(cluster);
            if (active instanceof Iterable<?> iterable) {
                for (Object entry : iterable) {
                    if (entry instanceof ICraftingCPU craftingCpu) {
                        cpus.add(craftingCpu);
                    }
                }
            }

            var remainingMethod = findMethod(cluster.getClass(), "getRemainingCapacityCPU");
            Object remaining = remainingMethod == null ? null : remainingMethod.invoke(cluster);
            if (remaining instanceof ICraftingCPU craftingCpu) {
                cpus.add(craftingCpu);
            }
        } catch (ReflectiveOperationException exception) {
            ModLogger.debug("Failed to inspect AdvancedAE quantum CPU peers from {}: {}",
                    cpu.getClass().getName(), exception.toString());
        }

        return cpus.isEmpty() ? java.util.List.of(cpu) : cpus;
    }

    @Nullable
    private static Object readFieldValue(Object object, String fieldName) {
        try {
            var field = findField(object.getClass(), fieldName);
            return field == null ? null : field.get(object);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private record ProviderReservation(ICraftingCPU cpu, long priority, long expiresAt) {
        private boolean isExpired() {
            return TickHandler.instance().getCurrentTick() > expiresAt;
        }
    }

    private record SuspendedCpuHold(long priority, String promotedCpuKey, long createdTick) {
    }
}

