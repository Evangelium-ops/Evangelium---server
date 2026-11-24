package io.multipaper.shreddedpaper.ticks;

import java.util.AbstractList;
import java.util.RandomAccess;
import net.minecraft.world.ticks.SavedTick;

/**
 * An immutable list of saved ticks of one chunk in packed form (see {@link PackedTicks} for the word layout). It is a real
 * List&lt;SavedTick&gt;: get(i) builds the SavedTick on demand, so code that does not know the packed form keeps working. The chunk
 * containers adopt the arrays directly, and the chunk NBT reader/writer reads and writes them without building objects.
 *
 * The arrays are never written after construction (they may be longer than size).
 */
public final class PackedTickList<T> extends AbstractList<SavedTick<T>> implements RandomAccess {

    private static final Object[] NO_TYPES = new Object[0];
    private static final long[] NO_WORDS = new long[0];
    private static final int[] NO_DELAYS = new int[0];

    final Object[] types; // type table; only indices used by the words are read
    final long[] words;
    final int[] delays;
    final int size;
    final int chunkX;
    final int chunkZ;

    public PackedTickList(Object[] types, long[] words, int[] delays, int size, int chunkX, int chunkZ) {
        this.types = types;
        this.words = words;
        this.delays = delays;
        this.size = size;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public static <T> PackedTickList<T> empty(int chunkX, int chunkZ) {
        return new PackedTickList<>(NO_TYPES, NO_WORDS, NO_DELAYS, 0, chunkX, chunkZ);
    }

    @Override
    @SuppressWarnings("unchecked")
    public SavedTick<T> get(int index) {
        if (index < 0 || index >= this.size) {
            throw new IndexOutOfBoundsException(index);
        }
        long word = this.words[index];
        return new SavedTick<>((T) this.types[PackedTicks.typeIndex(word)], PackedTicks.pos(word, this.chunkX, this.chunkZ), this.delays[index], PackedTicks.priority(word));
    }

    @Override
    public int size() {
        return this.size;
    }

    public long word(int index) {
        return this.words[index];
    }

    public int delay(int index) {
        return this.delays[index];
    }

    @SuppressWarnings("unchecked")
    public T type(int index) {
        return (T) this.types[PackedTicks.typeIndex(this.words[index])];
    }

    public int chunkX() {
        return this.chunkX;
    }

    public int chunkZ() {
        return this.chunkZ;
    }

    public Object[] types() {
        return this.types;
    }
}
