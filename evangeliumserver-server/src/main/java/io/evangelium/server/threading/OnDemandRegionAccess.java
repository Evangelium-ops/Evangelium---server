package io.evangelium.server.threading;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.logging.LogUtils;
import io.evangelium.server.config.EvangeliumServerConfiguration;
import io.evangelium.server.region.RegionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Evangelium - plugin compatibility: code that Paper would run on its one main thread may take the regions it touches.
 *
 * <p>Only called from the failing branch of a thread check (TickThread.ensureTickThread), so code that only asks whether it owns a
 * region keeps its old answer, and a check that passes costs nothing extra. Two cases:
 * <ul>
 *   <li><b>Server thread outside the world tick</b> (startup, onEnable, console commands, the Bukkit heartbeat, player list tick): it takes
 *       the level's global region lock, which stands for every region of that level. Nothing else can lock a region of the level while
 *       it is held. Released before the worlds tick, at the end of each tick, and when startup is done.</li>
 *   <li><b>Code of a plugin without Folia support on a tick thread</b> (it holds its plugin lock, like Paper's main thread would serialize
 *       it): it takes the one region it touches (radius 0). Released when the outermost plugin call of this thread ends.</li>
 * </ul>
 * Every take is a bounded try: if the region (or global lock) cannot be had within on-demand-region-wait-ms, or the owner of the region
 * waits for a plugin lock this thread holds, the check fails as it did before. Never a deadlock, at worst the old exception.
 */
public final class OnDemandRegionAccess {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    /** -Devangeliumserver.onDemandRegions=false turns both cases off regardless of evangelium.yml. */
    private static final boolean SYSTEM_SWITCH = Boolean.parseBoolean(System.getProperty("evangeliumserver.onDemandRegions", "true"));

    /** True while the Server thread waits for the worlds (regions tick): the Server thread may not take the world then. */
    private static volatile boolean worldsTicking = false;

    /** Server thread may take the world only during startup and inside tickServer (not across the sleep between ticks). */
    private static volatile boolean serverWindow = true;
    /** Levels whose global take failed in this tick (one bounded wait per level and tick). Server thread only. */
    private static final java.util.Set<ServerLevel> failedThisTick = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    /** Ticks in a row in which a level's global lock had a read stamp but no region entry (a thread RegionGuard abandoned still holds
     *  its stamp): after 3 the Server thread stops waiting for that level until the stamp is gone, then it takes the world again. */
    private static final Map<ServerLevel, Integer> orphanTicks = new IdentityHashMap<>();
    private static volatile long lastOrphanWarn = 0;

    /** MinecraftServer: after tickServer, and when startup is done. Gives the world back and closes the window until the next tick. */
    public static void endServerTick() {
        if (!isServerThread()) {
            return;
        }
        releaseServerThread();
        serverWindow = false;
        failedThisTick.clear();
    }

    /** MinecraftServer: right before tickServer. */
    public static void beginServerTick() {
        if (isServerThread()) {
            serverWindow = true;
        }
    }

        /** Server thread: levels whose global lock it holds, with the stamp. Only touched by the Server thread. */
    private static final Map<ServerLevel, Long> serverThreadGlobal = new IdentityHashMap<>();

    /** Regions of tick threads that are blocked waiting for a plugin lock: the holder of that lock may borrow them (see takeRegions). */
    private static final Map<Thread, Map<ServerLevel, EvangeliumServerRegionLocker.Loan>> waitingLoans = new java.util.concurrent.ConcurrentHashMap<>();

    /** Loans this thread borrowed from blocked threads; given back at the end of every plugin call and before a lock back-off. */
    private record Borrowed(EvangeliumServerRegionLocker locker, EvangeliumServerRegionLocker.Loan loan) {
    }

    private static final ThreadLocal<List<Borrowed>> borrowed = ThreadLocal.withInitial(ArrayList::new);

        /** Tick threads: regions taken for the current outermost plugin call. */
    private static final ThreadLocal<List<EvangeliumServerRegionLocker.RegionLock>> pluginTaken = ThreadLocal.withInitial(ArrayList::new);

