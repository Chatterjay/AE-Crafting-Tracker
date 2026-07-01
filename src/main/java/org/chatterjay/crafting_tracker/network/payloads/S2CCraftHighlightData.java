package org.chatterjay.crafting_tracker.network.payloads;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import org.chatterjay.crafting_tracker.Crafting_tracker;

public record S2CCraftHighlightData(List<HighlightEntry> entries, int runtimeRemainingTicks) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Crafting_tracker.MODID, "craft_highlight");
    public static final CustomPacketPayload.Type<S2CCraftHighlightData> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, S2CCraftHighlightData> STREAM_CODEC =
            StreamCodec.ofMember(S2CCraftHighlightData::write, S2CCraftHighlightData::new);

    public record HighlightEntry(BlockPos pos, int statusOrdinal, List<OutputItem> outputs) {
        public record OutputItem(ResourceLocation itemId, int outputType) {}
    }

    public S2CCraftHighlightData(FriendlyByteBuf buf) {
        this(readEntries(buf), buf.readVarInt());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (HighlightEntry entry : entries) {
            buf.writeBlockPos(entry.pos());
            buf.writeVarInt(entry.statusOrdinal());
            List<HighlightEntry.OutputItem> outputs = entry.outputs();
            buf.writeVarInt(outputs != null ? outputs.size() : 0);
            if (outputs != null) {
                for (HighlightEntry.OutputItem out : outputs) {
                    buf.writeResourceLocation(out.itemId());
                    buf.writeVarInt(out.outputType());
                }
            }
        }
        buf.writeVarInt(runtimeRemainingTicks);
    }

    private static List<HighlightEntry> readEntries(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<HighlightEntry> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            BlockPos pos = buf.readBlockPos();
            int ordinal = buf.readVarInt();
            int outputCount = buf.readVarInt();
            List<HighlightEntry.OutputItem> outputs;
            if (outputCount > 0) {
                outputs = new ArrayList<>(outputCount);
                for (int j = 0; j < outputCount; j++) {
                    ResourceLocation itemId = buf.readResourceLocation();
                    int outputType = buf.readVarInt();
                    outputs.add(new HighlightEntry.OutputItem(itemId, outputType));
                }
            } else {
                outputs = List.of();
            }
            list.add(new HighlightEntry(pos, ordinal, outputs));
        }
        return list;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
