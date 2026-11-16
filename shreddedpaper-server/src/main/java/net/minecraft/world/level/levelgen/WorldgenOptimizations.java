package net.minecraft.world.level.levelgen;

import io.multipaper.shreddedpaper.config.ShreddedPaperConfiguration;
import io.multipaper.shreddedpaper.util.DevSwitch;

/**
 * Knowledge - the chunk generation speed-ups, always on (DevSwitch names for developer benchmarks only); the forced fields are
 * test hooks that win over that. All of them give bit-identical terrain.
 */
public final class WorldgenOptimizations {
    private static final boolean SURFACE = DevSwitch.on("surface-rule-compiler");
    private static final boolean BIOME = DevSwitch.on("biome-lookup-cache");
    private static final boolean COLUMN_NOISE = DevSwitch.on("column-noise");
    private static final boolean CELL_INTERPOLATION = DevSwitch.on("cell-interpolation");
    private static final boolean AQUIFER_CENTRE_CACHE = DevSwitch.on("aquifer-centre-cache");
    private static final boolean CELL_FUSION = DevSwitch.on("cell-fusion");

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
        return SURFACE && ShreddedPaperConfiguration.get() != null;
    }

    public static boolean biome() {
        Boolean forced = forcedBiome;
        if (forced != null) return forced;
        return BIOME && ShreddedPaperConfiguration.get() != null;
    }

    public static boolean columnNoise() {
        Boolean forced = forcedColumnNoise;
        if (forced != null) return forced;
        return COLUMN_NOISE && ShreddedPaperConfiguration.get() != null;
    }

    public static boolean aquiferCentreCache() {
        Boolean forced = forcedAquiferCentreCache;
        if (forced != null) return forced;
        return AQUIFER_CENTRE_CACHE && ShreddedPaperConfiguration.get() != null;
    }

    public static boolean cellFusion() {
        Boolean forced = forcedCellFusion;
        if (forced != null) return forced;
        return CELL_FUSION && ShreddedPaperConfiguration.get() != null;
    }

    public static boolean cellInterpolation() {
        Boolean forced = forcedCellInterpolation;
        if (forced != null) return forced;
        return CELL_INTERPOLATION && ShreddedPaperConfiguration.get() != null;
    }
}
