package io.multipaper.shreddedpaper.config;

import io.papermc.paper.configuration.ConfigurationPart;
import org.spongepowered.configurate.objectmapping.meta.Comment;

import java.util.List;

@SuppressWarnings({ "InnerClassMayBeStatic" })
public class ShreddedPaperConfiguration extends ConfigurationPart {

    public static final String HEADER = """
            Settings of the Evangelium server (multithreading and optimizations).
            The Evangelium launcher sets most of these for you; change them only if you know why.
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
        @Comment("Ticks between an idle villager's checks for a new behavior, 1 to 4. 1 is vanilla. Above 1 a decision can be that many ticks late, never when hurt, trading, sleeping, near a hostile or a player, or when its activity changes; running behaviors, sensors and physics stay on every tick. Other mobs are never delayed.")
        public int villagerDecisionInterval = 4;
        @Comment("Far mobs think less often: beyond far-ai-start-distance from every player a mob's goal and target selectors run every N ticks (squared distance >> far-ai-distance-shift, at most far-ai-max-interval). Movement, looking, path following, sensing and mob-specific logic stay on every tick. Never for mobs with a target, hurt, in water or lava, leashed, riding or ridden, or bosses.")
        public boolean farAiEnabled = true;
        public int farAiStartDistance = 12;
        public int farAiDistanceShift = 8;
        public int farAiMaxInterval = 20;
        @Comment("Mob types that always think at vanilla speed, for example [\"minecraft:villager\", \"cow\"]. Use it when a farm or a plugin needs a mob type at full speed even far from players.")
        public java.util.List<String> farAiExemptTypes = java.util.List.of();
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
        @Comment("Off by default (measured: removes 58% of half-generated chunk reloads but no speed gain). A half generated chunk that is no longer wanted stays in memory this many ticks before it may unload, once per generation stage. 0 = off. -Dshreddedpaper.generationUnloadGraceTicks=<ticks> overrides this.")
        public int generationUnloadGraceTicks = 0;
        public boolean disableVanishApi = false;
        public boolean disableLocatorBar = true;
        public int maximumTrackersPerEntity = 500;
        public long trackerFullUpdateFrequency = 20;
        public long purgeStaleTicketsFrequency = 20;
        public boolean writePlayerSavesAsync = true;
        @Comment("Read a joining player's data file, stats and advancements on a background thread while the player is in the configuration phase, and reuse them at spawn (the tick threads then only apply them). -Dshreddedpaper.joinPreload=false overrides this.")
        public boolean preloadJoinData = true;
        @Comment("Build the registry and tag packets of the configuration phase once per known-packs set (rebuilt after a datapack reload) instead of once per join on the main thread. -Dshreddedpaper.cacheRegistrySync=false overrides this.")
        public boolean cacheRegistrySync = true;
        @Comment("Spawn work of joining players allowed per tick (ms, measured average per spawn). Players beyond it wait a tick or two longer in the joining screen instead of making one long tick for everybody. 0 = off (default: no measured gain, see notes/joins.md). -Dshreddedpaper.joinSpawnBudgetMs overrides this.")
        public long joinSpawnBudgetMs = 0;
        public ChunkPacketCaching chunkPacketCaching;

        public class ChunkPacketCaching extends ConfigurationPart {

            public boolean useSoftReferences = true;
            public long expireAfter = 1200;

        }

    }


}
