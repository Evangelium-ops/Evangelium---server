package ca.spottedleaf.moonrise.patches.chunk_system.scheduling;

import ca.spottedleaf.concurrentutil.executor.PrioritisedExecutor;
import ca.spottedleaf.concurrentutil.executor.queue.AreaDependentQueue;
import ca.spottedleaf.concurrentutil.util.Priority;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Knowledge - the queue for chunk tasks that write to their neighbours (FEATURES radius 1, light radius 2, relight rectangles):
 * two tasks whose areas overlap never run at the same time, same as Moonrise's AreaDependentQueue. The differences:
 * - AreaDependentQueue keeps a first-come queue per chunk position, so a waiting task blocks every later task that shares a
 *   position with it, even when that later task overlaps nothing that is running. With a pregenerator walking a compact
 *   block (Chunky's Hilbert walk) the waiting FEATURES tasks chain up behind one running task and about one runs at a time.
 *   Here a task may start as soon as no RUNNING task overlaps it.
 * - A square is claimed only when a worker actually starts the task (late claim): the pool gets a "pull" for every task that
 *   could start, and the pull claims and runs its task on the worker, or does nothing if a neighbour got there first. So no
 *   square is held by a task that sits in the pool queue behind other work.
 * To keep priorities and avoid starvation, a waiting task that is above NORMAL priority or has waited longer than
 * {@link #RESERVE_AFTER_NS} keeps its place: a later overlapping task may not start before it (like the old queue).
 * Pulls carry their task's (priority, subOrder) so the pool runs startable tasks in the old order.
 * With {@code useLegacy} every call goes to the old AreaDependentQueue unchanged.
 */
public final class RadiusTaskQueue {

    static final long RESERVE_AFTER_NS = Long.getLong("shreddedpaper.radiusQueueReserveMs", 250L) * 1_000_000L; // default 250 ms
    // waiters per position are kept in (priority, subOrder, id) order; only the first few per position are looked at, so a
    // flood of waiting tasks costs O(1) per wake-up (the ones further back move up as the front ones start).
    // Why no wake-up is lost with LOOK_AHEAD = 8: a waiting task T that is skipped at a position p (index >= 8 there) has at
    // least 8 waiters ahead of it at p. Each of those either starts later (it is then running on p, and its finish calls
    // pullAround over a square containing p) or is cancelled / re-prioritised (also pullAround over p). Every such event
    // removes one waiter ahead of T at p, so T's index at p only goes down, and after at most 8 events T is inside the look-ahead
    // at p and is re-tested by the next event there. The smallest waiter of p is always index 0, so the front of every list
    // is always re-tested; a task blocked only by a claim is re-tested when that claim is released (pullAround over the claim's
    // square, which contains the overlapping position). canStart only ever looks at waiters that come BEFORE the task, so
    // cutting the scan at 8 can only make it miss a reservation further back (fairness), never allow an overlap.
    private static final int LOOK_AHEAD = 8;
    private static final boolean VERIFY = Boolean.getBoolean("shreddedpaper.radiusQueueVerify");

    private final PrioritisedExecutor executor;
    private final AreaDependentQueue legacy;

    private final Object lock = new Object();
    private final Long2ObjectOpenHashMap<Task> claimed = new Long2ObjectOpenHashMap<>(); // position -> running task owning it
    private final Long2ObjectOpenHashMap<ArrayList<Task>> waitersByPos = new Long2ObjectOpenHashMap<>(); // position -> waiting tasks covering it
    private final AtomicLong ids = new AtomicLong();
    private final ConcurrentHashMap<Long, Task> verifyRunning = VERIFY ? new ConcurrentHashMap<>() : null;
    // generation counters (GenStats): pulls that found their task blocked
    public final AtomicLong wastedPulls = new AtomicLong();

    public RadiusTaskQueue(final PrioritisedExecutor executor, final AreaDependentQueue legacy, final boolean useLegacy) {
        this.executor = executor;
        this.legacy = useLegacy ? legacy : null;
        if (io.multipaper.shreddedpaper.util.GenStats.ENABLED && !useLegacy) {
            io.multipaper.shreddedpaper.util.GenStats.QUEUES.add(this);
        }
    }

    // The AreaDependentQueue calls used in the source tree (all of them must exist here, the field type changed):
    // createTask(x, z, radius, run, priority) - ChunkUpgradeGenericStatusTask (FEATURES), StarLightInterface (light)
    // queueTask(minX, minZ, maxX, maxZ, run)   - ThreadedLevelLightEngine.starlight$serverRelightChunks (fixlight / relight API)
    public PrioritisedExecutor.PrioritisedTask createTask(final int x, final int z, final int radius, final Runnable run, final Priority priority) {
        if (this.legacy != null) {
            return this.legacy.createTask(x, z, radius, run, priority);
        }
        return this.createTask(x - radius, z - radius, x + radius, z + radius, run, priority);
    }

    /** a task owning the rectangle minX..maxX, minZ..maxZ (inclusive), same rules as a square */
    public PrioritisedExecutor.PrioritisedTask createTask(final int minX, final int minZ, final int maxX, final int maxZ, final Runnable run, final Priority priority) {
        if (this.legacy != null) {
            return this.legacy.createTask(minX, minZ, maxX, maxZ, run, priority);
        }
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("min should be <= max"); // same check as AreaDependentQueue
        }
        return new Task(minX, minZ, maxX, maxZ, run, priority, this.executor.generateNextSubOrder());
    }

    /** same as AreaDependentQueue.queueTask(minX, minZ, maxX, maxZ, run): NORMAL priority, queued at once */
    public PrioritisedExecutor.PrioritisedTask queueTask(final int minX, final int minZ, final int maxX, final int maxZ, final Runnable run) {
        if (this.legacy != null) {
            return this.legacy.queueTask(minX, minZ, maxX, maxZ, run);
        }
        final PrioritisedExecutor.PrioritisedTask ret = this.createTask(minX, minZ, maxX, maxZ, run, Priority.NORMAL);
        ret.queue();
        return ret;
    }

    private static long key(final int x, final int z) {
        return ((long) z << 32) | (x & 0xFFFFFFFFL);
    }

    /** generation counters: waiting tasks by what blocks them: [3x3 (FEATURES) running, 5x5 (light) running, other area running, reservation only] */
    public void sampleBlockers(final long[] into) {
        synchronized (this.lock) {
            final long now = System.nanoTime();
            final java.util.Set<Task> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (final ArrayList<Task> list : this.waitersByPos.values()) {
                for (final Task t : list) {
                    if (!seen.add(t)) {
                        continue;
                    }
                    int reason = 3;
                    for (int z = t.minZ; z <= t.maxZ && reason == 3; ++z) {
                        for (int x = t.minX; x <= t.maxX; ++x) {
                            final Task owner = this.claimed.get(key(x, z));
                            if (owner != null) {
                                final int size = owner.maxX - owner.minX;
                                reason = size == 2 ? 0 : size == 4 ? 1 : 2;
                                break;
                            }
                        }
                    }
                    if (reason == 3 && this.canStart(t, now)) {
                        continue; // startable now (its pull is on the way)
                    }
                    into[reason]++;
                }
            }
        }
    }

    // under lock: no running task owns a position of the square, and no waiting task that comes first and keeps its place
    // covers one
    private boolean canStart(final Task task, final long now) {
        for (int z = task.minZ; z <= task.maxZ; ++z) {
            for (int x = task.minX; x <= task.maxX; ++x) {
                final long k = key(x, z);
                if (this.claimed.containsKey(k)) {
                    return false;
                }
                final ArrayList<Task> waiters = this.waitersByPos.get(k);
                if (waiters != null) {
                    for (int i = 0, len = Math.min(waiters.size(), LOOK_AHEAD); i < len; ++i) {
                        final Task other = waiters.get(i);
                        if (other == task || other.compareTo(task) > 0) {
                            break; // sorted: the rest come after this task
                        }
                        if (other.reserves(now)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    // under lock
    private void setClaim(final Task task, final boolean claim) {
        for (int z = task.minZ; z <= task.maxZ; ++z) {
            for (int x = task.minX; x <= task.maxX; ++x) {
                if (claim) {
                    this.claimed.put(key(x, z), task);
                } else {
                    this.claimed.remove(key(x, z));
                }
            }
        }
    }

    // under lock
    private void setWaiting(final Task task, final boolean add) {
        for (int z = task.minZ; z <= task.maxZ; ++z) {
            for (int x = task.minX; x <= task.maxX; ++x) {
                final long k = key(x, z);
                if (add) {
                    ArrayList<Task> waiters = this.waitersByPos.get(k);
                    if (waiters == null) {
                        this.waitersByPos.put(k, waiters = new ArrayList<>(4));
                    }
                    final int at = Collections.binarySearch(waiters, task);
                    waiters.add(at < 0 ? -at - 1 : at, task);
                } else {
                    final ArrayList<Task> waiters = this.waitersByPos.get(k);
                    waiters.remove(task);
                    if (waiters.isEmpty()) {
                        this.waitersByPos.remove(k);
                    }
                }
            }
        }
    }

    // under lock: give a pull to every waiting task around the given square that could start now and has none yet
    private void pullAround(final Task area, final List<PrioritisedExecutor.PrioritisedTask> toQueue) {
        final long now = System.nanoTime();
        for (int z = area.minZ; z <= area.maxZ; ++z) {
            for (int x = area.minX; x <= area.maxX; ++x) {
                final ArrayList<Task> waiters = this.waitersByPos.get(key(x, z));
                if (waiters != null) {
                    for (int i = 0, len = Math.min(waiters.size(), LOOK_AHEAD); i < len; ++i) {
                        final Task t = waiters.get(i);
                        if (t.pull == null && this.canStart(t, now)) {
                            this.givePull(t, toQueue);
                        }
                    }
                }
            }
        }
    }

    // under lock
    private void givePull(final Task task, final List<PrioritisedExecutor.PrioritisedTask> toQueue) {
        task.pull = this.executor.createTask(task::pulled, task.priority, task.subOrder, task.stream);
        toQueue.add(task.pull);
    }

    private static void queueAll(final List<PrioritisedExecutor.PrioritisedTask> toQueue) {
        for (int i = 0, len = toQueue.size(); i < len; ++i) {
            toQueue.get(i).queue();
        }
    }

    // -Dshreddedpaper.radiusQueueVerify=true: independent check that no two running tasks share a position
    private void verify(final Task task, final boolean start) {
        for (int z = task.minZ; z <= task.maxZ; ++z) {
            for (int x = task.minX; x <= task.maxX; ++x) {
                if (start) {
                    final Task other = this.verifyRunning.putIfAbsent(key(x, z), task);
                    if (other != null) {
                        throw new IllegalStateException("RadiusTaskQueue: overlapping tasks running at " + x + "," + z);
                    }
                } else {
                    this.verifyRunning.remove(key(x, z), task);
                }
            }
        }
    }

    /** for tests: positions currently claimed and waiting lists left (both empty when the queue is idle) */
    public String debugState() {
        synchronized (this.lock) {
            return "claimed=" + this.claimed.size() + " waitingPositions=" + this.waitersByPos.size();
        }
    }

    private final class Task implements PrioritisedExecutor.PrioritisedTask, Comparable<Task> {
        static final int NEW = 0, WAITING = 1, RUNNING = 3, DONE = 4;

        final int minX, minZ, maxX, maxZ; // owned area, inclusive
        final Runnable run;
        final long id = RadiusTaskQueue.this.ids.getAndIncrement();
        // all below under lock
        Priority priority;
        long subOrder;
        long stream;
        long queuedAt;
        int state = NEW;
        PrioritisedExecutor.PrioritisedTask pull; // the pool task that will try to start this one, null if none

        Task(final int minX, final int minZ, final int maxX, final int maxZ, final Runnable run, final Priority priority, final long subOrder) {
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.run = run;
            this.priority = priority;
            this.subOrder = subOrder;
        }

        @Override
        public int compareTo(final Task other) {
            final int p = this.priority.priority - other.priority.priority;
            if (p != 0) {
                return p;
            }
            final int s = Long.compare(this.subOrder, other.subOrder);
            return s != 0 ? s : Long.compare(this.id, other.id);
        }

        // under lock: a waiting task that keeps its place against later overlapping tasks
        boolean reserves(final long now) {
            return this.priority.isHigherPriority(Priority.NORMAL) || now - this.queuedAt > RESERVE_AFTER_NS;
        }

        // runs on a pool worker: claim and run this task if it can start now, otherwise wait for the next wake-up
        void pulled() {
            synchronized (RadiusTaskQueue.this.lock) {
                this.pull = null;
                if (this.state != WAITING) {
                    return;
                }
                if (!RadiusTaskQueue.this.canStart(this, System.nanoTime())) {
                    RadiusTaskQueue.this.wastedPulls.incrementAndGet();
                    return; // a neighbour started first; its release wakes this one again
                }
                RadiusTaskQueue.this.setWaiting(this, false);
                RadiusTaskQueue.this.setClaim(this, true);
                this.state = RUNNING;
            }
            if (VERIFY) {
                RadiusTaskQueue.this.verify(this, true);
            }
            try {
                this.run.run();
            } finally {
                if (VERIFY) {
                    RadiusTaskQueue.this.verify(this, false);
                }
                final List<PrioritisedExecutor.PrioritisedTask> toQueue = new ArrayList<>();
                synchronized (RadiusTaskQueue.this.lock) {
                    this.state = DONE;
                    RadiusTaskQueue.this.setClaim(this, false);
                    RadiusTaskQueue.this.pullAround(this, toQueue);
                }
                queueAll(toQueue);
            }
        }

        @Override
        public PrioritisedExecutor getExecutor() {
            return RadiusTaskQueue.this.executor;
        }

        @Override
        public boolean queue() {
            final List<PrioritisedExecutor.PrioritisedTask> toQueue = new ArrayList<>(1);
            synchronized (RadiusTaskQueue.this.lock) {
                if (this.state != NEW) {
                    return false;
                }
                this.queuedAt = System.nanoTime();
                this.state = WAITING;
                RadiusTaskQueue.this.setWaiting(this, true);
                if (RadiusTaskQueue.this.canStart(this, this.queuedAt)) {
                    RadiusTaskQueue.this.givePull(this, toQueue);
                }
            }
            queueAll(toQueue);
            return true;
        }

        @Override
        public boolean isQueued() {
            synchronized (RadiusTaskQueue.this.lock) {
                return this.state == WAITING;
            }
        }

        @Override
        public boolean cancel() {
            final List<PrioritisedExecutor.PrioritisedTask> toQueue = new ArrayList<>();
            final PrioritisedExecutor.PrioritisedTask toCancel;
            synchronized (RadiusTaskQueue.this.lock) {
                switch (this.state) {
                    case NEW:
                        this.state = DONE;
                        return true;
                    case WAITING:
                        RadiusTaskQueue.this.setWaiting(this, false);
                        this.state = DONE;
                        toCancel = this.pull;
                        this.pull = null;
                        RadiusTaskQueue.this.pullAround(this, toQueue); // it may have kept others out
                        break;
                    default:
                        return false;
                }
            }
            if (toCancel != null) {
                toCancel.cancel();
            }
            queueAll(toQueue);
            return true;
        }

        @Override
        public boolean execute() {
            // same as AreaDependentQueue: run now on this thread if it was not started yet
            if (!this.cancel()) {
                return false;
            }
            this.run.run();
            return true;
        }

        @Override
        public Priority getPriority() {
            synchronized (RadiusTaskQueue.this.lock) {
                return this.state >= RUNNING ? Priority.COMPLETING : this.priority;
            }
        }

        // changes priority / order; the waiter lists are sorted by these fields, so the task is taken out and put back, and
        // the waiters around it are re-tested (a raised task now keeps its place, a lowered one may let others start)
        private boolean update(final Priority priority, final long subOrder, final long stream) {
            final List<PrioritisedExecutor.PrioritisedTask> toQueue = new ArrayList<>();
            final PrioritisedExecutor.PrioritisedTask forward;
            synchronized (RadiusTaskQueue.this.lock) {
                if (this.state >= RUNNING) {
                    return false;
                }
                if (this.priority == priority && this.subOrder == subOrder && this.stream == stream) {
                    return false;
                }
                final boolean waiting = this.state == WAITING;
                if (waiting) {
                    RadiusTaskQueue.this.setWaiting(this, false);
                }
                this.priority = priority;
                this.subOrder = subOrder;
                this.stream = stream;
                if (waiting) {
                    RadiusTaskQueue.this.setWaiting(this, true);
                    RadiusTaskQueue.this.pullAround(this, toQueue);
                }
                forward = this.pull;
            }
            if (forward != null) {
                forward.setPrioritySubOrderStream(priority, subOrder, stream);
            }
            queueAll(toQueue);
            return true;
        }

        private Priority priority0() {
            synchronized (RadiusTaskQueue.this.lock) {
                return this.priority;
            }
        }

        @Override
        public boolean setPriority(final Priority priority) {
            return this.update(priority, this.getSubOrder(), this.getStream());
        }

        @Override
        public boolean raisePriority(final Priority priority) {
            return !this.priority0().isHigherOrEqualPriority(priority) && this.setPriority(priority);
        }

        @Override
        public boolean lowerPriority(final Priority priority) {
            return !this.priority0().isLowerOrEqualPriority(priority) && this.setPriority(priority);
        }

        @Override
        public long getSubOrder() {
            synchronized (RadiusTaskQueue.this.lock) {
                return this.subOrder;
            }
        }

        @Override
        public boolean setSubOrder(final long subOrder) {
            return this.update(this.priority0(), subOrder, this.getStream());
        }

        @Override
        public boolean raiseSubOrder(final long subOrder) {
            return this.getSubOrder() < subOrder && this.setSubOrder(subOrder);
        }

        @Override
        public boolean lowerSubOrder(final long subOrder) {
            return this.getSubOrder() > subOrder && this.setSubOrder(subOrder);
        }

        @Override
        public long getStream() {
            synchronized (RadiusTaskQueue.this.lock) {
                return this.stream;
            }
        }

        @Override
        public boolean setStream(final long stream) {
            return this.update(this.priority0(), this.getSubOrder(), stream);
        }

        @Override
        public boolean setPrioritySubOrderStream(final Priority priority, final long subOrder, final long stream) {
            return this.update(priority, subOrder, stream);
        }

        @Override
        public PrioritisedExecutor.PriorityState getPriorityState() {
            synchronized (RadiusTaskQueue.this.lock) {
                return this.state >= RUNNING ? null : new PrioritisedExecutor.PriorityState(this.priority, this.subOrder, this.stream);
            }
        }
    }
}