    private static volatile long lastWarn = 0;

    private OnDemandRegionAccess() {
    }

    private static EvangeliumServerConfiguration.Multithreading config() {
        EvangeliumServerConfiguration c = EvangeliumServerConfiguration.get();
        return c == null ? null : c.multithreading;
    }

    private static long waitNanos() {
        EvangeliumServerConfiguration.Multithreading c = config();
        return TimeUnit.MILLISECONDS.toNanos(c == null ? 250 : Math.max(0, c.onDemandRegionWaitMs));
    }

    // ---------------------------------------------------------------- called by the server tick

    /** MinecraftServer: right before the world tick futures start, and with false when they are all done. */
    public static void setWorldsTicking(boolean ticking) {
        if (ticking) {
            releaseServerThread();
        }
        worldsTicking = ticking;
    }

    /** MinecraftServer: end of a tick, end of startup. Releases the global locks the Server thread took. */
    public static void releaseServerThread() {
        if (!isServerThread()) {
            return;
        }
        worldsTicking = false; // also after a failed world tick
        if (serverThreadGlobal.isEmpty()) {
            return;
        }
        for (Map.Entry<ServerLevel, Long> e : serverThreadGlobal.entrySet()) {
            e.getKey().chunkScheduler.getRegionLocker().releaseGlobalForOwner(e.getValue());
        }
        serverThreadGlobal.clear();
    }

    private static boolean isServerThread() {
        MinecraftServer server = MinecraftServer.getServer();
        return server != null && Thread.currentThread() == server.getRunningThread();
    }

    // ---------------------------------------------------------------- called by SynchronousPluginExecution

    /** The outermost plugin call of this thread ended: give back what it took. */
    static void releasePluginTaken() {
        List<EvangeliumServerRegionLocker.RegionLock> taken = pluginTaken.get();
        if (taken.isEmpty()) {
            return;
        }
        for (int i = taken.size() - 1; i >= 0; i--) {
            try {
                taken.get(i).unlock();
            } catch (Throwable t) {
                LOGGER.error("Could not give back an on-demand region lock", t);
            }
        }
        taken.clear();
    }

    /**
     * A tick thread is about to block on a plugin lock while it holds regions: it publishes them. Only the thread that holds the plugin
     * lock it waits for may borrow them (it checks that this thread is queued on one of its locks), and it gives them back before it
     * releases any plugin lock, so this thread never runs while its regions are lent. Returns whether something was published.
     */
    static boolean publishLoans() {
        if (!SYSTEM_SWITCH || !EvangeliumServerTickThread.isEvangeliumServerTickThread()) {
            return false;
        }
        if (IntraRegion.inParallelPhase()) {
            return false; // helpers (or the owner while helpers run) share these regions: never lend them to a third thread
        }
        EvangeliumServerConfiguration.Multithreading c = config();
        MinecraftServer server = MinecraftServer.getServer();
        if (c == null || !c.takeRegionsForPluginsOnDemand || server == null) {
            return false;
        }
        Map<ServerLevel, EvangeliumServerRegionLocker.Loan> loans = null;
        for (ServerLevel level : server.getAllLevels()) {
            EvangeliumServerRegionLocker locker = level.chunkScheduler.getRegionLocker();
            if (!locker.getLockedRegions().isEmpty()) {
                if (loans == null) {
                    loans = new IdentityHashMap<>();
                }
                loans.put(level, locker.lend());
            }
        }
        if (loans == null) {
            return false;
        }
        waitingLoans.put(Thread.currentThread(), loans);
        return true;
    }

    static void withdrawLoans() {
        waitingLoans.remove(Thread.currentThread());
    }

