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
        TICK, MAIN_BEFORE_WORLDS, WAIT_FOR_WORLDS, MAIN_AFTER_WORLDS, WORLD, REGIONS_WALL, REGION_SUM, REGION_LARGEST, REGION_LOCK_WAIT, TRACK, FLUSH, PHASED,
        // joins (one sample per join, not per tick): main thread PrepareSpawnTask.start / poll of the preload, registry + tag sync,
        // the whole spawn on the region thread and its parts: ServerPlayer constructor (stats, advancements read + listeners),
        // the player file read + load, placeNewPlayer, PlayerJoinEvent inside it, the first full advancement packet
        JOIN_MAIN, JOIN_REGSYNC, JOIN_SPAWN, JOIN_CTOR, JOIN_STATS, JOIN_ADV_READ, JOIN_ADV_LISTEN, JOIN_LOAD, JOIN_PLACE, JOIN_EVENT, JOIN_ADV_PACKET,
        // Evangelium - random ticks (+ snow and ice) of all regions, wall time of each region's random tick phase, summed
        RANDOM_TICKS
    }

    private static final AtomicLong JOINS = new AtomicLong();

    /** nanoTime when timings are on, else 0 (so callers pay nothing when off). */
    public static long start() {
        return INTERVAL > 0 ? System.nanoTime() : 0L;
    }

    /** Adds the time since {@link #start()} to the phase (no-op when timings are off). */
    public static void since(Phase phase, long start) {
        if (INTERVAL > 0 && start != 0L) {
            add(phase, System.nanoTime() - start);
            if (phase == Phase.JOIN_SPAWN) {
                JOINS.incrementAndGet();
            }
        }
    }

    private static final AtomicLong[] TOTAL = new AtomicLong[Phase.values().length];
    private static final AtomicLong[] MAX = new AtomicLong[Phase.values().length];
    private static final AtomicLong REGIONS = new AtomicLong();
    private static final AtomicLong LARGEST_THIS_TICK = new AtomicLong();
    private static long ticks;
    public static final AtomicLong OWNER_CHUNKS = new AtomicLong();
    public static final AtomicLong HELPER_CHUNKS = new AtomicLong();
    public static final AtomicLong BATCHED = new AtomicLong();      // Evangelium - neighbour regions ticked under a moved lock
    public static final AtomicLong SLOT_WAITS = new AtomicLong();   // Evangelium - dispatcher slots that found no free region and waited

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
        line.append(" joins ").append(JOINS.getAndSet(0));
        line.append(" regions/tick ").append(String.format("%.1f", REGIONS.getAndSet(0) / (double) INTERVAL));
        line.append(" chunks by owner/helpers ").append(OWNER_CHUNKS.getAndSet(0)).append('/').append(HELPER_CHUNKS.getAndSet(0));
        line.append(" batched ").append(BATCHED.getAndSet(0)).append(" slotwaits ").append(SLOT_WAITS.getAndSet(0));
        LOGGER.info(line.toString());
    }
}
