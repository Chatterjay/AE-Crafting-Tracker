package org.chatterjay.crafting_tracker.server;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import org.chatterjay.crafting_tracker.Crafting_tracker;
import org.chatterjay.crafting_tracker.client.ClientHighlightCache;
import org.chatterjay.crafting_tracker.network.payloads.S2CCraftHighlightData;

import net.neoforged.fml.loading.FMLEnvironment;

public class CraftTrackerNetwork {
    public static void register(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(Crafting_tracker.MODID);
        registrar.playToClient(
                S2CCraftHighlightData.TYPE,
                S2CCraftHighlightData.STREAM_CODEC,
                (data, context) -> {
                    if (FMLEnvironment.dist.isClient()) {
                        context.enqueueWork(() -> ClientHighlightCache.INSTANCE.update(data));
                    }
                });
    }
}
