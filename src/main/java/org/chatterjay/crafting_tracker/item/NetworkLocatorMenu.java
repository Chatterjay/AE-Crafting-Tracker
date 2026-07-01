package org.chatterjay.crafting_tracker.item;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import org.chatterjay.crafting_tracker.Crafting_tracker;
import org.chatterjay.crafting_tracker.network.payloads.S2CLocatorHighlights;
import org.chatterjay.crafting_tracker.server.CraftTracker;
import org.chatterjay.crafting_tracker.server.NetworkLocatorScanner;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

public class NetworkLocatorMenu extends AbstractContainerMenu {

    private static final int FILTER_COLS = 3;
    private static final int FILTER_ROWS = 3;
    private static final int FILTER_SLOTS = FILTER_COLS * FILTER_ROWS;

    private static final Logger LOGGER = LogUtils.getLogger();

    private final SimpleContainer filterContainer = new SimpleContainer(FILTER_SLOTS) {
        @Override
        public void setChanged() {
            super.setChanged();
            onFiltersChanged();
        }
    };

    private final ItemStack toolStack;
    private final Player player;

    private int rescanCooldown = 0;
    private static final int RESCAN_INTERVAL = 40; // 2 seconds at 20 TPS

    // Client side constructor
    public NetworkLocatorMenu(int containerId, Inventory playerInv) {
        this(containerId, playerInv, ItemStack.EMPTY);
    }

    // Server side constructor
    public NetworkLocatorMenu(int containerId, Inventory playerInv, ItemStack toolStack) {
        super(null, containerId);
        this.toolStack = toolStack;
        this.player = playerInv.player;

        // Ghost filter slots (3x3 grid)
        int slotIndex = 0;
        for (int row = 0; row < FILTER_ROWS; row++) {
            for (int col = 0; col < FILTER_COLS; col++) {
                addSlot(new Slot(filterContainer, slotIndex, 62 + col * 18, 19 + row * 18) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return true;
                    }

                    @Override
                    public int getMaxStackSize() {
                        return 1;
                    }

                    @Override
                    public boolean mayPickup(Player player) {
                        return false;
                    }
                });
                slotIndex++;
            }
        }

        // Player inventory (3 rows of 9)
        int invLeft = 8;
        int invTop = 84;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, invLeft + col * 18, invTop + row * 18));
            }
        }

        // Player hotbar
        int hotbarTop = 142;
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, invLeft + col * 18, hotbarTop));
        }

        // Load saved filters from item NBT (server side)
        if (player.level() != null && !toolStack.isEmpty()) {
            var reg = player.level().registryAccess();
            List<ItemStack> saved = NetworkLocatorTool.getFilters(toolStack, reg);
            for (int i = 0; i < FILTER_SLOTS && i < saved.size(); i++) {
                filterContainer.setItem(i, saved.get(i));
            }
        }
    }

    @Nullable
    @Override
    public MenuType<?> getType() {
        return Crafting_tracker.NETWORK_LOCATOR_MENU.get();
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        // Intercept clicks on ghost filter slots (indices 0-8)
        if (slotId >= 0 && slotId < 9) {
            Slot slot = getSlot(slotId);
            // Right-click (button 1) always clears the slot (matches AE2 ghost slot behavior)
            if (button == 1) {
                slot.set(ItemStack.EMPTY);
                return;
            }
            ItemStack carried = getCarried();
            if (!carried.isEmpty()) {
                // Copy one item from cursor to ghost slot, don't consume cursor
                ItemStack copy = carried.copy();
                copy.setCount(1);
                slot.set(copy);
            } else {
                // Clear ghost slot
                slot.set(ItemStack.EMPTY);
            }
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    private void onFiltersChanged() {
        if (player.level() == null || player.level().isClientSide) return;
        if (toolStack.isEmpty()) return;

        LOGGER.info("[LocatorMenu] Filters changed for player {} — applying filter change", player.getName().getString());

        // Save filters to item NBT
        var reg = player.level().registryAccess();
        List<ItemStack> filters = List.of(
                filterContainer.getItem(0), filterContainer.getItem(1), filterContainer.getItem(2),
                filterContainer.getItem(3), filterContainer.getItem(4), filterContainer.getItem(5),
                filterContainer.getItem(6), filterContainer.getItem(7), filterContainer.getItem(8)
        );
        for (int i = 0; i < 9; i++) {
            ItemStack f = filters.get(i);
            if (!f.isEmpty()) {
                LOGGER.info("[LocatorMenu]   Slot {}: {} x{}", i, BuiltInRegistries.ITEM.getKey(f.getItem()), f.getCount());
            }
        }
        NetworkLocatorTool.setAllFilters(toolStack, filters, reg);

        performScan();
    }

    private void performScan() {
        if (player.level() == null || player.level().isClientSide) return;
        if (toolStack.isEmpty()) return;

        BlockPos boundPos = NetworkLocatorTool.getBoundPos(toolStack);
        if (boundPos == null) {
            LOGGER.info("[LocatorMenu] No bound position, clearing highlights");
            sendHighlights(Map.of());
            return;
        }

        ResourceLocation boundDim = NetworkLocatorTool.getBoundDimension(toolStack);
        if (boundDim == null) {
            LOGGER.info("[LocatorMenu] No bound dimension, clearing highlights");
            sendHighlights(Map.of());
            return;
        }

        // Only scan if the player is in the same dimension
        if (!player.level().dimension().location().equals(boundDim)) {
            LOGGER.info("[LocatorMenu] Wrong dimension (player={}, bound={}), clearing highlights",
                    player.level().dimension().location(), boundDim);
            sendHighlights(Map.of());
            return;
        }

        LOGGER.info("[LocatorMenu] Scanning network bound at {} for player {}", boundPos, player.getName().getString());

        // Scan the network
        Map<BlockPos, List<S2CLocatorHighlights.LocatorHit>> results =
                NetworkLocatorScanner.scan((ServerLevel) player.level(), boundPos, filterContainer, player);

        LOGGER.info("[LocatorMenu] Scan returned {} results", results.size());
        for (var e : results.entrySet()) {
            LOGGER.info("[LocatorMenu]   {} -> {} items", e.getKey(), e.getValue().size());
        }
        sendHighlights(results);
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (player.level() == null || player.level().isClientSide) return;
        if (toolStack.isEmpty()) return;
        if (--rescanCooldown > 0) return;
        rescanCooldown = RESCAN_INTERVAL;
        LOGGER.info("[LocatorMenu] Periodic rescan tick for player {}", player.getName().getString());
        performScan();
    }

    private void sendHighlights(Map<BlockPos, List<S2CLocatorHighlights.LocatorHit>> results) {
        if (player instanceof ServerPlayer sp) {
            long gameTime = sp.serverLevel().getGameTime();
            int remaining = CraftTracker.getRuntimeRemainingTicks(sp.getUUID(), gameTime);
            S2CLocatorHighlights packet = new S2CLocatorHighlights(results, remaining);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, packet);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        // No shift-click transfer for ghost slots
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    public SimpleContainer getFilterContainer() {
        return filterContainer;
    }
}
