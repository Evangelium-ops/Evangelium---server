package io.multipaper.shreddedpaper.spawn;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSpawnOverride;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressStructure;

/**
 * Evangelium: natural spawn attempts that provably spawn nothing are not made.
 *
 * <p>Every spawning chunk makes one attempt per category every tick (most categories). An attempt picks a random start, then walks up to
 * 3 x 4 random steps at the start's height, each at most {@link SpawnPlayerProbe#STEP_REACH} blocks from the start on each horizontal
 * axis. The mob is chosen from the spawn list at the first usable step (biome spawn list, or a structure's override) and every step's
 * spot must pass the mob's placement test. Two facts make most attempts of some categories certain failures, known from a per-chunk
 * summary of the 5x5 chunks around it (every step and every biome sample of an attempt started in the middle chunk lies inside them):
 * <ul>
 *   <li><b>no spawn list</b>: no biome in those chunks (at the start's height, +-1 section for the biome blending) has a non-empty spawn
 *       list for the category, and none of those chunks references a structure that overrides the category's list (or a fortress for
 *       monsters). Then the first usable step finds an empty list and every group of the attempt ends there: nothing can spawn.
 *       Typical: axolotls outside lush caves, fish and squid away from oceans and rivers.</li>
 *   <li><b>no water</b>: every mob that can ever be listed for the category (all biomes, all structure overrides) is placed IN_WATER (its
 *       spot must hold water), and no block of those chunks in the start's layer (every step is at the start's height) holds water
 *       (per-section layer masks, see {@link #waterLayers}). Typical: glow squid in dry caves, squid and fish next to a dry shore.</li>
 * </ul>
 * Skipping such an attempt changes nothing but the random numbers it would have drawn: the attempt had no other effect (with a
 * PreCreatureSpawnEvent listener the no-water rule is off, since that event would have been called for the picked mob). The summary is
 * only made when all 25 chunks are loaded (otherwise nothing is skipped). It is made again after any biome change in a loaded chunk, any
 * scanned layer of a loaded chunk getting its first water (water part only), and at least every {@link #MAX_AGE} ticks.
 *
 * <p>-Dshreddedpaper.spawnSkip=false: no skipping. -Dshreddedpaper.verifySpawnSkip=true: skippable attempts are still made, and a skipped
 * attempt that would have picked a mob (no spawn list) or found a valid spot (either rule) is counted and logged as a mismatch.
 */
