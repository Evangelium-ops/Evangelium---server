package io.multipaper.shreddedpaper.ai;

import io.multipaper.shreddedpaper.config.ShreddedPaperConfiguration;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Fixed registry-sized aggregates only: no entity/world references or per-entity lifecycle state. */
public final class AiDiagnostics {
    private AiDiagnostics() {}
    public static boolean goalOptimizationEnabled() {
        var config = ShreddedPaperConfiguration.get();
        return config != null && config.aiEngine != null && config.aiEngine.enabled && config.aiEngine.goalOptimization;
    }
    public static boolean spatialOptimizationEnabled() {
        var config = ShreddedPaperConfiguration.get();
        return config != null && config.aiEngine != null && config.aiEngine.enabled && config.aiEngine.spatialOptimization;
    }
    public static boolean navigationOptimizationEnabled() {
        var config = ShreddedPaperConfiguration.get();
        return config != null && config.aiEngine != null && config.aiEngine.enabled && config.aiEngine.navigationOptimization;
    }
    public static boolean brainOptimizationEnabled() {
        var config = ShreddedPaperConfiguration.get();
        return config != null && config.aiEngine != null && config.aiEngine.enabled && config.aiEngine.brainOptimization;
    }
    /**
     * Whether an idle villager looks for a new behavior this tick. Off (always true) unless villager-decision-interval is above 1. At most
     * interval - 1 ticks late, staggered by entity id so no scan or queue is needed, and never late for one that is hurt, trading, sleeping,
     * near a hostile or a player, or whose activity just changed.
     */
    public static boolean villagerDecidesNow(net.minecraft.world.entity.npc.villager.Villager villager, boolean activityChanged) {
        var config = ShreddedPaperConfiguration.get();
        if (config == null || config.aiEngine == null || !config.aiEngine.enabled || !config.aiEngine.brainOptimization || activityChanged) return true;
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
        if (config == null || config.aiEngine == null || !config.aiEngine.enabled || !config.aiEngine.profilingEnabled) return 0;
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
