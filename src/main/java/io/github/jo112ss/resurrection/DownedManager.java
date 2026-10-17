package io.github.jo112ss.resurrection;

import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;


/** Server-side state and rules for downed players. */
public final class DownedManager {
	/** How long a downed player can wait for help: 180 seconds. */
	public static final int DOWNED_TICKS = 180 * 20;
	/** How long a reviver must keep crouching beside them: 10 seconds. */
	public static final int REVIVE_TICKS = 10 * 20;
	/** XP levels the reviver spends. */
	public static final int XP_COST_LEVELS = 20;
	/** Max distance (blocks) between reviver and downed player. */
	public static final double REVIVE_RANGE = 2.5;
	/** Health a revived player comes back with (3 hearts). */
	public static final float REVIVE_HEALTH = 6.0f;

	private static final Identifier SPEED_MODIFIER = Resurrection.id("downed_speed");
	private static final Identifier JUMP_MODIFIER = Resurrection.id("downed_jump");

	private static final Map<UUID, State> STATES = new HashMap<>();
	/** Players we are killing ourselves, so the death event must not down them again. */
	private static final Set<UUID> EXPIRING = new HashSet<>();

	private DownedManager() {
	}

	private static final class State {
		final DamageSource source;
		int ticksLeft = DOWNED_TICKS;
		UUID reviver;
		int reviveTicks;

		State(DamageSource source) {
			this.source = source;
		}
	}

	// ---------------------------------------------------------------- events

	/** @return true to let the player die normally, false if they went down instead. */
	public static boolean onLethalDamage(ServerPlayer player, DamageSource source) {
		if (EXPIRING.contains(player.getUUID())) {
			// Our own call to die() after the timer ran out.
			return true;
		}
		if (Resurrection.isDowned(player)) {
			// Only damage that ignores invulnerability (void, /kill) reaches a downed player.
			clear(player);
			return true;
		}
		if (player.isSpectator() || player.isCreative() || !anyoneCanHelp(player)) {
			return true;
		}
		goDown(player, source);
		return false;
	}

	public static boolean allowDamage(ServerPlayer player, DamageSource source) {
		return !Resurrection.isDowned(player) || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
	}

	public static void onDisconnect(ServerPlayer player, MinecraftServer server) {
		if (!STATES.containsKey(player.getUUID())) {
			return;
		}
		if (server.isRunning()) {
			// Logging out doesn't dodge death.
			expire(player);
		} else {
			// Server shutting down: don't punish anyone, just stand them back up.
			clear(player);
		}
	}

	public static void clearAll() {
		STATES.clear();
		EXPIRING.clear();
	}

	// ---------------------------------------------------------------- tick

	public static void tick(MinecraftServer server) {
		if (STATES.isEmpty()) {
			return;
		}
		Set<UUID> busyRevivers = new HashSet<>();
		// Iterate over a snapshot: reviving or expiring removes entries from STATES.
		for (Map.Entry<UUID, State> entry : new ArrayList<>(STATES.entrySet())) {
			ServerPlayer downed = server.getPlayerList().getPlayer(entry.getKey());
			if (downed == null || !downed.isAlive()) {
				STATES.remove(entry.getKey());
				continue;
			}
			State state = entry.getValue();
			state.ticksLeft--;
			if (state.ticksLeft <= 0) {
				STATES.remove(entry.getKey());
				expire(downed, state);
				continue;
			}
			tickRevive(downed, state, busyRevivers);
		}
	}

