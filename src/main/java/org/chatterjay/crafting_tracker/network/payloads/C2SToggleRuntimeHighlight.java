package org.chatterjay.crafting_tracker.network.payloads;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import org.chatterjay.crafting_tracker.Crafting_tracker;

public record C2SToggleRuntimeHighlight(boolean enable) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<C2SToggleRuntimeHighlight> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Crafting_tracker.MODID, "toggle_runtime_highlight"));

    public static final StreamCodec<FriendlyByteBuf, C2SToggleRuntimeHighlight> STREAM_CODEC =
            StreamCodec.ofMember(
                    (data, buf) -> buf.writeBoolean(data.enable),
                    buf -> new C2SToggleRuntimeHighlight(buf.readBoolean())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
