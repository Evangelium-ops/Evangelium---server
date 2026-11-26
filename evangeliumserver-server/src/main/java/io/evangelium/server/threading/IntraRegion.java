package io.evangelium.server.threading;

import com.mojang.logging.LogUtils;
import io.evangelium.server.region.LevelChunkRegion;
import io.evangelium.server.util.TickTimings;
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
 * Evangelium fork: more than one thread inside one region tick.
 *
 * <p>EvangeliumServer ticks a region on one thread while it holds the 3x3 regions around it, because the work for one place may touch the
 * world up to a region away. The same rule, one level smaller: work that only reaches about one chunk away (random block ticks, snow
 * and ice, self-contained block entities) is grouped by chunk, and two chunks may run at the same time only when they are at least three
 * chunks apart, so what one thread touches (its chunk and the ring around it) never overlaps what another touches. Everything in one
 * chunk always runs on one thread.
 *
 * <p>The thread that owns the region does the work too, from the same queue; helper threads only take chunks when they are free. If
 * every helper is busy with other regions, the owner simply does everything itself, as before. A helper borrows the owner's locks
 * while it works ({@link EvangeliumServerRegionLocker#borrow}), so every ownership check passes exactly where it would for the owner.
 *
 * <p>Plugins: events fired by this work reach Folia-aware plugins from several threads, exactly as they already do from different regions
 * (two chunks three apart are no closer than two neighbouring regions); plugins that are not Folia-aware keep EvangeliumServer's per-plugin
 * lock, so they still see one thread at a time.
 */
public final class IntraRegion {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    /** -Devangeliumserver.intraRegionThreads=N: helper threads (0 = off). Default: a third of the region threads, at least 2. */
    public static final int HELPERS = Integer.getInteger("evangeliumserver.intraRegionThreads", Math.max(2, EvangeliumServerTickThread.THREAD_COUNT / 3));
    /** Fewer chunks than this with work in them: the owner does them alone (waking a helper costs more than a few chunks of work). */
    public static final int MIN_CHUNKS = Integer.getInteger("evangeliumserver.intraRegionMinChunks", 4);
    /** Random ticks are cheap at the normal speed (3); only from this speed on are they worth spreading over threads. */
    public static final int MIN_RANDOM_TICK_SPEED = Integer.getInteger("evangeliumserver.intraRegionMinRandomTickSpeed", 20);
    /** Fewer chunk-local block entities than this in a region: they tick on the owner alone (each one is a few microseconds). */
    public static final int MIN_BLOCK_ENTITIES = Integer.getInteger("evangeliumserver.intraRegionMinBlockEntities", 128);
    /** Fewer items and experience orbs than this in a region: they tick on the owner alone. */
    public static final int MIN_ENTITIES = Integer.getInteger("evangeliumserver.intraRegionMinEntities", 128);
    /** Fewer players than this in a region: they tick on the owner alone. -1 turns parallel player ticks off. */
    public static final int MIN_PLAYERS = Integer.getInteger("evangeliumserver.intraRegionMinPlayers", 8);
    /** Fewer mobs with a sensor due than this: they are sensed on the owner alone. */
    public static final int MIN_READ_ONLY_ITEMS = Integer.getInteger("evangeliumserver.intraRegionMinSensing", 8);

    private static final ExecutorService POOL = HELPERS <= 0 ? null
            : Executors.newFixedThreadPool(HELPERS, r -> {
                EvangeliumServerTickThread t = new EvangeliumServerTickThread(r, "EvangeliumRegionHelper-%d");
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

    /**
     * Entities that only reach a block or so: items (they merge with items next to them) and experience orbs (they reach a player who is
     * touching them). Not when riding or ridden (the vehicle ticks its passengers).
     */
    public static boolean isChunkLocal(net.minecraft.world.entity.Entity entity) {
        return POOL != null && (entity instanceof net.minecraft.world.entity.item.ItemEntity || entity instanceof net.minecraft.world.entity.ExperienceOrb)
                && !entity.isPassenger() && !entity.isVehicle();
    }

    /**
     * Players reach about a block during their own tick (pushing, touching items and orbs, the blocks they stand in, the container they
     * have open, which is at most 8 blocks away): fights and attacks come in as packets and are handled in another phase. Not when riding
     * or ridden, or spectating through another entity. Both the player tick and the player's entity tick (inventory sync, locator bar,
     * advancements, block breaking progress) run this way.
     */
    /**
     * Evangelium - players are never helper items any more (off unless -Devangeliumserver.intraRegionPlayers=true): a player tick runs plugin
     * code and can sync-load chunks mid-tick (a teleport into unloaded chunks), which must happen on the owner outside a parallel phase.
     */
    static final boolean PLAYERS_ON_HELPERS = Boolean.getBoolean("evangeliumserver.intraRegionPlayers");

    public static boolean parallelPlayers(int players) {
        return PLAYERS_ON_HELPERS && POOL != null && MIN_PLAYERS >= 0 && players >= MIN_PLAYERS;
    }

    public static boolean isChunkLocalPlayer(net.minecraft.server.level.ServerPlayer player) {
        return !player.isPassenger() && !player.isVehicle() && player.getCamera() == player;   // a spectator's camera can be anywhere
    }

    public static boolean parallelRandomTicks(int randomTickSpeed) {
        return POOL != null && randomTickSpeed >= MIN_RANDOM_TICK_SPEED;
    }

    /** One piece of chunk-local work. */
    // Evangelium start - plugin compatibility: a thread inside a parallel phase (the owner while helpers run, or a helper) shares its
    // regions with other threads, so it must never lend them (OnDemandRegionAccess.publishLoans) nor take regions on demand
    private static final ThreadLocal<int[]> PHASE = ThreadLocal.withInitial(() -> new int[1]);

    public static boolean inParallelPhase() {
        return PHASE.get()[0] > 0;
    }

    /**
     * Thrown by ServerChunkCache.syncLoad inside a parallel phase (owner or helper): neither may run region, chunk or server tasks while the
     * phase runs (a helper would run tasks of regions it does not hold, at the same time as the owner; the owner would run them while its
     * helpers work in the same regions). Only the one work item fails; it is logged once with its stack so any such path becomes known.
     */
    public static final class SyncLoadInPhaseException extends IllegalStateException {
        public SyncLoadInPhaseException(String message) {
            super(message);
        }
    }

    private static volatile boolean syncLoadLogged = false;

    private static void logSyncLoadOnce(SyncLoadInPhaseException e) {
        if (!syncLoadLogged) {
            syncLoadLogged = true;
            LOGGER.error("[IntraRegion] a work item of a parallel phase needed a sync chunk load; that item was skipped (logged once)", e);
        }
    }
    // Evangelium end

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
        // items of one chunk usually come one after another (block entities are added chunk by chunk): reuse the last list, same result
        long lastKey = 0;
        List<T> last = null;
        for (T item : items) {
            long key = chunkKey.applyAsLong(item);
            if (last == null || key != lastKey) {
                last = byChunk.computeIfAbsent(key, k -> new ArrayList<>());
                lastKey = key;
            }
            last.add(item);
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

    private static <T> void run(ServerLevel level, LevelChunkRegion region, EvangeliumServerRegionLocker.Loan loan, Schedule<T> schedule, ChunkWork<T> work) {
        final int[] phase = PHASE.get(); // Evangelium - plugin compatibility
        phase[0]++;
        try {
            run0(level, region, loan, schedule, work);
        } finally {
            phase[0]--;
        }
    }

    private static <T> void run0(ServerLevel level, LevelChunkRegion region, EvangeliumServerRegionLocker.Loan loan, Schedule<T> schedule, ChunkWork<T> work) {
        final AtomicInteger active = new AtomicInteger();   // helpers between taking part and having flushed their changes
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread owner = Thread.currentThread();
        // Evangelium - helper back-pressure: queue only as many helper tasks as there are helpers free right now. A task queued while every
        // helper is busy with other regions would only start after the owner already did everything (a stale no-op), and those piled up
        int wanted = reserveHelpers(Math.min(HELPERS, schedule.size - 1));
        for (int i = 0; i < wanted; i++) {
            POOL.execute(() -> {
                try {
                    helper(level, region, loan, schedule, work, active, failure, owner);
                } finally {
                    FREE_HELPERS.incrementAndGet();
                }
            });
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
        net.minecraft.world.entity.PhysicsCache.bump(); // Evangelium - helpers changed blocks in this region: the owner's caches start over
        Throwable t = failure.get();
        if (t instanceof RuntimeException r) {
            throw r;
        } else if (t instanceof Error e) {
            throw e;
        } else if (t != null) {
            throw new RuntimeException(t);
        }
    }

    /** Evangelium - helpers neither running nor queued; a helper task holds one from being queued until it ends. */
    private static final AtomicInteger FREE_HELPERS = new AtomicInteger(Math.max(0, HELPERS));
    /** -Devangeliumserver.helperBackPressure=false queues helper tasks as before (A/B switch). */
    private static final boolean BACK_PRESSURE = Boolean.parseBoolean(System.getProperty("evangeliumserver.helperBackPressure", "true"));

    /** Takes up to {@code wanted} free helpers; returns how many were taken (0 when all are busy: the owner works alone). */
    private static int reserveHelpers(int wanted) {
        if (wanted <= 0) {
            return 0;
        }
        if (!BACK_PRESSURE) {
            FREE_HELPERS.addAndGet(-wanted);   // keeps the counter balanced with the increments of the tasks
            return wanted;
        }
        while (true) {
            int free = FREE_HELPERS.get();
            int take = Math.min(wanted, free);
            if (take <= 0) {
                return 0;
            }
            if (FREE_HELPERS.compareAndSet(free, free - take)) {
                return take;
            }
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
                    net.minecraft.world.entity.PhysicsCache.bump(); // Evangelium - a new work unit: other threads changed blocks elsewhere meanwhile
                    for (T item : schedule.work.get(i)) {
                        try {
                            work.run(item);
                        } catch (SyncLoadInPhaseException e) {
                            logSyncLoadOnce(e); // Evangelium - only this item fails
                        }
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                schedule.finish(i);
            }
        }
    }

    private static <T> void helper(ServerLevel level, LevelChunkRegion region, EvangeliumServerRegionLocker.Loan loan, Schedule<T> schedule, ChunkWork<T> work,
                                   AtomicInteger active, AtomicReference<Throwable> failure, Thread owner) {
        active.incrementAndGet();   // before looking: the owner waits for every helper that might take a chunk
        try {
            if (schedule.allDone()) {
                return;   // the owner (or other helpers) already did everything: nothing was touched
            }
            EvangeliumServerRegionLocker locker = level.chunkScheduler.getRegionLocker();
            final int[] phase = PHASE.get(); // Evangelium - plugin compatibility (raised inside the try below, lowered in its finally)
            locker.borrow(loan);
            EvangeliumServerChunkTicker.setCurrentlyTickingRegion(region);
            level.handlingTickThreadLocal.set(true);
            EvangeliumServerChangesBroadcaster.setAsWorkerThread();
            try {
                phase[0]++;
                work(schedule, work, failure, TickTimings.HELPER_CHUNKS);
                EvangeliumServerChangesBroadcaster.broadcastChanges();   // the changes this helper made, while it still holds the region
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                level.handlingTickThreadLocal.set(false);
                EvangeliumServerChunkTicker.setCurrentlyTickingRegion(null);
                locker.giveBack(loan);
                phase[0]--;
                OnDemandRegionAccess.clearTickAbandoned(); // a helper's abandoned mark went to the owner through failure
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
