package org.chatterjay.crafting_tracker.server;

import com.mojang.logging.LogUtils;
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
import org.slf4j.Logger;

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
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int TYPE_ITEM = 0;
    private static final int TYPE_CHEMICAL = 3;
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
        if (filters.isEmpty()) return results;

        LOGGER.info("[LocatorScan] Scanning with {} filters for player {}", filters.size(), player.getName().getString());
        for (ItemStack f : filters) {
            LOGGER.info("[LocatorScan]   Filter: {} x{}", BuiltInRegistries.ITEM.getKey(f.getItem()), f.getCount());
        }

        // Get grid from bound position
        BlockEntity boundBe = level.getBlockEntity(boundPos);
        if (!(boundBe instanceof IInWorldGridNodeHost host)) {
            LOGGER.info("[LocatorScan] Bound position {} has no AE host", boundPos);
            return results;
        }

        IGrid grid = getGrid(host);
        if (grid == null) {
            LOGGER.info("[LocatorScan] No grid found at bound position {}", boundPos);
            return results;
        }
        LOGGER.info("[LocatorScan] Got grid, size={}, machine classes:", grid.size());
        for (var mc : grid.getMachineClasses()) {
            LOGGER.info("[LocatorScan]   Machine class: {}", mc.getName());
        }

        // Iterate ALL grid nodes and get owners (both BlockEntities and cable-attached parts)
        Set<BlockPos> visitedPos = new HashSet<>();
        Set<Object> visitedOwners = Collections.newSetFromMap(new IdentityHashMap<>());
        int totalNodes = 0;

        for (IGridNode node : grid.getNodes()) {
            totalNodes++;
            Object owner = node.getOwner();

            BlockPos pos = null;
            List<LocatorHit> foundItems = new ArrayList<>();
            Set<ResourceLocation> foundTypes = new HashSet<>();

            if (owner instanceof BlockEntity be) {
                if (be.isRemoved()) continue;
                pos = be.getBlockPos();
                if (!visitedPos.add(pos)) continue;

                LOGGER.info("[LocatorScan]   BE node at {}: {}",
                        pos, be.getType().builtInRegistryHolder().key().location());

                // 1. Check IItemHandler capability (covers ME Interfaces, chests, etc.)
                checkItemHandler(be, filters, foundItems, foundTypes);

                // 2. Check pattern provider patterns (only if we have room)
                if (foundTypes.size() < MAX_HITS_PER_POS) {
                    checkPatterns(owner, filters, foundItems, foundTypes);
                }
            } else if (owner instanceof StorageBusPart bus) {
                pos = getPartPos(bus);
                if (pos == null || !visitedOwners.add(owner)) continue;
                checkStorageBus(level, bus, filters, foundItems, foundTypes);
            } else if (owner instanceof IOBusPart bus) {
                pos = getPartPos(bus);
                if (pos == null || !visitedOwners.add(owner)) continue;
                checkIOBusConfig(bus, filters, foundItems, foundTypes);
            } else if (owner instanceof AEBasePart part) {
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
                LOGGER.info("[LocatorScan]   >>> FOUND {} items at {}", foundItems.size(), pos);
                for (LocatorHit hit : foundItems) {
                    LOGGER.info("[LocatorScan]     Hit: {}", hit.itemId());
                }
                results.put(pos.immutable(), foundItems);
            }
        }
        LOGGER.info("[LocatorScan] {} total nodes", totalNodes);

        if (!results.isEmpty()) {
            LOGGER.info("[LocatorScan] Scan complete: {} matches for {}", results.size(), player.getName().getString());
        }

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
        return List.of();
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
