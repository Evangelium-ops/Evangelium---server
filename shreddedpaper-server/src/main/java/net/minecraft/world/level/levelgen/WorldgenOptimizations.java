package net.minecraft.world.level.levelgen;

import io.multipaper.shreddedpaper.config.ShreddedPaperConfiguration;

/**
 * Knowledge - switches of the chunk generation speed-ups in surface rules and biome lookup (shreddedpaper.yml
 * optimizations section). A -Dshreddedpaper.<name>=true/false property wins over the config; the forced fields are test hooks
 * that win over both. All of them give bit-identical terrain; the switches exist to compare and to back out.
 */
public final class WorldgenOptimizations {
    private static final String SURFACE = System.getProperty("shreddedpaper.surfaceRuleCompiler");
    private static final String BIOME = System.getProperty("shreddedpaper.biomeLookupCache");

    public static volatile Boolean forcedSurface;
    public static volatile Boolean forcedBiome;

    private WorldgenOptimizations() {
    }

    public static boolean surfaceRules() {
        Boolean forced = forcedSurface;
        if (forced != null) return forced;
        if (SURFACE != null) return Boolean.parseBoolean(SURFACE);
        ShreddedPaperConfiguration config = ShreddedPaperConfiguration.get();
        return config != null && config.optimizations.surfaceRuleCompiler;
    }

    public static boolean biome() {
        Boolean forced = forcedBiome;
        if (forced != null) return forced;
        if (BIOME != null) return Boolean.parseBoolean(BIOME);
        ShreddedPaperConfiguration config = ShreddedPaperConfiguration.get();
        return config != null && config.optimizations.biomeLookupCache;
    }
}
