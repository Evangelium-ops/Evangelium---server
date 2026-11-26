package io.evangelium.server.threading;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.logging.LogUtils;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import io.evangelium.server.config.EvangeliumServerConfiguration;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class SynchronousPluginExecution {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    private static final Map<String, List<String>> cachedDependencyLists = new ConcurrentHashMap<>(); // Does not support dynamic plugin reloading, but oh well
    private static final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    private static final ThreadLocal<WeakReference<Plugin>> currentPlugin = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> heldPluginLocks = ThreadLocal.withInitial(ArrayList::new); // Use a list as the same plugin may be locked multiple times in a single thread due to recursion

    /** Evangelium - whether this thread holds every plugin lock that a call of this (non-Folia) plugin takes. False for Folia plugins. */
    public static boolean holdsLocksOf(Plugin plugin) {
        List<String> needed = cachedDependencyLists.get(plugin.getName());
        return needed != null && !needed.isEmpty() && heldPluginLocks.get().containsAll(needed);
    }

    /** True while this thread runs code of a plugin without Folia support (holding its plugin lock). Evangelium */
    public static boolean holdsAnyLock() {
        return !heldPluginLocks.get().isEmpty();
    }

    // Evangelium start - plugin compatibility
    /** Whether the given thread is queued on one of the plugin locks the current thread holds (it would wait for us). */
    static boolean isWaitingForOurLocks(Thread thread) {
        for (String plugin : heldPluginLocks.get()) {
            ReentrantLock lock = locks.get(plugin);
            if (lock != null && lock.hasQueuedThread(thread)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The plugin a Bukkit command belongs to: a PluginCommand's plugin, a PluginIdentifiableCommand's plugin (WorldEdit, BentoBox, ACF
     * based plugins), else the plugin whose class loader loaded the command's class (custom Command subclasses registered straight into
     * the CommandMap). Null for server and vanilla commands.
     */
    public static Plugin owningPlugin(org.bukkit.command.Command command) {
        if (command instanceof org.bukkit.command.PluginIdentifiableCommand identifiable) { // PluginCommand implements it too
            try {
                Plugin plugin = identifiable.getPlugin();
                if (plugin != null) {
                    return plugin;
                }
            } catch (Throwable ignored) {
                // a plugin's getPlugin() that throws: fall through to the class loader
            }
        }
        ClassLoader loader = command.getClass().getClassLoader();
        if (loader instanceof io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader pluginLoader) {
            return pluginLoader.getPlugin();
        }
        return null;
    }
    // Evangelium end

    public static Plugin getCurrentPlugin() {
        WeakReference<Plugin> pluginRef = currentPlugin.get();
        return pluginRef == null ? null : pluginRef.get();
    }

    public static void executeNoException(Plugin plugin, RunnableWithException runnable) {
        try {
            execute(plugin, runnable);
        } catch (RuntimeException e) {
            throw e; // passthrough, no need to wrap it again
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void execute(Plugin plugin, RunnableWithException runnable) throws Exception {
        if (OnDemandRegionAccess.isTickAbandoned()) {
            // Evangelium - a plugin that caught the RegionTickAbandonedError may not go on calling into plugins on this abandoned tick
            throw new RegionTickAbandonedError("[compat] this region tick was abandoned by RegionGuard; no more plugin calls on it");
        }
        EvangeliumServerConfiguration config = EvangeliumServerConfiguration.get();
        if (plugin == null || config == null || !config.multithreading.runUnsupportedPluginsInSync || plugin.getDescription().isFoliaSupported() || TickThread.isShutdownThread()) {
            // Multi-thread safe plugin, run it straight away
            runnable.run();
            return;
        }

        // Lock the plugins in a predictable order to prevent deadlocks
        List<String> pluginsToLock = cachedDependencyLists.get(plugin.getName());

        if (pluginsToLock == null) {
            // computeIfAbsent requires an expensive synchronized call even if the value is already present, so check with a get first
            pluginsToLock = cachedDependencyLists.computeIfAbsent(plugin.getName(), (name) -> {
                TreeSet<String> dependencyList = new TreeSet<>(Comparator.naturalOrder());
                LOGGER.info("Plugin {} does not support Folia! Initializing synchronous execution. This may cause a performance degradation.", plugin);
                fillPluginsToLock(plugin, dependencyList, new ArrayList<>());
                LOGGER.info("Dependency list calculated for {}: {}", plugin, dependencyList);
                return new ArrayList<>(dependencyList);
            });
        }

        lock(pluginsToLock);

        WeakReference<Plugin> parentPlugin = currentPlugin.get();
        try {
            currentPlugin.set(new WeakReference<>(plugin));
            runnable.run();
        } finally {
            currentPlugin.set(parentPlugin);
            // Evangelium - borrowed regions (any depth) and regions taken on demand (outermost call) go back before the plugin locks
            OnDemandRegionAccess.giveBackLoans();
            if (heldPluginLocks.get().size() == pluginsToLock.size()) {
                OnDemandRegionAccess.releasePluginTaken();
            }
            for (String pluginToLock : pluginsToLock) {
                getLock(pluginToLock).unlock();
                heldPluginLocks.get().remove(pluginToLock);
            }
        }
    }

    private static ReentrantLock getLock(String plugin) {
        ReentrantLock lock = locks.get(plugin);

        if (lock == null) {
            // computeIfAbsent requires an expensive synchronized call even if the value is already present, so check with a get first
            lock = locks.computeIfAbsent(plugin, (name) -> new ReentrantLock());
        }

        return lock;
    }

    private static void lock(List<String> pluginsToLock) {
        List<String> locksToAcquire = new ArrayList<>(pluginsToLock); // Must be a list as we can hold the same lock multiple times due to recursion

        if (!heldPluginLocks.get().isEmpty()) {
            // We already have some locks, take care to avoid a deadlock with another thread
            if (tryLockNow(locksToAcquire)) {
                heldPluginLocks.get().addAll(locksToAcquire);
                return;
            } else {
                // We failed to instantly acquire the lock, back off from all locks and try again to avoid a deadlock
                if (OnDemandRegionAccess.hasBorrowedLoans()) {
                    // Evangelium - this plugin call acts on regions lent by a thread blocked on our locks. Backing off would let that thread
                    // run again while the frames above may still hold references into its regions, so the call is stopped instead. The
                    // outer frames' finally blocks give the loans and their plugin locks back as usual.
                    OnDemandRegionAccess.giveBackLoans();
                    throw new IllegalStateException("[compat] plugin call stopped: it acts on regions lent by a blocked thread and now has to wait for another plugin's lock (" + locksToAcquire + ")");
                }
                for (String plugin : heldPluginLocks.get()) {
                    getLock(plugin).unlock();
                    locksToAcquire.add(plugin);
                }
                heldPluginLocks.get().clear();
                Collections.sort(locksToAcquire); // Ensure we lock in a predictable order to avoid deadlocks
            }
        }

        boolean published = false; // Evangelium - while blocked, the holder of the plugin lock may borrow this thread's regions
        long waitStart = 0;
        try {
            for (String plugin : locksToAcquire) {
                ReentrantLock lock = getLock(plugin);
                if (!lock.tryLock()) {
                    if (waitStart == 0) {
                        waitStart = System.nanoTime();
                    }
                    if (!published) {
                        try {
                            published = OnDemandRegionAccess.publishLoans();
                        } catch (Throwable t) {
                            LOGGER.error("[compat] could not publish this thread's regions while it waits for a plugin lock", t); // waiting without lending is still correct
                        }
                    }
                    // ReentrantLock.lock() ignores interrupts: a RegionGuard interrupt (or an abandoned tick) does not let this thread run
                    // again until it really holds the lock, and a borrower gives every loan back before it releases any plugin lock
                    // (execute's finally and the back-off above). So this thread never runs on its regions while they are lent.
                    lock.lock();
                }
                heldPluginLocks.get().add(plugin);
            }
        } finally {
            if (published) {
                OnDemandRegionAccess.withdrawLoans();
            }
        }
        // Evangelium - a tick thread that waited for a plugin lock (lending or not: an owner inside an intra-region phase never lends)
        // checks its regions are still its own: RegionGuard may have abandoned
        // its tick meanwhile (removing its lock entries). Then the stale regions are dropped from its view, the locks this call took are
        // released, the thread is marked abandoned and a RegionTickAbandonedError ends the whole region tick (event dispatch, entity and
        // block entity guards rethrow it instead of logging it, discarding the entity or going on).
        if (published || (waitStart != 0 && EvangeliumServerTickThread.isEvangeliumServerTickThread())) { // every contended wait of a tick thread: RegionGuard measures from the tick start, not from the wait
            boolean ok;
            try {
                ok = OnDemandRegionAccess.revalidateAfterWait();
            } catch (Throwable t) {
                LOGGER.error("[compat] could not check this thread's regions after a plugin lock wait", t);
                ok = false;
            }
            if (!ok) {
                for (String plugin : pluginsToLock) {
                    getLock(plugin).unlock();
                    heldPluginLocks.get().remove(plugin);
                }
                OnDemandRegionAccess.markTickAbandoned();
                throw new RegionTickAbandonedError("[compat] this region tick was abandoned by RegionGuard while it waited for a plugin lock; the plugin call is not run and the region tick ends");
            }
        }
    }

    private static boolean tryLockNow(List<String> locksToReacquire) {
        boolean success = true;

        List<ReentrantLock> locksAquired = new ArrayList<>();

        for (String plugin : locksToReacquire) {
            ReentrantLock lock = getLock(plugin);
            if (lock.tryLock()) {
                locksAquired.add(lock);
            } else {
                success = false;
                break;
            }
        }

        if (!success) {
            for (ReentrantLock lock : locksAquired) {
                lock.unlock();
            }
        }

        return success;
    }

    private static boolean fillPluginsToLock(Plugin plugin, TreeSet<String> pluginsToLock, List<String> parentList) {
        if (plugin.getDescription().isFoliaSupported()) {
            // Multi-thread safe plugin, we don't need to lock it
            return false;
        }

        if (pluginsToLock.contains(plugin.getName())) {
            // Already visited
            return true;
        }

        if (parentList.contains(plugin.getName())) {
            // Cyclic graph, add all the plugins in the cycle
            int i = parentList.size() - 1;
            do {
                pluginsToLock.add(parentList.get(i));
            } while (!parentList.get(i--).equals(plugin.getName()));
            return false;
        }
        parentList.add(plugin.getName());

        boolean hasDependency = false;

        for (String depend : plugin.getDescription().getDepend()) {
            Plugin dependPlugin = plugin.getServer().getPluginManager().getPlugin(depend);
            if (dependPlugin != null) {
                hasDependency |= fillPluginsToLock(dependPlugin, pluginsToLock, parentList);
            } else {
                LOGGER.warn("Could not find dependency " + depend + " for plugin " + plugin.getName() + " even though it is a required dependency - this code shouldn't've been able to be run!");
            }
        }

        for (String softDepend : plugin.getDescription().getSoftDepend()) {
            Plugin softDependPlugin = plugin.getServer().getPluginManager().getPlugin(softDepend);
            if (softDependPlugin != null) {
                hasDependency |= fillPluginsToLock(softDependPlugin, pluginsToLock, parentList);
            }
        }

        if (!hasDependency) {
            // Only add our own plugin if we have no dependencies to lock
            // If we have a dependency, there's no point in locking this plugin by itself as we'll always be locking the dependency anyway
            pluginsToLock.add(plugin.getName());
        }

        String lastRemoved = parentList.removeLast();
        if (!lastRemoved.equals(plugin.getName())) {
            LOGGER.error("Removed wrong last plugin from parentList. Expected {}, got {}", plugin.getName(), lastRemoved);
        }

        return true;
    }

    public interface RunnableWithException {
        void run() throws Exception;
    }

}
