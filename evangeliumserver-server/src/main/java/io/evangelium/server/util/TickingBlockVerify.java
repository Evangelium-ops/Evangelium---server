package io.evangelium.server.util;

import ca.spottedleaf.moonrise.common.list.ShortList;
import com.mojang.logging.LogUtils;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Evangelium - verify mode of the ticking-block set (benchmarks only): -Devangeliumserver.verifyTickingBlocks=true keeps Moonrise's
 * old ShortList next to the new set, compares the two after every change of the set of ticking positions of a section (block
 * change or recount), and from the random tick path also compares the set with the block states themselves. Mismatches are logged
 * (first 20 in full) and counted; a TICKSET_VERIFY summary line is logged every 30 s.
 * -Devangeliumserver.tickingBlockSetPlantBug=N (verify self-test only) drops every Nth add from the new set.
 */
public final class TickingBlockVerify {

    public static final boolean VERIFY = Boolean.getBoolean("evangeliumserver.verifyTickingBlocks");
    private static final java.util.Map<Object, ShortList> OLD_LISTS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>()); // section -> Moonrise list (identity: sections do not override equals)
    public static final int PLANT_BUG = VERIFY ? Integer.getInteger("evangeliumserver.tickingBlockSetPlantBug", 0) : 0;

    private static final AtomicLong COMPARES = new AtomicLong();
    private static final AtomicLong MISMATCHES = new AtomicLong();
    private static final AtomicLong TRUTH = new AtomicLong();
    private static final AtomicLong TRUTH_MISMATCHES = new AtomicLong();
    private static final AtomicLong POSITIONS = new AtomicLong();
    private static final AtomicLong ADDS = new AtomicLong();
    private static volatile long lastLog = System.nanoTime();

    private TickingBlockVerify() {
    }

    /** Verify mode: the Moonrise list kept next to a section's new set. */
    public static ShortList oldList(final Object section) {
        return OLD_LISTS.computeIfAbsent(section, k -> new ShortList());
    }

    public static boolean plantBug() {
        return ADDS.incrementAndGet() % PLANT_BUG == 0;
    }

    /** Old list and new set must hold the same positions, and as many as the section's mirrored size and ticking block count. */
    public static void compare(final ShortList old, final Object data, final int size, final int tickingBlockCount, final String where) {
        if (!TickingBlockSet.ENABLED || old == null) {
            return;
        }
        COMPARES.incrementAndGet();
        final int oldSize = old.size();
        final int newSize = TickingBlockSet.count(data, size);
        boolean ok = oldSize == newSize && size == newSize && tickingBlockCount == newSize;
        int missing = -1;
        if (ok) {
            for (int i = 0; i < oldSize; ++i) {
                final int pos = old.getRaw(i) & 0xFFFF;
                if (!TickingBlockSet.contains(data, size, pos)) {
                    ok = false;
                    missing = pos;
                    break;
                }
            }
        }
        if (ok && (COMPARES.get() & 15) == 0) {   // every 16th compare: every rank picks a distinct member (the random tick pick)
            final java.util.BitSet seen = new java.util.BitSet(4096);
            for (int r = 0; r < size && ok; ++r) {
                final int pos = TickingBlockSet.get(data, size, r);
                if (pos < 0 || seen.get(pos) || !TickingBlockSet.contains(data, size, pos)) {
                    ok = false;
                    missing = 10000 + r;
                }
                if (pos >= 0) seen.set(pos);
            }
        }
        POSITIONS.addAndGet(oldSize);
        if (!ok) {
            final long n = MISMATCHES.incrementAndGet();
            if (n <= 20) {
                LogUtils.getLogger().warn("TICKSET_MISMATCH {} where={} old={} new={} mirrored={} count={} missing={} bits={}", n, where, oldSize, newSize, size, tickingBlockCount, missing, TickingBlockSet.isBits(data));
            }
        }
        maybeLog();
    }

    /** The set against the block states: exactly the positions whose state is randomly ticking. */
    public static void truth(final PalettedContainer<BlockState> states, final Object data, final int size, final int tickingBlockCount) {
        TRUTH.incrementAndGet();
        int n = 0;
        int firstBad = -1;
        for (int pos = 0; pos < 4096; ++pos) {
            final boolean ticking = states.get(pos).isRandomlyTicking();
            if (ticking) ++n;
            if (ticking != TickingBlockSet.contains(data, size, pos) && firstBad < 0) firstBad = pos;
        }
        final int setCount = TickingBlockSet.count(data, size);
        if (firstBad >= 0 || n != tickingBlockCount || setCount != n || size != n) {
            final long m = TRUTH_MISMATCHES.incrementAndGet();
            if (m <= 20) {
                LogUtils.getLogger().warn("TICKSET_TRUTH_MISMATCH {} states={} set={} size={} count={} firstBadPos={} state={}", m, n, setCount, size, tickingBlockCount, firstBad, firstBad < 0 ? "-" : states.get(firstBad));
            }
        }
        maybeLog();
    }

    private static void maybeLog() {
        final long now = System.nanoTime();
        if (now - lastLog > 30_000_000_000L) {
            lastLog = now;
            LogUtils.getLogger().info("TICKSET_VERIFY compares={} positions={} mismatches={} truthChecks={} truthMismatches={}",
                COMPARES.get(), POSITIONS.get(), MISMATCHES.get(), TRUTH.get(), TRUTH_MISMATCHES.get());
        }
    }
}
