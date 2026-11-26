package io.evangelium.server.ticks;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.TickPriority;
import org.jspecify.annotations.Nullable;

/**
 * Reads and writes the chunk NBT lists "block_ticks" / "fluid_ticks" straight from/to {@link PackedTickList}, without SavedTick,
 * BlockPos or DataFixerUpper codec objects. The format on disk is unchanged (a list of {i: id, x, y, z, t: delay, p: priority}).
 *
 * Exactness: the fast reader only accepts the clean case (a ListTag of compounds with a known string id and int x/y/z/t/p); anything
 * else returns null and the caller uses the vanilla codec, which then behaves (and logs) exactly as before. The fast writer only
 * handles PackedTickList input whose types are registered; anything else goes through the codec.
 */
public final class PackedTickIO {

    private static final ConcurrentHashMap<Registry<?>, ConcurrentHashMap<String, Object>> BY_NAME = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Object, StringTag> NAMES = new ConcurrentHashMap<>();

    private PackedTickIO() {
    }

    /** The ticks of chunkPos in the list (the vanilla codec + filterTickListForChunk result), or null: use the codec. */
    public static <T> @Nullable List<SavedTick<T>> read(CompoundTag tag, String key, Registry<T> registry, ChunkPos chunkPos) {
        Tag value = tag.get(key);
        if (value == null) {
            return PackedTickList.empty(chunkPos.x, chunkPos.z);
        }
        if (!(value instanceof ListTag list)) {
            return null;
        }
        int n = list.size();
        Object[] types = new Object[4];
        int typeCount = 0;
        long[] words = new long[n];
        int[] delays = new int[n];
        int size = 0;
        ConcurrentHashMap<String, Object> byName = BY_NAME.computeIfAbsent(registry, r -> new ConcurrentHashMap<>());
        for (int i = 0; i < n; i++) {
            if (!(list.get(i) instanceof CompoundTag entry)) {
                return null;
            }
            if (!(entry.get("i") instanceof StringTag(String name))
                || !(entry.get("x") instanceof IntTag(int x))
                || !(entry.get("y") instanceof IntTag(int y))
                || !(entry.get("z") instanceof IntTag(int z))
                || !(entry.get("t") instanceof IntTag(int t))
                || !(entry.get("p") instanceof IntTag(int p))) {
                return null;
            }
            Object type = byName.get(name);
            if (type == null) {
                type = resolve(registry, name);
                if (type == null) {
                    return null;
                }
                byName.put(name, type);
            }
            if ((x >> 4) != chunkPos.x || (z >> 4) != chunkPos.z) {
                continue; // SavedTick.filterTickListForChunk
            }
            int typeIndex = -1;
            for (int j = 0; j < typeCount; j++) {
                if (types[j] == type) {
                    typeIndex = j;
                    break;
                }
            }
            if (typeIndex < 0) {
                if (typeCount == PackedTicks.MAX_TYPES) {
                    return null;
                }
                if (typeCount == types.length) {
                    types = Arrays.copyOf(types, typeCount * 2);
                }
                typeIndex = typeCount++;
                types[typeIndex] = type;
            }
            words[size] = PackedTicks.word(x, y, z, TickPriority.byValue(p).ordinal(), typeIndex);
            delays[size] = t;
            size++;
        }
        return new PackedTickList<>(Arrays.copyOf(types, typeCount), words, delays, size, chunkPos.x, chunkPos.z);
    }

    /** Same lookup as Registry.byNameCodec(): Identifier.read, then registry.get(identifier). */
    private static <T> @Nullable T resolve(Registry<T> registry, String name) {
        Identifier identifier = Identifier.read(name).result().orElse(null);
        if (identifier == null) {
            return null;
        }
        Optional<Holder.Reference<T>> reference = registry.get(identifier);
        return reference.map(Holder.Reference::value).orElse(null);
    }

    /** Writes the list like tag.store(key, SavedTick.codec(registry.byNameCodec()).listOf(), ticks); false: use the codec. */
    public static <T> boolean write(CompoundTag tag, String key, Registry<T> registry, List<SavedTick<T>> ticks) {
        if (!(ticks instanceof PackedTickList<T> packed)) {
            return false;
        }
        int n = packed.size();
        ListTag list = new ListTag();
        int cx = packed.chunkX() << 4;
        int cz = packed.chunkZ() << 4;
        for (int i = 0; i < n; i++) {
            long word = packed.word(i);
            StringTag name = name(registry, packed.type(i));
            if (name == null) {
                return false;
            }
            CompoundTag entry = new CompoundTag();
            // same insertion order as the record codec (i, x, y, z, t, p): identical map layout and bytes on disk
            entry.put("i", name);
            entry.put("x", IntTag.valueOf(cx | PackedTicks.localX(word)));
            entry.put("y", IntTag.valueOf(PackedTicks.y(word)));
            entry.put("z", IntTag.valueOf(cz | PackedTicks.localZ(word)));
            entry.put("t", IntTag.valueOf(packed.delay(i)));
            entry.put("p", IntTag.valueOf(PackedTicks.priority(word).getValue()));
            list.add(entry);
        }
        tag.put(key, list);
        return true;
    }

    private static <T> @Nullable StringTag name(Registry<T> registry, T type) {
        StringTag name = NAMES.get(type);
        if (name == null) {
            Optional<ResourceKey<T>> resourceKey = registry.getResourceKey(type);
            if (resourceKey.isEmpty()) {
                return null;
            }
            name = StringTag.valueOf(resourceKey.get().identifier().toString());
            NAMES.put(type, name);
        }
        return name;
    }
}
