package io.github.jo112ss.resurrection;

import java.util.function.Supplier;

import com.mojang.logging.LogUtils;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.slf4j.Logger;

@Mod(Resurrection.MOD_ID)
public class Resurrection {
	public static final String MOD_ID = "resurrection";
	public static final Logger LOGGER = LogUtils.getLogger();

	private static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
			DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MOD_ID);

	/** Synced to every client so the downed pose can be applied on both sides. */
	public static final Supplier<AttachmentType<Boolean>> DOWNED = ATTACHMENT_TYPES.register("downed",
			() -> AttachmentType.builder(() -> false).sync(ByteBufCodecs.BOOL).build());

	public Resurrection(IEventBus modEventBus, ModContainer modContainer) {
		ATTACHMENT_TYPES.register(modEventBus);

		IEventBus bus = NeoForge.EVENT_BUS;
		bus.addListener(Resurrection::onDeath);
		bus.addListener(Resurrection::onIncomingDamage);
		bus.addListener((ServerTickEvent.Post event) -> DownedManager.tick(event.getServer()));
		bus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
			if (event.getEntity() instanceof ServerPlayer player) {
				DownedManager.onDisconnect(player, player.level().getServer());
			}
		});
		bus.addListener((ServerStoppedEvent event) -> DownedManager.clearAll());
		bus.addListener((RegisterCommandsEvent event) -> ResurrectionCommand.register(event.getDispatcher()));

		// Downed players cannot fight, build or use items. These fire on both sides,
		// and the attachment is synced, so the client doesn't even try.
		bus.addListener((AttackEntityEvent event) -> block(event.getEntity(), event));
		bus.addListener((PlayerInteractEvent.EntityInteract event) -> block(event.getEntity(), event));
		bus.addListener((PlayerInteractEvent.RightClickItem event) -> block(event.getEntity(), event));
		bus.addListener((PlayerInteractEvent.RightClickBlock event) -> block(event.getEntity(), event));
		bus.addListener((PlayerInteractEvent.LeftClickBlock event) -> block(event.getEntity(), event));
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	public static boolean isDowned(Player player) {
		return player.hasData(DOWNED) && player.getData(DOWNED);
	}

	private static void onDeath(LivingDeathEvent event) {
		if (event.getEntity() instanceof ServerPlayer player && !DownedManager.onLethalDamage(player, event.getSource())) {
			event.setCanceled(true);
		}
	}

	private static void onIncomingDamage(LivingIncomingDamageEvent event) {
		if (event.getEntity() instanceof ServerPlayer player && !DownedManager.allowDamage(player, event.getSource())) {
			event.setCanceled(true);
		}
	}

	private static void block(Player player, ICancellableEvent event) {
		if (isDowned(player)) {
			event.setCanceled(true);
		}
	}
}
