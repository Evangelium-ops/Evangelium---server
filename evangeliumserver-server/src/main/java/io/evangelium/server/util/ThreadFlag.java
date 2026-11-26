package io.evangelium.server.util;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Evangelium - a per-thread boolean for flags that are read very often (every getBlockState / setBlock) but almost never set.
 * While no thread holds a non-default value, get() answers the default with one volatile read instead of a ThreadLocal lookup.
 * Same results as ThreadLocal.withInitial(() -> defaultValue): a thread only ever sees its own value.
 */
public final class ThreadFlag extends ThreadLocal<Boolean> {

    private final Boolean defaultValue;
    private final AtomicInteger changed = new AtomicInteger(); // threads whose value is not the default

    public ThreadFlag(boolean defaultValue) {
        this.defaultValue = defaultValue;
    }

    @Override
    protected Boolean initialValue() {
        return this.defaultValue;
    }

    @Override
    public Boolean get() {
        return this.changed.get() == 0 ? this.defaultValue : super.get();
    }

    @Override
    public void set(Boolean value) {
        boolean was = super.get() != this.defaultValue.booleanValue();
        boolean now = value.booleanValue() != this.defaultValue.booleanValue();
        if (was != now) {
            this.changed.addAndGet(now ? 1 : -1);
        }
        super.set(value);
    }

    @Override
    public void remove() {
        this.set(this.defaultValue);
        super.remove();
    }
}