public final class SpawnSkip {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("shreddedpaper.spawnSkip", "true"));
    public static final boolean VERIFY = Boolean.getBoolean("shreddedpaper.verifySpawnSkip");
    static final int MAX_AGE = Integer.getInteger("shreddedpaper.spawnSkipMaxAge", 1200);
    private static final int RETRY = 20;
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getClassLogger();

    /** Bumped when a loaded chunk's biomes change (fillbiome, plugins). */
    public static final AtomicInteger BIOME_EPOCH = new AtomicInteger();
    /** Bumped when a scanned layer of a loaded chunk gets its first water block. */
    public static final AtomicInteger WET_EPOCH = new AtomicInteger();

    public static final int NO_LIST = 1, NO_WATER = 2;

    private static final MobCategory[] CATS = MobCategory.values();

    /** Server-wide facts from the registries (biome spawn lists, structure overrides): fixed after startup. */
    private static final class Info {
        final Map<Biome, Integer> biomeMask = new IdentityHashMap<>();   // categories with a non-empty list in that biome
        final Map<Structure, Integer> structureMask = new IdentityHashMap<>();   // categories a structure overrides with a non-empty list
        int waterOnly;   // categories whose every possible mob is placed IN_WATER
        Structure fortress;
    }

    private static volatile Info info;

    private static Info info(ServerLevel level) {
        Info i = info;
        if (i != null) return i;
        synchronized (SpawnSkip.class) {
            if (info != null) return info;
            i = new Info();
            final java.util.List<java.util.Set<EntityType<?>>> types = new java.util.ArrayList<>();
            for (int c = 0; c < CATS.length; c++) types.add(new java.util.HashSet<>());
            final Registry<Biome> biomes = level.registryAccess().lookupOrThrow(Registries.BIOME);
            for (Biome biome : biomes) {
                int m = 0;
                for (MobCategory cat : CATS) {
                    final var list = biome.getMobSettings().getMobs(cat);
                    if (!list.isEmpty()) m |= 1 << cat.ordinal();
                    for (var w : list.unwrap()) types.get(cat.ordinal()).add(w.value().type());
                }
                i.biomeMask.put(biome, m);
            }
            final Registry<Structure> structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            for (Structure structure : structures) {
                int m = 0;
                for (Map.Entry<MobCategory, StructureSpawnOverride> e : structure.spawnOverrides().entrySet()) {
                    if (!e.getValue().spawns().isEmpty()) m |= 1 << e.getKey().ordinal();
                    for (var w : e.getValue().spawns().unwrap()) types.get(e.getKey().ordinal()).add(w.value().type());
                }
                i.structureMask.put(structure, m);
            }
            i.fortress = structures.getValue(net.minecraft.world.level.levelgen.structure.BuiltinStructures.FORTRESS);
            for (var w : NetherFortressStructure.FORTRESS_ENEMIES.unwrap()) types.get(MobCategory.MONSTER.ordinal()).add(w.value().type());
            for (int c = 0; c < CATS.length; c++) {
                boolean all = !types.get(c).isEmpty();
                for (EntityType<?> t : types.get(c)) all &= SpawnPlacements.getPlacementType(t) == SpawnPlacementTypes.IN_WATER;
                if (all) i.waterOnly |= 1 << c;
            }
            if (Boolean.getBoolean("shreddedpaper.spawnSkipPlant")) i.waterOnly |= 1 << MobCategory.MONSTER.ordinal(); // test hook for the verify mode: wrong rule
            LOGGER.info("natural spawn skip: categories placed only in water: {}", describe(i.waterOnly));
            info = i;
            return i;
        }
    }

    private static String describe(int mask) {
        final StringBuilder b = new StringBuilder();
        for (MobCategory c : CATS) if ((mask & (1 << c.ordinal())) != 0) b.append(b.isEmpty() ? "" : ",").append(c.getName());
        return b.toString();
    }

    /** Summary of the 5x5 chunks around one chunk. The spawn-list part is costly and only redone on biome changes or age; the water
     *  part is cheap (25 chunk lookups, one count per section) and is redone whenever any section of a loaded chunk got its first water. */
    public static final class Hood {
        final int[] mask;     // per section index of a start: categories that may have a non-empty list somewhere reachable
        final int biomeEpoch;
        final long tick;
        final boolean failed; // not all 25 chunks loaded: skip nothing, try again after RETRY ticks
        int[] wet;            // per section index: bit y = some of the 25 chunks may have water in layer y (null = one not loaded any more: no skip)
        int wetEpoch;

        Hood(int[] mask, int biomeEpoch, long tick, boolean failed) {
            this.mask = mask; this.biomeEpoch = biomeEpoch; this.tick = tick; this.failed = failed;
        }
    }

    private static int sectionMask(Info info, LevelChunkSection section) {
        int m = 0;
        for (MobCategory cat : CATS) {
            final int bit = 1 << cat.ordinal();
            if (section.getBiomes().maybeHas(h -> (info.biomeMask.getOrDefault(h.value(), -1) & bit) != 0)) m |= bit;
        }
        return m;
    }

    private static Hood compute(ServerLevel level, LevelChunk chunk, Info info, long now) {
        final int biomeEpoch = BIOME_EPOCH.get();   // read first: a change during the walk makes the next call redo it
        final int n = chunk.getSections().length;
        final int[] own = new int[n];
        int structures = 0;
        final int cx = chunk.getPos().x, cz = chunk.getPos().z;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                final LevelChunk c = dx == 0 && dz == 0 ? chunk : level.getChunkIfLoaded(cx + dx, cz + dz);
                if (c == null) {
                    return new Hood(null, biomeEpoch, now, true);
                }
                final LevelChunkSection[] sections = c.getSections();
                for (int s = 0; s < n && s < sections.length; s++) {
                    own[s] |= sectionMask(info, sections[s]);
                }
                for (Map.Entry<Structure, it.unimi.dsi.fastutil.longs.LongSet> e : c.getAllReferences().entrySet()) {
                    if (e.getValue().isEmpty()) continue;
                    structures |= info.structureMask.getOrDefault(e.getKey(), -1);
                    if (e.getKey() == info.fortress) structures |= 1 << MobCategory.MONSTER.ordinal();
                }
            }
        }
        final int[] mask = new int[n];
        for (int s = 0; s < n; s++) {
            mask[s] = own[s] | (s > 0 ? own[s - 1] : 0) | (s + 1 < n ? own[s + 1] : 0) | structures;
        }
        final Hood hood = new Hood(mask, biomeEpoch, now, false);
        computeWet(level, chunk, hood);
        return hood;
    }

    /** Layers of a section that may hold water (bit y). Scanned once per section object; LevelChunk.setBlockState adds bits (and bumps
     *  WET_EPOCH) when water appears in a layer without its bit; bits are never removed (a stale bit only means a skip is missed). */
    static int waterLayers(LevelChunkSection section) {
        int m = section.knowledge$waterLayers;
        if (m == -1) {
            m = 0;
            if (section.knowledge$waterCount != 0) {
                for (int y = 0; y < 16; y++) {
                    scan:
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (section.getBlockState(x, y, z).getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                                m |= 1 << y;
                                break scan;
                            }
                        }
                    }
                }
            }
            section.knowledge$waterLayers = m;
        }
        return m;
    }

    private static void computeWet(ServerLevel level, LevelChunk chunk, Hood hood) {
        final int wetEpoch = WET_EPOCH.get();   // read first, as above
        final int n = chunk.getSections().length;
        final int[] wet = new int[n];
        final int cx = chunk.getPos().x, cz = chunk.getPos().z;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                final LevelChunk c = dx == 0 && dz == 0 ? chunk : level.getChunkIfLoaded(cx + dx, cz + dz);
                if (c == null) {   // unloaded since: no water skips until the next full summary (conservative)
                    hood.wet = null;
                    hood.wetEpoch = wetEpoch;
                    return;
                }
                final LevelChunkSection[] sections = c.getSections();
                for (int s = 0; s < n && s < sections.length; s++) {
                    wet[s] |= waterLayers(sections[s]);
                }
            }
        }
        hood.wet = wet;
        hood.wetEpoch = wetEpoch;
    }

    /** 0 = the attempt may spawn something; NO_LIST or NO_WATER = it provably spawns nothing. */
    public static int cannotSpawn(ServerLevel level, LevelChunk chunk, MobCategory category, BlockPos start) {
        final Info info = info(level);
        final long now = level.getGameTime();
        Hood hood = chunk.knowledge$spawnHood;
        if (hood == null || hood.biomeEpoch != BIOME_EPOCH.get() || now - hood.tick >= (hood.failed ? RETRY : MAX_AGE) || now < hood.tick) {
            chunk.knowledge$spawnHood = hood = compute(level, chunk, info, now);
        }
        if (hood.failed) {
            return 0;
        }
        final int s = chunk.getSectionIndex(start.getY());
        if (s < 0 || s >= hood.mask.length) {
            return 0;
        }
        final int bit = 1 << category.ordinal();
        if ((hood.mask[s] & bit) == 0) {
            return NO_LIST;
        }
        if ((info.waterOnly & bit) != 0
                // a mob is picked in such an attempt, so PreCreatureSpawnEvent would be called at its steps: with a listener, make it
                && com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent.getHandlerList().getRegisteredListeners().length == 0) {
            if (hood.wetEpoch != WET_EPOCH.get()) {
                computeWet(level, chunk, hood);
            }
            if (hood.wet != null && (hood.wet[s] & (1 << (start.getY() & 15))) == 0) {
                return NO_WATER;
            }
        }
        return 0;
    }

    // ---------------------------------------------------------------------------------------------------------------- verify mode

    private static final ThreadLocal<int[]> VERIFYING = ThreadLocal.withInitial(() -> new int[1]);
    private static final java.util.concurrent.atomic.AtomicLong VERIFIED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong MISMATCHES = new java.util.concurrent.atomic.AtomicLong();

    public static void verifyBegin(int reason) {
        VERIFYING.get()[0] = reason;
    }

    public static void verifyEnd() {
        if (VERIFYING.get()[0] != 0) {
            VERIFYING.get()[0] = 0;
            final long n = VERIFIED.incrementAndGet();
            if ((n & 0x3FFFF) == 0) LOGGER.info("natural spawn skip verified {} skippable attempts, {} mismatches", n, MISMATCHES.get());
        }
    }

    /** A mob was picked (what = "pick") or a spot passed the placement checks (what = "spot") in the current attempt. */
    public static void verifyEvent(String what, MobCategory category, BlockPos pos) {
        final int reason = VERIFYING.get()[0];
        if (reason == 0 || (what.equals("pick") && reason != NO_LIST)) return;
        if (MISMATCHES.incrementAndGet() <= 50) {
            LOGGER.warn("natural spawn skip mismatch: {} attempt skipped as {} had a {} at {}", category.getName(), reason == NO_LIST ? "no-list" : "no-water", what, pos);
        }
    }
}
