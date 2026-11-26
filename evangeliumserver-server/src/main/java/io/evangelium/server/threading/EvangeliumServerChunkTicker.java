package io.evangelium.server.threading;

import ca.spottedleaf.moonrise.common.util.WorldUtil;
import com.google.common.collect.Lists;
import com.mojang.logging.LogUtils;
import io.evangelium.server.config.EvangeliumServerConfiguration;
import io.evangelium.server.region.RegionPos;
import io.evangelium.server.util.TickTimings;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import io.evangelium.server.region.LevelChunkRegion;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.util.CraftSpawnCategory;
import org.bukkit.entity.SpawnCategory;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class EvangeliumServerChunkTicker {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    static final boolean PROCESS_TRACK_QUEUE_IN_PARALLEL = io.evangelium.server.util.DevSwitch.on("process-track-queue-in-parallel");
    public static final boolean FLUSH_QUEUE_IN_PARALLEL = io.evangelium.server.util.DevSwitch.on("flush-queue-in-parallel"); // also read by Connection

    private static final ThreadLocal<LevelChunkRegion> currentlyTickingRegion = new ThreadLocal<>();

    private final ServerChunkCache serverChunkCache;

    private final List<Entity> trackedEntitiesWorkerList = new ArrayList<>(); // Re-usable list for processing tracked entities in parallel

    public EvangeliumServerChunkTicker(ServerChunkCache serverChunkCache) {
        this.serverChunkCache = serverChunkCache;
    }

    public CompletableFuture<Void> tickChunks(final long timeInhabited, final List<MobCategory> filteredSpawningCategories, final NaturalSpawner.SpawnState spawnState) {
        ServerLevel level = this.serverChunkCache.chunkMap.level;
        io.evangelium.server.region.SpawnRequestCheck.maybeCheck(level); // Evangelium - test hook -Devangeliumserver.verifySpawnRequests
        final long timingsStart = System.nanoTime(); // Evangelium - tick timings
        CompletableFuture<Void> future;
        if (RegionTickDispatcher.ENABLED) {
            // Evangelium - lock-aware dispatcher: most expensive free region first, neighbours under a moved lock (RegionTickDispatcher)
            final List<LevelChunkRegion> regions = new ArrayList<>();
            level.chunkSource.tickingRegions.forEach(regions::add);
            future = RegionTickDispatcher.tickAll(level.chunkScheduler.getRegionLocker(), regions,
                    region -> this.tickRegionLocked(level, region, timingsStart, timeInhabited, filteredSpawningCategories, spawnState));
        } else {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        level.chunkSource.tickingRegions.forEach(
                region -> futures.add(this.tickRegion(level, region, timeInhabited, filteredSpawningCategories, spawnState))
        );

        future = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        }
        if (TickTimings.enabled()) { // Evangelium - tick timings
            final long regionsStart = timingsStart;
            future = future.thenRun(() -> { TickTimings.add(TickTimings.Phase.REGIONS_WALL, System.nanoTime() - regionsStart); TickTimings.regionsDone(); });
        }

        if (PROCESS_TRACK_QUEUE_IN_PARALLEL) future = future.thenCompose(v -> timed(TickTimings.Phase.TRACK, () -> this.processTrackQueueInParallel(level)));

        if (FLUSH_QUEUE_IN_PARALLEL) future = future.thenCompose(v -> timed(TickTimings.Phase.FLUSH, () -> this.flushQueueInParallel(level)));

        return future;
    }

    /** Evangelium - tick timings: the time from the start of a phase until its future completes. */
    private static CompletableFuture<Void> timed(TickTimings.Phase phase, java.util.function.Supplier<CompletableFuture<Void>> step) {
        if (!TickTimings.enabled()) {
            return step.get();
        }
        final long start = System.nanoTime();
        return step.get().thenRun(() -> TickTimings.add(phase, System.nanoTime() - start));
    }

    /** processTrackQueue has been renamed to newTrackerTick */
    private CompletableFuture<Void> processTrackQueueInParallel(ServerLevel level) {
        level.getChunkSource().mainThreadProcessor.managedBlock(() -> level.chunkScheduler.getRegionLocker().globalLock().tryWriteLock() != 0);
        CompletableFuture<Void> allFuture = CompletableFuture.completedFuture(null);
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            trackedEntitiesWorkerList.clear();
            level.chunkSource.tickingRegions.forEach(
                    region -> region.forEachTrackedEntity(trackedEntitiesWorkerList::add)
            );

            List<List<Entity>> trackedEntitiesTasks = Lists.partition(trackedEntitiesWorkerList, Math.max(1, trackedEntitiesWorkerList.size() / EvangeliumServerTickThread.THREAD_COUNT / 3));
            for (List<Entity> trackedEntities : trackedEntitiesTasks) {
                if (trackedEntities.isEmpty()) continue;
                futures.add(CompletableFuture.runAsync(() -> trackedEntities.forEach(EvangeliumServerEntityTicker::processTrackQueue), EvangeliumServerTickThread.getExecutor()));
            }

            allFuture = allFuture.thenCompose(v -> CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)));
            return allFuture;
        } finally {
            allFuture.whenComplete((v, e) -> level.chunkScheduler.getRegionLocker().globalLock().tryUnlockWrite());
        }
    }

    private CompletableFuture<Void> flushQueueInParallel(ServerLevel level) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        List<List<ServerPlayer>> playersTasks = Lists.partition(new ArrayList<>(level.players()), Math.max(1, level.players().size() / EvangeliumServerTickThread.THREAD_COUNT / 3));
        for (List<ServerPlayer> players : playersTasks) {
            if (players.isEmpty()) continue;
            futures.add(CompletableFuture.runAsync(() -> players.forEach(player -> player.connection.connection.flushQueue()), EvangeliumServerTickThread.getExecutor()));
        }

        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    /** Evangelium - one region tick for RegionTickDispatcher: the caller holds the 3x3; failures are reported here as in tickRegion. */
    private void tickRegionLocked(final ServerLevel level, final LevelChunkRegion region, final long scheduledAt, final long timeInhabited, final List<MobCategory> filteredSpawningCategories, final NaturalSpawner.SpawnState spawnState) {
        final long started = System.nanoTime();
        TickTimings.add(TickTimings.Phase.REGION_LOCK_WAIT, started - scheduledAt);
        try {
            this._tickRegion(level, region, timeInhabited, filteredSpawningCategories, spawnState);
        } catch (Throwable e) {
            REGION_FAILED.apply(region, e);
        } finally {
            final long took = System.nanoTime() - started;
            TickTimings.region(took);
            RegionBudget.cost(region, took);
            RegionBudget.end(region, took); // Evangelium - tick budget
        }
    }

    private CompletableFuture<Void> tickRegion(final ServerLevel level, final LevelChunkRegion region, final long timeInhabited, final List<MobCategory> filteredSpawningCategories, final NaturalSpawner.SpawnState spawnState) {
        final long scheduledAt = System.nanoTime(); // Evangelium - tick timings
        return level.chunkScheduler.schedule(region.getRegionPos(), () -> {
            final long started = System.nanoTime();
            TickTimings.add(TickTimings.Phase.REGION_LOCK_WAIT, started - scheduledAt);
            try {
                this._tickRegion(level, region, timeInhabited, filteredSpawningCategories, spawnState);
            } finally {
                TickTimings.region(System.nanoTime() - started);
                RegionBudget.cost(region, System.nanoTime() - started); // Evangelium - kept in both modes
                RegionBudget.end(region, System.nanoTime() - started); // Evangelium - tick budget
            }
        }).exceptionally(e -> REGION_FAILED.apply(region, e));
    }

    /**
     * The one place a region failure crashes the server, used by both tick paths. The launcher's RegionGuard agent patches exactly this
     * lambda (one static lambda taking the region and the Throwable) so a failing region is frozen for a moment instead.
     */
    private static final java.util.function.BiFunction<LevelChunkRegion, Throwable, Void> REGION_FAILED = (region, e) -> {
        LogUtils.getClassLogger().error("Exception ticking region {}", region.getRegionPos(), e);
        MinecraftServer.getServer().moonrise$setChunkSystemCrash(new RuntimeException("Ticking thread crash while ticking region " + region.getRegionPos(), e));
        return null;
    };

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void presense(net.minecraft.world.entity.LivingEntity living) {
        ((net.minecraft.world.entity.ai.Brain) living.getBrain()).evangeliumPresense((ServerLevel) living.level(), living);
    }

    /** Evangelium - the region the current thread is ticking (null outside a region tick). */
    public static LevelChunkRegion getCurrentlyTickingRegion() {
        return currentlyTickingRegion.get();
    }

    /** Evangelium - intra-region helpers work for the region of the thread they help. */
    static void setCurrentlyTickingRegion(LevelChunkRegion region) {
        if (region == null) {
            currentlyTickingRegion.remove();
        } else {
            currentlyTickingRegion.set(region);
        }
    }

    public static boolean isCurrentlyTickingRegion(Level level, RegionPos regionPos) {
        LevelChunkRegion region = currentlyTickingRegion.get();
        return region != null && level.equals(region.getLevel()) && regionPos.equals(region.getRegionPos());
    }

    private void _tickRegion(final ServerLevel level, final LevelChunkRegion region, final long timeInhabited, final List<MobCategory> filteredSpawningCategories, final NaturalSpawner.SpawnState spawnState) {
        try {
            OnDemandRegionAccess.clearTickAbandoned(); // Evangelium - plugin compatibility: a new tick starts clean
            currentlyTickingRegion.set(region);
            net.minecraft.world.entity.PhysicsCache.bump(); // Evangelium - movement caches never outlive a region tick
            RegionBudget.begin(region); // Evangelium - tick budget

            if (!(EvangeliumServerTickThread.isEvangeliumServerTickThread())) {
                throw new IllegalStateException("Ticking region " + WorldUtil.getWorldName(level) + " " + region.getRegionPos() + " outside of EvangeliumServerTickThread!");
            }

            EvangeliumServerChangesBroadcaster.setAsWorkerThread();

            while (region.getInternalTaskQueue().executeTask()) ;

            level.moonrise$getChunkTaskScheduler().chunkHolderManager.processUnloads(region);

            region.forEachTickingEntity(entity -> {
                CraftEntity bukkitEntity = entity.getBukkitEntityRaw();
                if (bukkitEntity != null && !entity.isRemoved()) { // Entity could have been removed by another entity's task
                    bukkitEntity.taskScheduler.executeTick();
                }
            });

            OnDemandRegionAccess.checkTickAbandoned(); // Evangelium - plugin compatibility: an abandoned tick ends between phases
            region.tickTasks();
            OnDemandRegionAccess.checkTickAbandoned();

            if (level.tickRateManager().runsNormally()) {
                level.handlingTickThreadLocal.set(true);

                // Evangelium - a region over its tick budget runs its scheduled block and fluid ticks only in its turn (slow motion)
                if (RegionBudget.ticksRegionWork()) {
                    final long sliceStart = System.nanoTime(); // Evangelium - tick budget: divided work
                    level.blockTicks.tick(region.getRegionPos(), level.getGameTime(), level.paperConfig().environment.maxBlockTicks, level::tickBlock);
                    level.fluidTicks.tick(region.getRegionPos(), level.getGameTime(), level.paperConfig().environment.maxBlockTicks, level::tickFluid);
                    RegionBudget.sliced(RegionBudget.Work.SCHEDULED, System.nanoTime() - sliceStart);
                }

                // Evangelium - bookkeeping and mob spawning stay on this thread; random ticks, snow and ice reach about one chunk and run in
                // chunks at least three apart on several threads when the random tick speed makes it worth it (IntraRegion)
                final List<LevelChunk> randomTickChunks = new ArrayList<>();
                region.forEach(chunk -> {
                    if (this._tickChunk(region, level, chunk, timeInhabited, filteredSpawningCategories, spawnState) && RegionBudget.ticks(chunk.getPos())) { // Evangelium - tick budget
                        randomTickChunks.add(chunk);
                    }
                });
                final int randomTickSpeed = level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED);
                final long randomStart = System.nanoTime(); // Evangelium - tick budget: divided work
                if (IntraRegion.parallelRandomTicks(randomTickSpeed)) {
                    IntraRegion.forEachChunkLocal(level, region, randomTickChunks, chunk -> chunk.getPos().toLong(), chunk -> level.tickChunk(chunk, randomTickSpeed));
                } else {
                    for (LevelChunk chunk : randomTickChunks) {
                        level.tickChunk(chunk, randomTickSpeed);
                    }
                }
                RegionBudget.sliced(RegionBudget.Work.RANDOM, System.nanoTime() - randomStart);
                io.evangelium.server.util.TickTimings.add(io.evangelium.server.util.TickTimings.Phase.RANDOM_TICKS, System.nanoTime() - randomStart); // Evangelium - benchmark timings (no-op when off)

                // Evangelium - tick budget: block events (pistons, note blocks, bells) are redstone's other half: they run in their turn too
                if (RegionBudget.ticksRegionWork()) {
                    final long eventsStart = System.nanoTime();
                    level.runBlockEvents(region);
                    RegionBudget.sliced(RegionBudget.Work.EVENTS, System.nanoTime() - eventsStart);
                }

                level.handlingTickThreadLocal.set(false);
            }

            // Evangelium - mob sensors that fire this tick run first, read-only, on several threads (see IntraRegion#forEachReadOnly)
            final long entitiesStart = System.nanoTime(); // Evangelium - tick budget: sensors, entities and block entities are divided work
            if (IntraRegion.enabled()) {
                final long gameTime = level.getGameTime();
                final List<net.minecraft.world.entity.LivingEntity> sensing = new ArrayList<>();
                region.forEachTickingEntity(entity -> {
                    if (entity instanceof net.minecraft.world.entity.LivingEntity living && !living.isRemoved() && RegionBudget.ticks(living) && living.getBrain().evangeliumWantsPresensing(gameTime)) {
                        sensing.add(living);
                    }
                });
                IntraRegion.forEachReadOnly(level, region, sensing, EvangeliumServerChunkTicker::presense);
            }

            // Evangelium - items and experience orbs only reach a block or so (merging, being touched): they tick after the others,
            // chunks at least three apart on several threads, when a region has many of them
            final List<Entity> chunkLocalEntities = IntraRegion.enabled() ? new ArrayList<>() : null;
            // Evangelium - and the entity tick of the players of a crowded region (see IntraRegion#isChunkLocalPlayer)
            final List<Entity> playerEntities = IntraRegion.parallelPlayers(region.getPlayers().size()) ? new ArrayList<>() : null;
            region.forEachTickingEntity(entity -> {
                if (!RegionBudget.ticks(entity)) {
                    return; // Evangelium - tick budget: not this entity's turn in a slowed region
                }
                if (chunkLocalEntities != null && IntraRegion.isChunkLocal(entity)) {
                    chunkLocalEntities.add(entity);
                } else if (playerEntities != null && entity instanceof ServerPlayer player && IntraRegion.isChunkLocalPlayer(player)) {
                    playerEntities.add(entity);
                } else if (RegionBudget.alwaysTicks(entity)) {
                    final long playerStart = System.nanoTime(); // Evangelium - tick budget: players are never divided
                    EvangeliumServerEntityTicker.tickEntity(entity);
                    RegionBudget.notSliced(System.nanoTime() - playerStart);
                } else {
                    EvangeliumServerEntityTicker.tickEntity(entity);
                }
            });
            if (playerEntities != null && !playerEntities.isEmpty()) {
                final long playersStart = System.nanoTime();
                IntraRegion.forEachChunkLocal(level, region, playerEntities, entity -> entity.chunkPosition().toLong(), EvangeliumServerEntityTicker::tickEntity);
                RegionBudget.notSliced(System.nanoTime() - playersStart);
            }
            if (chunkLocalEntities != null && !chunkLocalEntities.isEmpty()) {
                if (chunkLocalEntities.size() < IntraRegion.MIN_ENTITIES) {
                    chunkLocalEntities.forEach(EvangeliumServerEntityTicker::tickEntity);
                } else {
                    IntraRegion.forEachChunkLocal(level, region, chunkLocalEntities, entity -> entity.chunkPosition().toLong(), EvangeliumServerEntityTicker::tickEntity);
                }
            }

            RegionBudget.sliced(RegionBudget.Work.ENTITIES, System.nanoTime() - entitiesStart);
            OnDemandRegionAccess.checkTickAbandoned();

            if (!PROCESS_TRACK_QUEUE_IN_PARALLEL) region.forEachTrackedEntity(EvangeliumServerEntityTicker::processTrackQueue);

            final long blockEntitiesStart = System.nanoTime();
            level.tickBlockEntities(region.tickingBlockEntities, region.pendingBlockEntityTickers);
            RegionBudget.sliced(RegionBudget.Work.BLOCK_ENTITIES, System.nanoTime() - blockEntitiesStart);
            OnDemandRegionAccess.checkTickAbandoned();

            // Evangelium - a crowd in one place: players in chunks at least three apart tick on several threads (IntraRegion#isChunkLocalPlayer)
            final List<ServerPlayer> regionPlayers = region.getPlayers();
            if (IntraRegion.parallelPlayers(regionPlayers.size())) {
                final List<ServerPlayer> chunkLocalPlayers = new ArrayList<>(regionPlayers.size());
                for (ServerPlayer player : regionPlayers) {
                    if (IntraRegion.isChunkLocalPlayer(player)) {
                        chunkLocalPlayers.add(player);
                    } else {
                        EvangeliumServerPlayerTicker.tickPlayer(player);
                    }
                }
                IntraRegion.forEachChunkLocal(level, region, chunkLocalPlayers, player -> player.chunkPosition().toLong(), EvangeliumServerPlayerTicker::tickPlayer);
            } else {
                regionPlayers.forEach(EvangeliumServerPlayerTicker::tickPlayer);
            }
            OnDemandRegionAccess.checkTickAbandoned();

            while (region.getInternalTaskQueue().executeTask()) ;

            EvangeliumServerChangesBroadcaster.broadcastChanges();

            if (region.isEmpty()) {
                RegionBudget.forget(region); // Evangelium - tick budget
                level.chunkSource.tickingRegions.remove(region.getRegionPos());
            }
        } finally {
            currentlyTickingRegion.remove();
            OnDemandRegionAccess.clearTickAbandoned(); // Evangelium - plugin compatibility
        }
    }

    /** Evangelium - returns whether the chunk gets random ticks (they run afterwards, see _tickRegion). */
    private boolean _tickChunk(final LevelChunkRegion levelChunkRegion, final ServerLevel world, final LevelChunk levelChunk, final long timeInhabited, final List<MobCategory> filteredSpawningCategories, final NaturalSpawner.SpawnState spawnState) {
        if (levelChunk.moonrise$getChunkHolder().vanillaChunkHolder.hasChangesToBroadcast())
            EvangeliumServerChangesBroadcaster.add(levelChunk.moonrise$getChunkHolder().vanillaChunkHolder); // EvangeliumServer

        // EvangeliumServer start - clear chunk packet cache
        if (levelChunk.cachedChunkPacket != null && levelChunk.cachedChunkPacketLastAccessed < world.getGameTime() - EvangeliumServerConfiguration.get().optimizations.chunkPacketCaching.expireAfter) {
            levelChunk.cachedChunkPacket = null;
            if (io.evangelium.server.util.ChunkPacketCacheStats.ON) io.evangelium.server.util.ChunkPacketCacheStats.EXPIRED.increment(); // Evangelium - cache counters
        }
        // EvangeliumServer end - clear chunk packet cache

        if (!levelChunk.moonrise$getChunkHolder().isEntityTickingReady()) {
            return false;
        }

        if (spawnState != null && levelChunkRegion.isPlayerTickingRequested(levelChunk.getPos()) && RegionBudget.ticks(levelChunk.getPos())) { // Evangelium - tick budget: a slowed farm gets its mobs at its own speed
            this._tickSpawningChunk(world, levelChunk, timeInhabited, filteredSpawningCategories, spawnState);
        }

        return true;
    }

    private void _tickSpawningChunk(final ServerLevel world, final LevelChunk levelChunk, final long timeInhabited, final List<MobCategory> filteredSpawningCategories, final NaturalSpawner.SpawnState spawnState) {
        if (!world.chunkSource.chunkMap.isChunkNearPlayer(world.chunkSource.chunkMap, levelChunk.getPos(), levelChunk)) {
            return;
        }

        world.chunkSource.tickSpawningChunk(levelChunk, timeInhabited, filteredSpawningCategories, spawnState);
    }

    public static boolean willTrySpawnMobsThisTick(final ServerLevel level) {
        for (MobCategory mobCategory : NaturalSpawner.SPAWNING_CATEGORIES) {
            SpawnCategory spawnCategory = CraftSpawnCategory.toBukkit(mobCategory);
            if (CraftSpawnCategory.isValidForLimits(spawnCategory)) {
                if (level.ticksPerSpawnCategory.getLong(spawnCategory) != 0 && level.getLevelData().getGameTime() % level.ticksPerSpawnCategory.getLong(spawnCategory) == 0) {
                    return true;
                }
            }
        }
        return false;
    }

}
