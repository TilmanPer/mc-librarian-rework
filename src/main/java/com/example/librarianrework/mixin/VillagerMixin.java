package com.example.librarianrework.mixin;

import com.example.librarianrework.LibrarianRework;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Villager experience handling:
 * - a level-up is held back while a player still has the trade window open
 * (performed right after closing),
 * - random enchanted books are stripped as soon as a librarian generates new
 * trades,
 * - learned-book prices follow the overlevel tier (checked twice a second,
 * never while trading).
 */
@Mixin(Villager.class)
public abstract class VillagerMixin {
	@Unique
	private boolean lr$pendingLevelUp;

	@Shadow
	protected abstract void increaseMerchantCareer(ServerLevel level);

	@Inject(method = "increaseMerchantCareer", at = @At("HEAD"), cancellable = true)
	private void lr$deferLevelUp(ServerLevel level, CallbackInfo ci) {
		if (LibrarianRework.isWindowOpen((Villager) (Object) this)) {
			lr$pendingLevelUp = true;
			ci.cancel();
		}
	}

	@Inject(method = "increaseMerchantCareer", at = @At("TAIL"))
	private void lr$afterLevelUp(ServerLevel level, CallbackInfo ci) {
		Villager self = (Villager) (Object) this;
		if (LibrarianRework.isLibrarian(self))
			LibrarianRework.stripRandomBooks(self);
	}

	@Inject(method = "customServerAiStep", at = @At("HEAD"))
	private void lr$tick(ServerLevel level, CallbackInfo ci) {
		Villager self = (Villager) (Object) this;
		boolean open = LibrarianRework.isWindowOpen(self);
		if (lr$pendingLevelUp && !open) {
			lr$pendingLevelUp = false;
			increaseMerchantCareer(level);
		}
		if (!open && self.tickCount % 40 == 0 && LibrarianRework.isLibrarian(self)) {
			LibrarianRework.refreshPrices(self);
		}
	}
}
