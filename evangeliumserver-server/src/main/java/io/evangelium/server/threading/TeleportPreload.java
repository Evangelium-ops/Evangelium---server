package io.evangelium.server.threading;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicketType;
import com.mojang.logging.LogUtils;
import io.evangelium.server.EvangeliumServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Evangelium: loads (and generates when needed) the chunks a teleport or a teleport-related search will touch BEFORE the work runs
 * on a region thread, the way Paper's teleportAsync / Folia do it. Without this the region thread that runs the teleport loads the
 * chunk synchronously (ServerChunkCache.syncLoad -> managedBlock) while it holds its 3x3 region locks, and since the regions tick in
 * lock step the whole server waits for the load or the generation (the 3-6 s ticks of a mass teleport into new terrain).
 * <p>
 * Every helper here only changes WHEN the work runs, never what it does: when all chunks are already loaded the work runs at once on
 * the calling thread, exactly as before. Otherwise the chunks are scheduled asynchronously; when the last one is ready they get a
 * short ticket (TICKET, {@link #HOLD_TICKS} ticks, loading only) so they stay loaded until the deferred work has run, and the work
 * then goes through the same EvangeliumServer.ensureSync hop as before. If a chunk unloads anyway (ticket expired under a long stall) or
 * a plugin changes the destination in an event, the old synchronous load still happens: correct, only slower.
 * <p>
 * Every caller of these helpers already ran its work asynchronously (ensureSync may defer to another region), so deferring it a bit
 * more cannot break an API that has to be synchronous. Switch: -Devangeliumserver.teleportPreload=false = old behaviour.
 */
public final class TeleportPreload {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("evangeliumserver.teleportPreload"));
    public static final long HOLD_TICKS = Long.getLong("evangeliumserver.teleportPreloadHoldTicks", 100L);
    /** Paper's teleportAsync margin: the entity box at the destination plus 3 blocks (what the first ticks there read). */
    private static final double MOVE_MARGIN = 3.0;

    /** Not registered (cannot persist), loading only, expires after HOLD_TICKS ticks; identifier = one id per preload. */
    public static final TicketType TICKET = ChunkSystemTicketType.create("evangelium:teleport_preload", Long::compareTo, HOLD_TICKS, TicketType.FLAG_LOADING);

    // diagnostics (cheap counters, printed by the sync-load report)
    public static final AtomicLong IMMEDIATE = new AtomicLong();
    public static final AtomicLong DEFERRED = new AtomicLong();
    public static final AtomicLong DEFERRED_CHUNKS = new AtomicLong();
    public static final AtomicLong DEFERRED_NANOS = new AtomicLong();

    // synchronous loads that made a tick thread wait (ServerChunkCache.syncLoad), always counted; logged with their caller when
    // -Devangeliumserver.syncLoadLog=<ms> is set and one took at least that long
    public static final AtomicLong SYNC_LOADS = new AtomicLong();
    public static final AtomicLong SYNC_LOAD_NANOS = new AtomicLong();
    public static final AtomicLong SYNC_LOAD_MAX_NANOS = new AtomicLong();
    private static final long SYNC_LOAD_LOG_NANOS = Long.getLong("evangeliumserver.syncLoadLog", -1L) * 1_000_000L;

    private TeleportPreload() {
    }

    public static void recordSyncLoad(ServerLevel level, int chunkX, int chunkZ, ChunkStatus status, long nanos) {
        final long n = SYNC_LOADS.incrementAndGet();
        final long total = SYNC_LOAD_NANOS.addAndGet(nanos);
        SYNC_LOAD_MAX_NANOS.accumulateAndGet(nanos, Math::max);
        if (SYNC_LOAD_LOG_NANOS >= 0L && nanos >= SYNC_LOAD_LOG_NANOS) {
            final String caller = StackWalker.getInstance().walk(frames -> frames
                .skip(1)
                .filter(f -> !f.getClassName().startsWith("net.minecraft.server.level.ServerChunkCache")
                    && !f.getClassName().startsWith("io.evangelium.server.threading.TeleportPreload")
                    && !f.getClassName().startsWith("net.minecraft.util.thread.")
                    && !f.getClassName().startsWith("net.minecraft.world.level.chunk.ChunkSource"))
                .limit(8)
                .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName() + ":" + f.getLineNumber())
                .collect(java.util.stream.Collectors.joining(" < ")));
            LOGGER.warn("SYNCLOAD {} ms chunk {} {} {} status {} on {} (count {} total {} ms) at {}", nanos / 1_000_000L,
                level.getWorld().getName(), chunkX, chunkZ, status, Thread.currentThread().getName(), n, total / 1_000_000L, caller);
        }
    }

    /**
     * Runs {@code task} once every chunk in the inclusive chunk range is loaded to at least {@code status}: at once on this thread if
     * they already are, otherwise on the thread that finishes the last load (the task must hop to its region itself, e.g. through
     * EvangeliumServer.ensureSync). The chunks keep a {@link #TICKET} for {@link #HOLD_TICKS} ticks after they are ready.
     */
    public static void whenLoaded(ServerLevel level, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, ChunkStatus status, Priority priority, Runnable task) {
        if (!ENABLED || TickThread.isShutdownThread() || allLoaded(level, minChunkX, minChunkZ, maxChunkX, maxChunkZ, status)) {
            IMMEDIATE.incrementAndGet();
            task.run();
            return;
        }
        final ChunkTaskScheduler scheduler = ((ChunkSystemServerLevel) level).moonrise$getChunkTaskScheduler();
        if (scheduler.hasShutdown()) {
            task.run();
            return;
        }
        final ChunkHolderManager holders = scheduler.chunkHolderManager;
        final Long id = ChunkTaskScheduler.getNextChunkLoadId();
        final int ticketLevel = ChunkTaskScheduler.getTicketLevel(status);
        final int required = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        final AtomicInteger remaining = new AtomicInteger(required);
        final long start = System.nanoTime();
        DEFERRED.incrementAndGet();
        DEFERRED_CHUNKS.addAndGet(required);

        final java.util.function.Consumer<ChunkAccess> onChunk = chunk -> {
            if (chunk != null) {
                // added before the scheduler drops its own load ticket (it runs this callback first)
                holders.addTicketAtLevel(TICKET, chunk.getPos(), ticketLevel, id);
            }
            if (remaining.decrementAndGet() == 0) {
                DEFERRED_NANOS.addAndGet(System.nanoTime() - start);
                try {
                    task.run();
                } catch (Throwable throwable) {
                    LOGGER.error("Deferred teleport work failed after its chunks loaded", throwable);
                }
            }
        };

        for (int cx = minChunkX; cx <= maxChunkX; ++cx) {
            for (int cz = minChunkZ; cz <= maxChunkZ; ++cz) {
                scheduler.scheduleChunkLoad(cx, cz, status, true, priority, onChunk);
            }
        }
    }

    public static void whenLoaded(ServerLevel level, BlockPos center, int radiusBlocks, ChunkStatus status, Priority priority, Runnable task) {
        whenLoaded(level, (center.getX() - radiusBlocks) >> 4, (center.getZ() - radiusBlocks) >> 4,
            (center.getX() + radiusBlocks) >> 4, (center.getZ() + radiusBlocks) >> 4, status, priority, task);
    }

    /**
     * An executor for CompletableFuture.*Async that first preloads the chunks within {@code radiusBlocks} of {@code pos} (FULL) and
     * then runs the work on pos's region, like {@code r -> EvangeliumServer.ensureSync(level, pos, r)} did.
     */
    public static Executor onRegionWhenLoaded(ServerLevel level, BlockPos pos, int radiusBlocks) {
        return onRegionWhenLoaded(level, pos, pos, radiusBlocks, ChunkStatus.FULL);
    }

    /** As above, with the region to run on ({@code regionPos}) and the area to preload ({@code center}, radius, status) given apart. */
    public static Executor onRegionWhenLoaded(ServerLevel level, BlockPos regionPos, BlockPos center, int radiusBlocks, ChunkStatus status) {
        return r -> whenLoaded(level, center, radiusBlocks, status, Priority.HIGHER, () -> EvangeliumServer.ensureSync(level, regionPos, r));
    }

    /**
     * The teleport of {@code entity} to {@code destination} in {@code level}: preloads the entity box there (+3 blocks, as Paper's
     * teleportAsync), then runs EvangeliumServer.ensureSync(entity, level, lockChunk, runnable) exactly as the caller did before.
     */
    public static void ensureSyncTeleport(Entity entity, ServerLevel level, Vec3 destination, ChunkPos lockChunk, Runnable runnable) {
        final AABB box = entity.getBoundingBoxAt(destination.x, destination.y, destination.z);
        final int minChunkX = Mth.floor(box.minX - 1.0E-7D - MOVE_MARGIN) >> 4;
        final int minChunkZ = Mth.floor(box.minZ - 1.0E-7D - MOVE_MARGIN) >> 4;
        final int maxChunkX = Mth.floor(box.maxX + 1.0E-7D + MOVE_MARGIN) >> 4;
        final int maxChunkZ = Mth.floor(box.maxZ + 1.0E-7D + MOVE_MARGIN) >> 4;
        whenLoaded(level, minChunkX, minChunkZ, maxChunkX, maxChunkZ, ChunkStatus.FULL,
            entity instanceof ServerPlayer ? Priority.HIGHER : Priority.NORMAL,
            () -> EvangeliumServer.ensureSync(entity, level, lockChunk, runnable));
    }

    /** Gives already loaded FULL chunks a {@link #TICKET} (teleportAsync: its load tickets go before its deferred teleport runs). */
    public static void hold(ServerLevel level, java.util.List<? extends ChunkAccess> chunks) {
        if (!ENABLED || chunks.isEmpty()) {
            return;
        }
        final ChunkHolderManager holders = ((ChunkSystemServerLevel) level).moonrise$getChunkTaskScheduler().chunkHolderManager;
        final Long id = ChunkTaskScheduler.getNextChunkLoadId();
        final int ticketLevel = ChunkTaskScheduler.getTicketLevel(ChunkStatus.FULL);
        for (int i = 0, len = chunks.size(); i < len; ++i) {
            holders.addTicketAtLevel(TICKET, chunks.get(i).getPos(), ticketLevel, id);
        }
    }

    public static boolean allLoaded(ServerLevel level, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, ChunkStatus status) {
        final boolean full = status == ChunkStatus.FULL;
        for (int cx = minChunkX; cx <= maxChunkX; ++cx) {
            for (int cz = minChunkZ; cz <= maxChunkZ; ++cz) {
                if (full ? level.getChunkSource().getChunkAtIfLoadedImmediately(cx, cz) == null
                         : level.moonrise$getSpecificChunkIfLoaded(cx, cz, status) == null) {
                    return false;
                }
            }
        }
        return true;
    }
}
