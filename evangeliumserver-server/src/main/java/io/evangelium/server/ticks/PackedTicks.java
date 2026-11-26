package io.evangelium.server.ticks;

import com.mojang.logging.LogUtils;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.TickPriority;
import org.slf4j.Logger;

/**
 * Switches and helpers for the packed scheduled-tick storage (LevelChunkTicks / ProtoChunkTicks / PackedTickList).
 *
 * A pending tick of one chunk is stored as one "word" (long) plus a delay (int, saved ticks) or a trigger tick and a sub-tick order
 * (two longs, the live queue):
 * <pre>
 *   bits 32..63  y (full int)
 *   bits 28..31  x & 15
 *   bits 24..27  z & 15
 *   bits 21..23  priority ordinal (TickPriority.ordinal(), 0..6)
 *   bits  0..20  index into the container's type table (Block / Fluid)
 * </pre>
 * The chunk (x >> 4, z >> 4) is the same for all ticks of a container; a container that ever gets a tick of another chunk switches
 * to the old object structure (never happens in vanilla code paths, but keeps every input exact).
 */
public final class PackedTicks {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    /** -Devangeliumserver.packedTicks=false: old object structure everywhere. */
    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("evangeliumserver.packedTicks"));
    /** -Devangeliumserver.packedTicksIO=false: chunk NBT block_ticks/fluid_ticks through the vanilla codecs (default: same as ENABLED). */
    public static final boolean IO = ENABLED && !"false".equalsIgnoreCase(System.getProperty("evangeliumserver.packedTicksIO"));
    /** -Devangeliumserver.packedTicksVerify=true: run the old structure side by side and count every difference. */
    public static final boolean VERIFY = ENABLED && Boolean.getBoolean("evangeliumserver.packedTicksVerify");

    public static final int PRI_SHIFT = 21;
    public static final long PRI_MASK = 7L << PRI_SHIFT;
    public static final long TYPE_MASK = (1L << PRI_SHIFT) - 1;
    public static final int MAX_TYPES = 1 << PRI_SHIFT;
    public static final long KEY_MASK = ~PRI_MASK;

    private static final TickPriority[] PRIORITIES = TickPriority.values();

    private PackedTicks() {
    }

    public static long word(int x, int y, int z, int priorityOrdinal, int typeIndex) {
        return ((long) y << 32) | ((long) (x & 15) << 28) | ((long) (z & 15) << 24) | ((long) priorityOrdinal << PRI_SHIFT) | typeIndex;
    }

    public static int y(long word) {
        return (int) (word >> 32);
    }

    public static int localX(long word) {
        return (int) (word >>> 28) & 15;
    }

    public static int localZ(long word) {
        return (int) (word >>> 24) & 15;
    }

    public static int priorityOrdinal(long word) {
        return (int) ((word & PRI_MASK) >>> PRI_SHIFT);
    }

    public static TickPriority priority(long word) {
        return PRIORITIES[priorityOrdinal(word)];
    }

    public static int typeIndex(long word) {
        return (int) (word & TYPE_MASK);
    }

    public static BlockPos pos(long word, int chunkX, int chunkZ) {
        return new BlockPos((chunkX << 4) | localX(word), y(word), (chunkZ << 4) | localZ(word));
    }

    // ---------------------------------------------------------------- verify

    public static final AtomicLong VERIFY_CHECKS = new AtomicLong();
    public static final AtomicLong VERIFY_FIRED = new AtomicLong();
    public static final AtomicLong VERIFY_MISMATCHES = new AtomicLong();
    /** Firing lists that differ only inside groups of ticks with the same (priority, sub-tick order) from different chunks. */
    public static final AtomicLong VERIFY_TIES = new AtomicLong();
    private static final AtomicLong LOGGED = new AtomicLong();

    public static void check() {
        VERIFY_CHECKS.incrementAndGet();
    }

    public static void mismatch(String what) {
        long n = VERIFY_MISMATCHES.incrementAndGet();
        if (LOGGED.incrementAndGet() <= 50) {
            LOGGER.error("[packedTicksVerify] MISMATCH #{}: {}", n, what, new Throwable("where"));
        }
    }

    private static final AtomicLong LAST_LOG = new AtomicLong(Long.MIN_VALUE / 2);

    public static void maybeLog(long gameTime) {
        long last = LAST_LOG.get();
        if (gameTime - last >= 1200L && LAST_LOG.compareAndSet(last, gameTime)) {
            LOGGER.info("[packedTicksVerify] {}", summary());
        }
    }

    public static String summary() {
        return "packedTicksVerify checks=" + VERIFY_CHECKS.get() + " fired=" + VERIFY_FIRED.get() + " mismatches=" + VERIFY_MISMATCHES.get() + " crossChunkTies=" + VERIFY_TIES.get();
    }

    public static boolean same(ScheduledTick<?> a, ScheduledTick<?> b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.type() == b.type() && a.pos().equals(b.pos()) && a.triggerTick() == b.triggerTick() && a.priority() == b.priority()
            && a.subTickOrder() == b.subTickOrder();
    }

    public static boolean same(SavedTick<?> a, SavedTick<?> b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.type() == b.type() && a.pos().equals(b.pos()) && a.delay() == b.delay() && a.priority() == b.priority();
    }

    public static boolean sameSaved(java.util.List<? extends SavedTick<?>> a, java.util.List<? extends SavedTick<?>> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!same(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean sameScheduled(java.util.List<? extends ScheduledTick<?>> a, java.util.List<? extends ScheduledTick<?>> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!same(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when the two firing lists of one region tick differ only by the order of ticks that tie on (priority, sub-tick order) and
     * sit in different chunks. The level queue orders chunk heads by (priority, sub-tick order) only; a tie between two chunks is
     * broken by the region's next-tick map iteration order, which depends on the order of its puts. Intra-region helper threads
     * schedule into different chunks at the same time (that is also how two ticks get the same sub-tick order: the per-level counter
     * is a plain long++), so the put order, and with it the tie order, is decided by thread timing in the old code as well; the shadow
     * and the real map see the same puts in a different interleaving. Within one chunk ties are resolved by the PriorityQueue-identical
     * heap, so a same-chunk tie must still match exactly.
     */
    public static boolean sameExceptCrossChunkTies(java.util.List<? extends ScheduledTick<?>> actual, java.util.List<? extends ScheduledTick<?>> expected) {
        if (actual.size() != expected.size()) {
            return false;
        }
        int n = actual.size();
        int i = 0;
        while (i < n) {
            ScheduledTick<?> e = expected.get(i);
            int j = i + 1;
            while (j < n && expected.get(j).priority() == e.priority() && expected.get(j).subTickOrder() == e.subTickOrder()) {
                j++;
            }
            // group [i, j) of expected: actual must hold the same ticks there, each tick with the same key, and the per-chunk order kept
            java.util.List<ScheduledTick<?>> a = new java.util.ArrayList<>(actual.subList(i, j));
            java.util.List<ScheduledTick<?>> b = new java.util.ArrayList<>(expected.subList(i, j));
            for (ScheduledTick<?> t : a) {
                if (t.priority() != e.priority() || t.subTickOrder() != e.subTickOrder()) {
                    return false;
                }
            }
            if (!sameScheduled(byChunk(a), byChunk(b))) {
                return false;
            }
            i = j;
        }
        return true;
    }

    /** Stable sort by chunk: keeps the order inside each chunk, so a same-chunk reorder is still a difference. */
    private static java.util.List<ScheduledTick<?>> byChunk(java.util.List<ScheduledTick<?>> list) {
        list.sort(java.util.Comparator.comparingLong(t -> net.minecraft.world.level.ChunkPos.asLong(t.pos())));
        return list;
    }

    public static String describe(Object o) {
        return Objects.toString(o);
    }
}
