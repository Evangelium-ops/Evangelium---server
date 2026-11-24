package io.multipaper.shreddedpaper.threading;

import io.multipaper.shreddedpaper.region.LevelChunkRegion;
import io.multipaper.shreddedpaper.region.RegionPos;
import io.multipaper.shreddedpaper.util.TickTimings;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Evangelium: hands the ticking regions of one world to the tick threads for one tick.
 *
 * <p>Before, every region was its own task on the pool in map order; a task whose 3x3 was locked by a neighbour waited for that unlock and
 * then went to the back of the queue. Now a small number of worker slots (at most one per tick thread) take regions themselves:
 * <ul>
 *   <li>the most expensive region first (its recent tick cost, then its player count), among those whose 3x3 is free right now, so the
 *       long ones start early and do not end up as the tail of the tick;</li>
 *   <li>optional ("two regions become one", -Dshreddedpaper.neighbourBatching, off by default because it measured slower): a worker that
 *       finished region A ticks a waiting next-door region B right away, moving its lock over (it only takes the outer ring it is missing,
 *       see ShreddedPaperRegionLocker#tryMoveLock). Region data and region size never change;</li>
 *   <li>when no waiting region is free, the slot waits for an unlock without holding a thread.</li>
 * </ul>
 * Every region still ticks under its own 3x3 lock with its own RegionBudget and currentlyTickingRegion, exactly as before.
 */
final class RegionTickDispatcher {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getClassLogger();

    /** -Dshreddedpaper.regionDispatcher=false: one pool task per region as before (A/B switch). */
    static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("shreddedpaper.regionDispatcher", "true"));
    /**
     * -Dshreddedpaper.neighbourBatching ("two regions become one"): "false" (default) = never; "best" = after a region the slot takes the
     * most expensive waiting region it could start now, where a neighbour counts as startable when the lock can move to it; "eager" =
     * always the most expensive waiting neighbour if it costs at least half the most expensive waiting region.
     * Measured on CT190 (spread, 290 regions, 7 threads, two runs each): tick tail (regions wall - region sum / threads) false 2.84/2.85 ms,
     * best 3.74/3.44, eager 3.56/3.51, old scheduler 5.26/5.43. Neighbours never ran in parallel anyway, so the handover saves nothing,
     * and holding the outer ring through the handover blocks other slots; batching stays available for other loads.
     */
    static final String BATCHING_MODE = System.getProperty("shreddedpaper.neighbourBatching", "false");
    static final boolean NEIGHBOUR_BATCHING = !"false".equals(BATCHING_MODE);
    private static final boolean EAGER = "eager".equals(BATCHING_MODE);
    /** How many waiting regions a slot with nothing free hangs on (the first unlock of any of them wakes it). */
    private static final int WAIT_ON = 4;

    private static final class Entry {
        final LevelChunkRegion region;
        final RegionPos pos;
        final long cost;
        final int players;
        volatile boolean taken;   // Evangelium - claimed by CAS (TAKEN), so slots can look for free regions without the dispatcher lock

        boolean claim() {
            return TAKEN.compareAndSet(this, false, true);
        }

        Entry(LevelChunkRegion region) {
            this.region = region;
            this.pos = region.getRegionPos();
            this.cost = RegionBudget.cost(region);
            this.players = region.getPlayers().size();
        }
    }

    private static final java.lang.invoke.VarHandle TAKEN;

    static {
        try {
            TAKEN = java.lang.invoke.MethodHandles.lookup().findVarHandle(Entry.class, "taken", boolean.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * -Dshreddedpaper.dispatcherLockFreeScan=false: look for a free region while holding the dispatcher lock (previous behaviour). Default:
     * the scan (a looksFree and maybe a lock attempt per waiting region) runs outside it and claims the region by CAS, so a slot that is
     * descheduled in the middle of a scan no longer stalls every other slot that only wants to count a finished region.
     */
    private static final boolean LOCK_FREE_SCAN = Boolean.parseBoolean(System.getProperty("shreddedpaper.dispatcherLockFreeScan", "true"));

    private final ShreddedPaperRegionLocker locker;
    private final Consumer<LevelChunkRegion> tick;
    private final Executor executor;
    private final Entry[] pending;   // most expensive first
    private final Long2ObjectOpenHashMap<Entry> byPos;
    private final CompletableFuture<Void> done = new CompletableFuture<>();
    // guarded by this
    private volatile int firstPending;   // Evangelium - volatile: read by the lock-free scan, written under this
    private int pendingCount;
    private int unfinished;
    private int slots;     // slots alive: running, scanning, queued on the pool or waiting for an unlock
    private int running;   // slots holding a region lock
    private final int maxSlots = ShreddedPaperTickThread.THREAD_COUNT;
    /** -Dshreddedpaper.slotWaitAll=true: every slot with nothing free waits for an unlock (first version) instead of only the last one. */
    private static final boolean WAIT_ALL = Boolean.getBoolean("shreddedpaper.slotWaitAll");

    private RegionTickDispatcher(ShreddedPaperRegionLocker locker, List<LevelChunkRegion> regions, Consumer<LevelChunkRegion> tick) {
        this.locker = locker;
        this.tick = tick;
        this.executor = ShreddedPaperTickThread.getExecutor();
        this.pending = new Entry[regions.size()];
        this.byPos = new Long2ObjectOpenHashMap<>(regions.size() * 2);
        for (int i = 0; i < this.pending.length; i++) {
            Entry e = new Entry(regions.get(i));
            this.pending[i] = e;
            this.byPos.put(e.pos.longKey, e);
        }
        Arrays.sort(this.pending, (a, b) -> a.cost != b.cost ? Long.compare(b.cost, a.cost) : Integer.compare(b.players, a.players));
        this.pendingCount = this.pending.length;
        this.unfinished = this.pending.length;
    }

    /**
     * Ticks every region once; {@code tick} runs with the region's 3x3 locked and must not throw (it reports its own failures).
     * The future completes after the last region has been ticked and its lock released.
     */
    static CompletableFuture<Void> tickAll(ShreddedPaperRegionLocker locker, List<LevelChunkRegion> regions, Consumer<LevelChunkRegion> tick) {
        if (regions.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        RegionTickDispatcher d = new RegionTickDispatcher(locker, regions, tick);
        int slots = Math.min(ShreddedPaperTickThread.THREAD_COUNT, regions.size());
        d.slots = slots;
        for (int i = 0; i < slots; i++) {
            d.executor.execute(d::work);
        }
        return d.done;
    }

    /** One worker slot: takes regions until none is waiting, or parks itself (holding no thread) until a waiting region's 3x3 unlocks. */
    private void work() {
        ShreddedPaperRegionLocker.RegionLock held = null;
        Entry current = null;
        while (true) {
            Entry next = null;
            if (current != null) {
                // "two regions become one": a waiting neighbour of the region just ticked, under the locks we mostly hold already
                if (NEIGHBOUR_BATCHING) {
                    synchronized (this) {
                        ShreddedPaperRegionLocker.RegionLock moved = null;
                        Entry neighbour = null;
                        if (EAGER) {
                            neighbour = bestNeighbour(current.pos);
                            moved = neighbour == null ? null : this.locker.tryMoveLock(held, neighbour.pos);
                        } else {
                            // the normal order (most expensive first), with "free" meaning: a neighbour the lock can move to, or any other
                            // region whose 3x3 is free apart from what we hold (it will be free once we release)
                            for (int i = this.firstPending; i < this.pending.length; i++) {
                                Entry e = this.pending[i];
                                if (e.taken) {
                                    continue;
                                }
                                if (Math.abs(e.pos.x - current.pos.x) <= 1 && Math.abs(e.pos.z - current.pos.z) <= 1) {
                                    moved = this.locker.tryMoveLock(held, e.pos);
                                    if (moved != null) {
                                        neighbour = e;
                                        break;
                                    }
                                } else if (this.locker.looksFree(e.pos)) {
                                    break;   // a more expensive region elsewhere can start: release and take it the normal way
                                }
                            }
                        }
                        if (moved != null) {
                            take(neighbour);
                            this.unfinished--;   // current is done; neighbour is still unfinished, so this never reaches 0 here
                            held = moved;
                            next = neighbour;
                            TickTimings.BATCHED.incrementAndGet();
                        }
                    }
                }
                if (next == null) {
                    held.unlock();
                    held = null;
                    boolean last;
                    synchronized (this) {
                        this.running--;
                        last = --this.unfinished == 0;
                    }
                    if (last) {
                        this.done.complete(null);   // after the unlock: what follows (tracker) takes the world's global lock
                        return;
                    }
                    // let other pool tasks (scheduled region tasks, other worlds' slots) in between, as the old one-task-per-region did
                    if (this.executor instanceof ThreadPoolExecutor pool && !pool.getQueue().isEmpty()) {
                        synchronized (this) {
                            if (this.pendingCount == 0) {
                                this.slots--;
                                return;
                            }
                        }
                        this.executor.execute(this::work);
                        return;
                    }
                }
            }

            if (next == null && LOCK_FREE_SCAN) {
                // Evangelium - find and lock a free region without the dispatcher lock; the CAS on Entry.taken makes the claim unique (the
                // 3x3 region lock already does: two slots can never hold the same 3x3)
                Entry found = null;
                int foundAt = -1;
                ShreddedPaperRegionLocker.RegionLock foundLock = null;
                for (int i = this.firstPending; i < this.pending.length; i++) {
                    Entry e = this.pending[i];
                    if (e.taken || !this.locker.looksFree(e.pos)) {
                        continue;
                    }
                    ShreddedPaperRegionLocker.RegionLock lock = this.locker.tryTakeLockNow(e.pos);
                    if (lock == null) {
                        continue;
                    }
                    if (!e.claim()) {
                        lock.unlock();
                        continue;
                    }
                    found = e;
                    foundAt = i;
                    foundLock = lock;
                    break;
                }
                RegionPos[] waitOn = null;
                boolean spawn = false;
                synchronized (this) {
                    if (found != null) {
                        take(found);
                        this.running++;
                        held = foundLock;
                        next = found;
                        // more free work waiting and a slot to spare: wake one more slot (slots end when they find nothing free)
                        if (this.slots < this.maxSlots && anotherFree(foundAt + 1)) {
                            this.slots++;
                            spawn = true;
                        }
                    } else {
                        if (this.pendingCount == 0) {
                            this.slots--;
                            return;   // the slot ends; the regions still running finish on their own slots
                        }
                        if (this.running > 0 && !WAIT_ALL) {
                            // nothing free, but other slots are ticking: each rescans after its unlock, so this slot can end
                            this.slots--;
                            return;
                        }
                        waitOn = new RegionPos[Math.min(WAIT_ON, this.pendingCount)];
                        int n = 0;
                        for (int i = this.firstPending; i < this.pending.length && n < waitOn.length; i++) {
                            if (!this.pending[i].taken) {
                                waitOn[n++] = this.pending[i].pos;
                            }
                        }
                    }
                }
                if (spawn) {
                    this.executor.execute(this::work);
                }
                if (next == null) {
                    waitForUnlock(waitOn);
                    return;
                }
            }

            if (next == null) {
                RegionPos[] waitOn = null;
                boolean spawn = false;
                synchronized (this) {
                    if (this.pendingCount == 0) {
                        this.slots--;
                        return;   // the slot ends; the regions still running finish on their own slots
                    }
                    for (int i = this.firstPending; i < this.pending.length; i++) {
                        Entry e = this.pending[i];
                        if (e.taken || !this.locker.looksFree(e.pos)) {
                            continue;
                        }
                        ShreddedPaperRegionLocker.RegionLock lock = this.locker.tryTakeLockNow(e.pos);
                        if (lock != null) {
                            take(e);
                            this.running++;
                            held = lock;
                            next = e;
                            // more free work waiting and a slot to spare: wake one more slot (slots end when they find nothing free)
                            if (this.slots < this.maxSlots && anotherFree(i + 1)) {
                                this.slots++;
                                spawn = true;
                            }
                            break;
                        }
                    }
                    if (next == null && this.running > 0 && !WAIT_ALL) {
                        // nothing free, but other slots are ticking: each rescans when its region is done, so this slot can end
                        this.slots--;
                        return;
                    }
                    if (next == null) {
                        waitOn = new RegionPos[Math.min(WAIT_ON, this.pendingCount)];
                        int n = 0;
                        for (int i = this.firstPending; i < this.pending.length && n < waitOn.length; i++) {
                            if (!this.pending[i].taken) {
                                waitOn[n++] = this.pending[i].pos;
                            }
                        }
                    }
                }
                if (spawn) {
                    this.executor.execute(this::work);
                }
                if (next == null) {
                    waitForUnlock(waitOn);
                    return;
                }
            }

            current = next;
            try {
                this.tick.accept(current.region);
            } catch (Throwable t) {
                // tick reports its own failures; this only keeps the lock and the counts right if it did not
                LOGGER.error("Unexpected failure ticking region {}", current.pos, t);
            }
        }
    }

    /** Nothing free and no slot of ours is ticking: whatever blocks the waiting regions is someone else's lock; run again on its unlock. */
    private void waitForUnlock(RegionPos[] waitOn) {
        TickTimings.SLOT_WAITS.incrementAndGet();
        final AtomicBoolean woken = new AtomicBoolean();
        for (RegionPos pos : waitOn) {
            if (pos == null) {
                continue;
            }
            this.locker.onUnlock(pos, () -> {
                if (woken.compareAndSet(false, true)) {
                    this.executor.execute(this::work);
                }
                return CompletableFuture.completedFuture(null);
            });
            if (woken.get()) {
                break;   // it was free already: the slot runs again
            }
        }
    }

    /** Whether a waiting region after index from has its whole 3x3 unlocked right now (a short look, for waking another slot). */
    private boolean anotherFree(int from) {
        int looked = 0;
        for (int i = from; i < this.pending.length && looked < 32; i++) {
            Entry e = this.pending[i];
            if (e.taken) {
                continue;
            }
            looked++;
            if (this.locker.looksUnlocked(e.pos)) {
                return true;
            }
        }
        return false;
    }

    private void take(Entry e) {
        e.taken = true;   // already claimed by the lock-free scan; set here for the paths under this lock
        this.pendingCount--;
        while (this.firstPending < this.pending.length && this.pending[this.firstPending].taken) {
            this.firstPending++;
        }
    }

    /** The most expensive waiting region next to pos, if it is worth ticking now rather than the most expensive waiting one overall. */
    private Entry bestNeighbour(RegionPos pos) {
        if (this.pendingCount == 0) {
            return null;
        }
        Entry best = null;
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) {
                    continue;
                }
                Entry e = this.byPos.get(RegionPos.asLong(pos.x + x, pos.z + z));
                if (e != null && !e.taken && (best == null || e.cost > best.cost)) {
                    best = e;
                }
            }
        }
        if (best == null) {
            return null;
        }
        // a much more expensive region is waiting elsewhere: release and let the normal order decide (it may be free for us)
        Entry head = this.pending[this.firstPending];
        return best.cost * 2 >= head.cost ? best : null;
    }
}
