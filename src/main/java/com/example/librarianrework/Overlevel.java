package com.example.librarianrework;

/**
 * Overlevel maths, shared by server and client.
 * Uses dynamic formulas so you can easily change the max level or scaling curves.
 */
public final class Overlevel {
    public static final int MASTER_LEVEL = 5;
    public static final int MASTER_XP = 250; // Vanilla total at Master
    public static final int MAX_TIER = 10;   // Number of overlevel tiers (Levels 6 to 15)

    private Overlevel() {}

    /**
     * Dynamic formula for cumulative XP required to reach a given overlevel tier.
     * Change the multiplier (10.0) or exponent (1.6) here to instantly rescale the curve.
     */
    public static long cumulative(int tier) {
        int clampedTier = Math.max(0, Math.min(tier, MAX_TIER));
        long totalOverXp = 0;
        
        // Sums up the delta XP needed for each step from 1 up to the target tier
        for (int t = 1; t <= clampedTier; t++) {
            // Maps tier 1 to level 6, tier 2 to level 7, etc.
            int actualLevel = MASTER_LEVEL + t;
            totalOverXp += Math.round(10.0 * Math.pow(actualLevel, 1.6));
        }
        return totalOverXp;
    }

    /** Current overlevel tier (0 = plain Master, MAX_TIER = max rank) for a villager's total XP. */
    public static int tier(int villagerXp) {
        long over = Math.max(0, villagerXp - MASTER_XP);
        int t = 0;
        while (t < MAX_TIER && over >= cumulative(t + 1)) {
            t++;
        }
        return t;
    }

    /** XP needed to go from this tier to the next. */
    public static long needed(int tier) {
        int clampedTier = Math.max(0, Math.min(tier, MAX_TIER - 1));
        int actualLevel = MASTER_LEVEL + clampedTier + 1;
        return Math.round(10.0 * Math.pow(actualLevel, 1.6));
    }

    /** XP gathered towards the next tier. */
    public static long progress(int villagerXp, int tier) {
        return Math.max(0, Math.max(0, villagerXp - MASTER_XP) - cumulative(tier));
    }

    /**
     * Maps overlevel progress onto vanilla's Journeyman->Expert range (150..250) 
     * so the unmodified vanilla bar renderer draws the right fill.
     */
    public static int barXp(int villagerXp) {
        int t = tier(villagerXp);
        if (t >= MAX_TIER) return 250;
        
        long req = needed(t);
        if (req <= 0) return 250;
        
        long prog = progress(villagerXp, t);
        return 150 + (int) (100L * prog / req);
    }

    /** Scales the "XP you would gain" preview the same way. */
    public static int barFutureXp(int villagerXp, int gain) {
        int t = tier(villagerXp);
        if (t >= MAX_TIER) return 0;
        
        long req = needed(t);
        if (req <= 0) return 0;
        
        return (int) (100L * gain / req);
    }

    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    public static String roman(int tier) {
        return ROMAN[Math.max(0, Math.min(tier, MAX_TIER))];
    }
}