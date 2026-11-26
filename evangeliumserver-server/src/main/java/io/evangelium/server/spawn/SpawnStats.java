package io.evangelium.server.spawn;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.entity.MobCategory;

/**
 * Evangelium: developer counters for natural spawning (where attempts end, how long they take, what spawned where).
 * -Devangeliumserver.spawnStats=<seconds> prints them every that many seconds (and resets); off (0) costs one static final check.
 */
public final class SpawnStats {
    public static final int INTERVAL = Integer.getInteger("evangeliumserver.spawnStats", 0);
    public static final boolean ON = INTERVAL > 0;

    public static final int CHUNK = 0, CAP = 1, GATE = 2, ATTEMPT = 3, BELOW_MIN = 4, START_SOLID = 5, NO_PLAYER = 6, STEP = 7, DISTANCE = 8,
        MOB_EMPTY = 9, MOB_PICKED = 10, VALID_FAIL = 11, ENERGY_FAIL = 12, MOB_FAIL = 13, SPAWNED = 14, NANOS = 15, SKIPPED = 16, N = 17;
    private static final String[] NAMES = {"chunk", "cap", "gate", "attempt", "belowMin", "startSolid", "noPlayer", "step", "distance",
        "mobEmpty", "mobPicked", "validFail", "energyFail", "mobFail", "spawned", "ms", "skipped"};
    private static final MobCategory[] CATS = MobCategory.values();
    private static final LongAdder[][] C = new LongAdder[CATS.length][N];
    private static final ConcurrentHashMap<String, LongAdder> SPAWNS = new ConcurrentHashMap<>();
    private static final AtomicLong LAST = new AtomicLong(System.nanoTime());
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getClassLogger();

    static {
        for (LongAdder[] row : C) for (int i = 0; i < N; i++) row[i] = new LongAdder();
    }

    private SpawnStats() {
    }

    public static void inc(MobCategory category, int counter) {
        C[category.ordinal()][counter].increment();
    }

    public static void add(MobCategory category, int counter, long v) {
        C[category.ordinal()][counter].add(v);
    }

    public static void spawned(String key) {
        SPAWNS.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    public static void maybeDump() {
        final long last = LAST.get();
        final long now = System.nanoTime();
        if (now - last < INTERVAL * 1_000_000_000L || !LAST.compareAndSet(last, now)) {
            return;
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("SPAWNSTATS window=").append((now - last) / 1_000_000L).append("ms");
        for (MobCategory category : CATS) {
            final LongAdder[] row = C[category.ordinal()];
            if (row[CHUNK].sum() == 0 && row[ATTEMPT].sum() == 0) continue;
            sb.append("\nSPAWNSTATS ").append(category.getName());
            for (int i = 0; i < N; i++) {
                long v = row[i].sumThenReset();
                if (i == NANOS) v /= 1_000_000L;
                sb.append(' ').append(NAMES[i]).append('=').append(v);
            }
        }
        final java.util.TreeMap<String, Long> spawns = new java.util.TreeMap<>();
        for (var e : SPAWNS.entrySet()) {
            final long v = e.getValue().sumThenReset();
            if (v != 0) spawns.put(e.getKey(), v);
        }
        sb.append("\nSPAWNSTATS spawns ").append(spawns);
        LOGGER.info(sb.toString());
    }
}
