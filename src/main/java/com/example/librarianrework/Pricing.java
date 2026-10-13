package com.example.librarianrework;

/** Emerald prices of taught books. Same numbers as vanilla's enchanted-book trade roll. */
public final class Pricing {
	private Pricing() {}

	/** Cheapest vanilla roll: 2 + 3L (x2 for treasure), capped at 64. */
	public static int min(int enchantLevel, boolean treasure) {
		int p = 2 + 3 * enchantLevel;
		return Math.min(treasure ? p * 2 : p, 64);
	}

	/** Worst vanilla roll: 6 + 13L (x2 for treasure), capped at 64. */
	public static int max(int enchantLevel, boolean treasure) {
		int p = 6 + 13 * enchantLevel;
		return Math.min(treasure ? p * 2 : p, 64);
	}

	/** Starts at the max price (Master) and falls linearly to the min price at Master X. */
	public static int price(int enchantLevel, boolean treasure, int overlevelTier) {
		int hi = max(enchantLevel, treasure);
		int lo = min(enchantLevel, treasure);
		double t = Math.min(overlevelTier, Overlevel.MAX_TIER) / (double) Overlevel.MAX_TIER;
		return (int) Math.round(hi - (hi - lo) * t);
	}
}
