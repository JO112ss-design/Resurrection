package io.github.jo112ss.resurrection;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Resurrection implements ModInitializer {
	public static final String MOD_ID = "resurrection";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Synced to every client so the downed pose can be applied on both sides. */
	public static final AttachmentType<Boolean> DOWNED = AttachmentRegistry.create(
			id("downed"),
			builder -> builder.syncWith(ByteBufCodecs.BOOL, AttachmentSyncPredicate.all()));

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	public static boolean isDowned(Player player) {
		return Boolean.TRUE.equals(((AttachmentTarget) player).getAttached(DOWNED));
	}

	@Override
	public void onInitialize() {
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) ->
				!(entity instanceof ServerPlayer player) || DownedManager.onLethalDamage(player, source));

		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
				!(entity instanceof ServerPlayer player) || DownedManager.allowDamage(player, source));

		ServerTickEvents.END_SERVER_TICK.register(DownedManager::tick);

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				DownedManager.onDisconnect(handler.player, server));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> DownedManager.clearAll());

		// Downed players cannot fight, build or use items. These fire on both sides,
		// and the attachment is synced, so the client doesn't even try.
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> block(player));
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> block(player));
		UseItemCallback.EVENT.register((player, level, hand) -> block(player));
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> block(player));
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> block(player));
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> !isDowned(player));

		LOGGER.info("Resurrection loaded");
	}

	private static InteractionResult block(Player player) {
		return isDowned(player) ? InteractionResult.FAIL : InteractionResult.PASS;
	}
}
