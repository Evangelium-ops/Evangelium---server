package io.evangelium.server.player;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.evangelium.server.config.EvangeliumServerConfiguration;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.players.NameAndId;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Evangelium fork: everything a join reads from disk, read on a background thread while the player is still in the configuration
 * phase: the player data file (gunzip + NBT + DataConverter), the stats file (JSON + DataFixer) and the advancements file
 * (JSON + DataFixer). Before this, the main thread read the player file once (PrepareSpawnTask.start) and the spawn region thread
 * read it a second time plus stats and advancements, so a join wave stalled the lock-step tick for everyone.
 *
 * Staleness guard: every save of a player's files (player data, stats, advancements) bumps a per-player counter before and after it
 * (a seqlock on a striped array). A preload is only used if the counter was even (no save in progress) when it started and is
 * unchanged when it is used; otherwise the caller reads again the old way. Collisions in the stripes only cause a needless re-read.
 */
public final class JoinPreload {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final boolean PROPERTY = Boolean.parseBoolean(System.getProperty("evangeliumserver.joinPreload", "true"));
    private static final boolean DETACHED_ADVANCEMENTS = Boolean.parseBoolean(System.getProperty("evangeliumserver.joinPreloadAdvancementListeners", "true"));
    private static final int STRIPES = 1024;
    private static final boolean DECODE_RECIPE_BOOK = Boolean.parseBoolean(System.getProperty("evangeliumserver.joinPreloadRecipeBook", "true"));
    public static final boolean DEBUG = Boolean.getBoolean("evangeliumserver.joinPreloadDebug");
    /** Test hook only: "read" = pretend a save ran during each player's first read, "spawn" = pretend one ran before spawn. */
    public static final String TEST_STALE = System.getProperty("evangeliumserver.joinPreloadTestStale", "");
    private static final java.util.Set<UUID> TEST_BUMPED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Test hook (see TEST_STALE): called on the main thread after the preload was taken into use. */
    public static void testStaleBeforeSpawn(UUID id) {
        if ("spawn".equals(TEST_STALE)) {
            beginSave(id.toString());
            endSave(id.toString());
        }
    }
    private static final AtomicLongArray SAVE_EPOCH = new AtomicLongArray(STRIPES);
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 4)), r -> {
        Thread t = new Thread(r, "Join Data Loader #" + THREAD_ID.incrementAndGet());
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((thread, e) -> LOGGER.error("Uncaught exception in {}", thread.getName(), e));
        return t;
    });
    /** The preload the current thread is building a ServerPlayer from (set only around the constructor). */
    private static final ThreadLocal<JoinPreload> CURRENT = new ThreadLocal<>();

    public final UUID id;
    private final long epoch;
    /** The converted player data (empty = new player); the first copy is for PrepareSpawnTask.start, the second for spawn. */
    public final Optional<CompoundTag> playerTag;
    private @Nullable CompoundTag spawnTag;
    /** lastModified of the player file, read at the same time (CraftBukkit's first-played fix-up). */
    public final long playerFileModified;
    private final @Nullable Path statsPath;
    private final @Nullable Map<Stat<?>, Integer> stats;
    private final @Nullable Path advancementsPath;
    private final PlayerAdvancements.@Nullable Preparsed advancements;
    /** The recipe book decoded from the player file (1000+ recipe keys for a long-time player), or null (decode it the old way). */
    private net.minecraft.stats.ServerRecipeBook.@Nullable Packed recipeBook;

    private JoinPreload(UUID id, long epoch, Optional<CompoundTag> playerTag, long playerFileModified, @Nullable Path statsPath,
                        @Nullable Map<Stat<?>, Integer> stats, @Nullable Path advancementsPath, PlayerAdvancements.@Nullable Preparsed advancements) {
        this.id = id;
        this.epoch = epoch;
        this.playerTag = playerTag;
        this.spawnTag = playerTag.map(CompoundTag::copy).orElse(null);
        this.playerFileModified = playerFileModified;
        this.statsPath = statsPath;
        this.stats = stats;
        this.advancementsPath = advancementsPath;
        this.advancements = advancements;
    }

    public static boolean enabled() {
        return PROPERTY && EvangeliumServerConfiguration.get().optimizations.preloadJoinData;
    }

    private static int stripe(String uuid) {
        int h = uuid.hashCode();
        return (h ^ (h >>> 16)) & (STRIPES - 1);
    }

    /** Call before writing any of this player's files (player data, stats, advancements). */
    public static void beginSave(String uuid) {
        SAVE_EPOCH.incrementAndGet(stripe(uuid));
    }

    /** Call after the write (also when it failed). */
    public static void endSave(String uuid) {
        SAVE_EPOCH.incrementAndGet(stripe(uuid));
    }

    /** True when no save of this player started since the preload began (and none was running then). */
    public boolean stillValid() {
        return (this.epoch & 1) == 0 && SAVE_EPOCH.get(stripe(this.id.toString())) == this.epoch;
    }

    /** Starts reading on the join loader threads. Never fails: an exception completes the future exceptionally. */
    public static CompletableFuture<JoinPreload> start(MinecraftServer server, NameAndId nameAndId, GameProfile profile) {
        return CompletableFuture.supplyAsync(() -> load(server, nameAndId, profile), EXECUTOR);
    }

    private static JoinPreload load(MinecraftServer server, NameAndId nameAndId, GameProfile profile) {
        String uuid = nameAndId.id().toString();
        long epoch = SAVE_EPOCH.get(stripe(uuid));
        if ("read".equals(TEST_STALE) && TEST_BUMPED.add(nameAndId.id())) {
            beginSave(uuid);
            endSave(uuid);
        }
        // the player file: PlayerDataStorage.load waits for this player's pending async write first, then reads and converts
        Optional<CompoundTag> tag = server.getPlayerList().loadPlayerData(nameAndId);
        long modified = tag.isPresent() ? new File(server.playerDataStorage.getPlayerDir(), uuid + ".dat").lastModified() : 0L;
        // stats: a read or parse failure leaves it null, and the ServerPlayer constructor then reads it the old way (and logs it)
        Path statsPath = null;
        Map<Stat<?>, Integer> stats = null;
        try {
            statsPath = server.getPlayerList().locateStatsFile(profile);
            stats = ServerStatsCounter.preparse(server, statsPath);
        } catch (Throwable t) {
            stats = null;
        }
        Path advPath = null;
        PlayerAdvancements.Preparsed adv = null;
        try {
            advPath = server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(uuid + ".json");
            adv = PlayerAdvancements.preparse(server.getFixerUpper(), advPath);
            if (DETACHED_ADVANCEMENTS) {
                // progress applied and listeners registered here too; a failure means the player's thread does it all again from the file
                adv.detached = PlayerAdvancements.detached(server.getFixerUpper(), server.getPlayerList(), server.getAdvancements(), advPath, adv);
            }
        } catch (Throwable t) {
            adv = null;
        }
        JoinPreload preload = new JoinPreload(nameAndId.id(), epoch, tag, modified, statsPath, stats, advPath, adv);
        if (DECODE_RECIPE_BOOK && tag.isPresent() && tag.get().get("recipeBook") != null) {
            try {
                preload.recipeBook = net.minecraft.stats.ServerRecipeBook.Packed.CODEC
                    .parse(server.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), tag.get().get("recipeBook"))
                    .result().orElse(null);
            } catch (Throwable t) {
                preload.recipeBook = null;
            }
        }
        return preload;
    }

    /** The second copy of the player tag, for spawn (once). */
    public Optional<CompoundTag> takeSpawnTag() {
        CompoundTag t = this.spawnTag;
        this.spawnTag = null;
        return Optional.ofNullable(t);
    }

    /** Runs the action (a ServerPlayer constructor) with this preload visible to PlayerList.getPlayerStats / getPlayerAdvancements. */
    public <T> T with(java.util.function.Supplier<T> action) {
        JoinPreload before = CURRENT.get();
        CURRENT.set(this);
        try {
            return action.get();
        } finally {
            if (before == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(before);
            }
        }
    }

    /** ServerPlayer.readAdditionalSaveData: the recipe book decoded on the loader thread for this player (once), or null. */
    public static net.minecraft.stats.ServerRecipeBook.@Nullable Packed recipeBookFor(UUID id) {
        JoinPreload p = CURRENT.get();
        if (p == null || !p.id.equals(id)) {
            return null;
        }
        net.minecraft.stats.ServerRecipeBook.Packed packed = p.recipeBook;
        p.recipeBook = null;
        return packed;
    }

    /** The preloaded stats for this profile, or null (read them the old way). */
    public static @Nullable ServerStatsCounter statsFor(MinecraftServer server, GameProfile profile) {
        JoinPreload p = CURRENT.get();
        if (p == null || p.stats == null || p.statsPath == null || !p.id.equals(profile.id()) || !p.stillValid()) {
            return null;
        }
        return new ServerStatsCounter(server, p.statsPath, p.stats);
    }

    /** The preparsed advancements for this player, or null (read them the old way). */
    public static PlayerAdvancements.@Nullable Preparsed advancementsFor(UUID id, Path path) {
        JoinPreload p = CURRENT.get();
        if (p == null || p.advancements == null || !p.id.equals(id) || !path.equals(p.advancementsPath) || !p.stillValid()) {
            return null;
        }
        return p.advancements;
    }
}
