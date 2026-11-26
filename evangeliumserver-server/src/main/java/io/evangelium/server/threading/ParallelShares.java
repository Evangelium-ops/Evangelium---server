package io.evangelium.server.threading;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntConsumer;

/**
 * Evangelium: splits one serial job into shares that the tick threads and the caller work on together.
 *
 * <p>The caller takes shares too, and only ever waits for shares a tick thread has already taken (and is running), so it never waits for
 * a task stuck behind other work in the pool: if every tick thread is busy, the caller simply does all shares itself.
 */
public final class ParallelShares {

    private ParallelShares() {
    }

    public static int threads() {
        return EvangeliumServerTickThread.THREAD_COUNT;
    }

    /** Runs work(0) .. work(shares - 1), each exactly once, possibly at the same time; returns when all are done. */
    public static void run(int shares, IntConsumer work) {
        if (shares <= 1) {
            for (int i = 0; i < shares; i++) {
                work.accept(i);
            }
            return;
        }
        final AtomicInteger next = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread caller = Thread.currentThread();
        final Runnable worker = () -> {
            int i;
            while ((i = next.getAndIncrement()) < shares) {
                try {
                    work.accept(i);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    if (done.incrementAndGet() == shares) {
                        LockSupport.unpark(caller);
                    }
                }
            }
        };
        final int helpers = Math.min(shares, threads()) - 1;
        for (int k = 0; k < helpers; k++) {
            EvangeliumServerTickThread.getExecutor().execute(worker);
        }
        worker.run();
        while (done.get() < shares) {
            LockSupport.parkNanos(ParallelShares.class, 50_000L);
        }
        Throwable t = failure.get();
        if (t != null) {
            if (t instanceof RuntimeException r) throw r;
            if (t instanceof Error e) throw e;
            throw new RuntimeException(t);
        }
    }
}
