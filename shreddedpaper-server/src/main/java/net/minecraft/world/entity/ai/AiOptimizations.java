package net.minecraft.world.entity.ai;

import io.multipaper.shreddedpaper.util.DevSwitch;

/**
 * Knowledge - the mob AI speed-ups. All give the same mob decisions and are always on; for developer before/after runs only, the hidden
 * -Devangelium.dev.off=<names> turns one off (names: secondary-poi-skip, block-scan-palette-check, brain-loops, poi-no-candidate-memo,
 * search-constants, large-path-type-cache). Test hooks: -Dshreddedpaper.verifyAi=true recomputes every shortcut the vanilla way and
 * throws on a difference; -Dshreddedpaper.aiPlantBug=<name> breaks one shortcut on purpose to prove verifyAi catches it.
 */
public final class AiOptimizations {
    private static final boolean SECONDARY_POI_SKIP = DevSwitch.on("secondary-poi-skip");
    private static final boolean BLOCK_SCAN_PALETTE_CHECK = DevSwitch.on("block-scan-palette-check");
    private static final boolean BRAIN_LOOPS = DevSwitch.on("brain-loops");
    private static final boolean POI_NO_CANDIDATE_MEMO = DevSwitch.on("poi-no-candidate-memo");
    private static final boolean SEARCH_CONSTANTS = DevSwitch.on("search-constants");
    private static final boolean LARGE_PATH_TYPE_CACHE = DevSwitch.on("large-path-type-cache");
    public static final boolean VERIFY = Boolean.getBoolean("shreddedpaper.verifyAi");
    public static final String PLANT = System.getProperty("shreddedpaper.aiPlantBug", "");

    private AiOptimizations() {
    }

    /** Villagers whose profession has no secondary work blocks skip the 9x5x9 block scan for them. */
    public static boolean secondaryPoiSkip() {
        return SECONDARY_POI_SKIP;
    }

    /** Zombies' turtle-egg search skips the block walk when no section palette in the box can hold the block. */
    public static boolean blockScanPaletteCheck() {
        return BLOCK_SCAN_PALETTE_CHECK;
    }

    /** Brain internals: plain loops, running pass over stateful behaviours, memory reads and checks without extra objects. */
    public static boolean brainLoops() {
        return BRAIN_LOOPS;
    }

    /** AcquirePoi skips a search that found nothing from the same block while no POI changed, loaded or unloaded since. */
    public static boolean poiNoCandidateMemo() {
        return POI_NO_CANDIDATE_MEMO;
    }

    /** Path search reads the mob's step height and path costs once per search. */
    public static boolean searchConstants() {
        return SEARCH_CONSTANTS;
    }

    /** The level path-type cache has 32768 slots instead of 4096. */
    public static boolean largePathTypeCache() {
        return LARGE_PATH_TYPE_CACHE;
    }

    public static void mismatch(String what) {
        throw new IllegalStateException("verifyAi mismatch: " + what);
    }
}
