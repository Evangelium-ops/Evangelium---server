package io.multipaper.shreddedpaper.threading;

import com.mojang.logging.LogUtils;
import io.multipaper.shreddedpaper.region.LevelChunkRegion;
import io.multipaper.shreddedpaper.util.TickTimings;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.ToLongFunction;

/**
 * Knowledge fork: more than one thread inside one region tick.
 *
 * <p>ShreddedPaper ticks a region on one thread while it holds the 3x3 regions around it, because the work for one place may touch the
 * world up to a region away. The same rule, one level smaller: work that only reaches about one chunk away (random block ticks, snow
 * and ice, self-contained block entities) is grouped by chunk, and two chunks may run at the same time only when they are at least three
 * chunks apart, so what one thread touches (its chunk and the ring around it) never overlaps what another touches. Everything in one
 * chunk always runs on one thread.
 *
 * <p>The thread that owns the region does the work too, from the same queue; helper threads only take chunks when they are free. If
 * every helper is busy with other regions, the owner simply does everything itself, as before. A helper borrows the owner's locks
 * while it works ({@link ShreddedPaperRegionLocker#borrow}), so every ownership check passes exactly where it would for the owner.
 *
 * <p>Plugins: events fired by this work reach Folia-aware plugins from several threads, exactly as they already do from different regions
 * (two chunks three apart are no closer than two neighbouring regions); plugins that are not Folia-aware keep ShreddedPaper's per-plugin
 * lock, so they still see one thread at a time.
 */
public final class IntraRegion {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    /** -Dshreddedpaper.intraRegionThreads=N: helper threads (0 = off). Default: a third of the region threads, at least 2. */
    public static final int HELPERS = Integer.getInteger("shreddedpaper.intraRegionThreads", Math.max(2, ShreddedPaperTickThread.THREAD_COUNT / 3));
    /** Fewer chunks than this with work in them: the owner does them alone (waking a helper costs more than a few chunks of work). */
    public static final int MIN_CHUNKS = Integer.getInteger("shreddedpaper.intraRegionMinChunks", 4);
    /** Random ticks are cheap at the normal speed (3); only from this speed on are they worth spreading over threads. */
    public static final int MIN_RANDOM_TICK_SPEED = Integer.getInteger("shreddedpaper.intraRegionMinRandomTickSpeed", 20);
    /** Fewer chunk-local block entities than this in a region: they tick on the owner alone (each one is a few microseconds). */
    public static final int MIN_BLOCK_ENTITIES = Integer.getInteger("shreddedpaper.intraRegionMinBlockEntities", 128);
    /** Fewer mobs with a sensor due than this: they are sensed on the owner alone. */
    public static final int MIN_READ_ONLY_ITEMS = Integer.getInteger("shreddedpaper.intraRegionMinSensing", 8);

    private static final ExecutorService POOL = HELPERS <= 0 ? null
            : Executors.newFixedThreadPool(HELPERS, r -> {
                ShreddedPaperTickThread t = new ShreddedPaperTickThread(r, "ShreddedPaperRegionHelper-%d");
                t.setDaemon(true);   // never keeps the server process alive
                return t;
            });

    /**
     * Block entities that only reach a block or two (their own container, the block next to them, items right above, mobs spawned
     * within a few blocks). Left out on purpose: anything acting on players or entities far away or moving redstone right away (beacons,
     * conduits, pistons, daylight detectors, sculk, bells, end gateways, trial spawners, vaults). Comparator output changes of these
     * containers go through scheduled ticks, so they never spread while the chunks run.
     */
    private static final Set<BlockEntityType<?>> CHUNK_LOCAL = Set.of(
            BlockEntityType.FURNACE, BlockEntityType.BLAST_FURNACE, BlockEntityType.SMOKER, BlockEntityType.BREWING_STAND,
            BlockEntityType.CAMPFIRE, BlockEntityType.HOPPER, BlockEntityType.MOB_SPAWNER);

    static {
        LOGGER.info("Intra-region parallelism: {} helper threads", HELPERS);
    }

    private IntraRegion() {
    }

    public static boolean enabled() {
        return POOL != null;
    }

    public static boolean isChunkLocal(BlockEntityType<?> type) {
        return POOL != null && type != null && CHUNK_LOCAL.contains(type);
    }

