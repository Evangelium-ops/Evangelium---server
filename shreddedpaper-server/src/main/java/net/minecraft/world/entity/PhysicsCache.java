package net.minecraft.world.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Knowledge - per-thread caches of block answers that entity movement asks again and again within one piece of tick work.
 *
 * <p>Validity rule (the "block change epoch"): every entry carries the thread's epoch at the time it was stored and is used only while
 * the epoch is unchanged. The epoch moves on every block change made by this thread (LevelChunk.setBlockState), at the start of every
 * region tick, at the start of every intra-region work unit (one chunk's work on a helper or the owner) and after every parallel phase.
 * Inside one such unit no other thread changes the blocks this thread can reach (ShreddedPaper locks the regions around the ticking
 * region; intra-region work units run only three chunks apart), so a cached answer equals a fresh one.
 */
public final class PhysicsCache {
    private static final ThreadLocal<PhysicsCache> LOCAL = ThreadLocal.withInitial(PhysicsCache::new);
    private static final int FLOW_SIZE = 4096; // power of two; a 16x16 chunk of a water sheet is 256 cells, a stream region a few thousand

    private int epoch;
    // tests: -Dshreddedpaper.physicsStats=true prints flow cache hits, misses and epoch bumps every 10 seconds
    private static final boolean STATS = Boolean.getBoolean("shreddedpaper.physicsStats");
    private static final java.util.concurrent.atomic.LongAdder HITS = new java.util.concurrent.atomic.LongAdder(), MISSES = new java.util.concurrent.atomic.LongAdder(),
        BUMPS = new java.util.concurrent.atomic.LongAdder(), BLOCK_BUMPS = new java.util.concurrent.atomic.LongAdder();
    static {
        if (STATS) {
            final Thread t = new Thread(() -> {
                while (true) {
                    try { Thread.sleep(10_000L); } catch (InterruptedException e) { return; }
                    System.out.println("PHYS_STATS flowHits=" + HITS.sumThenReset() + " flowMisses=" + MISSES.sumThenReset() + " bumps=" + BUMPS.sumThenReset() + " blockBumps=" + BLOCK_BUMPS.sumThenReset());
                }
            }, "PhysicsStats");
            t.setDaemon(true);
            t.start();
        }
    }

    /** A block changed on this thread (counted apart in the stats). */
    public static void blockChanged() {
        if (STATS) BLOCK_BUMPS.increment();
        bump();
    }
    private final long[] flowPos = new long[FLOW_SIZE];
    private final Object[] flowLevel = new Object[FLOW_SIZE];
    private final Object[] flowState = new Object[FLOW_SIZE];
    private final int[] flowStamp = new int[FLOW_SIZE]; // epoch, or the section's change counter
    private final Object[] flowOwner = new Object[FLOW_SIZE]; // null: epoch entry; else the LevelChunkSection the stamp belongs to
    private final Vec3[] flowValue = new Vec3[FLOW_SIZE];

    private PhysicsCache() {
    }

    private static PhysicsCache get() {
        if (Thread.currentThread() instanceof io.multipaper.shreddedpaper.threading.ShreddedPaperTickThread tick) {
            final Object cache = tick.knowledge$physicsCache;
            if (cache != null) return (PhysicsCache) cache;
            final PhysicsCache created = new PhysicsCache();
            tick.knowledge$physicsCache = created;
            return created;
        }
        return LOCAL.get();
    }

    /** This tick thread's cache (its epoch is read through {@link #epoch()}), or null on any other thread. */
    public static @org.jspecify.annotations.Nullable PhysicsCache tickCache() {
        if (Thread.currentThread() instanceof io.multipaper.shreddedpaper.threading.ShreddedPaperTickThread tick) {
            final Object cache = tick.knowledge$physicsCache;
            return cache != null ? (PhysicsCache) cache : get();
        }
        return null;
    }

    public int epoch() {
        return this.epoch;
    }

    /** A block changed on this thread, or a new unit of tick work starts: forget everything. */
    public static void bump() {
        if (STATS) BUMPS.increment();
        final PhysicsCache cache = get();
        if (++cache.epoch == -1) cache.epoch = 0; // -1 marks empty slots
    }

    /**
     * FluidState.getFlow(level, pos), cached (flowCache switch; verifyPhysics recomputes on every hit). getFlow reads the four
     * horizontal neighbours and the cells above and below them. For a cell not on the edge of its 16x16x16 section (section given)
     * all of those lie in the same section, so the entry is keyed on the section object and its block change counter and stays good
     * across ticks until a block of that section changes. Edge cells use this thread's epoch (see the class comment).
     */
    public static Vec3 flow(final Level level, final BlockPos pos, final FluidState state, final net.minecraft.world.level.chunk.@org.jspecify.annotations.Nullable LevelChunkSection section) {
        if (!PhysicsOptimizations.flowCache()) return state.getFlow(level, pos);
        final PhysicsCache cache = get();
        final int lx = pos.getX() & 15, ly = pos.getY() & 15, lz = pos.getZ() & 15;
        final boolean inner = section != null && lx != 0 && lx != 15 && ly != 0 && ly != 15 && lz != 0 && lz != 15;
        final Object owner = inner ? section : null;
        final int stamp = inner ? section.knowledge$changes : cache.epoch;
        final long key = pos.asLong();
        final int slot = (int) it.unimi.dsi.fastutil.HashCommon.mix(key) & (FLOW_SIZE - 1);
        if (cache.flowLevel[slot] == level && cache.flowPos[slot] == key && cache.flowState[slot] == state && cache.flowOwner[slot] == owner && cache.flowStamp[slot] == stamp) {
            final Vec3 cached = cache.flowValue[slot];
            if (STATS) HITS.increment();
            if (PhysicsOptimizations.VERIFY) {
                final Vec3 fresh = state.getFlow(level, pos);
                PhysicsOptimizations.check(cached.equals(fresh), "flow cache at " + pos + ": " + cached + " vs " + fresh, state);
            }
            return cached;
        }
        if (STATS) MISSES.increment();
        final Vec3 fresh = state.getFlow(level, pos);
        cache.flowStamp[slot] = stamp;
        cache.flowOwner[slot] = owner;
        cache.flowPos[slot] = key;
        cache.flowLevel[slot] = level;
        cache.flowState[slot] = state;
        cache.flowValue[slot] = PhysicsOptimizations.PLANT.equals("flow") && fresh.lengthSqr() > 0.0 ? fresh.scale(0.5) : fresh; // plant-bug test hook
        return fresh;
    }
}
