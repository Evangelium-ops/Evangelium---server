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
