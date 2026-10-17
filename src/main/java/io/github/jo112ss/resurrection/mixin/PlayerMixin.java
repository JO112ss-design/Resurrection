package io.github.jo112ss.resurrection.mixin;

import io.github.jo112ss.resurrection.Resurrection;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps a downed player lying flat (the crawling pose) on both server and client. */
@Mixin(Player.class)
public abstract class PlayerMixin {
	@Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
	private void resurrection$forceDownedPose(CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (Resurrection.isDowned(self)) {
			self.setPose(Pose.SWIMMING);
			ci.cancel();
		}
	}
}
