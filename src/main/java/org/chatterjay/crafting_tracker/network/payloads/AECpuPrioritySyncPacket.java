package org.chatterjay.crafting_tracker.network.payloads;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.chatterjay.crafting_tracker.Crafting_tracker;
import org.chatterjay.crafting_tracker.client.AECpuPriorityClientState;

public record AECpuPrioritySyncPacket(int serial, long priority) implements CustomPacketPayload {
    public static final Type<AECpuPrioritySyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Crafting_tracker.MODID, "ae_cpu_priority_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AECpuPrioritySyncPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, AECpuPrioritySyncPacket::serial,
                    ByteBufCodecs.VAR_LONG, AECpuPrioritySyncPacket::priority,
                    AECpuPrioritySyncPacket::new
            );

    private void handleInClient() {
        AECpuPriorityClientState.setPriority(serial, priority);
    }

    public static void handle(final AECpuPrioritySyncPacket packet, final IPayloadContext context) {
        context.enqueueWork(packet::handleInClient);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

