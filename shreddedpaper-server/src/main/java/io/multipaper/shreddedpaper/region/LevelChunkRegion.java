package io.multipaper.shreddedpaper.region;

import ca.spottedleaf.concurrentutil.executor.queue.PrioritisedTaskQueue;
import ca.spottedleaf.moonrise.common.list.IteratorSafeOrderedReferenceSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class LevelChunkRegion {

    private final ServerLevel level;
    private final RegionPos regionPos;
    private final List<LevelChunk> levelChunks = new ArrayList<>(RegionPos.REGION_SIZE * RegionPos.REGION_SIZE);
    // Knowledge - ChunkPos.longKey -> how many players request it: every player in range adds and removes his own request (NearbyPlayers
    // SPAWN_RANGE), so a plain set lost a chunk for everyone when one of two players left, and an AFK player never re-adds it (no natural spawning)
    private final Long2IntOpenHashMap playerTickingChunkRequests = new Long2IntOpenHashMap();
    private final IteratorSafeOrderedReferenceSet<Entity> tickingEntities = new IteratorSafeOrderedReferenceSet<>(); // Use IteratorSafeOrderedReferenceSet to maintain entity tick order
    private final Set<Entity> trackedEntities = new ObjectOpenHashSet<>();
    private final ConcurrentLinkedQueue<DelayedTask> scheduledTasks = new ConcurrentLinkedQueue<>(); // Writable tasks
    private final PrioritisedTaskQueue internalTasks; // Read-only tasks
    private final ObjectOpenHashSet<ServerPlayer> players = new ObjectOpenHashSet<>();
    public final LongLinkedOpenHashSet unloadQueue = new LongLinkedOpenHashSet();
    public final List<TickingBlockEntity> tickingBlockEntities = new ReferenceArrayList<>();
    public final io.multipaper.shreddedpaper.threading.RegionBudget.State budget = new io.multipaper.shreddedpaper.threading.RegionBudget.State(); // Knowledge - tick budget
    public final List<TickingBlockEntity> pendingBlockEntityTickers = new ReferenceArrayList<>();
    private final ObjectOpenHashSet<Mob> navigatingMobs = new ObjectOpenHashSet<>();
    private final ObjectLinkedOpenHashSet<BlockEventData> blockEvents = new ObjectLinkedOpenHashSet<>();
    private volatile long lastAccessTick;
    public ArrayDeque<RedstoneTorchBlock.Toggle> redstoneUpdateInfos;

    public LevelChunkRegion(ServerLevel level, RegionPos regionPos) {
        this(level, regionPos, null);
    }

    /** Evangelium - the map is told after each task is queued (the hook runs once the task is visible), see LevelChunkRegionMap#regionsWithTasks */
    public LevelChunkRegion(ServerLevel level, RegionPos regionPos, LevelChunkRegionMap map) {
        this.level = level;
        this.regionPos = regionPos;
        this.internalTasks = map == null ? new PrioritisedTaskQueue() : new PrioritisedTaskQueue(new java.util.concurrent.atomic.AtomicLong(), 0L, task -> map.markHasTasks(this));

        this.bumpLastAccess();
    }

    public void bumpLastAccess() {
        this.lastAccessTick = this.level.levelData.getGameTime();
    }

    public synchronized void add(LevelChunk levelChunk) {
        this.levelChunks.add(levelChunk);
    }

    public synchronized void remove(LevelChunk levelChunk) {
        if (!this.levelChunks.remove(levelChunk)) {
            throw new IllegalStateException("Tried to remove a chunk that wasn't in the region: " + levelChunk.getPos());
        }
    }

    private static final boolean PLANT_BUG = Boolean.getBoolean("shreddedpaper.spawnRequestsPlantBug"); // Knowledge - proves SpawnRequestCheck catches the old bug

    public synchronized void addPlayerTickingRequest(final ChunkPos chunkPos) {
        this.playerTickingChunkRequests.addTo(chunkPos.toLong(), 1);
    }

    public synchronized void removePlayerTickingRequest(final ChunkPos chunkPos) {
        final long key = chunkPos.toLong();
        if (this.playerTickingChunkRequests.addTo(key, -1) <= 1 || PLANT_BUG) { // PLANT_BUG: test hook, the old set behaviour
            this.playerTickingChunkRequests.remove(key);
        }
    }

    /** Knowledge - a copy of the request counts, for SpawnRequestCheck */
    public synchronized Long2IntOpenHashMap copyPlayerTickingRequests() {
        return new Long2IntOpenHashMap(this.playerTickingChunkRequests);
    }

    public synchronized boolean isPlayerTickingRequested(final ChunkPos chunkPos) {
        if (chunkPos.getRegionPos().toLong() != this.regionPos.toLong()) {
            throw new IllegalStateException("Chunk %s is not in region %s".formatted(chunkPos, this.regionPos));
        }

        return this.playerTickingChunkRequests.containsKey(chunkPos.toLong());
    }

    public synchronized void addTickingEntity(Entity entity) {
        if (!this.tickingEntities.add(entity)) {
            throw new IllegalStateException("Tried to add an entity that was already in the ticking list: " + entity);
        }
    }

    public synchronized void removeTickingEntity(Entity entity) {
        if (!this.tickingEntities.remove(entity)) {
            throw new IllegalStateException("Tried to remove an entity that wasn't in the ticking list: " + entity);
        }
    }

    public void forEachTickingEntity(Consumer<Entity> action) {
        IteratorSafeOrderedReferenceSet.Iterator<Entity> iterator = this.tickingEntities.iterator();
        try {
            while (iterator.hasNext()) {
                action.accept(iterator.next());
            }
        } finally {
            iterator.finishedIterating();
        }
    }

    public synchronized void addTrackedEntity(Entity entity) {
        if (!this.trackedEntities.add(entity)) {
            throw new IllegalStateException("Tried to add an entity that was already tracked: " + entity);
        }
    }

    public synchronized void removeTrackedEntity(Entity entity) {
        if (!this.trackedEntities.remove(entity)) {
            throw new IllegalStateException("Tried to remove an entity that wasn't already tracked: " + entity);
        }
    }

    public synchronized void forEachTrackedEntity(Consumer<Entity> action) {
        this.trackedEntities.forEach(action);
    }

    public ServerLevel getLevel() {
        return this.level;
    }

    public void scheduleTask(Runnable task, long delay) {
        this.scheduledTasks.add(new DelayedTask(task, delay));
    }

    public PrioritisedTaskQueue getInternalTaskQueue() {
        return this.internalTasks;
    }

    public synchronized void addPlayer(ServerPlayer player) {
        if (!this.players.add(player)) {
            throw new IllegalStateException("Tried to add a player that was already in the region: " + player.getUUID());
        }
    }

    public synchronized void removePlayer(ServerPlayer player) {
        if (!this.players.remove(player)) {
            throw new IllegalStateException("Tried to remove a player that wasn't in the region: " + player.getUUID());
        }
    }

    public synchronized List<ServerPlayer> getPlayers() {
        return this.players.isEmpty() ? List.of() : new ObjectArrayList<>(this.players);
    }

    public synchronized void addNavigationMob(Mob mob) {
        this.navigatingMobs.add(mob);
    }

    public synchronized void removeNavigationMob(Mob mob) {
        this.navigatingMobs.remove(mob);
    }

    public synchronized void collectNavigatingMobs(List<Mob> collection) {
        collection.addAll(this.navigatingMobs);
    }

    public RegionPos getRegionPos() {
        return regionPos;
    }

    public void forEach(Consumer<LevelChunk> consumer) {
        // This method has the chance of skipping a chunk if a chunk is removed via another thread during this iteration
        for (int i = 0; i < this.levelChunks.size(); i++) {
            try {
                LevelChunk levelChunk = this.levelChunks.get(i);
                if (levelChunk != null) {
                    consumer.accept(levelChunk);
                }
            } catch (IndexOutOfBoundsException e) {
                // Ignore - multithreaded modification
            }
        }
    }

    public void tickTasks() {
        if (this.scheduledTasks.isEmpty()) return;

        List<DelayedTask> toRun = new ArrayList<>();
        for (DelayedTask task : this.scheduledTasks) {
            // Check if a task should run before executing the tasks, as tasks may add more tasks while they are running
            if (task.shouldRun()) {
                toRun.add(task);
            }
        }

        this.scheduledTasks.removeAll(toRun);
        for (int i = 0; i < toRun.size(); i++) {
            try {
                toRun.get(i).run();
            } catch (Throwable t) {
                // Evangelium - the tick ends here (RegionTickAbandonedError): the due tasks not run yet go back and run next time
                this.scheduledTasks.addAll(toRun.subList(i + 1, toRun.size()));
                throw t;
            }
        }
    }

    public synchronized void addBlockEvent(BlockEventData blockEvent) {
        this.blockEvents.add(blockEvent);
    }

    public synchronized void addAllBlockEvents(Collection<BlockEventData> blockEvents) {
        this.blockEvents.addAll(blockEvents);
    }

    public boolean hasBlockEvents() {
        return !this.blockEvents.isEmpty();
    }

    public synchronized BlockEventData removeFirstBlockEvent() {
        return this.blockEvents.removeFirst();
    }

    public synchronized void removeBlockEventsIf(Predicate<BlockEventData> predicate) {
        this.blockEvents.removeIf(predicate);
    }

    public boolean isEmpty() {
        return this.lastAccessTick < this.level.levelData.getGameTime() - 20
                && levelChunks.isEmpty()
                && playerTickingChunkRequests.isEmpty()
                && tickingEntities.size() == 0
                && scheduledTasks.isEmpty()
                && internalTasks.getTotalTasksExecuted() >= internalTasks.getTotalTasksScheduled()
                && players.isEmpty()
                && unloadQueue.isEmpty()
                && tickingBlockEntities.isEmpty()
                && pendingBlockEntityTickers.isEmpty()
                && trackedEntities.isEmpty()
                && navigatingMobs.isEmpty()
                && blockEvents.isEmpty()
                ;
    }
}
