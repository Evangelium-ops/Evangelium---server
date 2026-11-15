package io.multipaper.shreddedpaper.util;

import com.mojang.serialization.DataResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.LongStream;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Reads and writes a chunk section's block_states tag without going through the DataFixerUpper codecs (BlockState.CODEC inside
 * PalettedContainer's RecordCodecBuilder), with the same results: the same palette list, the same data array and the same
 * PalettedContainer.unpack/pack calls. Anything the codec would treat specially (unknown block or property value, wrong tag types,
 * an unpack error) returns null and the caller uses the codec as before, so errors and partial results stay vanilla.
 */
public final class FastPaletteCodec {

    private static final ConcurrentHashMap<String, Block> BLOCKS_BY_NAME = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockState, CompoundTag> ENCODED = new ConcurrentHashMap<>();

    private FastPaletteCodec() {
    }

    public static PalettedContainer<BlockState> readBlockStates(CompoundTag tag, Strategy<BlockState> strategy, BlockState[] presetValues) {
        if (!(tag.get("palette") instanceof ListTag paletteTag) || paletteTag.isEmpty()) {
            return null;
        }
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        for (int i = 0, n = paletteTag.size(); i < n; i++) {
            if (!(paletteTag.get(i) instanceof CompoundTag entry)) {
                return null;
            }
            BlockState state = decode(entry);
            if (state == null) {
                return null;
            }
            palette.add(state);
        }
        Tag data = tag.get("data");
        Optional<LongStream> storage;
        if (data == null) {
            storage = Optional.empty();
        } else if (data instanceof LongArrayTag longs) {
            storage = Optional.of(LongStream.of(longs.getAsLongArray()));
        } else {
            return null;
        }
        DataResult<PalettedContainer<BlockState>> result = PalettedContainer.unpack(strategy, new PalettedContainerRO.PackedData<>(palette, storage),
            Blocks.AIR.defaultBlockState(), presetValues);
        return result.result().orElse(null);
    }

    private static BlockState decode(CompoundTag entry) {
        if (!(entry.get("Name") instanceof StringTag(String name))) {
            return null;
        }
        Block block = BLOCKS_BY_NAME.get(name);
        if (block == null) {
            Identifier id = Identifier.tryParse(name);
            Optional<Holder.Reference<Block>> holder = id == null ? Optional.empty() : BuiltInRegistries.BLOCK.get(id);
            if (holder.isEmpty()) {
                return null;
            }
            block = holder.get().value();
            BLOCKS_BY_NAME.put(name, block);
        }
        BlockState state = block.defaultBlockState();
        if (state.getValues().isEmpty()) {
            return state;   // the codec reads nothing more for a block without properties
        }
        Tag properties = entry.get("Properties");
        if (properties == null) {
            return state;
        }
        if (!(properties instanceof CompoundTag compound)) {
            return null;
        }
        // missing properties keep the default state's value and unknown keys are ignored, like the codec
        for (Property<?> property : state.getProperties()) {
            Tag value = compound.get(property.getName());
            if (value == null) {
                continue;
            }
            if (!(value instanceof StringTag(String string))) {
                return null;
            }
            state = withValue(state, property, string);
            if (state == null) {
                return null;
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String string) {
        Optional<T> value = property.getValue(string);
        return value.isPresent() ? state.setValue(property, value.get()) : null;
    }

    public static CompoundTag writeBlockStates(PalettedContainer<BlockState> container, Strategy<BlockState> strategy) {
        PalettedContainerRO.PackedData<BlockState> packed = container.pack(strategy);
        List<BlockState> entries = packed.paletteEntries();
        ListTag palette = new ListTag();
        for (int i = 0, n = entries.size(); i < n; i++) {
            palette.add(encode(entries.get(i)).copy());
        }
        CompoundTag tag = new CompoundTag();
        tag.put("palette", palette);
        packed.storage().ifPresent(longs -> tag.put("data", new LongArrayTag(longs.toArray())));
        return tag;
    }

    // the template is never handed out, only copies of it
    private static CompoundTag encode(BlockState state) {
        CompoundTag tag = ENCODED.get(state);
        if (tag == null) {
            tag = new CompoundTag();
            tag.putString("Name", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            if (!state.getValues().isEmpty()) {
                CompoundTag properties = new CompoundTag();
                for (Property<?> property : state.getProperties()) {
                    properties.putString(property.getName(), name(state, property));
                }
                tag.put("Properties", properties);
            }
            ENCODED.put(state, tag);
        }
        return tag;
    }

    private static <T extends Comparable<T>> String name(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