    public static boolean parallelRandomTicks(int randomTickSpeed) {
        return POOL != null && randomTickSpeed >= MIN_RANDOM_TICK_SPEED;
    }

    /** One piece of chunk-local work. */
    public interface ChunkWork<T> {
        void run(T item) throws Exception;
    }

    /**
     * Runs {@code work} for every item, grouped by the chunk each item is in; chunks at least three apart may run on different threads at
     * the same time. Must be called by the thread that owns the region, from inside its tick.
     */
    public static <T> void forEachChunkLocal(ServerLevel level, LevelChunkRegion region, List<T> items, ToLongFunction<T> chunkKey, ChunkWork<T> work) {
        if (POOL == null || region == null || items.size() < MIN_CHUNKS) {   // no region: not inside a region tick, stay serial
            runSerially(items, work);
            return;
        }
        Long2ObjectLinkedOpenHashMap<List<T>> byChunk = new Long2ObjectLinkedOpenHashMap<>();
        for (T item : items) {
            byChunk.computeIfAbsent(chunkKey.applyAsLong(item), k -> new ArrayList<>()).add(item);
        }
        if (byChunk.size() < MIN_CHUNKS) {
            runSerially(items, work);
            return;
        }
        final long start = System.nanoTime();
        Schedule<T> schedule = new Schedule<>(byChunk);
        try {
            run(level, region, level.chunkScheduler.getRegionLocker().lend(), schedule, work);
        } finally {
            TickTimings.add(TickTimings.Phase.PHASED, System.nanoTime() - start);
        }
    }

