package com.example.librarianrework;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * Librarian Rework.
 *  - Librarians never sell random enchanted books (stripped whenever trades are generated / opened).
 *  - Right-click a librarian with a single-enchantment book: he learns it and sells it for emeralds.
 *  - Learn capacity = villager level (1-5). Treasure enchantments only for Master (level 5).
 *  - Master librarians gain overlevels (see Overlevel); each tier lowers the price of every learned book.
 */
public class LibrarianRework implements ModInitializer {
	public static final String MOD_ID = "librarian_rework";
	/** Villager XP granted per trade of a learned book, by villager level 1-5. */
	private static final int[] TRADE_XP = {1, 5, 10, 15, 30};

	/** One learned book (stored in the villager's save data). */
	public record Learned(String enchantment, int level) {
		public static final Codec<Learned> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("enchantment").forGetter(Learned::enchantment),
				Codec.INT.fieldOf("level").forGetter(Learned::level)
		).apply(i, Learned::new));

		String key() {
			return enchantment + "@" + level;
		}
	}

	public static final AttachmentType<List<Learned>> LEARNED = AttachmentRegistry.createPersistent(
			Identifier.fromNamespaceAndPath(MOD_ID, "learned_books"), Learned.CODEC.listOf());

	/** Who last opened a librarian's trade screen (weak: entries vanish with the villager). */
	private static final Map<Villager, ServerPlayer> TRADING = Collections.synchronizedMap(new WeakHashMap<>());

	@Override
	public void onInitialize() {
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (hand != InteractionHand.MAIN_HAND || player.isSpectator()) return InteractionResult.PASS;
			if (!(entity instanceof Villager villager) || villager.isBaby() || !isLibrarian(villager)) {
				return InteractionResult.PASS;
			}

			if (level instanceof ServerLevel) {
				stripRandomBooks(villager);
				refreshPrices(villager);
			}

			ItemStack held = player.getItemInHand(hand);
			ItemEnchantments stored = held.get(DataComponents.STORED_ENCHANTMENTS);
			if (!held.is(Items.ENCHANTED_BOOK) || stored == null || stored.isEmpty()) {
				if (player instanceof ServerPlayer sp) TRADING.put(villager, sp);
				return InteractionResult.PASS; // vanilla opens the trade screen
			}

			if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.SUCCESS; // client: swing, no trade screen
			}
			teach(serverPlayer, serverLevel, villager, held, stored);
			return InteractionResult.SUCCESS;
		});
	}

	public static boolean isLibrarian(Villager villager) {
		return villager.getVillagerData().profession().is(VillagerProfession.LIBRARIAN);
	}

	/** True while a player still has this villager's trade window open. */
	public static boolean isWindowOpen(Villager villager) {
		if (villager.isTrading()) return true;
		ServerPlayer p = TRADING.get(villager);
		return p != null && !p.isRemoved() && p.containerMenu instanceof MerchantMenu;
	}

	private static List<Learned> learnedOf(Villager villager) {
		List<Learned> list = villager.getAttached(LEARNED);
		return list == null ? List.of() : list;
	}

	/** Overlevel tier of a villager (0 unless he is Master). */
	public static int tierOf(Villager villager) {
		if (villager.getVillagerData().level() < Overlevel.MASTER_LEVEL) return 0;
		return Overlevel.tier(villager.getVillagerXp());
	}

	private static void teach(ServerPlayer player, ServerLevel level, Villager villager,
							  ItemStack held, ItemEnchantments stored) {
		if (stored.size() != 1) {
			msg(player, "The librarian can only learn books with a single enchantment.");
			return;
		}

		int villagerLevel = villager.getVillagerData().level();
		List<Learned> learned = learnedOf(villager);
		if (learned.size() >= villagerLevel) {
			msg(player, "This librarian can't learn more books yet [" + learned.size() + "/" + villagerLevel
					+ " Learned]. Level him up to increase his capacity.");
			return;
		}

		var entry = stored.entrySet().iterator().next();
		Holder<Enchantment> enchantment = entry.getKey();
		int enchLevel = entry.getIntValue();
		boolean treasure = enchantment.is(EnchantmentTags.TREASURE);

		if (treasure && villagerLevel < Overlevel.MASTER_LEVEL) {
			msg(player, "Only a Master librarian can learn treasure enchantments.");
			return;
		}

		Learned newBook = new Learned(enchantment.getRegisteredName(), enchLevel);
		if (learned.stream().anyMatch(l -> l.key().equals(newBook.key()))) {
			msg(player, "The librarian already knows that book.");
			return;
		}

		ItemStack result = new ItemStack(Items.ENCHANTED_BOOK);
		ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
		mutable.set(enchantment, enchLevel);
		result.set(DataComponents.STORED_ENCHANTMENTS, mutable.toImmutable());

		villager.getOffers().add(new MerchantOffer(
				new ItemCost(Items.EMERALD, Pricing.price(enchLevel, treasure, tierOf(villager))),
				Optional.of(new ItemCost(Items.BOOK)),
				result, 0, 12, TRADE_XP[Math.min(villagerLevel, 5) - 1], 0.2F));

		List<Learned> updated = new ArrayList<>(learned);
		updated.add(newBook);
		villager.setAttached(LEARNED, updated);

		if (!player.hasInfiniteMaterials()) held.shrink(1);

		level.playSound(null, villager.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1.0F, 1.0F);
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.getX(), villager.getY() + 1.5, villager.getZ(),
				8, 0.3, 0.3, 0.3, 0.0);
		player.sendSystemMessage(Component.empty()
				.append("The librarian learned ")
				.append(Enchantment.getFullname(enchantment, enchLevel))
				.append(" [" + updated.size() + "/" + villagerLevel + " Learned]"), true);
	}

	/**
	 * Removes every enchanted-book offer that isn't one the librarian learned. Each learned book may match
	 * exactly one offer, so our own offers (which come first) survive and later vanilla ones go.
	 */
	public static void stripRandomBooks(Villager villager) {
		List<String> allowed = new ArrayList<>();
		for (Learned l : learnedOf(villager)) allowed.add(l.key());

		Iterator<MerchantOffer> it = villager.getOffers().iterator();
		while (it.hasNext()) {
			ItemStack result = it.next().getResult();
			if (!result.is(Items.ENCHANTED_BOOK)) continue;
			String key = bookKey(result);
			if (key == null || !allowed.remove(key)) it.remove();
		}
	}

	/** Re-prices learned books to match the librarian's current overlevel tier. */
	public static void refreshPrices(Villager villager) {
		List<Learned> learned = learnedOf(villager);
		if (learned.isEmpty()) return;

		int tier = tierOf(villager);
		List<String> allowed = new ArrayList<>();
		for (Learned l : learned) allowed.add(l.key());

		MerchantOffers offers = villager.getOffers();
		for (int i = 0; i < offers.size(); i++) {
			MerchantOffer old = offers.get(i);
			ItemStack result = old.getResult();
			if (!result.is(Items.ENCHANTED_BOOK)) continue;
			String key = bookKey(result);
			if (key == null || !allowed.remove(key)) continue; // not ours

			var entry = result.get(DataComponents.STORED_ENCHANTMENTS).entrySet().iterator().next();
			int want = Pricing.price(entry.getIntValue(), entry.getKey().is(EnchantmentTags.TREASURE), tier);
			if (old.getBaseCostA().count() != want) {
				offers.set(i, new MerchantOffer(
						new ItemCost(Items.EMERALD, want),
						Optional.of(new ItemCost(Items.BOOK)),
						result.copy(), old.getUses(), old.getMaxUses(), old.getXp(), old.getPriceMultiplier()));
			}
		}
	}

	/** "namespace:id@level" for a single-enchantment book, otherwise null. */
	private static String bookKey(ItemStack book) {
		ItemEnchantments e = book.get(DataComponents.STORED_ENCHANTMENTS);
		if (e == null || e.size() != 1) return null;
		var en = e.entrySet().iterator().next();
		return en.getKey().getRegisteredName() + "@" + en.getIntValue();
	}

	private static void msg(ServerPlayer player, String text) {
		player.sendSystemMessage(Component.literal(text), true);
	}
}
