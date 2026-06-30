package org.chatterjay.crafting_tracker.network.payloads;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import org.chatterjay.crafting_tracker.Crafting_tracker;
import org.chatterjay.crafting_tracker.api.CraftStatus;

public record S2CCraftHighlightData(List<HighlightEntry> entries) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Crafting_tracker.MODID, "craft_highlight");
    public static final CustomPacketPayload.Type<S2CCraftHighlightData> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, S2CCraftHighlightData> STREAM_CODEC =
            StreamCodec.ofMember(S2CCraftHighlightData::write, S2CCraftHighlightData::new);

    public record HighlightEntry(BlockPos pos, int statusOrdinal) {}

    public S2CCraftHighlightData(FriendlyByteBuf buf) {
        this(readEntries(buf));
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (HighlightEntry entry : entries) {
            buf.writeBlockPos(entry.pos());
            buf.writeVarInt(entry.statusOrdinal());
        }
    }

    private static List<HighlightEntry> readEntries(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<HighlightEntry> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(new HighlightEntry(buf.readBlockPos(), buf.readVarInt()));
        }
        return list;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