    /**
     * Runs {@code work} for every item on any free thread, with no distance rule. Only for work that reads the world and writes nothing
     * but state of its own item (mob sensors write their own mob's memories): while it runs nothing in the region changes, because the
     * owner waits here and the regions around are locked. Items are handed out in a few batches (each item is small).
     */
    public static <T> void forEachReadOnly(ServerLevel level, LevelChunkRegion region, List<T> items, ChunkWork<T> work) {
        if (POOL == null || region == null || items.size() < MIN_READ_ONLY_ITEMS) {
            runSerially(items, work);
            return;
        }
        int batches = Math.min(HELPERS + 1, Math.max(2, items.size() / 4)) * 2;
        Long2ObjectLinkedOpenHashMap<List<T>> byBatch = new Long2ObjectLinkedOpenHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            // batches get keys far apart, so the distance rule never holds one back
            byBatch.computeIfAbsent(net.minecraft.world.level.ChunkPos.asLong((i % batches) * 8, 0), k -> new ArrayList<>()).add(items.get(i));
        }
        final long start = System.nanoTime();
        try {
            run(level, region, level.chunkScheduler.getRegionLocker().lend(), new Schedule<>(byBatch), work);
        } finally {
            TickTimings.add(TickTimings.Phase.PHASED, System.nanoTime() - start);
        }
    }

    private static <T> void runSerially(List<T> items, ChunkWork<T> work) {
        for (T item : items) {
            try {
                work.run(item);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Which chunk may run next: the first waiting chunk that is at least three chunks from every running one. Small (a region has at
     * most 64 chunks and only a few run at once), so one monitor and a linear scan are enough.
     */
    private static final class Schedule<T> {
        private static final byte WAITING = 0;
        private static final byte RUNNING = 1;
        private static final byte DONE = 2;

        final int size;
        final int[] x;
        final int[] z;
        final List<List<T>> work;
        final byte[] state;
        private int firstWaiting;
        private int doneCount;
        private final int[] running;
        private int runningCount;

        Schedule(Long2ObjectLinkedOpenHashMap<List<T>> byChunk) {
            this.size = byChunk.size();
            this.x = new int[size];
            this.z = new int[size];
            this.work = new ArrayList<>(byChunk.values());
            this.state = new byte[size];
            this.running = new int[size];
            int i = 0;
            for (long key : byChunk.keySet()) {
                this.x[i] = (int) key;
                this.z[i] = (int) (key >> 32);
                i++;
            }
        }

        /** The index of a chunk to run, -1 when none is waiting any more, -2 when the waiting ones are all too close to running ones. */
        synchronized int take() {
            while (firstWaiting < size && state[firstWaiting] != WAITING) {
                firstWaiting++;
            }
            if (firstWaiting >= size) {
                return -1;
            }
            outer:
            for (int i = firstWaiting; i < size; i++) {
                if (state[i] != WAITING) {
                    continue;
                }
                for (int r = 0; r < runningCount; r++) {
                    int j = running[r];
                    if (Math.abs(x[i] - x[j]) <= 2 && Math.abs(z[i] - z[j]) <= 2) {
                        continue outer;   // what the two touch (their chunk and the ring around it) would overlap
                    }
                }
                state[i] = RUNNING;
                running[runningCount++] = i;
                return i;
            }
            return -2;
        }

        synchronized void finish(int i) {
            state[i] = DONE;
            doneCount++;
            for (int r = 0; r < runningCount; r++) {
                if (running[r] == i) {
                    running[r] = running[--runningCount];
                    break;
                }
            }
            notifyAll();
        }

        synchronized boolean allDone() {
            return doneCount == size;
        }

        /** Waits briefly for a running chunk to finish (it may unblock a waiting one). */
        synchronized void awaitChange() {
            try {
                wait(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static <T> void run(ServerLevel level, LevelChunkRegion region, ShreddedPaperRegionLocker.Loan loan, Schedule<T> schedule, ChunkWork<T> work) {
        final AtomicInteger active = new AtomicInteger();   // helpers between taking part and having flushed their changes
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread owner = Thread.currentThread();
        int wanted = Math.min(HELPERS, schedule.size - 1);
        for (int i = 0; i < wanted; i++) {
            POOL.execute(() -> helper(level, region, loan, schedule, work, active, failure, owner));
        }
        work(schedule, work, failure, TickTimings.OWNER_CHUNKS);
        // the chunks still running on helpers, and their change broadcasts: every helper unparks the owner when it is done, so a plain park
        // cannot miss the end (a timed park would oversleep: on some systems the shortest sleep is close to a millisecond)
        int spins = 0;
        while (!schedule.allDone() || active.get() != 0) {
            if (++spins < 2_000) {
                Thread.onSpinWait();
            } else {
                LockSupport.park(schedule);
            }
        }
        Throwable t = failure.get();
        if (t instanceof RuntimeException r) {
            throw r;
        } else if (t instanceof Error e) {
            throw e;
        } else if (t != null) {
            throw new RuntimeException(t);
        }
    }

    private static <T> void work(Schedule<T> schedule, ChunkWork<T> work, AtomicReference<Throwable> failure, AtomicLong counter) {
        while (true) {
            int i = schedule.take();
            if (i == -1) {
                return;
            }
            if (i == -2) {
                schedule.awaitChange();
                continue;
            }
            try {
                if (failure.get() == null) {
                    if (TickTimings.enabled()) {
                        counter.incrementAndGet();
                    }
                    for (T item : schedule.work.get(i)) {
                        work.run(item);
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                schedule.finish(i);
            }
        }
    }

    private static <T> void helper(ServerLevel level, LevelChunkRegion region, ShreddedPaperRegionLocker.Loan loan, Schedule<T> schedule, ChunkWork<T> work,
                                   AtomicInteger active, AtomicReference<Throwable> failure, Thread owner) {
        active.incrementAndGet();   // before looking: the owner waits for every helper that might take a chunk
        try {
            if (schedule.allDone()) {
                return;   // the owner (or other helpers) already did everything: nothing was touched
            }
            ShreddedPaperRegionLocker locker = level.chunkScheduler.getRegionLocker();
            locker.borrow(loan);
            ShreddedPaperChunkTicker.setCurrentlyTickingRegion(region);
            level.handlingTickThreadLocal.set(true);
            ShreddedPaperChangesBroadcaster.setAsWorkerThread();
            try {
                work(schedule, work, failure, TickTimings.HELPER_CHUNKS);
                ShreddedPaperChangesBroadcaster.broadcastChanges();   // the changes this helper made, while it still holds the region
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                level.handlingTickThreadLocal.set(false);
                ShreddedPaperChunkTicker.setCurrentlyTickingRegion(null);
                locker.giveBack(loan);
            }
        } finally {
            active.decrementAndGet();
            LockSupport.unpark(owner);
        }
    }

    public static void shutdown() {
        if (POOL != null) {
            POOL.shutdownNow();
        }
    }
}
