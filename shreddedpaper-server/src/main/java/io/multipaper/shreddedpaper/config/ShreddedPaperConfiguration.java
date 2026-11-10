package io.multipaper.shreddedpaper.config;

import io.papermc.paper.configuration.ConfigurationPart;
import org.spongepowered.configurate.objectmapping.meta.Comment;

import java.util.List;

@SuppressWarnings({ "InnerClassMayBeStatic" })
public class ShreddedPaperConfiguration extends ConfigurationPart {

    public static final String HEADER = """
            This is the main configuration file for ShreddedPaper.
            There's quite alot to configure. Read the docs for more information.

            Docs: https://github.com/MultiPaper/ShreddedPaper/blob/main/SHREDDEDPAPER_YAML.md\s
            """;

    private static ShreddedPaperConfiguration instance;

    public static ShreddedPaperConfiguration get() {
        return instance;
    }

    static void set(ShreddedPaperConfiguration instance) {
        ShreddedPaperConfiguration.instance = instance;
    }

    public AiEngine aiEngine;

    public class AiEngine extends ConfigurationPart {
        public boolean enabled = true;
        public boolean goalOptimization = true;
        public boolean spatialOptimization = true;
        public boolean navigationOptimization = true;
        @Comment("Iterate fixed Brain sensors and behaviors as flat arrays in their original order; timers, activity checks and owner-thread execution remain unchanged.")
        public boolean brainOptimization = true;
        @Comment("Ticks between an idle villager's checks for a new behavior, 1 to 4. 1 is vanilla. Above 1 a decision can be that many ticks late, never when hurt, trading, sleeping, near a hostile or a player, or when its activity changes; running behaviors, sensors and physics stay on every tick. Other mobs are never delayed.")
        public int villagerDecisionInterval = 4;
        @Comment("Far mobs think less often: beyond far-ai-start-distance from every player a mob's goal and target selectors run every N ticks (squared distance >> far-ai-distance-shift, at most far-ai-max-interval). Movement, looking, path following, sensing and mob-specific logic stay on every tick. Never for mobs with a target, hurt, in water or lava, leashed, riding or ridden, or bosses.")
        public boolean farAiEnabled = true;
        public int farAiStartDistance = 12;
        public int farAiDistanceShift = 8;
        public int farAiMaxInterval = 20;
        @Comment("Owner-thread AI timing; disabled by default. Counts sampled invocations, not total AI CPU time.")
        public boolean profilingEnabled = false;
        @Comment("Profile one invocation per mob every N entity ticks, staggered by entity id; clamped to 1..1200.")
        public int profilingIntervalTicks = 20;
    }

    public Multithreading multithreading;

    public class Multithreading extends ConfigurationPart {

        public int threadCount = -1;
        public int regionSize = 8;
        public boolean runUnsupportedPluginsInSync = true;
        public boolean allowUnsupportedPluginsToModifyChunksViaGlobalScheduler = true;

    }

    public Optimizations optimizations;

    public class Optimizations extends ConfigurationPart {

        public int entityActivationCheckFrequency = 20;
        @Comment("Mobs packed together stop collecting who pushes them once the push limit (max-entity-collisions) and the cramming rule are both answered; the same mobs are pushed and crammed as before, only the rest of the crowd is not filtered every tick.")
        public boolean cappedPushSearch = true;
        public boolean disableVanishApi = false;
        public boolean disableLocatorBar = true;
        public boolean useLazyExecuteWhenNotFlushing = true;
        public boolean processTrackQueueInParallel = true;
        public boolean flushQueueInParallel = true;
        public int maximumTrackersPerEntity = 500;
        public long trackerFullUpdateFrequency = 20;
        public long purgeStaleTicketsFrequency = 20;
        public boolean writePlayerSavesAsync = true;
        public ChunkPacketCaching chunkPacketCaching;

        public class ChunkPacketCaching extends ConfigurationPart {

            public boolean enabled = true;
            public boolean useSoftReferences = true;
            public long expireAfter = 1200;

        }

    }


}
