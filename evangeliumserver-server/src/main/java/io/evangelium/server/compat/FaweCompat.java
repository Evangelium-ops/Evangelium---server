package io.evangelium.server.compat;

import com.mojang.logging.LogUtils;
import io.evangelium.server.config.EvangeliumServerConfiguration;
import io.evangelium.server.threading.EvangeliumServerTickThread;
import io.evangelium.server.threading.SynchronousPluginExecution;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.FutureTask;

/**
 * Evangelium - FastAsyncWorldEdit compatibility.
 *
 * <p>FAWE's TaskManager.sync() asks whether it runs on "the main thread" (the one thread it saw at enable). On one of our region threads
 * the answer is no, so it puts a FutureTask into QueueHandler.syncTasks (or syncWhenFree) and waits for it. Those queues are emptied by a
 * Bukkit repeating task in the main heartbeat, and the main thread waits for the region that waits for it: a deadlock.
 *
 * <p>Here the two queues are swapped (reflection, no bytecode change) for a queue that runs a FutureTask added from a region tick thread
 * right away on that thread, under FAWE's plugin lock. What it touches outside the thread's regions is taken on demand
 * (OnDemandRegionAccess, bounded wait); if that fails the FutureTask ends with the thread-check exception and FAWE reports the failed
 * operation. Every other thread (FAWE's own workers, the Server thread) keeps FAWE's normal queueing. If FAWE is missing nothing happens;
 * if its internals are not the expected ones, one warning and FAWE stays as it is.
 */
public final class FaweCompat {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final String PLUGIN = "FastAsyncWorldEdit";
    /** -Devangeliumserver.faweInline=true|false overrides evangelium.yml either way. */
    private static final String SYSTEM_SWITCH = System.getProperty("evangeliumserver.faweInline");

    private FaweCompat() {
    }

    static boolean enabled() {
        EvangeliumServerConfiguration c = EvangeliumServerConfiguration.get();
        if (SYSTEM_SWITCH != null) {
            return Boolean.parseBoolean(SYSTEM_SWITCH);
        }
        return c != null && c.multithreading.faweRunMainThreadWorkOnRegionThread;
    }

