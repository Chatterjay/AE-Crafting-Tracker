package org.chatterjay.crafting_tracker.item;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class NetworkLocatorMenu extends AbstractContainerMenu {

    public static final int FILTER_COLS = 3;
    public static final int FILTER_ROWS = 3;
    public static final int FILTER_SLOTS = FILTER_COLS * FILTER_ROWS;

    private static final int RESCAN_INTERVAL = 40;

    private final SimpleContainer filterContainer = new SimpleContainer(FILTER_SLOTS) {
        @Override
        public void setChanged() {
            super.setChanged();
            onFiltersChanged();
        }
    };

    private final ItemStack toolStack;
    private final Player player;

    private int rescanCooldown = RESCAN_INTERVAL;
    private boolean suppressFilterUpdates;

    public NetworkLocatorMenu(int containerId, Inventory playerInv) {
        this(containerId, playerInv, ItemStack.EMPTY);
    }

    public NetworkLocatorMenu(int containerId, Inventory playerInv, ItemStack toolStack) {
        super(null, containerId);
        this.toolStack = toolStack;
        this.player = playerInv.player;

        addFilterSlots();
        addPlayerInventory(playerInv);
        loadSavedFilters();
    }

    @Nullable
    @Override
    public MenuType<?> getType() {
        return Crafting_tracker.NETWORK_LOCATOR_MENU.get();
    }

    private void addFilterSlots() {
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
    }

    private void addPlayerInventory(Inventory playerInv) {
        int invLeft = 8;
        int invTop = 84;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, invLeft + col * 18, invTop + row * 18));
            }
        }

        int hotbarTop = 142;
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, invLeft + col * 18, hotbarTop));
        }
    }

    private void loadSavedFilters() {
        if (player.level() == null || toolStack.isEmpty()) return;
        var reg = player.level().registryAccess();
        List<ItemStack> saved = NetworkLocatorTool.getFilterSlots(toolStack, reg);
        suppressFilterUpdates = true;
        try {
            for (int i = 0; i < FILTER_SLOTS && i < saved.size(); i++) {
                filterContainer.setItem(i, saved.get(i));
            }
        } finally {
            suppressFilterUpdates = false;
        }
        if (!player.level().isClientSide) {
            performScan();
        }
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (isFilterSlot(slotId)) {
            if (button == 1) {
                updateFilterSlot(slotId, ItemStack.EMPTY);
                return;
            }

            ItemStack carried = getCarried();
            updateFilterSlot(slotId, carried.isEmpty() ? ItemStack.EMPTY : carried);
            return;
        }

        super.clicked(slotId, button, clickType, player);
    }

    public void setFilterSlot(int slotIndex, ItemStack stack) {
        if (!isFilterSlot(slotIndex)) return;
        ItemStack ghostStack = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        getSlot(slotIndex).set(ghostStack);
    }

    public void updateFilterSlot(int slotIndex, ItemStack stack) {
        if (!isFilterSlot(slotIndex)) return;
        ItemStack ghostStack = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        suppressFilterUpdates = true;
        try {
            getSlot(slotIndex).set(ghostStack);
        } finally {
            suppressFilterUpdates = false;
        }
        onFiltersChanged();
    }

    public boolean isFilterSlot(int slotIndex) {
        return slotIndex >= 0 && slotIndex < FILTER_SLOTS;
    }

    public boolean isFilterMenuSlot(Slot slot) {
        return getFilterSlotIndex(slot) >= 0;
    }

    public int getFilterSlotIndex(Slot slot) {
        int menuSlotIndex = this.slots.indexOf(slot);
        return isFilterSlot(menuSlotIndex) ? menuSlotIndex : -1;
    }

    private void onFiltersChanged() {
        if (suppressFilterUpdates) return;
        if (player.level() == null || player.level().isClientSide) return;
        if (toolStack.isEmpty()) return;

        NetworkLocatorTool.setAllFilters(toolStack, getFilterStacks(), player.level().registryAccess());
        rescanCooldown = RESCAN_INTERVAL;
        performScan();
    }

    private List<ItemStack> getFilterStacks() {
        List<ItemStack> filters = new ArrayList<>(FILTER_SLOTS);
        for (int i = 0; i < FILTER_SLOTS; i++) {
            filters.add(filterContainer.getItem(i));
        }
        return filters;
    }

    private void performScan() {
        if (player.level() == null || player.level().isClientSide) return;
        if (toolStack.isEmpty()) return;

        BlockPos boundPos = NetworkLocatorTool.getBoundPos(toolStack);
        ResourceLocation boundDim = NetworkLocatorTool.getBoundDimension(toolStack);
        if (boundPos == null || boundDim == null || !player.level().dimension().location().equals(boundDim)) {
            sendHighlights(Map.of());
            return;
        }

        Map<BlockPos, List<S2CLocatorHighlights.LocatorHit>> results =
                NetworkLocatorScanner.scan((ServerLevel) player.level(), boundPos, filterContainer, player);
        sendHighlights(results);
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (player.level() == null || player.level().isClientSide) return;
        if (toolStack.isEmpty()) return;
        if (--rescanCooldown > 0) return;
        rescanCooldown = RESCAN_INTERVAL;
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