	/** @return true if the player was revived this tick. */
	private static boolean tickRevive(ServerPlayer downed, State state, Set<UUID> busyRevivers) {
		ServerLevel level = downed.level();
		long time = level.getGameTime();

		ServerPlayer reviver = state.reviver == null ? null : level.getServer().getPlayerList().getPlayer(state.reviver);
		if (reviver != null && !canRevive(reviver, downed, busyRevivers)) {
			reviver = null;
		}
		if (reviver == null) {
			state.reviver = null;
			state.reviveTicks = 0;
			reviver = findReviver(downed, busyRevivers, time);
			if (reviver != null) {
				state.reviver = reviver.getUUID();
			}
		}

		if (time % 10 == 0) {
			level.sendParticles(ParticleTypes.GLOW, downed.getX(), downed.getY() + 0.3, downed.getZ(),
					2, 0.4, 0.1, 0.4, 0.0);
		}

		if (reviver == null) {
			if (time % 10 == 0) {
				int seconds = (state.ticksLeft + 19) / 20;
				downed.sendOverlayMessage(Component.translatable("resurrection.downed.status", seconds)
						.withStyle(seconds <= 30 ? ChatFormatting.RED : ChatFormatting.GOLD));
			}
			return false;
		}

		busyRevivers.add(reviver.getUUID());
		state.reviveTicks++;

		// "Casting" effects
		level.sendParticles(ParticleTypes.ENCHANT, downed.getX(), downed.getY() + 0.8, downed.getZ(),
				4, 0.5, 0.5, 0.5, 0.5);
		if (state.reviveTicks % 20 == 1) {
			level.playSound(null, downed.getX(), downed.getY(), downed.getZ(),
					SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 0.8f,
					0.8f + 0.06f * (state.reviveTicks / 20));
		}

		Component bar = progressBar(state.reviveTicks, REVIVE_TICKS);
		reviver.sendOverlayMessage(Component.translatable("resurrection.revive.progress", downed.getDisplayName(), bar)
				.withStyle(ChatFormatting.AQUA));
		downed.sendOverlayMessage(Component.translatable("resurrection.downed.being_revived", reviver.getDisplayName(), bar)
				.withStyle(ChatFormatting.AQUA));

		if (state.reviveTicks < REVIVE_TICKS) {
			return false;
		}
		if (!payCost(reviver)) {
			state.reviver = null;
			state.reviveTicks = 0;
			return false;
		}
		revive(downed, reviver);
		return true;
	}

	private static ServerPlayer findReviver(ServerPlayer downed, Set<UUID> busyRevivers, long time) {
		ServerPlayer best = null;
		double bestDist = Double.MAX_VALUE;
		for (ServerPlayer other : downed.level().players()) {
			if (other == downed || other.isSpectator() || !other.isAlive() || Resurrection.isDowned(other)) {
				continue;
			}
			double dist = other.distanceToSqr(downed);
			if (dist > REVIVE_RANGE * REVIVE_RANGE) {
				continue;
			}
			if (!other.isShiftKeyDown()) {
				if (time % 10 == 0) {
					other.sendOverlayMessage(Component.translatable("resurrection.revive.hint",
							downed.getDisplayName(), XP_COST_LEVELS).withStyle(ChatFormatting.YELLOW));
				}
				continue;
			}
			if (!hasEnoughXp(other)) {
				if (time % 10 == 0) {
					other.sendOverlayMessage(Component.translatable("resurrection.revive.not_enough_xp", XP_COST_LEVELS)
							.withStyle(ChatFormatting.RED));
				}
				continue;
			}
			if (busyRevivers.contains(other.getUUID())) {
				continue;
			}
			if (dist < bestDist) {
				best = other;
				bestDist = dist;
			}
		}
		return best;
	}

	private static boolean canRevive(ServerPlayer reviver, ServerPlayer downed, Set<UUID> busyRevivers) {
		return reviver.isAlive()
				&& !reviver.isSpectator()
				&& !Resurrection.isDowned(reviver)
				&& reviver.level() == downed.level()
				&& reviver.isShiftKeyDown()
				&& reviver.distanceToSqr(downed) <= REVIVE_RANGE * REVIVE_RANGE
				&& hasEnoughXp(reviver)
				&& !busyRevivers.contains(reviver.getUUID());
	}

	// ---------------------------------------------------------------- transitions

