package io.evangelium.server.ticks;

import net.minecraft.world.ticks.*;

import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Verbatim copy of the old (object based) net.minecraft.world.ticks.LevelChunkTicks. Used when -Devangeliumserver.packedTicks=false,
 * as the fallback of a packed container that gets unusual input, and as the shadow in -Devangeliumserver.packedTicksVerify mode.
 */
public class LegacyChunkTicks<T> implements SerializableTickContainer<T>, TickContainerAccess<T> { // Paper - rewrite chunk system
    private final Queue<ScheduledTick<T>> tickQueue = new PriorityQueue<>(ScheduledTick.DRAIN_ORDER);
    private @Nullable List<SavedTick<T>> pendingTicks;
    private final Set<ScheduledTick<?>> ticksPerPosition = new ObjectOpenCustomHashSet<>(ScheduledTick.UNIQUE_TICK_HASH);
    private @Nullable BiConsumer<LegacyChunkTicks<T>, ScheduledTick<T>> onTickAdded;

    // Paper start - rewrite chunk system
    /*
     * Since ticks are saved using relative delays, we need to consider the entire tick list dirty when there are scheduled ticks
     * and the last saved tick is not equal to the current tick
     */
    /*
     * In general, it would be nice to be able to "re-pack" ticks once the chunk becomes non-ticking again, but that is a
     * bit out of scope for the chunk system
     */

    private boolean dirty;
    private long lastSaved = Long.MIN_VALUE;

    public final boolean moonrise$isDirty(final long tick) {
        return this.dirty || (!this.tickQueue.isEmpty() && tick != this.lastSaved);
    }

    public final void moonrise$clearDirty() {
        this.dirty = false;
    }
    // Paper end - rewrite chunk system

    public LegacyChunkTicks() {
    }

    public LegacyChunkTicks(List<SavedTick<T>> pendingTicks) {
        this.pendingTicks = pendingTicks;

        for (SavedTick<T> savedTick : pendingTicks) {
            this.ticksPerPosition.add(ScheduledTick.probe(savedTick.type(), savedTick.pos()));
        }
    }

    public void setOnTickAdded(@Nullable BiConsumer<LegacyChunkTicks<T>, ScheduledTick<T>> onTickAdded) {
        this.onTickAdded = onTickAdded;
    }

    public @Nullable ScheduledTick<T> peek() {
        return this.tickQueue.peek();
    }

    public @Nullable ScheduledTick<T> poll() {
        ScheduledTick<T> scheduledTick = this.tickQueue.poll();
        if (scheduledTick != null) {
            this.ticksPerPosition.remove(scheduledTick); this.dirty = true; // Paper - rewrite chunk system
        }

        return scheduledTick;
    }

    @Override
    public void schedule(ScheduledTick<T> tick) {
        if (this.ticksPerPosition.add(tick)) {
            this.scheduleUnchecked(tick); this.dirty = true; // Paper - rewrite chunk system
        }
    }

    private void scheduleUnchecked(ScheduledTick<T> tick) {
        this.tickQueue.add(tick);
        if (this.onTickAdded != null) {
            this.onTickAdded.accept(this, tick);
        }
    }

    @Override
    public boolean hasScheduledTick(BlockPos pos, T type) {
        return this.ticksPerPosition.contains(ScheduledTick.probe(type, pos));
    }

    public void removeIf(Predicate<ScheduledTick<T>> predicate) {
        Iterator<ScheduledTick<T>> iterator = this.tickQueue.iterator();

        while (iterator.hasNext()) {
            ScheduledTick<T> scheduledTick = iterator.next();
            if (predicate.test(scheduledTick)) {
                iterator.remove(); this.dirty = true; // Paper - rewrite chunk system
                this.ticksPerPosition.remove(scheduledTick);
            }
        }
    }

    public Stream<ScheduledTick<T>> getAll() {
        return this.tickQueue.stream();
    }

    @Override
    public int count() {
        return this.tickQueue.size() + (this.pendingTicks != null ? this.pendingTicks.size() : 0);
    }

    @Override
    public List<SavedTick<T>> pack(long gameTime) {
        this.lastSaved = gameTime; // Paper - rewrite chunk system
        List<SavedTick<T>> list = new ArrayList<>(this.tickQueue.size());
        if (this.pendingTicks != null) {
            list.addAll(this.pendingTicks);
        }

        for (ScheduledTick<T> scheduledTick : this.tickQueue) {
            list.add(scheduledTick.toSavedTick(gameTime));
        }

        return list;
    }

    public void unpack(long gameTime) {
        if (this.pendingTicks != null) {
            this.lastSaved = gameTime; // Paper - rewrite chunk system
            int i = -this.pendingTicks.size();

            for (SavedTick<T> savedTick : this.pendingTicks) {
                this.scheduleUnchecked(savedTick.unpack(gameTime, i++));
            }
        }

        this.pendingTicks = null;
    }

    // Evangelium start - packed ticks: restore state / introspection for the fallback and verify paths
    /**
     * Rebuilds the exact old state: queue elements must be given in the old PriorityQueue's array order (adding a valid heap in
     * array order to an empty PriorityQueue never moves an element, so the internal layout is identical).
     */
    public static <T> LegacyChunkTicks<T> restore(@Nullable List<SavedTick<T>> pending, List<ScheduledTick<T>> queueInArrayOrder,
                                                  Iterable<ScheduledTick<?>> keys, boolean dirty, long lastSaved) {
        LegacyChunkTicks<T> ticks = new LegacyChunkTicks<>();
        ticks.pendingTicks = pending;
        for (ScheduledTick<T> tick : queueInArrayOrder) {
            ticks.tickQueue.add(tick);
        }
        for (ScheduledTick<?> key : keys) {
            ticks.ticksPerPosition.add(key);
        }
        ticks.dirty = dirty;
        ticks.lastSaved = lastSaved;
        return ticks;
    }

    public @Nullable List<SavedTick<T>> pendingTicks() {
        return this.pendingTicks;
    }

    /** The queue in its internal array order (= iteration order). */
    public List<ScheduledTick<T>> queueInArrayOrder() {
        return new ArrayList<>(this.tickQueue);
    }

    public int keyCount() {
        return this.ticksPerPosition.size();
    }

    public long lastSaved() {
        return this.lastSaved;
    }

    public boolean dirtyFlag() {
        return this.dirty;
    }
    // Evangelium end
}
