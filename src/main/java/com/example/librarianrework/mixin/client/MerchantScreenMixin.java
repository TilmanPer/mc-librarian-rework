package com.example.librarianrework.mixin.client;

import com.example.librarianrework.Overlevel;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Trade screen changes for librarians:
 *  - title: "Librarian - Master III [4/5]"
 *  - XP bar stays visible at Master and tracks progress to the next overlevel tier.
 *
 * Rendering code moved a lot in 26.x, so these hooks target the vanilla calls (not whole method signatures)
 * and use require = 0: if a call can't be found the screen simply stays vanilla instead of crashing.
 */
@Mixin(MerchantScreen.class)
public abstract class MerchantScreenMixin {
	private static final String LEVEL_NAME_CALL =
			"Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;";
	private static final String MENU = "Lnet/minecraft/world/inventory/MerchantMenu;";

	@SuppressWarnings("unchecked")
	private MerchantMenu lr$menu() {
		return ((AbstractContainerScreen<MerchantMenu>) (Object) this).getMenu();
	}

	private boolean lr$isLibrarian() {
		MerchantMenu menu = lr$menu();
		Component title = ((Screen) (Object) this).getTitle();
		if (title.getContents() instanceof TranslatableContents tc && tc.getKey().endsWith(".librarian")) return true;
		return lr$learned(menu) > 0; // renamed librarians: recognised by their (only possible) book trades
	}

	private static int lr$learned(MerchantMenu menu) {
		int n = 0;
		for (MerchantOffer offer : menu.getOffers()) {
			if (offer.getResult().is(Items.ENCHANTED_BOOK)) n++;
		}
		return n;
	}

	/** Level name ("Master") -> "Master III [4/5]". */
	@ModifyExpressionValue(method = "/^[a-zA-Z].*/", at = @At(value = "INVOKE", target = LEVEL_NAME_CALL), require = 0)
	private MutableComponent lr$levelTitle(MutableComponent original) {
		if (!(original.getContents() instanceof TranslatableContents tc) || !tc.getKey().startsWith("merchant.level.")) {
			return original;
		}
		if (!lr$isLibrarian()) return original;

		MerchantMenu menu = lr$menu();
		int level = menu.getTraderLevel();
		MutableComponent out = original;
		if (level >= Overlevel.MASTER_LEVEL) {
			int tier = Overlevel.tier(menu.getTraderXp());
			if (tier > 0) out = out.append(" " + Overlevel.roman(tier));
		}
		return out.append(" [" + lr$learned(menu) + "/" + level + "]");
	}

	// --- XP bar: pretend a Master is a Journeyman->Expert villager whose XP is the overlevel progress ---

	@ModifyExpressionValue(method = "/.*[Pp]rogress.*/", at = @At(value = "INVOKE", target = MENU + "getTraderLevel()I"), require = 0)
	private int lr$barLevel(int level) {
		return level >= Overlevel.MASTER_LEVEL && lr$isLibrarian() ? 4 : level;
	}

	@ModifyExpressionValue(method = "/.*[Pp]rogress.*/", at = @At(value = "INVOKE", target = MENU + "getTraderXp()I"), require = 0)
	private int lr$barXp(int xp) {
		MerchantMenu menu = lr$menu();
		return menu.getTraderLevel() >= Overlevel.MASTER_LEVEL && lr$isLibrarian() ? Overlevel.barXp(xp) : xp;
	}

	@ModifyExpressionValue(method = "/.*[Pp]rogress.*/", at = @At(value = "INVOKE", target = MENU + "getFutureTraderXp()I"), require = 0)
	private int lr$barFutureXp(int gain) {
		MerchantMenu menu = lr$menu();
		return menu.getTraderLevel() >= Overlevel.MASTER_LEVEL && lr$isLibrarian()
				? Overlevel.barFutureXp(menu.getTraderXp(), gain) : gain;
	}
}