    /** Called after any plugin enabled. */
    public static void pluginEnabled(Plugin plugin) {
        if (!PLUGIN.equals(plugin.getName())) {
            return;
        }
        if (!enabled()) {
            LOGGER.info("[compat] FastAsyncWorldEdit: main-thread work from region threads waits for the main thread as usual (switch off)");
            return;
        }
        try {
            ClassLoader loader = plugin.getClass().getClassLoader();
            Class<?> faweClass = Class.forName("com.fastasyncworldedit.core.Fawe", true, loader);
            Object fawe = faweClass.getMethod("instance").invoke(null);
            if (fawe == null) {
                LOGGER.warn("[compat] FastAsyncWorldEdit: Fawe.instance() is null after enable; leaving FAWE as it is");
                return;
            }
            Object queueHandler = faweClass.getMethod("getQueueHandler").invoke(fawe);
            Class<?> handlerClass = Class.forName("com.fastasyncworldedit.core.queue.implementation.QueueHandler", false, loader);
            if (queueHandler == null || !handlerClass.isInstance(queueHandler)) {
                LOGGER.warn("[compat] FastAsyncWorldEdit: unexpected queue handler {}; leaving FAWE as it is", queueHandler);
                return;
            }
            Field syncTasks = queueField(handlerClass, "syncTasks");
            Field syncWhenFree = queueField(handlerClass, "syncWhenFree");
            if (syncTasks == null || syncWhenFree == null) {
                LOGGER.warn("[compat] FastAsyncWorldEdit {}: QueueHandler has no syncTasks/syncWhenFree queues of the expected type; leaving FAWE as it is",
                    plugin.getPluginMeta().getVersion());
                return;
            }
            Object originalSyncTasks = syncTasks.get(queueHandler);
            PENDING_DRAINS.clear();
            if (!swap(queueHandler, syncTasks, plugin)) {
                return;
            }
            if (!swap(queueHandler, syncWhenFree, plugin)) {
                // half swapped is worse than not at all: put the first queue back (its tasks move back too)
                PENDING_DRAINS.clear();
                try {
                    @SuppressWarnings("unchecked") ConcurrentLinkedQueue<Object> swapped = (ConcurrentLinkedQueue<Object>) syncTasks.get(queueHandler);
                    syncTasks.set(queueHandler, originalSyncTasks);
                    Object task;
                    while ((task = swapped.poll()) != null) {
                        ((ConcurrentLinkedQueue<Object>) originalSyncTasks).offer(task);
                    }
                } catch (Throwable t) {
                    LOGGER.warn("[compat] FastAsyncWorldEdit: could not put QueueHandler.syncTasks back ({})", t.toString());
                }
                return;
            }
            final java.util.List<Runnable> drains = new java.util.ArrayList<>(PENDING_DRAINS);
            PENDING_DRAINS.clear();
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> drains.forEach(Runnable::run), 1L);
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> drains.forEach(Runnable::run), 40L);
            LOGGER.info("[compat] FastAsyncWorldEdit {}: main-thread work asked for on a region thread now runs on that thread (no wait for the main thread)",
                plugin.getPluginMeta().getVersion());
        } catch (Throwable t) {
            LOGGER.warn("[compat] FastAsyncWorldEdit internals are not the expected ones ({}); leaving FAWE as it is", t.toString());
        }
    }

    private static Field queueField(Class<?> handlerClass, String name) {
        try {
            Field f = handlerClass.getDeclaredField(name);
            if (Modifier.isStatic(f.getModifiers()) || f.getType() != ConcurrentLinkedQueue.class) {
                return null;
            }
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static final java.util.List<Runnable> PENDING_DRAINS = new java.util.ArrayList<>();

    @SuppressWarnings("unchecked")
    private static boolean swap(Object queueHandler, Field field, Plugin plugin) {
        ConcurrentLinkedQueue<Object> old;
        InlineQueue replacement = new InlineQueue(plugin);
        try {
            old = (ConcurrentLinkedQueue<Object>) field.get(queueHandler);
            if (old instanceof InlineQueue) {
                return true;
            }
            field.set(queueHandler, replacement);
            if (field.get(queueHandler) != replacement) {
                throw new IllegalStateException("the field kept its old value");
            }
        } catch (Throwable t) {
            // a final field the JVM refuses to change (or anything else): leave FAWE exactly as it is
            LOGGER.warn("[compat] FastAsyncWorldEdit: could not swap QueueHandler.{} ({}); leaving FAWE as it is", field.getName(), t.toString());
            return false;
        }
        // tasks added to the old queue by a thread that read the field just before the swap move over: now, and twice more later once
        // both queues are swapped (see pluginEnabled)
        drain(old, replacement);
        PENDING_DRAINS.add(() -> drain(old, replacement));
        return true;
    }

    private static void drain(ConcurrentLinkedQueue<Object> old, InlineQueue replacement) {
        Object task;
        while ((task = old.poll()) != null) {
            replacement.queue(task); // normal queueing: never inline from here
        }
    }

    /** FAWE's sync queue: FutureTasks from region tick threads run at once on that thread. */
    static final class InlineQueue extends ConcurrentLinkedQueue<Object> {
        private final transient Plugin plugin;

        InlineQueue(Plugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean offer(Object task) {
            if (task instanceof FutureTask<?> futureTask && inlineHere(this.plugin)) {
                // FutureTask.run keeps any exception for the waiting get(); FAWE's own plugin lock is held already (checked in inlineHere)
                SynchronousPluginExecution.executeNoException(this.plugin, futureTask::run);
                return true;
            }
            return super.offer(task);
        }

        @Override
        public boolean add(Object task) {
            return this.offer(task);
        }

        @Override
        public boolean addAll(java.util.Collection<?> tasks) {
            boolean changed = false;
            for (Object task : tasks) {
                changed |= this.offer(task);
            }
            return changed;
        }

        void queue(Object task) {
            super.offer(task);
        }

        /**
         * Only on a region tick thread that is not a helper (nor an owner whose helpers run) and that holds FAWE's own plugin lock(s) already (FAWE
         * without Folia support, called from its own locked command): then what the task touches outside the thread's regions goes
         * through the on-demand take. Even so FAWE writes chunk sections directly, so a task touching a far region can race with that
         * region's tick before any check runs: this is why the switch is off by default.
         */
        private static boolean inlineHere(Plugin fawe) {
            return enabled() && EvangeliumServerTickThread.isEvangeliumServerTickThread() && !io.evangelium.server.threading.IntraRegion.inParallelPhase()
                && SynchronousPluginExecution.holdsLocksOf(fawe);
        }
    }
}
