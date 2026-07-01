package org.chatterjay.crafting_tracker.server;

import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import org.chatterjay.crafting_tracker.Crafting_tracker;
import org.chatterjay.crafting_tracker.client.ClientHighlightCache;
import org.chatterjay.crafting_tracker.client.ClientLocatorCache;
import org.chatterjay.crafting_tracker.item.NetworkLocatorMenu;
import org.chatterjay.crafting_tracker.network.payloads.C2SToggleRuntimeHighlight;
import org.chatterjay.crafting_tracker.network.payloads.C2SUpdateFilterSlot;
import org.chatterjay.crafting_tracker.network.payloads.S2CCraftHighlightData;
import org.chatterjay.crafting_tracker.network.payloads.S2CLocatorHighlights;

import net.neoforged.fml.loading.FMLEnvironment;

public class CraftTrackerNetwork {

    public static void register(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(Crafting_tracker.MODID);

        // Provider highlights
        registrar.playToClient(
                S2CCraftHighlightData.TYPE,
                S2CCraftHighlightData.STREAM_CODEC,
                (data, context) -> {
                    if (FMLEnvironment.dist.isClient()) {
                        context.enqueueWork(() -> ClientHighlightCache.INSTANCE.update(data));
                    }
                });

        // Locator highlights
        registrar.playToClient(
                S2CLocatorHighlights.TYPE,
                S2CLocatorHighlights.STREAM_CODEC,
                (data, context) -> {
                    if (FMLEnvironment.dist.isClient()) {
                        context.enqueueWork(() -> ClientLocatorCache.INSTANCE.update(data));
                    }
                });

        // Filter slot updates from client (EMI drag-drop, scroll-wheel clear)
        registrar.playToServer(
                C2SUpdateFilterSlot.TYPE,
                C2SUpdateFilterSlot.STREAM_CODEC,
                (data, context) -> {
                    context.enqueueWork(() -> {
                        var player = context.player();
                        if (player != null && player.containerMenu instanceof NetworkLocatorMenu menu) {
                            if (data.slotIndex() >= 0 && data.slotIndex() < 9) {
                                menu.getFilterContainer().setItem(data.slotIndex(), data.stack());
                            }
                        }
                    });
                });

        // Runtime highlight toggle from the locator screen
        registrar.playToServer(
                C2SToggleRuntimeHighlight.TYPE,
                C2SToggleRuntimeHighlight.STREAM_CODEC,
                (data, context) -> {
                    context.enqueueWork(() -> {
                        var player = context.player();
                        if (player != null) {
                            MinecraftServer server = player.getServer();
                            if (server != null) {
                                long gameTime = server.overworld().getGameTime();
                                if (data.enable()) {
                                    CraftTracker.enableRuntimeHighlight(player.getUUID(), gameTime);
                                } else {
                                    CraftTracker.disableRuntimeHighlight(player.getUUID());
                                    // Send empty packet to clear client-side cache immediately
                                    if (player instanceof ServerPlayer sp) {
                                        PacketDistributor.sendToPlayer(sp, new S2CCraftHighlightData(List.of(), 0));
                                    }
                                }
                            }
                        }
                    });
                });
    }
}
