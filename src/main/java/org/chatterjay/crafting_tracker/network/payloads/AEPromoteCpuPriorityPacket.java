package org.chatterjay.crafting_tracker.network.payloads;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.chatterjay.crafting_tracker.Crafting_tracker;
import org.chatterjay.crafting_tracker.util.AeCpuPrioritySelector;
import org.chatterjay.crafting_tracker.util.CpuPriorityMenuAccess;
import org.chatterjay.crafting_tracker.util.ModLogger;

public record AEPromoteCpuPriorityPacket(int serial) implements CustomPacketPayload {
    public static final Type<AEPromoteCpuPriorityPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Crafting_tracker.MODID, "ae_promote_cpu_priority"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AEPromoteCpuPriorityPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, AEPromoteCpuPriorityPacket::serial,
                    AEPromoteCpuPriorityPacket::new
            );

    private void handleInServer(final IPayloadContext context) {
        if (!AeCpuPrioritySelector.isEnabled()) {
            ModLogger.debug("AE CPU runtime priority action ignored because feature is disabled");
            return;
        }

        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }

        int requestedSerial = serial;
        boolean lower = requestedSerial < 0;
        int targetSerial = Math.abs(requestedSerial);
        if (player.containerMenu instanceof CpuPriorityMenuAccess access) {
            ModLogger.debug("Handling AE CPU runtime priority {} request for serial {}",
                    lower ? "lower" : "promote", targetSerial);
            long priority = access.craftingtracker$setCpuPriority(targetSerial, lower);
            for (var entry : access.craftingtracker$currentCpuPriorities().entrySet()) {
                PacketDistributor.sendToPlayer(player, new AECpuPrioritySyncPacket(entry.getKey(), entry.getValue()));
            }
            PacketDistributor.sendToPlayer(player, new AECpuPrioritySyncPacket(targetSerial, priority));
            player.sendSystemMessage(lower
                    ? (priority <= 0
                            ? Component.translatable("crafting_tracker.message.ae_cpu_priority.cleared")
                            : Component.translatable("crafting_tracker.message.ae_cpu_priority.lowered_level", priority))
                    : Component.translatable("crafting_tracker.message.ae_cpu_priority.promoted_level", priority), true);
        } else {
            ModLogger.debug("AE CPU runtime priority action ignored because current menu is {}", player.containerMenu.getClass().getName());
        }
    }

    public static void handle(final AEPromoteCpuPriorityPacket packet, final IPayloadContext context) {
        context.enqueueWork(() -> packet.handleInServer(context));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

