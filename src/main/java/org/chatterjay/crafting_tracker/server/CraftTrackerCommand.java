package org.chatterjay.crafting_tracker.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.util.List;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import net.neoforged.neoforge.network.PacketDistributor;

import org.chatterjay.crafting_tracker.network.payloads.S2CCraftHighlightData;

public class CraftTrackerCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var node = Commands.literal("crafttracker")
                .executes(ctx -> executeStatus(ctx.getSource()))
                .then(Commands.literal("toggle")
                        .executes(ctx -> executeToggle(ctx.getSource())))
                .then(Commands.literal("on")
                        .executes(ctx -> executeOn(ctx.getSource())))
                .then(Commands.literal("off")
                        .executes(ctx -> executeOff(ctx.getSource())))
                .then(Commands.literal("status")
                        .executes(ctx -> executeStatus(ctx.getSource())));
        dispatcher.register(node);
    }

    private static int executeToggle(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        UUID id = player.getUUID();
        boolean enabled = !CraftTracker.isEnabledFor(id);
        CraftTracker.setEnabledFor(id, enabled);

        Component msg = Component.translatable(enabled
                ? "chat.crafting_tracker.enabled"
                : "chat.crafting_tracker.disabled");
        player.sendSystemMessage(msg);
        return 1;
    }

    private static int executeOn(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CraftTracker.setEnabledFor(player.getUUID(), true);
        player.sendSystemMessage(Component.translatable("chat.crafting_tracker.enabled"));
        return 1;
    }

    private static int executeOff(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CraftTracker.setEnabledFor(player.getUUID(), false);
        PacketDistributor.sendToPlayer(player, new S2CCraftHighlightData(List.of(), 0));
        player.sendSystemMessage(Component.translatable("chat.crafting_tracker.disabled"));
        return 1;
    }

    private static int executeStatus(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        boolean enabled = CraftTracker.isEnabledFor(player.getUUID());
        player.sendSystemMessage(Component.translatable(enabled
                ? "chat.crafting_tracker.status_enabled"
                : "chat.crafting_tracker.status_disabled"));
        return 1;
    }
}
