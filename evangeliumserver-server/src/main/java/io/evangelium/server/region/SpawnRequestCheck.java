package io.evangelium.server.region;

import ca.spottedleaf.moonrise.common.list.ReferenceList;
import ca.spottedleaf.moonrise.common.misc.NearbyPlayers;
import ca.spottedleaf.moonrise.patches.chunk_tick_iteration.ChunkTickConstants;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * Evangelium - test hook -Devangeliumserver.verifySpawnRequests=<seconds>: every so many seconds the per-chunk player ticking request counts of
 * the regions (natural spawning needs one) are compared with the players NearbyPlayers has in each chunk's spawn range. Updates run on other
 * threads, so a difference is only reported when it is still the same at the next check.
 */
public final class SpawnRequestCheck {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    public static final int SECONDS = Integer.getInteger("evangeliumserver.verifySpawnRequests", 0);
    private static final java.util.Map<String, Set<String>> LAST = new java.util.concurrent.ConcurrentHashMap<>();

    private SpawnRequestCheck() {
    }

    public static void maybeCheck(ServerLevel level) {
        if (SECONDS <= 0 || level.getGameTime() % (SECONDS * 20L) != 0) {
            return;
        }
        NearbyPlayers nearby = level.moonrise$getNearbyPlayers();
        Set<String> diffs = new HashSet<>();
        LongOpenHashSet seen = new LongOpenHashSet();
        int[] checked = {0};
        level.chunkSource.tickingRegions.forEach(region -> {
            Long2IntOpenHashMap counts = region.copyPlayerTickingRequests();
            for (Long2IntOpenHashMap.Entry e : counts.long2IntEntrySet()) {
                long key = e.getLongKey();
                seen.add(key);
                checked[0]++;
                ReferenceList<ServerPlayer> list = nearby.getPlayersByChunk(ChunkPos.getX(key), ChunkPos.getZ(key), NearbyPlayers.NearbyMapType.SPAWN_RANGE);
                int expected = list == null ? 0 : list.size();
                if (expected != e.getIntValue()) {
                    diffs.add(level.dimension() + " " + new ChunkPos(key) + " count=" + e.getIntValue() + " players=" + expected);
                }
            }
        });
        int r = ChunkTickConstants.PLAYER_SPAWN_TRACK_RANGE;
        for (ServerPlayer player : level.players()) {
            ChunkPos c = player.chunkPosition();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    long key = ChunkPos.asLong(c.x + dx, c.z + dz);
                    if (seen.contains(key)) {
                        continue;
                    }
                    ReferenceList<ServerPlayer> list = nearby.getPlayersByChunk(c.x + dx, c.z + dz, NearbyPlayers.NearbyMapType.SPAWN_RANGE);
                    if (list != null && list.size() > 0) {
                        diffs.add(level.dimension() + " " + new ChunkPos(key) + " count=0 players=" + list.size());
                    }
                }
            }
        }
        Set<String> persistent = new HashSet<>(diffs);
        persistent.retainAll(LAST.getOrDefault(level.dimension().toString(), Set.of()));
        LAST.put(level.dimension().toString(), diffs);
        for (String d : persistent) {
            LOGGER.warn("SPAWN_REQUESTS_DIFF {}", d);
        }
        LOGGER.info("SPAWN_REQUESTS_CHECK {} chunks={} diffs={}", level.dimension(), checked[0], persistent.size());
    }
}
