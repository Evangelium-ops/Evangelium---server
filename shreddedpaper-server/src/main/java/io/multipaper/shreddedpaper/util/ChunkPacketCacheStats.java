package io.multipaper.shreddedpaper.util;

import com.mojang.logging.LogUtils;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;

/**
 * Counters for the chunk packet cache (PlayerChunkSender): how often a chunk sent to a player reused the packet built for an earlier
 * player, and why it did not. Off unless -Dshreddedpaper.chunkPacketCacheStats=<seconds>; then a CPC_STATS line with the totals since
 * start is logged every that many seconds. Measuring only: the counters never change what is sent.
 */
public final class ChunkPacketCacheStats {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final boolean ON;
    public static final LongAdder HIT = new LongAdder();
    /** no packet cached for the chunk (never built, expired, or invalidated by a change) */
    public static final LongAdder MISS_EMPTY = new LongAdder();
    /** the cached packet's soft or weak reference was cleared by the garbage collector */
    public static final LongAdder MISS_CLEARED = new LongAdder();
    /** a packet was cached, but with the other anti-xray mode */
    public static final LongAdder MISS_MODIFY = new LongAdder();
    public static final LongAdder BUILD_NANOS = new LongAdder();
    public static final LongAdder EXPIRED = new LongAdder();
    public static final LongAdder INVALIDATED = new LongAdder();
    /** hits by ticks since the chunk's packet was last used: up to 20, 100, 200, 600, more (what a shorter expire-after would lose) */
    private static final long[] GAP_LIMITS = {20, 100, 200, 600};
    private static final LongAdder[] HIT_GAP = {new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder()};

    public static void hit(long gapTicks) {
        HIT.increment();
        int i = 0;
        while (i < GAP_LIMITS.length && gapTicks > GAP_LIMITS[i]) {
            i++;
        }
        HIT_GAP[i].increment();
    }

    static {
        long seconds = Long.getLong("shreddedpaper.chunkPacketCacheStats", 0L);
        ON = seconds > 0;
        if (ON) {
            Thread t = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(seconds * 1000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    long hit = HIT.sum(), empty = MISS_EMPTY.sum(), cleared = MISS_CLEARED.sum(), modify = MISS_MODIFY.sum();
                    long built = empty + cleared + modify;
                    LOGGER.info("CPC_STATS hit={} miss_empty={} miss_cleared={} miss_modify={} built={} build_ms={} expired={} invalidated={} hit_gap_le20/100/200/600/more={}/{}/{}/{}/{}",
                            hit, empty, cleared, modify, built, BUILD_NANOS.sum() / 1_000_000L, EXPIRED.sum(), INVALIDATED.sum(),
                            HIT_GAP[0].sum(), HIT_GAP[1].sum(), HIT_GAP[2].sum(), HIT_GAP[3].sum(), HIT_GAP[4].sum());
                }
            }, "chunk-packet-cache-stats");
            t.setDaemon(true);
            t.start();
        }
    }

    private ChunkPacketCacheStats() {
    }
}
