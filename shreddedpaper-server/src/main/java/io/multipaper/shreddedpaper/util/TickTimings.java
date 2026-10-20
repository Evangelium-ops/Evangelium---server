package io.multipaper.shreddedpaper.util;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Knowledge fork: where the time of a tick goes, logged every N ticks when -Dshreddedpaper.timings=N is set (off otherwise, and then
 * every call here returns at once). Main thread: the part before the worlds tick, the wait for the worlds, the part after them. Worlds:
 * the whole world tick, the region phase as seen from outside (wall time), the sum and the largest of the region ticks inside it, the
 * time regions waited for a neighbour's lock, the parallel tracker and flush phases. Averages per tick in milliseconds.
 */
public final class TickTimings {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    public static final int INTERVAL = Integer.getInteger("shreddedpaper.timings", 0);

    public enum Phase {
        TICK, MAIN_BEFORE_WORLDS, WAIT_FOR_WORLDS, MAIN_AFTER_WORLDS, WORLD, REGIONS_WALL, REGION_SUM, REGION_LARGEST, REGION_LOCK_WAIT, TRACK, FLUSH, PHASED
    }

    private static final AtomicLong[] TOTAL = new AtomicLong[Phase.values().length];
    private static final AtomicLong[] MAX = new AtomicLong[Phase.values().length];
    private static final AtomicLong REGIONS = new AtomicLong();
    private static final AtomicLong LARGEST_THIS_TICK = new AtomicLong();
    private static long ticks;
    public static final AtomicLong OWNER_CHUNKS = new AtomicLong();
    public static final AtomicLong HELPER_CHUNKS = new AtomicLong();

    static {
        for (int i = 0; i < TOTAL.length; i++) {
            TOTAL[i] = new AtomicLong();
            MAX[i] = new AtomicLong();
        }
    }

    private TickTimings() {
    }

    public static boolean enabled() {
        return INTERVAL > 0;
    }

    public static void add(Phase phase, long nanos) {
        if (INTERVAL <= 0) {
            return;
        }
        TOTAL[phase.ordinal()].addAndGet(nanos);
        MAX[phase.ordinal()].accumulateAndGet(nanos, Math::max);
    }

    /** One region tick (inside the lock). */
    public static void region(long nanos) {
        if (INTERVAL <= 0) {
            return;
        }
        REGIONS.incrementAndGet();
        TOTAL[Phase.REGION_SUM.ordinal()].addAndGet(nanos);
        LARGEST_THIS_TICK.accumulateAndGet(nanos, Math::max);
    }

    /** The region phase of one world is over: its largest region tick counts toward REGION_LARGEST. */
    public static void regionsDone() {
        if (INTERVAL <= 0) {
            return;
        }
        add(Phase.REGION_LARGEST, LARGEST_THIS_TICK.getAndSet(0));
    }

    /** Called by the main thread at the end of every tick. */
    public static void endTick(long tickNanos) {
        if (INTERVAL <= 0) {
            return;
        }
        add(Phase.TICK, tickNanos);
        if (++ticks % INTERVAL != 0) {
            return;
        }
        StringBuilder line = new StringBuilder("tick timings over ").append(INTERVAL).append(" ticks (avg / max ms):");
        for (Phase p : Phase.values()) {
            long total = TOTAL[p.ordinal()].getAndSet(0);
            long max = MAX[p.ordinal()].getAndSet(0);
            line.append(' ').append(p.name().toLowerCase()).append(' ')
                    .append(String.format("%.2f/%.1f", total / 1e6 / INTERVAL, max / 1e6));
        }
        line.append(" regions/tick ").append(String.format("%.1f", REGIONS.getAndSet(0) / (double) INTERVAL));
        line.append(" chunks by owner/helpers ").append(OWNER_CHUNKS.getAndSet(0)).append('/').append(HELPER_CHUNKS.getAndSet(0));
        LOGGER.info(line.toString());
    }
}
