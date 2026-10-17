package io.github.jo112ss.resurrection;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Operator commands for testing, usable in single player with cheats on.
 * <ul>
 *     <li>{@code /resurrection down [targets]} – go down without dying first</li>
 *     <li>{@code /resurrection revive [targets]} – revive instantly, no XP cost</li>
 * </ul>
 */
public final class ResurrectionCommand {
	private ResurrectionCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal(Resurrection.MOD_ID)
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("down")
						.executes(ctx -> down(ctx.getSource(), List.of(ctx.getSource().getPlayerOrException())))
						.then(Commands.argument("targets", EntityArgument.players())
								.executes(ctx -> down(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets")))))
				.then(Commands.literal("revive")
						.executes(ctx -> revive(ctx.getSource(), List.of(ctx.getSource().getPlayerOrException())))
						.then(Commands.argument("targets", EntityArgument.players())
								.executes(ctx -> revive(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets"))))));
	}

	private static int down(CommandSourceStack source, Collection<ServerPlayer> targets) throws CommandSyntaxException {
		return apply(source, targets, DownedManager::forceDown, "resurrection.command.down", "resurrection.command.down.none");
	}

	private static int revive(CommandSourceStack source, Collection<ServerPlayer> targets) throws CommandSyntaxException {
		return apply(source, targets, DownedManager::forceRevive, "resurrection.command.revive", "resurrection.command.revive.none");
	}

	private static int apply(CommandSourceStack source, Collection<ServerPlayer> targets, Predicate<ServerPlayer> action,
			String successKey, String noneKey) {
		int count = 0;
		for (ServerPlayer player : targets) {
			if (action.test(player)) {
				count++;
			}
		}
		if (count == 0) {
			source.sendFailure(Component.translatable(noneKey));
		} else {
			int finalCount = count;
			source.sendSuccess(() -> Component.translatable(successKey, finalCount), true);
		}
		return count;
	}
}
