package com.dwinovo.animus.entity;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server commands. For now just a dev {@code /animus_summon [name]} to create a
 * companion at the caller's position — a stand-in trigger for the Phase-0 spine
 * (spawn / stand / persist). The shipping summon UX is a roster-panel button.
 */
public final class AnimusCommands {

    private AnimusCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("animus_summon")
                        .executes(ctx -> summon(ctx, "Animus"))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> summon(ctx, StringArgumentType.getString(ctx, "name")))));
    }

    private static int summon(CommandContext<CommandSourceStack> ctx, String name)
            throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        ServerLevel level = (ServerLevel) owner.level();
        AnimusPlayer body = Companions.summon(
                level.getServer(), owner.getUUID(), name, level, owner.position());
        ctx.getSource().sendSuccess(() ->
                Component.literal("Summoned companion '" + name + "' (" + body.getUUID() + ")"), false);
        return 1;
    }
}
