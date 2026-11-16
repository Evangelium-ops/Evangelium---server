package io.multipaper.shreddedpaper.util;

import com.mojang.logging.LogUtils;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Knowledge - chunk generation counters for benchmarks, off unless -Dshreddedpaper.genStats=true: unloads of chunks that are
 * not FULL yet (their finished stages get saved), proto chunks read back from disk, unload graces given. Logged every 10 s.
 */
public final class GenStats {

    public static final boolean ENABLED = Boolean.getBoolean("shreddedpaper.genStats");

    public static final AtomicLong PROTO_UNLOADS = new AtomicLong();
    public static final AtomicLong PROTO_RELOADS = new AtomicLong();
    public static final AtomicLong GRACES = new AtomicLong();
    public static final java.util.List<ca.spottedleaf.moonrise.patches.chunk_system.scheduling.RadiusTaskQueue> QUEUES = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final long[] BLOCKERS = new long[4];
    private static final java.util.concurrent.ConcurrentHashMap<Object, Timed> TIMED = new java.util.concurrent.ConcurrentHashMap<>();

    private static long blockerSamples;
    // per generation status index (0..11) + 12 = starlight light task: tasks running now, nanos spent, samples of running
    private static final int SLOTS = 14; // 13 = FEATURES tasks queued in the radius queue, not running yet
    private static final java.util.concurrent.atomic.AtomicIntegerArray RUNNING = new java.util.concurrent.atomic.AtomicIntegerArray(SLOTS);
    private static final java.util.concurrent.atomic.AtomicLongArray NANOS = new java.util.concurrent.atomic.AtomicLongArray(SLOTS);
    private static final long[] RUN_SAMPLES = new long[SLOTS];
    private static long samples;

    public static long begin(final int slot) {
        RUNNING.incrementAndGet(slot);
        return System.nanoTime();
    }

    public static void inc(final int slot) {
        RUNNING.incrementAndGet(slot);
    }

    public static void dec(final int slot) {
        RUNNING.decrementAndGet(slot);
    }

    public static void end(final int slot, final long start) {
        NANOS.addAndGet(slot, System.nanoTime() - start);
        RUNNING.decrementAndGet(slot);
    }

    static {
        if (ENABLED) {
            final Thread t = new Thread(() -> {
                long last = System.nanoTime();
                while (true) {
                    try {
                        Thread.sleep(5L);
                    } catch (final InterruptedException e) {
                        return;
                    }
                    ++samples;
                    for (int i = 0; i < SLOTS; ++i) RUN_SAMPLES[i] += RUNNING.get(i);
                    if (samples % 20 == 0) { // every 100 ms
                        ++blockerSamples;
                        for (final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.RadiusTaskQueue q : QUEUES) q.sampleBlockers(BLOCKERS);
                    }
                    if (System.nanoTime() - last < 10_000_000_000L) continue;
                    last = System.nanoTime();
                    final StringBuilder b = new StringBuilder();
                    for (int i = 0; i < SLOTS; ++i) {
                        if (NANOS.get(i) == 0L && i != 13) continue;
                        b.append(' ').append(i == 12 ? "lightq" : i == 13 ? "featWaiting" : net.minecraft.world.level.chunk.status.ChunkStatus.getStatusList().get(i).getName())
                            .append('=').append(String.format("%.1fs/%.2f", NANOS.get(i) / 1e9, (double) RUN_SAMPLES[i] / samples));
                    }
                    if (blockerSamples > 0) {
                        long wasted = 0;
                        for (final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.RadiusTaskQueue q : QUEUES) wasted += q.wastedPulls.get();
                        b.append(" wastedPulls=").append(wasted);
                        b.append(String.format(" waitingBlockedBy: r1Running=%.2f r2Running=%.2f otherRunning=%.2f reservation=%.2f",
                            (double) BLOCKERS[0] / blockerSamples, (double) BLOCKERS[1] / blockerSamples, (double) BLOCKERS[2] / blockerSamples, (double) BLOCKERS[3] / blockerSamples));
                        java.util.Arrays.fill(BLOCKERS, 0L);
                        blockerSamples = 0;
                    }
                    if (!TIMED.isEmpty()) LogUtils.getLogger().info("GENFEAT{}", topTimed(25));
                    LogUtils.getLogger().info("GENSTATS protoUnloads={} protoReloads={} graces={} cpuTotal/avgRunningLast10s:{}", PROTO_UNLOADS.get(), PROTO_RELOADS.get(), GRACES.get(), b);
                    java.util.Arrays.fill(RUN_SAMPLES, 0L);
                    samples = 0L;
                }
            }, "GenStats");
            t.setDaemon(true);
            t.start();
        }
    }

    // per placed feature / structure / decoration overhead: nanos and calls, keyed by the object, named on first use
    public static final class Timed {
        final String name;
        final java.util.concurrent.atomic.LongAdder nanos = new java.util.concurrent.atomic.LongAdder();
        final java.util.concurrent.atomic.LongAdder calls = new java.util.concurrent.atomic.LongAdder();
        Timed(final String name) { this.name = name; }
        public void add(final long ns) { this.nanos.add(ns); this.calls.increment(); }
    }

    public static Timed timed(final Object key, final java.util.function.Supplier<String> name) {
        Timed t = TIMED.get(key);
        return t != null ? t : TIMED.computeIfAbsent(key, k -> new Timed(name.get()));
    }

    static String topTimed(final int n) {
        final StringBuilder b = new StringBuilder();
        long parts = 0;
        for (final Map.Entry<Object, Timed> e : TIMED.entrySet()) if (!"TOTAL".equals(e.getKey())) parts += e.getValue().nanos.sum();
        b.append(String.format(" allFeaturesAndStructures=%.1fs", parts / 1e9));
        TIMED.values().stream().sorted((a, c) -> Long.compare(c.nanos.sum(), a.nanos.sum())).limit(n)
            .forEach(t -> b.append(String.format(" %s=%.1fs/%d", t.name, t.nanos.sum() / 1e9, t.calls.sum())));
        return b.toString();
    }

    private GenStats() {}
}
