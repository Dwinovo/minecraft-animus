package com.dwinovo.animus.entity;

import com.dwinovo.animus.network.payload.ClientUiActionPayload;
import com.dwinovo.animus.platform.Services;
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
 * The unified server-side {@code /animus} command tree, modelled on Carpet's
 * {@code /player} verbs. Lives entirely on the server so it never collides with
 * the client command dispatcher; the two inherently client-local verbs
 * ({@code settings}, {@code reset}) act on the caller's own client by firing a
 * {@link ClientUiActionPayload} back at them.
 *
 * <pre>
 *   /animus player summon &lt;name&gt;    create a companion fake player at the caller
 *   /animus player despawn &lt;name&gt;   send the named owned companion dormant
 *   /animus settings                  open the settings GUI on the caller's client
 *   /animus reset                     clear the caller's conversation loops
 * </pre>
 */
public final class AnimusCommands {

    private AnimusCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("animus")
                .then(Commands.literal("player")
                        .then(Commands.literal("summon")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> summon(ctx, StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("despawn")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> despawn(ctx, StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("settings")
                        .executes(ctx -> clientAction(ctx, ClientUiActionPayload.Action.OPEN_SETTINGS)))
                .then(Commands.literal("reset")
                        .executes(ctx -> clientAction(ctx, ClientUiActionPayload.Action.RESET_LOOPS))));
    }

    private static int summon(CommandContext<CommandSourceStack> ctx, String name)
            throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        ServerLevel level = (ServerLevel) owner.level();
        AnimusPlayer body = Companions.summon(
                level.getServer(), owner.getUUID(), name, level, owner.position());
        // Push the updated roster so the owner's G panel can reach the new companion.
        Companions.syncRosterToOwner(level.getServer(), owner);
        ctx.getSource().sendSuccess(() ->
                Component.literal("Summoned companion '" + name + "' (" + body.getUUID() + ")"), false);
        return 1;
    }

    private static int despawn(CommandContext<CommandSourceStack> ctx, String name)
            throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        for (ServerPlayer p : owner.level().getServer().getPlayerList().getPlayers()) {
            if (p instanceof AnimusPlayer companion
                    && companion.isOwnedByPlayer(owner.getUUID())
                    && companion.getName().getString().equals(name)) {
                Companions.dormant(owner.level().getServer(), companion);
                Companions.syncRosterToOwner(owner.level().getServer(), owner);
                ctx.getSource().sendSuccess(() ->
                        Component.literal("Companion '" + name + "' is now dormant"), false);
                return 1;
            }
        }
        ctx.getSource().sendFailure(
                Component.literal("No live companion of yours named '" + name + "'"));
        return 0;
    }

    private static int clientAction(CommandContext<CommandSourceStack> ctx,
                                    ClientUiActionPayload.Action action)
            throws CommandSyntaxException {
        ServerPlayer caller = ctx.getSource().getPlayerOrException();
        Services.NETWORK.sendToPlayer(caller, new ClientUiActionPayload(action));
        return 1;
    }
}
