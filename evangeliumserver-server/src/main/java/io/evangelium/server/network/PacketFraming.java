package io.evangelium.server.network;

import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;

/**
 * Outgoing packets are written once: the packet encoder leaves {@link #HEADROOM} free bytes in front of every packet, and the
 * compression encoder (below the threshold) and the length prepender write their headers into that room instead of copying the
 * whole packet into a new buffer. Plugins between the encoder and the prepender (PacketEvents, ViaVersion) still get one ByteBuf
 * per packet and may read, change, clear or replace it; a buffer without free room in front (cleared, replaced, sliced, shared)
 * takes the old copying path, or for big packets gets its length header as a separate small write (same bytes on the wire).
 * <p>
 * Frames smaller than packetCoalesceMax are copied (once) into one buffer per channel that goes out on the next flush, a bigger
 * packet, any other write, or close: one outbound entry per flush instead of one per packet. Order and bytes stay the same.
 * <p>
 * Switches: -Devangeliumserver.packetFrameInPlace=false (old copying path everywhere, no coalescing), -Devangeliumserver.packetCoalesce=false, -Devangeliumserver.packetFrameSplitMin=N (bytes
 * from which a packet without room gets a separate header write instead of a copy, 0 = never), -Devangeliumserver.packetFrameVerify=true
 * (every packet is also encoded the old way and compared byte for byte; mismatches are counted and the first ones logged),
 * -Devangeliumserver.packetFrameStats=true (path counts every 60 s).
 */
public final class PacketFraming {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 3 bytes for the frame length (max 2^21-1) + 1 byte for the "not compressed" data length 0. */
    public static final int HEADROOM = 4;
    public static final boolean IN_PLACE = Boolean.parseBoolean(System.getProperty("evangeliumserver.packetFrameInPlace", "true"));
    public static final int SPLIT_MIN = Integer.getInteger("evangeliumserver.packetFrameSplitMin", 4096);
    public static final boolean VERIFY = Boolean.getBoolean("evangeliumserver.packetFrameVerify");
    /** Small frames are gathered into one buffer per channel until the next flush. */
    public static final boolean COALESCE = IN_PLACE && Boolean.parseBoolean(System.getProperty("evangeliumserver.packetCoalesce", "true"));
    /** Frames from this size on are not gathered (in place or split instead). */
    public static final int COALESCE_MAX = Integer.getInteger("evangeliumserver.packetCoalesceMax", 1024);
    public static final int COALESCE_INITIAL = 2048;
    /** A gathered buffer is sent once it would grow past this size. */
    public static final int COALESCE_CAP = Integer.getInteger("evangeliumserver.packetCoalesceCap", 65536);
    public static final boolean STATS = VERIFY || Boolean.getBoolean("evangeliumserver.packetFrameStats");

    public static final LongAdder PREPEND_COALESCED = new LongAdder();
    public static final LongAdder PREPEND_IN_PLACE = new LongAdder();
    public static final LongAdder PREPEND_SPLIT = new LongAdder();
    public static final LongAdder PREPEND_COPY = new LongAdder();
    public static final LongAdder COMPRESS_RAW_IN_PLACE = new LongAdder();
    public static final LongAdder COMPRESS_RAW_COPY = new LongAdder();
    public static final LongAdder COMPRESS_DEFLATE = new LongAdder();
    public static final LongAdder ENCODE_VERIFIED = new LongAdder();
    public static final LongAdder FRAME_VERIFIED = new LongAdder();
    public static final LongAdder MISMATCHES = new LongAdder();
    private static final AtomicInteger LOGGED = new AtomicInteger();

    static {
        if (STATS) {
            var timer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "PacketFraming stats");
                t.setDaemon(true);
                return t;
            });
            timer.scheduleAtFixedRate(() -> LOGGER.info("[PacketFraming] {}", summary()), 60, 60, TimeUnit.SECONDS);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> LOGGER.info("[PacketFraming] final {}", summary())));
        }
    }

    private PacketFraming() {
    }

    public static String summary() {
        return "inPlace=" + IN_PLACE + " coalesce=" + COALESCE + " prepend coalesced/inPlace/split/copy=" + PREPEND_COALESCED.sum() + "/" + PREPEND_IN_PLACE.sum() + "/" + PREPEND_SPLIT.sum() + "/" + PREPEND_COPY.sum()
            + " compress rawInPlace/rawCopy/deflate=" + COMPRESS_RAW_IN_PLACE.sum() + "/" + COMPRESS_RAW_COPY.sum() + "/" + COMPRESS_DEFLATE.sum()
            + " verify encode/frame/mismatches=" + ENCODE_VERIFIED.sum() + "/" + FRAME_VERIFIED.sum() + "/" + MISMATCHES.sum();
    }

    /**
     * True when {@code n} bytes in front of the reader index may be overwritten: the buffer is not derived from another one (a slice or
     * duplicate shares memory with its parent), nobody else holds a reference, and it is writable.
     */
    public static boolean hasOwnHeadroom(ByteBuf buf, int n) {
        return buf.readerIndex() >= n && buf.refCnt() == 1 && buf.unwrap() == null && !buf.isReadOnly() && !(buf instanceof CompositeByteBuf);
    }

    /** Writes {@code value} as a minimal VarInt into the {@code size} bytes in front of the reader index and moves the reader index there. */
    public static void prependVarInt(ByteBuf buf, int value, int size) {
        int start = buf.readerIndex() - size;
        int at = start;
        while ((value & ~0x7F) != 0) {
            buf.setByte(at++, (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.setByte(at, value);
        buf.readerIndex(start);
    }

    public static void compare(String what, ByteBuf expected, ByteBuf actual, Object context) {
        if (ByteBufUtil.equals(expected, actual)) {
            return;
        }
        MISMATCHES.increment();
        if (LOGGED.incrementAndGet() <= 20) {
            int n = Math.min(64, Math.max(expected.readableBytes(), actual.readableBytes()));
            LOGGER.error("[PacketFraming] {} mismatch ({}): expected {} bytes {} actual {} bytes {}", what, context,
                expected.readableBytes(), ByteBufUtil.hexDump(expected, expected.readerIndex(), Math.min(n, expected.readableBytes())),
                actual.readableBytes(), ByteBufUtil.hexDump(actual, actual.readerIndex(), Math.min(n, actual.readableBytes())));
        }
    }
}
