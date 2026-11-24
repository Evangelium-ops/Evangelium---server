package io.multipaper.shreddedpaper.util;

import java.util.concurrent.locks.StampedLock;
import java.util.function.Supplier;

/**
 * A java.util.concurrent.locks.StampedLock, but simpler to use and harder to
 * accidentally get wrong. Removes all the boilerplate code for you.
 */
public class SimpleStampedLock {

    private final StampedLock lock = new StampedLock();

    public void write(Runnable runnable) {
        write(() -> {
            runnable.run();
            return null;
        });
    }

    public <T> T write(Supplier<T> supplier) {
        lock.writeLock();
        try {
            return supplier.get();
        } finally {
            lock.tryUnlockWrite();
        }
    }

    public void read(Runnable runnable) {
        read(() -> {
            runnable.run();
            return null;
        });
    }

    public <T> T read(Supplier<T> supplier) {
        lock.readLock();
        try {
            return supplier.get();
        } finally {
            lock.tryUnlockRead();
        }
    }

    /**
     * Evangelium - optimisticRead(() -> map.get(key)) without the capturing lambda (one allocation per call on hot paths such as the
     * nearby-player lookups of natural spawning).
     */
    public <V> V optimisticGet(final it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap<V> map, final long key) {
        final long attempt = lock.tryOptimisticRead();
        if (attempt != 0L) {
            try {
                final V ret = map.get(key);
                if (lock.validate(attempt)) {
                    return ret;
                }
            } catch (final Error error) {
                throw error;
            } catch (final Throwable thr) {
                // ignore
            }
        }
        lock.readLock();
        try {
            return map.get(key);
        } finally {
            lock.tryUnlockRead();
        }
    }

    /**
     * Read the value without acquiring a lock. If the read fails, it will fall
     * back to acquiring the lock.
     * @param supplier The method to get the value. This method may be run twice.
     */
    public <T> T optimisticRead(Supplier<T> supplier) {
        final long attempt = lock.tryOptimisticRead();
        if (attempt != 0L) {
            try {
                final T ret = supplier.get();

                if (lock.validate(attempt)) {
                    return ret;
                }
            } catch (final Error error) {
                throw error;
            } catch (final Throwable thr) {
                // ignore
            }
        }

        // Optimistic read failed, fall back to a read lock
        return read(supplier);
    }

}
