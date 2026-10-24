package io.github.jo112ss.resurrection.mixin.compat;

import io.github.jo112ss.resurrection.Resurrection;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * RPGmod compatibility: downed players cannot use job skills (skill keys) or cast staff spells.
 * {@code @Pseudo} + {@code require = 0}: does nothing when RPGmod isn't installed.
 */
@Pseudo
@Mixin(targets = "com.jo112ss.rpgmod.job.JobManager", remap = false)
public abstract class RpgModJobManagerMixin {
	/** Skill 1 / 2 / ultimate keys. */
	@Inject(method = "useSkill", at = @At("HEAD"), cancellable = true, require = 0)
	private static void resurrection$blockSkill(ServerPlayer player, int slot, CallbackInfo ci) {
		if (Resurrection.isDowned(player)) {
			player.sendOverlayMessage(Component.translatable("resurrection.downed.no_skill").withStyle(ChatFormatting.RED));
			ci.cancel();
		}
	}

	/** Starting to charge a staff spell (runs on both sides). */
	@Inject(method = "canStartCast", at = @At("HEAD"), cancellable = true, require = 0)
	private static void resurrection$blockStartCast(Player player, ItemStack staff, boolean notify,
			CallbackInfoReturnable<Boolean> cir) {
		if (Resurrection.isDowned(player)) {
			cir.setReturnValue(false);
		}
	}

	/** Releasing a charged staff spell. */
	@Inject(method = "releaseCast", at = @At("HEAD"), cancellable = true, require = 0)
	private static void resurrection$blockReleaseCast(ServerPlayer player, ItemStack staff, int held,
			CallbackInfoReturnable<Boolean> cir) {
		if (Resurrection.isDowned(player)) {
			cir.setReturnValue(false);
		}
	}
}