    /**
     * After waiting with published regions: are they still this thread's? RegionGuard may have abandoned the tick meanwhile (it removes
     * the thread's lock entries). If so the stale regions are dropped from this thread's sets and false is returned: the caller fails
     * the plugin call instead of going on with a view of regions others may now hold.
     */
    static boolean revalidateAfterWait() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return true;
        }
        boolean ok = true;
        for (ServerLevel level : server.getAllLevels()) {
            ok &= level.chunkScheduler.getRegionLocker().revalidateOwnLocks();
        }
        return ok;
    }

    // ---- abandoned region ticks (RegionGuard removed this thread's lock entries while it waited for a plugin lock)
    private static final ThreadLocal<boolean[]> TICK_ABANDONED = ThreadLocal.withInitial(() -> new boolean[1]);

    /** The current region tick of this thread was abandoned: it must end at once and change nothing (no entity discards either). */
    public static boolean isTickAbandoned() {
        return TICK_ABANDONED.get()[0];
    }

    static void markTickAbandoned() {
        TICK_ABANDONED.get()[0] = true;
    }

    /** EvangeliumServerChunkTicker: at the start and end of every region tick. */
    public static void clearTickAbandoned() {
        TICK_ABANDONED.get()[0] = false;
    }

    /** Throws RegionTickAbandonedError when this thread's region tick was abandoned (checked between the phases of a region tick). */
    public static void checkTickAbandoned() {
        if (TICK_ABANDONED.get()[0]) {
            throw new RegionTickAbandonedError("[compat] this region tick was abandoned by RegionGuard; it ends here");
        }
    }

    /** Whether this thread holds a borrowed loan right now. */
    static boolean hasBorrowedLoans() {
        return !borrowed.get().isEmpty();
    }

    /** Gives back every loan this thread borrowed. */
    static void giveBackLoans() {
        List<Borrowed> list = borrowed.get();
        if (list.isEmpty()) {
            return;
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            list.get(i).locker().giveBackExtra(list.get(i).loan());
        }
        list.clear();
    }

        // ---------------------------------------------------------------- called by TickThread.ensureTickThread on a failed check

    /**
     * Tries to give the current thread the regions covering the chunk rectangle. Returns true when the thread now owns all of them.
     */
    public static boolean tryTake(Level world, int fromChunkX, int fromChunkZ, int toChunkX, int toChunkZ) {
        if (!SYSTEM_SWITCH || !(world instanceof ServerLevel level) || isTickAbandoned()) {
            return false; // never take regions for an abandoned region tick
        }
        EvangeliumServerConfiguration.Multithreading c = config();
        if (c == null) {
            return false;
        }
        if (isServerThread()) {
            return c.serverThreadTakesWorld && serverWindow && !worldsTicking && takeGlobal(level);
        }
        if (c.takeRegionsForPluginsOnDemand && SynchronousPluginExecution.holdsAnyLock() && EvangeliumServerTickThread.isEvangeliumServerTickThread()
                && !IntraRegion.inParallelPhase()) { // never from a helper or an owner whose helpers run: they share their regions
            return takeRegions(level, fromChunkX, fromChunkZ, toChunkX, toChunkZ);
        }
        return false;
    }

    private static boolean takeGlobal(ServerLevel level) {
        if (serverThreadGlobal.containsKey(level)) {
            return true;
        }
        if (failedThisTick.contains(level)) {
            return false; // one bounded wait per level and tick, not one per failed check
        }
        EvangeliumServerRegionLocker locker = level.chunkScheduler.getRegionLocker();
        // Ticks in a row whose bounded wait failed (or that saw an orphan read stamp: a stamp with no region entry, left by a tick RegionGuard
        // abandoned). From the third one on, only one non-blocking try per tick (no 250 ms wait); the first success resets it, so the
        // Server thread recovers by itself once the stamp is gone
        boolean stuck = orphanTicks.getOrDefault(level, 0) >= 3;
        if (locker.hasOrphanReadStamp()) {
            stuck |= orphanTicks.merge(level, 1, Integer::sum) >= 3;
        }
        long deadline = stuck ? System.nanoTime() : System.nanoTime() + waitNanos();
        int tries = 0;
        long stamp;
        while ((stamp = locker.tryTakeGlobalForOwner()) == 0) {
            if (System.nanoTime() - deadline > 0) {
                failedThisTick.add(level);
                if (!stuck) {
                    orphanTicks.merge(level, 1, Integer::sum);
                }
                long now = System.nanoTime();
                if (stuck && now - lastOrphanWarn > TimeUnit.MINUTES.toNanos(1)) {
                    lastOrphanWarn = now;
                    LOGGER.warn("[compat] the Server thread cannot take the world " + level.getWorld().getName() + " for several ticks (a region lock stamp is still held, likely by a tick RegionGuard abandoned); it only tries once per tick until it works again");
                } else if (!stuck) {
                    warn("the Server thread could not take the world " + level.getWorld().getName() + " (a region is locked by another thread)");
                }
                return false;
            }
            LockSupport.parkNanos(Math.min(200_000L, 10_000L * (++tries)));
        }
        orphanTicks.remove(level);
        serverThreadGlobal.put(level, stamp);
        return true;
    }

    /**
     * Takes the regions around the chunk rectangle with the region lock radius (the 3x3 around each region it covers, as a region tick
     * holds them): a block changed at a region border may update blocks in the next region without a further check.
     */
    private static boolean takeRegions(ServerLevel level, int fromChunkX, int fromChunkZ, int toChunkX, int toChunkZ) {
        EvangeliumServerRegionLocker locker = level.chunkScheduler.getRegionLocker();
        final int r = EvangeliumServerRegionLocker.REGION_LOCK_RADIUS;
        int minX = (Math.min(fromChunkX, toChunkX) >> RegionPos.REGION_SHIFT) - r, maxX = (Math.max(fromChunkX, toChunkX) >> RegionPos.REGION_SHIFT) + r;
        int minZ = (Math.min(fromChunkZ, toChunkZ) >> RegionPos.REGION_SHIFT) - r, maxZ = (Math.max(fromChunkZ, toChunkZ) >> RegionPos.REGION_SHIFT) + r;
        if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) > 64) {
            return false; // a huge area is not something to lock region by region
        }
        long deadline = System.nanoTime() + waitNanos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                RegionPos pos = new RegionPos(x, z);
                if (locker.lostBorrowed(pos)) {
                    // a region lent to this call by a blocked thread was released by RegionGuard (it abandoned that thread's tick): this
                    // call may hold references into it, so it stops here instead of going on in a region others can now lock
                    throw new IllegalStateException("[compat] region " + pos + " was lent to this plugin call by a blocked thread and has been released by RegionGuard; the plugin call is stopped");
                }
                if (locker.hasLock(pos)) {
                    continue;
                }
                int tries = 0;
                EvangeliumServerRegionLocker.RegionLock lock;
                while ((lock = locker.internalTryTakeLockNow(pos, 0)) == null) {
                    Thread owner = locker.ownerOf(pos);
                    if (owner != null && SynchronousPluginExecution.isWaitingForOurLocks(owner)) {
                        // the owner is blocked until we release a plugin lock we hold: act in its place with its regions, give them back first
                        Map<ServerLevel, EvangeliumServerRegionLocker.Loan> loans = waitingLoans.get(owner);
                        EvangeliumServerRegionLocker.Loan loan = loans == null ? null : loans.get(level);
                        if (loan != null && loan.local().contains(pos)) {
                            locker.borrowExtra(loan);
                            borrowed.get().add(new Borrowed(locker, loan));
                            if (locker.hasLock(pos)) {
                                break;
                            }
                        }
                        warn("region " + pos + " is held by " + owner.getName() + ", which waits for a plugin lock held by " + Thread.currentThread().getName());
                        return false;
                    }
                    if (System.nanoTime() - deadline > 0) {
                        warn(Thread.currentThread().getName() + " could not take region " + pos + " in " + level.getWorld().getName() + " in time (held by " + (owner == null ? "?" : owner.getName()) + ")");
                        return false;
                    }
                    LockSupport.parkNanos(Math.min(200_000L, 10_000L * (++tries)));
                }
                if (lock != null) {
                    pluginTaken.get().add(lock);
                }
            }
        }
        return true;
    }

    private static void warn(String what) {
        long now = System.nanoTime();
        if (now - lastWarn > TimeUnit.SECONDS.toNanos(10)) {
            lastWarn = now;
            LOGGER.warn("[compat] " + what + "; the thread check fails as before");
        }
    }
}
