package io.evangelium.server.util;

import java.util.Arrays;

/**
 * Evangelium - the positions (x | z << 4 | y << 8) of the randomly ticking blocks of one chunk section. Replaces Moonrise's
 * ShortList there (a Short2ShortOpenHashMap + two short arrays per section: ~264 MB of short[] at 20k chunks before the lazy map,
 * ~70 MB after it).
 * <p>
 * The section holds one field with the data and one with the size; these static methods work on them:
 * <ul>
 * <li>no ticking block: null (nothing allocated);</li>
 * <li>up to {@link #LIST_MAX} blocks: a short[] list (at most 144 bytes);</li>
 * <li>more: a long[66]: [0..1] the member counts of the eight 512-position blocks (16 bits each; on the array's first cache line),
 *     [2..65] the 4096 bits (544 bytes).</li>
 * </ul>
 * Random ticks draw a uniform r in [0, 4096) and tick the member of rank r when r < size (Paper's rule; the size lives in the section,
 * so the usual miss reads nothing else): every ticking block is picked with chance exactly 1/4096 per try and at most one block per
 * try, the same distribution as Paper's list and vanilla's position sampling.
 * <p>
 * Not thread-safe: same access rule as the section's block states (only the thread that ticks or edits the chunk touches it; the
 * IntraRegion helpers run random ticks of chunks more than 2 apart, and a random tick edits at most one chunk away).
 */
public final class TickingBlockSet {

    /** -Devangeliumserver.tickingBlockSet=false: Moonrise's ShortList as before. */
    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("evangeliumserver.tickingBlockSet"));
    /** Biggest list before the data switches to bits (and back below half of it). 0 = always bits. */
    public static final int LIST_MAX = Math.max(0, Math.min(4096, Integer.getInteger("evangeliumserver.tickingBlockListMax", 64)));
    private static final int LIST_MIN = LIST_MAX / 2;
    private static final int BASE = 2;   // first word of the bits

    private TickingBlockSet() {
    }

    /** Empty data for about {@code expected} positions (bulk load with {@link #addNew}). */
    public static Object withCapacity(final int expected) {
        return expected > LIST_MAX ? new long[BASE + 64] : new short[Math.max(4, Math.min(LIST_MAX, expected))];
    }

    public static boolean isBits(final Object data) {
        return data instanceof long[];
    }

    /**
     * The member of rank r (0 <= r < size): list mode list[r] (Paper's order), bit mode the r-th set bit. Every member has exactly
     * one rank, so a uniform r in [0, 4096) with "r < size, else nothing" picks each member with chance 1/4096. -1 if out of range.
     */
    public static int get(final Object data, final int size, int r) {
        if (r >= size || data == null) {
            return -1;
        }
        if (data instanceof short[] list) {
            return list[r];
        }
        final long[] bits = (long[])data;
        for (int b = 0; b < 8; ++b) {
            final int c = blockCount(bits, b);
            if (r >= c) {
                r -= c;
                continue;
            }
            for (int w = BASE + (b << 3), end = w + 8; w < end; ++w) {
                final long word = bits[w];
                final int wc = Long.bitCount(word);
                if (r < wc) {
                    return ((w - BASE) << 6) | Long.numberOfTrailingZeros(Long.expand(1L << r, word));   // r-th set bit of the word (pdep)
                }
                r -= wc;
            }
            return -1;
        }
        return -1;
    }

    private static int blockCount(final long[] bits, final int block) {
        return (int)(bits[block >>> 2] >>> ((block & 3) << 4)) & 0xFFFF;
    }

    private static void setBit(final long[] bits, final int pos) {
        bits[BASE + (pos >>> 6)] |= 1L << pos;
        final int block = pos >>> 9;
        bits[block >>> 2] += 1L << ((block & 3) << 4);
    }

    private static void clearBit(final long[] bits, final int pos) {
        bits[BASE + (pos >>> 6)] &= ~(1L << pos);
        final int block = pos >>> 9;
        bits[block >>> 2] -= 1L << ((block & 3) << 4);
    }

    public static boolean contains(final Object data, final int size, final int pos) {
        if (data instanceof long[] bits) {
            return (bits[BASE + (pos >>> 6)] & (1L << pos)) != 0L;
        }
        return data != null && indexOf((short[])data, size, (short)pos) >= 0;
    }

    /** Adds a position that is not in the set (check {@link #contains} first); returns the new data, the size grows by one. */
    public static Object addNew(final Object data, final int size, final int pos) {
        if (data instanceof long[] bits) {
            setBit(bits, pos);
            return bits;
        }
        short[] list = (short[])data;
        if (size >= LIST_MAX) {
            final long[] bits = new long[BASE + 64];
            for (int i = 0; i < size; ++i) {
                setBit(bits, list[i] & 0xFFFF);
            }
            setBit(bits, pos);
            return bits;
        }
        if (list == null) {
            list = new short[Math.min(4, Math.max(1, LIST_MAX))];
        } else if (list.length == size) {
            list = Arrays.copyOf(list, Math.min(LIST_MAX, Math.max(4, size * 2)));
        }
        list[size] = (short)pos;
        return list;
    }

    /** Removes a position that is in the set (check {@link #contains} first); returns the new data (null when empty), size shrinks by one. */
    public static Object remove(final Object data, final int size, final int pos) {
        final int newSize = size - 1;
        if (newSize == 0) {
            return null;
        }
        if (data instanceof long[] bits) {
            clearBit(bits, pos);
            if (newSize >= LIST_MIN) {
                return bits;
            }
            final short[] list = new short[Math.max(4, Math.min(LIST_MAX, newSize * 2))];
            int n = 0;
            for (int w = 0; w < 64; ++w) {
                long word = bits[BASE + w];
                while (word != 0L) {
                    list[n++] = (short)((w << 6) | Long.numberOfTrailingZeros(word));
                    word &= word - 1L;
                }
            }
            return list;
        }
        final short[] list = (short[])data;
        final int index = indexOf(list, size, (short)pos);
        list[index] = list[newSize];   // move the last entry into the hole, as Moonrise's ShortList does
        list[newSize] = 0;
        return list;
    }

    /** Calls {@code consumer} for every position (order unspecified). */
    public static void forEach(final Object data, final int size, final java.util.function.IntConsumer consumer) {
        if (data instanceof long[] bits) {
            for (int w = 0; w < 64; ++w) {
                long word = bits[BASE + w];
                while (word != 0L) {
                    consumer.accept((w << 6) | Long.numberOfTrailingZeros(word));
                    word &= word - 1L;
                }
            }
            return;
        }
        if (data != null) {
            final short[] list = (short[])data;
            for (int i = 0; i < size; ++i) {
                consumer.accept(list[i] & 0xFFFF);
            }
        }
    }

    /** Number of members the data really holds (verify mode; bit mode counts bits). */
    public static int count(final Object data, final int size) {
        if (data instanceof long[] bits) {
            int n = 0;
            for (int w = 0; w < 64; ++w) n += Long.bitCount(bits[BASE + w]);
            int blocks = 0;
            for (int b = 0; b < 8; ++b) blocks += blockCount(bits, b);
            return blocks == n ? n : -1 - n;   // block counts out of step: negative
        }
        return data == null ? 0 : size;
    }

    private static int indexOf(final short[] list, final int count, final short pos) {
        for (int i = 0; i < count; ++i) {
            if (list[i] == pos) {
                return i;
            }
        }
        return -1;
    }
}
