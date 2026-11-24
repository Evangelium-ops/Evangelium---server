import io.multipaper.shreddedpaper.ticks.LegacyChunkTicks;
import io.multipaper.shreddedpaper.ticks.PackedTickIO;
import io.multipaper.shreddedpaper.ticks.PackedTicks;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import com.mojang.serialization.Codec;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.SavedTick;

/**
 * Chunk tick IO + memory micro-benchmark over a real world: reads every chunk NBT of a region folder, then
 *  - read: vanilla codec + filter vs PackedTickIO.read (block_ticks + fluid_ticks), checks equal results
 *  - write: vanilla codec store vs PackedTickIO.write
 *  - memory: live heap of one LevelChunkTicks per chunk built from the saved lists, old class (LegacyChunkTicks, = not ticking chunk)
 *    vs packed, measured after full GCs.
 * usage: java -Xmx6G -cp ... TickIOBench <world/region> [rounds]
 */
public class TickIOBench {
    static Codec<List<SavedTick<Block>>> BC; static Codec<List<SavedTick<Block>>> bc() { return SavedTick.codec(BuiltInRegistries.BLOCK.byNameCodec()).listOf(); }
    static Codec<List<SavedTick<Fluid>>> FC; static Codec<List<SavedTick<Fluid>>> fc() { return SavedTick.codec(BuiltInRegistries.FLUID.byNameCodec()).listOf(); }

    public static void main(String[] args) throws Exception {
        TickFuzz.bootstrap();
        BC = bc();
        FC = fc();
        File dir = new File(args[0]);
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        List<CompoundTag> tags = new ArrayList<>();
        List<ChunkPos> poss = new ArrayList<>();
        for (File f : dir.listFiles((d, n) -> n.endsWith(".mca"))) {
            readRegion(f, tags, poss);
        }
        long ticks = 0;
        for (int i = 0; i < tags.size(); i++) {
            ticks += slow(tags.get(i), "block_ticks", BC, poss.get(i)).size() + slow(tags.get(i), "fluid_ticks", FC, poss.get(i)).size();
        }
        System.out.println("chunks=" + tags.size() + " saved ticks=" + ticks);
        // correctness on this world
        for (int i = 0; i < tags.size(); i++) {
            check(tags.get(i), "block_ticks", BC, BuiltInRegistries.BLOCK, poss.get(i));
            check(tags.get(i), "fluid_ticks", FC, BuiltInRegistries.FLUID, poss.get(i));
        }
        System.out.println("fast read == codec read for every chunk, fast write == codec write (tags + bytes)");
        for (int r = 0; r < rounds; r++) {
            long t0 = System.nanoTime();
            long n0 = 0;
            for (int i = 0; i < tags.size(); i++) {
                n0 += slow(tags.get(i), "block_ticks", BC, poss.get(i)).size() + slow(tags.get(i), "fluid_ticks", FC, poss.get(i)).size();
            }
            long t1 = System.nanoTime();
            long n1 = 0;
            List<List<SavedTick<Block>>> bl = new ArrayList<>();
            List<List<SavedTick<Fluid>>> fl = new ArrayList<>();
            for (int i = 0; i < tags.size(); i++) {
                List<SavedTick<Block>> b = PackedTickIO.read(tags.get(i), "block_ticks", BuiltInRegistries.BLOCK, poss.get(i));
                List<SavedTick<Fluid>> f = PackedTickIO.read(tags.get(i), "fluid_ticks", BuiltInRegistries.FLUID, poss.get(i));
                n1 += b.size() + f.size();
                bl.add(b);
                fl.add(f);
            }
            long t2 = System.nanoTime();
            List<List<SavedTick<Block>>> bs = new ArrayList<>();
            List<List<SavedTick<Fluid>>> fs = new ArrayList<>();
            for (int i = 0; i < bl.size(); i++) {
                bs.add(new ArrayList<>(bl.get(i)));
                fs.add(new ArrayList<>(fl.get(i)));
            }
            long t3 = System.nanoTime();
            for (int i = 0; i < bs.size(); i++) {
                CompoundTag t = new CompoundTag();
                t.store("block_ticks", BC, bs.get(i));
                t.store("fluid_ticks", FC, fs.get(i));
            }
            long t4 = System.nanoTime();
            for (int i = 0; i < bl.size(); i++) {
                CompoundTag t = new CompoundTag();
                PackedTickIO.write(t, "block_ticks", BuiltInRegistries.BLOCK, bl.get(i));
                PackedTickIO.write(t, "fluid_ticks", BuiltInRegistries.FLUID, fl.get(i));
            }
            long t5 = System.nanoTime();
            System.out.printf("round %d: read codec %.1f ms, fast %.1f ms (%d/%d ticks) | write codec %.1f ms, fast %.1f ms%n", r,
                (t1 - t0) / 1e6, (t2 - t1) / 1e6, n0, n1, (t4 - t3) / 1e6, (t5 - t4) / 1e6);
        }
        // memory: one container per chunk, as for loaded but not ticking chunks
        for (String mode : new String[] {"old", "packed", "old", "packed"}) {
            List<Object> keep = new ArrayList<>();
            long before = used();
            for (int i = 0; i < tags.size(); i++) {
                if (mode.equals("old")) {
                    List<SavedTick<Block>> b = slow(tags.get(i), "block_ticks", BC, poss.get(i));
                    List<SavedTick<Fluid>> f = slow(tags.get(i), "fluid_ticks", FC, poss.get(i));
                    keep.add(new LegacyChunkTicks<>(b));
                    keep.add(new LegacyChunkTicks<>(f));
                } else {
                    keep.add(new LevelChunkTicks<>(PackedTickIO.read(tags.get(i), "block_ticks", BuiltInRegistries.BLOCK, poss.get(i))));
                    keep.add(new LevelChunkTicks<>(PackedTickIO.read(tags.get(i), "fluid_ticks", BuiltInRegistries.FLUID, poss.get(i))));
                }
            }
            long after = used();
            System.out.printf("memory %s: %.1f MB for %d containers (%.1f bytes per saved tick)%n", mode, (after - before) / 1048576.0,
                keep.size(), (after - before) / (double) ticks);
            keep.clear();
        }
    }