	private static void goDown(ServerPlayer player, DamageSource source) {
		player.setHealth(1.0f);
		player.stopUsingItem();
		player.setSprinting(false);
		player.stopRiding();
		player.setGlowingTag(true);
		player.setData(Resurrection.DOWNED, true);
		addModifier(player.getAttribute(Attributes.MOVEMENT_SPEED), SPEED_MODIFIER);
		addModifier(player.getAttribute(Attributes.JUMP_STRENGTH), JUMP_MODIFIER);
		STATES.put(player.getUUID(), new State(source));

		ServerLevel level = player.level();
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1.0f, 0.8f);
		level.getServer().getPlayerList().broadcastSystemMessage(
				Component.translatable("resurrection.downed.broadcast", player.getDisplayName(), DOWNED_TICKS / 20)
						.withStyle(ChatFormatting.GOLD), false);
	}

	private static void revive(ServerPlayer downed, ServerPlayer reviver) {
		clear(downed);
		downed.setHealth(Math.min(REVIVE_HEALTH, downed.getMaxHealth()));

		ServerLevel level = downed.level();
		level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, downed.getX(), downed.getY() + 1.0, downed.getZ(),
				40, 0.4, 0.6, 0.4, 0.4);
		level.playSound(null, downed.getX(), downed.getY(), downed.getZ(),
				SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 0.8f, 1.1f);
		level.getServer().getPlayerList().broadcastSystemMessage(
				Component.translatable("resurrection.revive.success", downed.getDisplayName(), reviver.getDisplayName())
						.withStyle(ChatFormatting.GREEN), false);
	}

	private static void expire(ServerPlayer player) {
		State state = STATES.remove(player.getUUID());
		if (state != null) {
			expire(player, state);
		}
	}

	private static void expire(ServerPlayer player, State state) {
		clear(player);
		player.setHealth(0.0f);
		EXPIRING.add(player.getUUID());
		try {
			player.die(state.source);
		} finally {
			EXPIRING.remove(player.getUUID());
		}
	}

	/** Removes everything that marks a player as downed. */
	private static void clear(ServerPlayer player) {
		STATES.remove(player.getUUID());
		player.removeData(Resurrection.DOWNED);
		player.setGlowingTag(false);
		removeModifier(player.getAttribute(Attributes.MOVEMENT_SPEED), SPEED_MODIFIER);
		removeModifier(player.getAttribute(Attributes.JUMP_STRENGTH), JUMP_MODIFIER);
	}

	// ---------------------------------------------------------------- helpers

	private static boolean anyoneCanHelp(ServerPlayer player) {
		for (ServerPlayer other : player.level().getServer().getPlayerList().getPlayers()) {
			if (other != player && !other.isSpectator() && other.isAlive() && !Resurrection.isDowned(other)) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasEnoughXp(ServerPlayer player) {
		return player.isCreative() || player.experienceLevel >= XP_COST_LEVELS;
	}

	private static boolean payCost(ServerPlayer reviver) {
		if (reviver.isCreative()) {
			return true;
		}
		if (reviver.experienceLevel < XP_COST_LEVELS) {
			return false;
		}
		reviver.giveExperienceLevels(-XP_COST_LEVELS);
		return true;
	}

	private static void addModifier(AttributeInstance attribute, Identifier id) {
		if (attribute != null) {
			// -100% of the total value: the downed player stays where they fell.
			attribute.addOrUpdateTransientModifier(
					new AttributeModifier(id, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
	}

	private static void removeModifier(AttributeInstance attribute, Identifier id) {
		if (attribute != null) {
			attribute.removeModifier(id);
		}
	}

	private static Component progressBar(int ticks, int total) {
		int filled = Math.min(10, ticks * 10 / total);
		MutableComponent bar = Component.literal("■".repeat(filled)).withStyle(ChatFormatting.GREEN);
		bar.append(Component.literal("■".repeat(10 - filled)).withStyle(ChatFormatting.DARK_GRAY));
		return bar;
	}
}
