package io.multipaper.shreddedpaper.ai;

import io.multipaper.shreddedpaper.config.ShreddedPaperConfiguration;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Fixed registry-sized aggregates only: no entity/world references or per-entity lifecycle state. */
public final class AiDiagnostics {
    private AiDiagnostics() {}
    // developer benchmarks only: "ai-engine" turns all of them off, as the removed ai-engine.enabled did
    private static final boolean ENGINE = io.multipaper.shreddedpaper.util.DevSwitch.on("ai-engine");
    private static final boolean GOAL = ENGINE && io.multipaper.shreddedpaper.util.DevSwitch.on("goal-optimization");
    private static final boolean SPATIAL = ENGINE && io.multipaper.shreddedpaper.util.DevSwitch.on("spatial-optimization");
    private static final boolean NAVIGATION = ENGINE && io.multipaper.shreddedpaper.util.DevSwitch.on("navigation-optimization");
    private static final boolean BRAIN = ENGINE && io.multipaper.shreddedpaper.util.DevSwitch.on("brain-optimization");
    /** the far AI reads this instead of the removed ai-engine.enabled */
    public static boolean engineOn() {
        return ENGINE;
    }
    public static boolean goalOptimizationEnabled() {
        var config = ShreddedPaperConfiguration.get();
        return GOAL && config != null && config.aiEngine != null;
    }
    public static boolean spatialOptimizationEnabled() {
        var config = ShreddedPaperConfiguration.get();
        return SPATIAL && config != null && config.aiEngine != null;
    }
    public static boolean navigationOptimizationEnabled() {
        var config = ShreddedPaperConfiguration.get();
        return NAVIGATION && config != null && config.aiEngine != null;
    }
    public static boolean brainOptimizationEnabled() {
        var config = ShreddedPaperConfiguration.get();
        return BRAIN && config != null && config.aiEngine != null;
    }
    /**
     * Whether an idle villager looks for a new behavior this tick. Off (always true) unless villager-decision-interval is above 1. At most
     * interval - 1 ticks late, staggered by entity id so no scan or queue is needed, and never late for one that is hurt, trading, sleeping,
     * near a hostile or a player, or whose activity just changed.
     */
    public static boolean villagerDecidesNow(net.minecraft.world.entity.npc.villager.Villager villager, boolean activityChanged) {
        var config = ShreddedPaperConfiguration.get();
        if (!BRAIN || config == null || config.aiEngine == null || activityChanged) return true;
        int interval = Math.clamp(config.aiEngine.villagerDecisionInterval, 1, 4);
        if (interval == 1 || Math.floorMod((long) villager.tickCount + villager.getId(), interval) == 0) return true;
        if (villager.hurtTime > 0 || villager.isTrading() || villager.isSleeping()) return true;
        var brain = villager.getBrain();
        return brain.hasMemoryValue(net.minecraft.world.entity.ai.memory.MemoryModuleType.HURT_BY)
            || brain.hasMemoryValue(net.minecraft.world.entity.ai.memory.MemoryModuleType.NEAREST_HOSTILE)
            || brain.hasMemoryValue(net.minecraft.world.entity.ai.memory.MemoryModuleType.NEAREST_VISIBLE_PLAYER);
    }
    private static class Metrics {
        static final Sample[] TYPES = java.util.stream.IntStream.range(0, BuiltInRegistries.ENTITY_TYPE.size())
            .mapToObj(ignored -> new Sample()).toArray(Sample[]::new);
    }
    public static final class Sample {
        private final LongAdder count = new LongAdder();
        private final LongAdder nanos = new LongAdder();
        private final AtomicLong maxNanos = new AtomicLong();
        public long count() { return count.sum(); }
        public long nanos() { return nanos.sum(); }
        public long maxNanos() { return maxNanos.get(); }
    }
    public static Sample sample(int registryId) {
        return registryId >= 0 && registryId < Metrics.TYPES.length ? Metrics.TYPES[registryId] : null;
    }
    /** Called from the existing owning entity tick; disabled path does not read entity state or initialize metrics. */
    public static long begin(Mob mob) {
        var config = ShreddedPaperConfiguration.get();
        if (!ENGINE || config == null || config.aiEngine == null || !config.aiEngine.profilingEnabled) return 0;
        int interval = Math.clamp(config.aiEngine.profilingIntervalTicks, 1, 1200);
        if (Math.floorMod((long) mob.tickCount + mob.getId(), interval) != 0) return 0;
        return System.nanoTime();
    }
    public static void end(Mob mob, long started) {
        if (started == 0) return;
        long elapsed = System.nanoTime() - started;
        Sample sample = sample(BuiltInRegistries.ENTITY_TYPE.getId(mob.getType()));
        if (sample != null) {
            sample.count.increment();
            sample.nanos.add(elapsed);
            sample.maxNanos.accumulateAndGet(elapsed, Math::max);
        }
    }
}
