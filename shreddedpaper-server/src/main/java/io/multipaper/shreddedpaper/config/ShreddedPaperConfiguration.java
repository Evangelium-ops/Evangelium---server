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
        @Comment("Mobs packed together stop collecting who pushes them once the push limit (max-entity-collisions) and the cramming rule are both answered; the same mobs are pushed and crammed as before, only the rest of the crowd is not filtered every tick.")
        public boolean cappedPushSearch = true;
        @Comment("Each chunk section keeps a copy of its entities' hitboxes side by side in memory, kept exact on every move, so finding who touches a box (pushing, pickups, attacks) reads one array instead of every entity. Same entities found in the same order.")
        public boolean packedEntityBoxes = true;
        @Comment("Players who watch another player do not get that player's motion packets from jumping or being pushed (a client moves other players only from their position packets, so nothing looks different). Knockback motion, elytra flight and every packet about yourself are still sent. Fighting crowds send about a quarter fewer packets.")
        public boolean skipWatchedPlayerMotion = true;
        @Comment("Movement speed, attack speed, reach and mining attributes of a player are sent only to that player's own client (sprinting and switching items changed them for every watcher). Watchers still get every other attribute, and all of them when they start seeing the player.")
        public boolean skipWatchedPlayerOwnAttributes = true;
        @Comment("A mob standing still where no block around it changed reuses last tick's block answers (falling, supporting block, blocks it is inside, fluids) instead of asking every block again; a fresh look at least once a second. Same results; only block edits that bypass the server (some world-edit plugins writing chunk memory directly) are seen up to a second late by a resting mob.")
        public boolean restingEntityCache = true;
        @Comment("An entity whose whole path this tick lies in air skips the block-by-block walk that looks for blocks it touches (cactus, fire, pressure plates, water...): the walk would find nothing. Same results. -Dshreddedpaper.airBlockWalkSkip=true/false overrides this.")
        public boolean airBlockWalkSkip = true;
        @Comment("Every chunk section counts its water and lava blocks, so the two fluid checks every entity makes each tick return at once when no section around it holds that fluid. Same results. -Dshreddedpaper.fluidSectionCounts=true/false overrides this.")
        public boolean fluidSectionCounts = true;
        @Comment("Collision checks take the stored collision shape of every block whose type has no special collision rule (stone, dirt, grass, planks...) instead of asking the block for each entity again. Same results. -Dshreddedpaper.contextFreeShapes=true/false overrides this.")
        public boolean contextFreeShapes = true;
        @Comment("An entity's region-ownership checks (moving, standing on a block) that already passed this tick on the same thread for the same position are not repeated. These checks only guard against plugin misuse; they still run once per tick and position. -Dshreddedpaper.tickThreadCheckMemo=true/false overrides this.")
        public boolean tickThreadCheckMemo = true;
        @Comment("Entity position and speed writes skip Paper's watchdog lock (posLock): nothing in this server reads under it, so every move paid a lock for nothing. -Dshreddedpaper.noPosLock=true/false overrides this.")
        public boolean noPosLock = true;
        @Comment("Entities pushed by flowing water or lava reuse the flow direction of a fluid block already worked out by the same thread in the same piece of tick work (any block change on that thread, and every new region tick or chunk work unit, starts over). Same results; most useful for item and mob streams. -Dshreddedpaper.flowCache=true/false overrides this.")
        public boolean flowCache = true;
        @Comment("An entity that is not burning and whose path this tick lies only in air and plain water skips the per-block water geometry test of the block walk: water's only effect there is putting fire out, which changes nothing for an entity that is not burning. Same results. -Dshreddedpaper.waterWalkSkip=true/false overrides this.")
        public boolean waterWalkSkip = true;
        @Comment("The block under an entity that slows it or makes it slide (asked four or five times per tick) is worked out once per tick and position; any block change on that tick thread starts over. Same results. -Dshreddedpaper.onPosMemo=true/false overrides this.")
        public boolean onPosMemo = true;
        @Comment("Chunk generation turns each density function tree (the terrain formulas) into generated Java code once per tree shape instead of walking the tree for every sample. Same operations in the same order, so the terrain is bit-identical; any function it does not understand keeps the normal path. -Dshreddedpaper.densityFunctionCompiler=true/false overrides this.")
        public boolean densityFunctionCompiler = true;
        @Comment("Chunk generation turns the surface rules (which block goes on top: grass, sand, snow, terracotta...) into generated Java code once per rule shape, and reuses the biome answer of a block instead of building new objects for it. Same checks in the same order, so the terrain is bit-identical. -Dshreddedpaper.surfaceRuleCompiler=true/false overrides this.")
        public boolean surfaceRuleCompiler = true;
        @Comment("Biome lookups reuse the per-cell random offsets of the last cell asked and search the biome tree with an early stop; exactly the same biome answers. -Dshreddedpaper.biomeLookupCache=true/false overrides this.")
        public boolean biomeLookupCache = true;
        @Comment("Chunk generation samples each terrain noise for a whole column of noise corners at once (the parts that only depend on x and z once per column, not once per corner) and a noise that does not depend on height only once per column. Same operations per value, so the terrain is bit-identical. -Dshreddedpaper.columnNoise=true/false overrides this.")
        public boolean columnNoise = true;
        @Comment("Chunk generation interpolates the terrain density inside each noise cell with the in-cell fractions computed once and the parts that do not change along a row computed once per row. Same operations per block, so the terrain is bit-identical. -Dshreddedpaper.cellInterpolation=true/false overrides this.")
        public boolean cellInterpolation = true;
        @Comment("Chunk generation computes the terrain formula of a noise cell block by block in one pass (needs cell-interpolation) instead of one pass per formula step. Same operations per value, so the terrain is bit-identical. -Dshreddedpaper.cellFusion=true/false overrides this.")
        public boolean cellFusion = true;
        @Comment("Chunk generation keeps the 12 nearby aquifer centres of the current aquifer cell unpacked, so a cave block measures its distances to them without looking each one up again. Same centres in the same order, so the same blocks and fluid updates. -Dshreddedpaper.aquiferCentreCache=true/false overrides this.")
        public boolean aquiferCentreCache = true;
        @Comment("A chunk loaded from disk is written back only when something in it changed (blocks, block entities, light, scheduled ticks, structures, plugin data), instead of every chunk being rewritten when it unloads. Only difference: a chunk that spent under a minute near players since its last save does not store that minute in its regional difficulty clock (inhabited time). -Dshreddedpaper.skipUnchangedChunkSaves=true/false overrides this.")
        public boolean skipUnchangedChunkSaves = true;
        @Comment("Off by default (measured: removes 58% of half-generated chunk reloads but no speed gain). A half generated chunk that is no longer wanted stays in memory this many ticks before it may unload, once per generation stage. 0 = off. -Dshreddedpaper.generationUnloadGraceTicks=<ticks> overrides this.")
        public int generationUnloadGraceTicks = 0;
        @Comment("Chunk generation tasks that write into their neighbours (decoration, light) start as soon as no running task overlaps them, instead of queueing behind other waiting tasks; overlapping tasks still never run together, and a task that waited 250 ms or has a raised priority keeps its place. Pregenerators that walk in blocks (Chunky) run several of these at once instead of about one. -Dshreddedpaper.radiusQueueOverlapOnly=true/false overrides this.")
        public boolean radiusQueueOverlapOnly = true;
        @Comment("A light section whose light the engine recomputed to exactly the same values (common on every chunk load: border checks and sky sections initialised again) no longer counts as a light change, so the chunk is not saved again and no light packet is sent for it. Light values are unchanged. -Dshreddedpaper.skipUnchangedLightUpdates=true/false overrides this.")
        public boolean skipUnchangedLightUpdates = true;
        @Comment("When a chunk loads, the light engine checks its edges against the loaded neighbours. At the two corner columns of an edge it also reads the chunk beside the corner; if that chunk is not loaded it was read as dark air, so light coming from it was removed and came back when it loaded (both chunks were rewritten on every visit). Now a darker answer next to an unloaded chunk is ignored; the corner is checked when that chunk loads. Brighter answers are acted on as before. -Dshreddedpaper.lightEdgeChecksIgnoreUnloaded=true/false overrides this.")
        public boolean lightEdgeChecksIgnoreUnloaded = true;
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
