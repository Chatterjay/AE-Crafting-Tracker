package org.chatterjay.crafting_tracker.server;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

import net.neoforged.neoforge.network.PacketDistributor;

import org.chatterjay.crafting_tracker.item.NetworkLocatorTool;
import org.chatterjay.crafting_tracker.network.payloads.S2CCraftHighlightData;
import org.chatterjay.crafting_tracker.network.payloads.S2CLocatorHighlights;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class LocatorTrackingService {

    private static final int FILTER_SLOTS = 9;
    private static final int SCAN_INTERVAL_TICKS = 40;

    private final Set<UUID> playersWithLocatorLastTick = new HashSet<>();
    private final Map<UUID, BlockPos> lastBoundPositions = new HashMap<>();
    private int tickCounter;

    void onServerTick(MinecraftServer server, long gameTime) {
        tickCounter++;

        Set<UUID> currentLocatorPlayers = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ItemStack locator = findLocator(player);
            if (locator.isEmpty()) continue;

            UUID playerId = player.getUUID();
            currentLocatorPlayers.add(playerId);
            handleBindingChange(server, player, locator, playerId, gameTime);
        }

        clearPlayersWithoutLocator(server, currentLocatorPlayers);
        playersWithLocatorLastTick.clear();
        playersWithLocatorLastTick.addAll(currentLocatorPlayers);

        if (tickCounter % SCAN_INTERVAL_TICKS == 0) {
            scanBoundLocators(server, gameTime);
        }
    }

    private void handleBindingChange(MinecraftServer server, ServerPlayer player, ItemStack locator,
                                     UUID playerId, long gameTime) {
        BoundLocator bound = getUsableBoundLocator(player, locator);
        if (bound == null) {
            if (lastBoundPositions.remove(playerId) != null) {
                clearLocatorHighlights(player);
            }
            return;
        }

        BlockPos lastPos = lastBoundPositions.get(playerId);
        if (lastPos != null && !lastPos.equals(bound.pos())) {
            clearLocatorHighlights(player);
            performLocatorScan(server, player, locator, bound.pos(), gameTime);
        }
        lastBoundPositions.put(playerId, bound.pos());
    }

    private void scanBoundLocators(MinecraftServer server, long gameTime) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ItemStack locator = findLocator(player);
            if (locator.isEmpty()) continue;

            BoundLocator bound = getUsableBoundLocator(player, locator);
            if (bound == null) continue;

            performLocatorScan(server, player, locator, bound.pos(), gameTime);
        }
    }

    private void clearPlayersWithoutLocator(MinecraftServer server, Set<UUID> currentLocatorPlayers) {
        for (UUID uuid : playersWithLocatorLastTick) {
            if (currentLocatorPlayers.contains(uuid)) continue;

            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            CraftTracker.clearRuntimeState(uuid);
            lastBoundPositions.remove(uuid);
            if (player != null) {
                clearLocatorHighlights(player);
                PacketDistributor.sendToPlayer(player, new S2CCraftHighlightData(List.of(), 0));
            }
        }
    }

    private void performLocatorScan(MinecraftServer server, ServerPlayer player, ItemStack locator,
                                    BlockPos boundPos, long gameTime) {
        var reg = player.level().registryAccess();
        List<ItemStack> filters = NetworkLocatorTool.getFilters(locator, reg);
        if (filters.isEmpty()) {
            sendHighlights(player, Map.of(), gameTime);
            return;
        }

        SimpleContainer filterContainer = new SimpleContainer(FILTER_SLOTS);
        for (int i = 0; i < Math.min(filters.size(), FILTER_SLOTS); i++) {
            filterContainer.setItem(i, filters.get(i));
        }

        Map<BlockPos, List<S2CLocatorHighlights.LocatorHit>> results =
                NetworkLocatorScanner.scan((ServerLevel) player.level(), boundPos, filterContainer, player);
        sendHighlights(player, results, gameTime);
    }

    private void sendHighlights(ServerPlayer player, Map<BlockPos, List<S2CLocatorHighlights.LocatorHit>> results,
                                long gameTime) {
        int runtimeRemaining = CraftTracker.getRuntimeRemainingTicks(player.getUUID(), gameTime);
        PacketDistributor.sendToPlayer(player, new S2CLocatorHighlights(results, runtimeRemaining));
    }

    private void clearLocatorHighlights(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new S2CLocatorHighlights(Map.of(), 0));
    }

    private static ItemStack findLocator(ServerPlayer player) {
        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.getItem() instanceof NetworkLocatorTool) return mainHand;

        ItemStack offHand = player.getOffhandItem();
        if (offHand.getItem() instanceof NetworkLocatorTool) return offHand;

        for (ItemStack stack : player.getInventory().items) {
            if (stack.getItem() instanceof NetworkLocatorTool) return stack;
        }
        return ItemStack.EMPTY;
    }

    private static BoundLocator getUsableBoundLocator(ServerPlayer player, ItemStack locator) {
        if (!NetworkLocatorTool.isBound(locator)) return null;

        ResourceLocation boundDim = NetworkLocatorTool.getBoundDimension(locator);
        if (boundDim == null || !player.level().dimension().location().equals(boundDim)) return null;

        BlockPos boundPos = NetworkLocatorTool.getBoundPos(locator);
        return boundPos == null ? null : new BoundLocator(boundPos);
    }

    private record BoundLocator(BlockPos pos) {}
}