    static long used() throws InterruptedException {
        for (int i = 0; i < 4; i++) {
            System.gc();
            Thread.sleep(200);
        }
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    static <T> List<SavedTick<T>> slow(CompoundTag tag, String key, Codec<List<SavedTick<T>>> codec, ChunkPos pos) {
        return SavedTick.filterTickListForChunk(tag.read(key, codec).orElse(List.of()), pos);
    }

    static <T> void check(CompoundTag tag, String key, Codec<List<SavedTick<T>>> codec, Registry<T> reg, ChunkPos pos) throws Exception {
        List<SavedTick<T>> s = slow(tag, key, codec, pos);
        List<SavedTick<T>> f = PackedTickIO.read(tag, key, reg, pos);
        if (f == null || !PackedTicks.sameSaved(s, f)) throw new AssertionError("read differs " + pos);
        CompoundTag a = new CompoundTag();
        a.store(key, codec, s);
        CompoundTag b = new CompoundTag();
        // through a container, like a chunk save
        List<SavedTick<T>> packed = new LevelChunkTicks<>(f).pack(0);
        if (!PackedTickIO.write(b, key, reg, packed)) throw new AssertionError("write refused");
        if (!a.equals(b) || !java.util.Arrays.equals(TickFuzz.bytes(a), TickFuzz.bytes(b))) throw new AssertionError("write differs " + pos);
    }

    static void readRegion(File f, List<CompoundTag> tags, List<ChunkPos> poss) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            int[] offsets = new int[1024];
            for (int i = 0; i < 1024; i++) offsets[i] = raf.readInt();
            for (int i = 0; i < 1024; i++) {
                int off = offsets[i] >>> 8;
                if (off == 0) continue;
                raf.seek(off * 4096L);
                int len = raf.readInt();
                int type = raf.readByte();
                if ((type & 0x80) != 0 || len <= 1) continue; // external chunk file: skip
                byte[] data = new byte[len - 1];
                raf.readFully(data);
                DataInputStream in = switch (type) {
                    case 1 -> new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data)));
                    case 2 -> new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)));
                    case 3 -> new DataInputStream(new ByteArrayInputStream(data));
                    default -> null;
                };
                if (in == null) continue;
                CompoundTag tag = NbtIo.read(in, NbtAccounter.unlimitedHeap());
                tags.add(tag);
                poss.add(new ChunkPos(tag.getIntOr("xPos", 0), tag.getIntOr("zPos", 0)));
            }
        }
    }
}
