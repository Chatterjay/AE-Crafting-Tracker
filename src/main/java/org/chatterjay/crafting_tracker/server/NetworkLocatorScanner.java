package org.chatterjay.crafting_tracker.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import net.neoforged.neoforge.capabilities.Capabilities;

import org.chatterjay.crafting_tracker.network.payloads.S2CLocatorHighlights.LocatorHit;
import org.chatterjay.crafting_tracker.config.CTConfig;
import org.chatterjay.crafting_tracker.util.ModLogger;
import appeng.api.crafting.IPatternDetails;
import appeng.helpers.InterfaceLogicHost;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.parts.AEBasePart;
import appeng.parts.automation.ExportBusPart;
import appeng.parts.automation.IOBusPart;
import appeng.parts.automation.ImportBusPart;
import appeng.parts.automation.StorageLevelEmitterPart;
import appeng.parts.storagebus.StorageBusPart;

import com.glodblock.github.extendedae.common.tileentities.matrix.TileAssemblerMatrixPattern;

import net.pedroksl.advanced_ae.common.logic.AdvPatternProviderLogicHost;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class NetworkLocatorScanner {

    private static final int TYPE_ITEM = 0;
    private static final int TYPE_CHEMICAL = 3;
    private static final String ECO_PATTERN_BUS_CLASS =
            "cn.dancingsnow.neoecoae.blocks.entity.crafting.ECOCraftingPatternBusBlockEntity";
    private static final String TRINITY_DATA_CORE_CLASS =
            "com.fish_dan_.data_energistics.blockentity.TrinityDataCoreBlockEntity";
    private static final String TRINITY_ACCESS_HATCH_CLASS =
            "com.fish_dan_.data_energistics.blockentity.TrinityAccessHatchBlockEntity";
    private static final String TRINITY_PATTERN_CORE_CLASS =
            "com.fish_dan_.data_energistics.blockentity.TrinityPatternCoreBlockEntity";
    /** Max distinct icon slots per position */
    private static final int MAX_HITS_PER_POS = 3;

    /**
     * Scans the AE network at the bound position for all BlockEntity-hosted machines
     * whose inventory or patterns match items in the filter container.
     */
    public static Map<BlockPos, List<LocatorHit>> scan(ServerLevel level, BlockPos boundPos,
                                                       Container filterContainer, Player player) {
        Map<BlockPos, List<LocatorHit>> results = new HashMap<>();

        // Gather non-empty filter items
        List<ItemStack> filters = new ArrayList<>();
        for (int i = 0; i < filterContainer.getContainerSize(); i++) {
            ItemStack stack = filterContainer.getItem(i);
            if (!stack.isEmpty()) filters.add(stack);
        }
        String filterSummary = filterSummary(filters);
        if (filters.isEmpty()) {
            ModLogger.debugThrottled("locator.scan.empty." + boundPos.asLong(), CTConfig.debugLogIntervalTicks,
                    "Locator scan skipped bound={} player={} reason=no_filters",
                    boundPos, player.getGameProfile().getName());
            return results;
        }

        ModLogger.debugThrottled("locator.scan.start." + boundPos.asLong(), CTConfig.debugLogIntervalTicks,
                "Locator scan start bound={} player={} filters={}",
                boundPos, player.getGameProfile().getName(), filterSummary);

        // Get grid from bound position
        BlockEntity boundBe = level.getBlockEntity(boundPos);
        if (!(boundBe instanceof IInWorldGridNodeHost host)) {
            ModLogger.debugThrottled("locator.scan.no_host." + boundPos.asLong(), CTConfig.debugLogIntervalTicks,
                    "Locator scan skipped bound={} reason=not_ae_host", boundPos);
            return results;
        }

        IGrid grid = getGrid(host);
        if (grid == null) {
            ModLogger.debugThrottled("locator.scan.no_grid." + boundPos.asLong(), CTConfig.debugLogIntervalTicks,
                    "Locator scan skipped bound={} reason=no_grid", boundPos);
            return results;
        }

        // Iterate ALL grid nodes and get owners (both BlockEntities and cable-attached parts)
        Set<BlockPos> visitedPos = new HashSet<>();
        Set<Object> visitedOwners = Collections.newSetFromMap(new IdentityHashMap<>());
        int nodeCount = 0;
        int blockEntityCount = 0;
        int partCount = 0;
        int matchedOwnerCount = 0;
        for (IGridNode node : grid.getNodes()) {
            nodeCount++;
            Object owner = node.getOwner();

            BlockPos pos = null;
            List<LocatorHit> foundItems = new ArrayList<>();
            Set<ResourceLocation> foundTypes = new HashSet<>();

            if (owner instanceof BlockEntity be) {
                blockEntityCount++;
                if (be.isRemoved()) continue;

                if (isTrinityDataCore(be)) {
                    scanTrinityDataCore(be, filters, results);
                    continue;
                }
                if (isTrinityAccessHatch(be)) {
                    scanTrinityAccessHatch(be, filters, results);
                    continue;
                }

                pos = be.getBlockPos();
                if (!visitedPos.add(pos)) continue;

                // 1. Check IItemHandler capability (covers ME Interfaces, chests, etc.)
                checkItemHandler(be, filters, foundItems, foundTypes);

                // 2. Check pattern provider patterns (only if we have room)
                if (foundTypes.size() < MAX_HITS_PER_POS) {
                    checkPatterns(owner, filters, foundItems, foundTypes);
                }
            } else if (owner instanceof StorageBusPart bus) {
                partCount++;
                pos = getPartPos(bus);
                if (pos == null || !visitedOwners.add(owner)) continue;
                checkStorageBus(level, bus, filters, foundItems, foundTypes);
            } else if (owner instanceof IOBusPart bus) {
                partCount++;
                pos = getPartPos(bus);
                if (pos == null || !visitedOwners.add(owner)) continue;
                checkIOBusConfig(bus, filters, foundItems, foundTypes);
            } else if (owner instanceof AEBasePart part) {
                partCount++;
                pos = getPartPos(part);
                if (pos == null || !visitedOwners.add(owner)) continue;
                checkPatterns(owner, filters, foundItems, foundTypes);
                if (foundTypes.size() < MAX_HITS_PER_POS && owner instanceof InterfaceLogicHost ifaceHost) {
                    checkInterfaceInventory(ifaceHost, filters, foundItems, foundTypes);
                }
                if (foundTypes.size() < MAX_HITS_PER_POS && owner instanceof StorageLevelEmitterPart emitter) {
                    checkLevelEmitterConfig(emitter, filters, foundItems, foundTypes);
                }
            }

            if (pos != null && !foundItems.isEmpty()) {
                results.put(pos.immutable(), foundItems);
                matchedOwnerCount++;
                ModLogger.debugThrottled("locator.hit." + boundPos.asLong() + "." + pos.asLong(), CTConfig.debugLogIntervalTicks,
                        "Locator hit bound={} pos={} owner={} hits={}",
                        boundPos, pos, owner == null ? "null" : owner.getClass().getName(), hitSummary(foundItems));
            }
        }

        ModLogger.debugThrottled("locator.scan.finish." + boundPos.asLong(), CTConfig.debugLogIntervalTicks,
                "Locator scan finish bound={} nodes={} blockEntities={} parts={} visitedBlocks={} visitedParts={} matchedOwners={} results={} filters={}",
                boundPos, nodeCount, blockEntityCount, partCount, visitedPos.size(), visitedOwners.size(),
                matchedOwnerCount, results.size(), filterSummary);
        return results;
    }

    @Nullable
    private static IGrid getGrid(IInWorldGridNodeHost host) {
        IGridNode node = host.getGridNode(null);
        if (node != null) return node.getGrid();
        for (Direction dir : Direction.values()) {
            node = host.getGridNode(dir);
            if (node != null && node.getGrid() != null) return node.getGrid();
        }
        return null;
    }

    private static void checkItemHandler(BlockEntity be, List<ItemStack> filters, List<LocatorHit> foundItems, Set<ResourceLocation> foundTypes) {
        if (be.getLevel() == null) return;
        var cap = be.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, be.getBlockPos(), null);
        if (cap == null) return;

        for (int i = 0; i < cap.getSlots() && foundTypes.size() < MAX_HITS_PER_POS; i++) {
            ItemStack slotStack = cap.getStackInSlot(i);
            if (slotStack.isEmpty()) continue;
            for (ItemStack filter : filters) {
                if (filter.getItem() == slotStack.getItem()) {
                    tryAddHit(foundItems, foundTypes, filter);
                    break;
                }
            }
        }
    }

    private static void checkPatterns(Object owner, List<ItemStack> filters, List<LocatorHit> foundItems, Set<ResourceLocation> foundTypes) {
        List<IPatternDetails> patterns = getPatterns(owner);
        checkPatternList(patterns, filters, foundItems, foundTypes);
    }

    private static void checkPatternList(List<IPatternDetails> patterns, List<ItemStack> filters,
                                         List<LocatorHit> foundItems, Set<ResourceLocation> foundTypes) {
        if (patterns.isEmpty()) return;

        for (IPatternDetails pattern : patterns) {
            if (foundTypes.size() >= MAX_HITS_PER_POS) return;
            GenericStack output = pattern.getPrimaryOutput();
            if (output == null) continue;
            AEKey key = output.what();

            for (ItemStack filter : filters) {
                if (keyMatchesFilter(key, filter)) {
                    tryAddHit(foundItems, foundTypes, filter);
                    break;
                }
            }
        }
    }

    private static List<IPatternDetails> getPatterns(Object owner) {
        if (owner instanceof PatternProviderLogicHost host) return host.getLogic().getAvailablePatterns();
        if (owner instanceof TileAssemblerMatrixPattern matrix) return matrix.getAvailablePatterns();
        if (owner instanceof AdvPatternProviderLogicHost host) return host.getLogic().getAvailablePatterns();
        if (isEcoPatternBus(owner)) {
            return invokePatternList(owner, "getLocalAvailablePatterns");
        }
        if (isTrinityPatternCore(owner)) {
            return getTrinityCorePatterns(owner);
        }
        return List.of();
    }

    private static boolean isEcoPatternBus(Object owner) {
        return owner != null && owner.getClass().getName().equals(ECO_PATTERN_BUS_CLASS);
    }

    private static boolean isTrinityDataCore(Object owner) {
        return owner != null && owner.getClass().getName().equals(TRINITY_DATA_CORE_CLASS);
    }

    private static boolean isTrinityAccessHatch(Object owner) {
        return owner != null && owner.getClass().getName().equals(TRINITY_ACCESS_HATCH_CLASS);
    }

    private static boolean isTrinityPatternCore(Object owner) {
        return owner != null && owner.getClass().getName().equals(TRINITY_PATTERN_CORE_CLASS);
    }

    private static List<IPatternDetails> invokePatternList(Object owner, String methodName) {
        try {
            Object value = owner.getClass().getMethod(methodName).invoke(owner);
            if (!(value instanceof List<?> list)) return List.of();
            List<IPatternDetails> patterns = new ArrayList<>();
            for (Object pattern : list) {
                if (pattern instanceof IPatternDetails details) patterns.add(details);
            }
            return patterns;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return List.of();
        }
    }

    private static List<IPatternDetails> getTrinityCorePatterns(Object core) {
        try {
            List<IPatternDetails> patterns = new ArrayList<>();
            var decodedPattern = core.getClass().getMethod("decodedPattern", int.class);
            Object occupied = core.getClass().getMethod("occupiedPatternSlots").invoke(core);
            if (occupied instanceof Iterable<?> slots) {
                for (Object value : slots) {
                    if (!(value instanceof Number number)) continue;
                    Object pattern = decodedPattern.invoke(core, number.intValue());
                    if (pattern instanceof IPatternDetails details) patterns.add(details);
                }
                return patterns;
            }

            Object capacity = core.getClass().getMethod("patternCapacity").invoke(core);
            if (!(capacity instanceof Number number)) return List.of();
            int count = Math.max(0, Math.min(number.intValue(), 512));
            for (int slot = 0; slot < count; slot++) {
                Object pattern = decodedPattern.invoke(core, slot);
                if (pattern instanceof IPatternDetails details) patterns.add(details);
            }
            return patterns;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return List.of();
        }
    }

    /**
     * DataE publishes patterns through its aggregate catalog rather than the
     * normal AE provider API. Highlight the data core itself when a catalog
     * pattern matches; mapping a virtual pattern back to a child core is not
     * required for locator use and is unreliable while the layout is changing.
     */
    private static void scanTrinityDataCore(
            BlockEntity dataCore, List<ItemStack> filters,
            Map<BlockPos, List<LocatorHit>> results) {
        scanTrinityCatalog(dataCore, dataCore, filters, results, "dataCore");
    }

    /**
     * The access hatch is the AE2 grid owner for DataE's crafting provider. Its
     * pattern provider is backed by the bound data core, so report the core as
     * the locator position instead of the hatch position.
     */
    private static void scanTrinityAccessHatch(
            BlockEntity accessHatch, List<ItemStack> filters,
            Map<BlockPos, List<LocatorHit>> results) {
        try {
            Object dataCore = invokeNoArg(accessHatch, "patternProviderHost");
            if (!(dataCore instanceof BlockEntity)) {
                // Older DataE builds may not expose the provider-specific host
                // resolver, but their binding resolver still identifies the core.
                dataCore = invokeNoArg(accessHatch, "boundHost");
            }
            if (!(dataCore instanceof BlockEntity)) {
                ModLogger.debugThrottled("locator.trinity.hatch.unresolved." + accessHatch.getBlockPos().asLong(),
                        CTConfig.debugLogIntervalTicks,
                        "Locator DataE access hatch host unresolved hatch={} value={} methods=patternProviderHost,boundHost",
                        accessHatch.getBlockPos(), dataCore == null ? "null" : dataCore.getClass().getName());
                return;
            }
            scanTrinityCatalog(accessHatch, (BlockEntity) dataCore, filters, results, "accessHatch");
        } catch (ReflectiveOperationException | RuntimeException e) {
            ModLogger.debugThrottled("locator.trinity.hatch.error." + accessHatch.getBlockPos().asLong(),
                    CTConfig.debugLogIntervalTicks,
                    "Locator DataE access hatch host resolution failed hatch={} error={}",
                    accessHatch.getBlockPos(), e.toString());
        }
    }

    private static void scanTrinityCatalog(
            BlockEntity source, BlockEntity dataCore, List<ItemStack> filters,
            Map<BlockPos, List<LocatorHit>> results, String sourceType) {
        try {
            var catalogField = findField(dataCore.getClass(), "patternCatalog");
            Object catalog;
            if (catalogField != null) {
                catalogField.setAccessible(true);
                catalog = catalogField.get(dataCore);
            } else {
                // Keep compatibility with builds that only expose the catalog
                // through the generated getter.
                catalog = invokeNoArg(dataCore, "getPatternCatalog");
            }
            if (catalog == null) return;
            List<IPatternDetails> patterns = invokePatternList(catalog, "getAvailablePatterns");
            if (patterns.isEmpty()) {
                // Fall back to physical cores for older DataE builds that do
                // not expose the aggregate list on the catalog interface.
                Object mountsValue = catalog.getClass().getMethod("mountedCores").invoke(catalog);
                if (mountsValue instanceof Iterable<?> mounts) {
                    patterns = new ArrayList<>();
                    for (Object mount : mounts) {
                        Object core = mount.getClass().getMethod("core").invoke(mount);
                        if (isTrinityPatternCore(core)) patterns.addAll(getTrinityCorePatterns(core));
                    }
                }
            }

            List<LocatorHit> hits = new ArrayList<>();
            Set<ResourceLocation> types = new HashSet<>();
            checkPatternList(patterns, filters, hits, types);
            if (!hits.isEmpty()) {
                BlockPos dataCorePos = dataCore.getBlockPos().immutable();
                results.merge(dataCorePos, hits, NetworkLocatorScanner::mergeHits);
                ModLogger.debugThrottled("locator.trinity.hit." + dataCorePos.asLong(),
                        CTConfig.debugLogIntervalTicks,
                        "Locator DataE hit sourceType={} source={} dataCore={} patterns={} hits={}",
                        sourceType, source.getBlockPos(), dataCorePos, patterns.size(), hitSummary(hits));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            ModLogger.debugThrottled("locator.trinity.error." + source.getBlockPos().asLong(),
                    CTConfig.debugLogIntervalTicks,
                    "Locator DataE scan failed sourceType={} source={} dataCore={} error={}",
                    sourceType, source.getBlockPos(), dataCore.getBlockPos(), e.toString());
        }
    }

    private static Object invokeNoArg(Object target, String methodName) throws ReflectiveOperationException {
        var method = findMethod(target.getClass(), methodName);
        if (method == null) return null;
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static java.lang.reflect.Method findMethod(Class<?> type, String name) {
        while (type != null) {
            try {
                return type.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static List<LocatorHit> mergeHits(List<LocatorHit> first, List<LocatorHit> second) {
        List<LocatorHit> merged = new ArrayList<>(first);
        Set<ResourceLocation> types = new HashSet<>();
        for (LocatorHit hit : merged) types.add(hit.itemId());
        for (LocatorHit hit : second) {
            if (merged.size() >= MAX_HITS_PER_POS || !types.add(hit.itemId())) continue;
            merged.add(hit);
        }
        return merged;
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name) {
        while (type != null) {
            try {
                return type.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static boolean keyMatchesFilter(AEKey key, ItemStack filter) {
        if (key instanceof AEItemKey itemKey) {
            return filter.getItem() == itemKey.getItem();
        }
        return false;
    }

    private static LocatorHit buildHit(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return new LocatorHit(id, TYPE_ITEM);
    }

    private static String filterSummary(List<ItemStack> filters) {
        StringBuilder summary = new StringBuilder();
        for (ItemStack filter : filters) {
            if (!summary.isEmpty()) summary.append(',');
            summary.append(BuiltInRegistries.ITEM.getKey(filter.getItem()));
        }
        return summary.toString();
    }

    private static String hitSummary(List<LocatorHit> hits) {
        StringBuilder summary = new StringBuilder();
        for (LocatorHit hit : hits) {
            if (!summary.isEmpty()) summary.append(',');
            summary.append(hit.itemId()).append('#').append(hit.outputType());
        }
        return summary.toString();
    }

    /**
     * Adds a hit for the given filter item only if it hasn't been added yet
     * (deduplicates by item id) and we haven't reached MAX_HITS_PER_POS.
     */
    private static void tryAddHit(List<LocatorHit> foundItems, Set<ResourceLocation> foundTypes, ItemStack filter) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(filter.getItem());
        if (foundTypes.add(id)) {
            foundItems.add(buildHit(filter));
        }
    }

    // --- Part (cable-attached) helpers ---

    @Nullable
    private static BlockPos getPartPos(AEBasePart part) {
        var be = part.getHost().getBlockEntity();
        return be != null ? be.getBlockPos() : null;
    }

    /**
     * Check the inventory behind a storage bus for filter items.
     */
    private static void checkStorageBus(ServerLevel level, StorageBusPart bus, List<ItemStack> filters,
                                         List<LocatorHit> foundItems, Set<ResourceLocation> foundTypes) {
        Direction side = bus.getSide();
        BlockPos cablePos = bus.getHost().getBlockEntity().getBlockPos();
        BlockPos targetPos = cablePos.relative(side);

        var cap = level.getCapability(Capabilities.ItemHandler.BLOCK, targetPos, side.getOpposite());
        if (cap == null) return;

        for (int i = 0; i < cap.getSlots() && foundTypes.size() < MAX_HITS_PER_POS; i++) {
            ItemStack slotStack = cap.getStackInSlot(i);
            if (slotStack.isEmpty()) continue;
            for (ItemStack filter : filters) {
                if (filter.getItem() == slotStack.getItem()) {
                    tryAddHit(foundItems, foundTypes, filter);
                    break;
                }
            }
        }
    }

    /**
     * Check the filter config of an import/export bus for matching items.
     */
    private static void checkIOBusConfig(IOBusPart bus, List<ItemStack> filters,
                                          List<LocatorHit> foundItems, Set<ResourceLocation> foundTypes) {
        var config = bus.getConfig();
        for (int i = 0; i < config.size() && foundTypes.size() < MAX_HITS_PER_POS; i++) {
            var key = config.getKey(i);
            if (!(key instanceof AEItemKey itemKey)) continue;
            for (ItemStack filter : filters) {
                if (filter.getItem() == itemKey.getItem()) {
                    tryAddHit(foundItems, foundTypes, filter);
                    break;
                }
            }
        }
    }

    /**
     * Check the internal buffer of an ME Interface part for matching items.
     */
    private static void checkInterfaceInventory(InterfaceLogicHost host, List<ItemStack> filters,
                                                 List<LocatorHit> foundItems, Set<ResourceLocation> foundTypes) {
        var storage = host.getStorage();
        if (storage == null) return;
        for (int i = 0; i < storage.size() && foundTypes.size() < MAX_HITS_PER_POS; i++) {
            var stack = storage.getStack(i);
            if (stack == null) continue;
            for (ItemStack filter : filters) {
                if (keyMatchesFilter(stack.what(), filter)) {
                    tryAddHit(foundItems, foundTypes, filter);
                    break;
                }
            }
        }
    }

    /**
     * Check the config of a Level Emitter part for matching items.
     */
    private static void checkLevelEmitterConfig(StorageLevelEmitterPart emitter, List<ItemStack> filters,
                                                 List<LocatorHit> foundItems, Set<ResourceLocation> foundTypes) {
        var config = emitter.getConfig();
        for (int i = 0; i < config.size() && foundTypes.size() < MAX_HITS_PER_POS; i++) {
            var key = config.getKey(i);
            if (!(key instanceof AEItemKey itemKey)) continue;
            for (ItemStack filter : filters) {
                if (filter.getItem() == itemKey.getItem()) {
                    tryAddHit(foundItems, foundTypes, filter);
                    break;
                }
            }
        }
    }
}
