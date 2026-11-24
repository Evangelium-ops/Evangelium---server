package io.multipaper.shreddedpaper.player;

import io.multipaper.shreddedpaper.config.ShreddedPaperConfiguration;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Knowledge fork: spreads the spawn work of a join wave over several ticks. The spawn of a player (ServerPlayer, its data,
 * placeNewPlayer) runs on the spawn region's thread, and ShreddedPaper ticks in lock step, so five heavy joins in one tick made one
 * long tick for everybody. A player whose spawn is ready waits in the configuration phase (still on the "joining world" screen,
 * keep-alives normal) until the tick's spawn budget has room: each tick adds the budget, each released join takes the measured
 * average spawn cost, the budget never saves up more than one tick. At least one join gets through every few ticks, so a wave of 20
 * heavy players takes about a second longer to finish and nobody notices a lag spike. 0 = off.
 */
public final class JoinPacer {

    private static final long PROPERTY_MS = Long.getLong("shreddedpaper.joinSpawnBudgetMs", -1L);
    private static final AtomicLong AVG_COST_NANOS = new AtomicLong(10_000_000L); // a first guess until spawns are measured
    private static int lastTick = Integer.MIN_VALUE;
    private static long tokens;

    private JoinPacer() {
    }

    private static long budgetNanos() {
        long ms = PROPERTY_MS >= 0 ? PROPERTY_MS : ShreddedPaperConfiguration.get().optimizations.joinSpawnBudgetMs;
        return ms * 1_000_000L;
    }

    /** Main thread, once per tick per waiting player: may this player's spawn go ahead now? */
    public static synchronized boolean tryAcquire() {
        long budget = budgetNanos();
        if (budget <= 0) {
            return true;
        }
        int tick = MinecraftServer.currentTick;
        if (tick != lastTick) {
            long ticks = lastTick == Integer.MIN_VALUE ? 1 : Math.max(1, Math.min(20, tick - lastTick));
            tokens = Math.min(budget, tokens + budget * ticks);
            lastTick = tick;
        }
        if (tokens <= 0) {
            return false;
        }
        tokens -= AVG_COST_NANOS.get();
        return true;
    }

    /** Any thread: one spawn took this long (moving average over about 8 spawns). */
    public static void spawnCost(long nanos) {
        AVG_COST_NANOS.getAndUpdate(avg -> avg + (Math.min(nanos, 1_000_000_000L) - avg) / 8);
    }
}
