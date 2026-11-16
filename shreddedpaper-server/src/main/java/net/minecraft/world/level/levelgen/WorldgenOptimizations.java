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
    private static final String COLUMN_NOISE = System.getProperty("shreddedpaper.columnNoise");
    private static final String CELL_INTERPOLATION = System.getProperty("shreddedpaper.cellInterpolation");
    private static final String AQUIFER_CENTRE_CACHE = System.getProperty("shreddedpaper.aquiferCentreCache");
    private static final String CELL_FUSION = System.getProperty("shreddedpaper.cellFusion");

    public static volatile Boolean forcedSurface;
    public static volatile Boolean forcedBiome;
    public static volatile Boolean forcedColumnNoise;
    public static volatile Boolean forcedCellInterpolation;
    public static volatile Boolean forcedCellFusion;
    public static volatile Boolean forcedAquiferCentreCache;

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

    public static boolean columnNoise() {
        Boolean forced = forcedColumnNoise;
        if (forced != null) return forced;
        if (COLUMN_NOISE != null) return Boolean.parseBoolean(COLUMN_NOISE);
        ShreddedPaperConfiguration config = ShreddedPaperConfiguration.get();
        return config != null && config.optimizations.columnNoise;
    }

    public static boolean aquiferCentreCache() {
        Boolean forced = forcedAquiferCentreCache;
        if (forced != null) return forced;
        if (AQUIFER_CENTRE_CACHE != null) return Boolean.parseBoolean(AQUIFER_CENTRE_CACHE);
        ShreddedPaperConfiguration config = ShreddedPaperConfiguration.get();
        return config != null && config.optimizations.aquiferCentreCache;
    }

    public static boolean cellFusion() {
        Boolean forced = forcedCellFusion;
        if (forced != null) return forced;
        if (CELL_FUSION != null) return Boolean.parseBoolean(CELL_FUSION);
        ShreddedPaperConfiguration config = ShreddedPaperConfiguration.get();
        return config != null && config.optimizations.cellFusion;
    }

    public static boolean cellInterpolation() {
        Boolean forced = forcedCellInterpolation;
        if (forced != null) return forced;
        if (CELL_INTERPOLATION != null) return Boolean.parseBoolean(CELL_INTERPOLATION);
        ShreddedPaperConfiguration config = ShreddedPaperConfiguration.get();
        return config != null && config.optimizations.cellInterpolation;
    }
}
