import io.multipaper.shreddedpaper.ticks.LegacyChunkTicks;
import io.multipaper.shreddedpaper.ticks.LegacyLevelTicks;
import io.multipaper.shreddedpaper.ticks.PackedTickList;
import io.multipaper.shreddedpaper.ticks.PackedTicks;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ProtoChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;

/**
 * Differential test: packed LevelChunkTicks / LevelTicks / ProtoChunkTicks against the old object structure (LegacyChunkTicks /
 * LegacyLevelTicks / a reference list+set), random operations, exact comparison after every step (values, order, heap array order).
 * usage: java -cp <server classes + libs>:. TickFuzz [seeds] [steps]
 */
public class TickFuzz {
    static final String[] TYPES = new String[12];
    static {
        for (int i = 0; i < TYPES.length; i++) {
            TYPES[i] = ("type" + i).intern();
        }
    }
    static final TickPriority[] PRI = TickPriority.values();
    static long checks;

    static void fail(String msg) {
        throw new AssertionError(msg);
    }

    static boolean same(ScheduledTick<?> a, ScheduledTick<?> b) {
        return PackedTicks.same(a, b);
    }

    static void eqQueue(LevelChunkTicks<String> n, LegacyChunkTicks<String> o, String where) {
        List<ScheduledTick<String>> a = n.getAll().toList();
        List<ScheduledTick<String>> b = o.getAll().toList();
        if (!PackedTicks.sameScheduled(a, b)) {
            fail(where + " queue order\n new=" + a + "\n old=" + b);
        }
        if (!same(n.peek(), o.peek())) {
            fail(where + " peek new=" + n.peek() + " old=" + o.peek());
        }
        checks++;
    }

    static BlockPos randomPos(Random r, int cx, int cz) {
        return new BlockPos((cx << 4) + r.nextInt(16), r.nextInt(6) - 64 + (r.nextInt(20) == 0 ? r.nextInt(4000) - 2000 : 0), (cz << 4) + r.nextInt(16));
    }

    static List<SavedTick<String>> randomSaved(Random r, int cx, int cz, int n) {
        List<SavedTick<String>> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new SavedTick<>(TYPES[r.nextInt(r.nextBoolean() ? 3 : TYPES.length)], randomPos(r, cx, cz), r.nextInt(12) - 3, PRI[r.nextInt(PRI.length)]));
        }
        return list;
    }

    // ------------------------------------------------------------------ container level
    static void containerRun(long seed, int steps) {
        Random r = new Random(seed);
        int cx = r.nextInt(2000) - 1000, cz = r.nextInt(2000) - 1000;
        LevelChunkTicks<String> n;
        LegacyChunkTicks<String> o;
        if (r.nextBoolean()) {
            List<SavedTick<String>> saved = randomSaved(r, cx, cz, r.nextInt(r.nextBoolean() ? 5 : 300));
            n = new LevelChunkTicks<>(r.nextBoolean() ? saved : asPacked(saved, cx, cz));
            o = new LegacyChunkTicks<>(new ArrayList<>(saved));
        } else {
            n = new LevelChunkTicks<>();
            o = new LegacyChunkTicks<>();
        }
        List<ScheduledTick<String>> addedN = new ArrayList<>();
        List<ScheduledTick<String>> addedO = new ArrayList<>();
        n.setOnTickAdded((c, t) -> addedN.add(t));
        o.setOnTickAdded((c, t) -> { if (t.equals(c.peek())) addedO.add(t); });
        long game = r.nextInt(1000);
        long sub = 0;
        boolean unpacked = false;
        for (int step = 0; step < steps; step++) {
            int op = r.nextInt(100);
            String where = "seed " + seed + " step " + step + " op " + op;
            if (op < 35) {
                BlockPos pos = randomPos(r, cx, cz);
                if (r.nextInt(400) == 0) {
                    pos = randomPos(r, cx + 1, cz); // foreign chunk -> old structure fallback
                }
                long s = r.nextInt(10) == 0 ? sub - r.nextInt(5) : sub++; // duplicates/backwards like the racy counter
                ScheduledTick<String> t = new ScheduledTick<>(TYPES[r.nextInt(r.nextBoolean() ? 3 : TYPES.length)], pos, game + r.nextInt(6), PRI[r.nextInt(PRI.length)], s);
                n.schedule(t);
                o.schedule(t);
            } else if (op < 55) {
                ScheduledTick<String> a = n.poll();
                ScheduledTick<String> b = o.poll();
                if (!same(a, b)) fail(where + " poll new=" + a + " old=" + b);
            } else if (op < 70) {
                BlockPos pos = r.nextInt(30) == 0 ? randomPos(r, cx - 1, cz) : randomPos(r, cx, cz);
                String type = TYPES[r.nextInt(TYPES.length)];
                if (n.hasScheduledTick(pos, type) != o.hasScheduledTick(pos, type)) fail(where + " has " + pos + " " + type);
            } else if (op < 75) {
                if (n.count() != o.count()) fail(where + " count " + n.count() + " " + o.count());
            } else if (op < 80) {
                List<SavedTick<String>> a = n.pack(game);
                List<SavedTick<String>> b = o.pack(game);
                if (!PackedTicks.sameSaved(a, b)) fail(where + " pack\n new=" + a + "\n old=" + b);
                if (a instanceof PackedTickList<String> && r.nextInt(3) == 0) {
                    // reload round trip: both sides start again from the saved list
                    List<SavedTick<String>> copy = new ArrayList<>(b);
                    n = new LevelChunkTicks<>(a);
                    o = new LegacyChunkTicks<>(copy);
                    n.setOnTickAdded((c, t) -> addedN.add(t));
                    o.setOnTickAdded((c, t) -> { if (t.equals(c.peek())) addedO.add(t); });
                    unpacked = false;
                }
            } else if (op < 84) {
                if (!unpacked || r.nextInt(10) == 0) {
                    n.unpack(game);
                    o.unpack(game);
                    unpacked = true;
                }
            } else if (op < 88) {
                long t = game + r.nextInt(3) - 1;
                if (n.moonrise$isDirty(t) != o.moonrise$isDirty(t)) fail(where + " dirty");
                if (r.nextBoolean()) {
                    n.moonrise$clearDirty();
                    o.moonrise$clearDirty();
                }
            } else if (op < 89) {
                int y = r.nextInt(6) - 64;
                n.removeIf(t -> t.pos().getY() == y);
                o.removeIf(t -> t.pos().getY() == y);
            } else {
                game += r.nextInt(3);
            }
            eqQueue(n, o, where);
            if (n.count() != o.count()) fail(where + " count");
            if (!sameList(addedN, addedO)) fail(where + " onTickAdded heads new=" + addedN + " old=" + addedO);
        }
    }

    static boolean sameList(List<ScheduledTick<String>> a, List<ScheduledTick<String>> b) {
        return PackedTicks.sameScheduled(a, b);
    }

    static List<SavedTick<String>> asPacked(List<SavedTick<String>> saved, int cx, int cz) {
        if (!PackedTicks.ENABLED) return saved;
        // build through a ProtoChunkTicks-free path: a LevelChunkTicks from the plain list packs it
        LevelChunkTicks<String> tmp = new LevelChunkTicks<>(saved);
        List<SavedTick<String>> list = tmp.pack(0);
        if (!(list instanceof PackedTickList<String> p)) throw new AssertionError("not packed");
        if (!PackedTicks.sameSaved(p, saved)) throw new AssertionError("pack of fresh list differs");
        return p;
    }

    // ------------------------------------------------------------------ level (region) level
    static void levelRun(long seed, int ticks) {
        Random r = new Random(seed);
        int chunks = 1 + r.nextInt(9);
        long tickSeed = r.nextLong();
        LevelTicks<String> nl = new LevelTicks<>(pos -> Math.floorMod((int) (pos * 31 + tickSeed), 7) != 0 || r.nextInt(1) == 0);
        LegacyLevelTicks<String> ol = new LegacyLevelTicks<>(pos -> Math.floorMod((int) (pos * 31 + tickSeed), 7) != 0 || r.nextInt(1) == 0);
        List<LevelChunkTicks<String>> ns = new ArrayList<>();
        List<LegacyChunkTicks<String>> os = new ArrayList<>();
        List<ChunkPos> cps = new ArrayList<>();
        long game = 100;
        for (int i = 0; i < chunks; i++) {
            ChunkPos cp = new ChunkPos(i % 3, i / 3);
            cps.add(cp);
            List<SavedTick<String>> saved = randomSaved(r, cp.x, cp.z, r.nextInt(200));
            LevelChunkTicks<String> n = new LevelChunkTicks<>(saved);
            LegacyChunkTicks<String> o = new LegacyChunkTicks<>(new ArrayList<>(saved));
            nl.addContainer(cp, n);
            ol.addContainer(cp, o);
            n.unpack(game);
            o.unpack(game);
            ns.add(n);
            os.add(o);
        }
        long[] sub = {0};
        int max = 1 + r.nextInt(r.nextBoolean() ? 20 : 400);
        for (int t = 0; t < ticks; t++) {
            List<String> firedN = new ArrayList<>();
            List<String> firedO = new ArrayList<>();
            long g = game;
            Random tickRandomN = new Random(seed * 31 + t);
            Random tickRandomO = new Random(seed * 31 + t);
            long subStart = sub[0];
            // new side runs the ticker and schedules follow-up ticks; the old side collects only and replays the same schedules
            List<ScheduledTick<String>> scheduled = new ArrayList<>();
            List<ScheduledTick<String>> expected = ol.collectOnly(g, max);
            nl.tick(g, max, (pos, type) -> {
                firedN.add(pos + type);
                if (nl.hasScheduledTick(pos, type) != hasOld(ol, os, cps, pos, type)) fail("has mismatch during run");
                int k = tickRandomN.nextInt(4);
                for (int i = 0; i < k; i++) {
                    BlockPos p = pos.offset(tickRandomN.nextInt(5) - 2, tickRandomN.nextInt(3) - 1, tickRandomN.nextInt(5) - 2);
                    int ci = indexOf(cps, p);
                    if (ci < 0) continue;
                    ScheduledTick<String> st = new ScheduledTick<>(TYPES[tickRandomN.nextInt(4)], p, g + tickRandomN.nextInt(4), PRI[tickRandomN.nextInt(PRI.length)], sub[0]++);
                    scheduled.add(st);
                    nl.schedule(st);
                    ol.schedule(st);
                }
            });
            for (ScheduledTick<String> e : expected) firedO.add(e.pos() + e.type());
            if (!firedN.equals(firedO)) fail("seed " + seed + " tick " + t + " fired\n new=" + firedN + "\n old=" + firedO);
            checks++;
            // external schedules (players / neighbours) between ticks
            int ext = r.nextInt(5);
            for (int i = 0; i < ext; i++) {
                int ci = r.nextInt(chunks);
                ScheduledTick<String> st = new ScheduledTick<>(TYPES[r.nextInt(4)], randomPos(r, cps.get(ci).x, cps.get(ci).z), game + r.nextInt(5), PRI[r.nextInt(PRI.length)], sub[0]++);
                nl.schedule(st);
                ol.schedule(st);
            }
            for (int i = 0; i < chunks; i++) {
                eqQueue(ns.get(i), os.get(i), "seed " + seed + " tick " + t + " chunk " + i);
                if (r.nextInt(50) == 0) {
                    // save + reload a chunk
                    List<SavedTick<String>> a = ns.get(i).pack(game);
                    List<SavedTick<String>> b = os.get(i).pack(game);
                    if (!PackedTicks.sameSaved(a, b)) fail("pack in level run");
                    nl.removeContainer(cps.get(i));
                    ol.removeContainer(cps.get(i));
                    LevelChunkTicks<String> n = new LevelChunkTicks<>(a);
                    LegacyChunkTicks<String> o = new LegacyChunkTicks<>(new ArrayList<>(b));
                    nl.addContainer(cps.get(i), n);
                    ol.addContainer(cps.get(i), o);
                    n.unpack(game);
                    o.unpack(game);
                    ns.set(i, n);
                    os.set(i, o);
                }
            }
            game++;
        }
    }

    static boolean hasOld(LegacyLevelTicks<String> ol, List<LegacyChunkTicks<String>> os, List<ChunkPos> cps, BlockPos pos, String type) {
        return ol.hasScheduledTick(pos, type);
    }

    static int indexOf(List<ChunkPos> cps, BlockPos p) {
        for (int i = 0; i < cps.size(); i++) {
            if (cps.get(i).x == (p.getX() >> 4) && cps.get(i).z == (p.getZ() >> 4)) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ proto
    static void protoRun(long seed, int steps) {
        Random r = new Random(seed);
        int cx = r.nextInt(100), cz = -r.nextInt(100);
        List<SavedTick<String>> init = randomSaved(r, cx, cz, r.nextInt(100));
        ProtoChunkTicks<String> p = ProtoChunkTicks.load(r.nextBoolean() ? init : asPacked(init, cx, cz));
        List<SavedTick<String>> refList = new ArrayList<>();
        Set<SavedTick<?>> refSet = new it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet<>(SavedTick.UNIQUE_TICK_HASH);
        for (SavedTick<String> s : init) if (refSet.add(s)) refList.add(s);
        for (int step = 0; step < steps; step++) {
            int op = r.nextInt(10);
            if (op < 5) {
                BlockPos pos = r.nextInt(300) == 0 ? randomPos(r, cx, cz + 1) : randomPos(r, cx, cz);
                ScheduledTick<String> t = new ScheduledTick<>(TYPES[r.nextInt(TYPES.length)], pos, 0, PRI[r.nextInt(PRI.length)], 0);
                p.schedule(t);
                SavedTick<String> s = new SavedTick<>(t.type(), t.pos(), 0, t.priority());
                if (refSet.add(s)) refList.add(s);
            } else if (op < 8) {
                BlockPos pos = randomPos(r, cx, cz);
                String type = TYPES[r.nextInt(TYPES.length)];
                if (p.hasScheduledTick(pos, type) != refSet.contains(SavedTick.probe(type, pos))) fail("proto has seed " + seed);
            } else {
                if (!PackedTicks.sameSaved(p.pack(0), refList)) fail("proto pack seed " + seed);
                if (!PackedTicks.sameSaved(p.scheduledTicks(), refList)) fail("proto scheduledTicks seed " + seed);
                if (r.nextInt(4) == 0) {
                    // proto -> level chunk ticks, like ProtoChunk.unpackTicks
                    LevelChunkTicks<String> n = new LevelChunkTicks<>(p.scheduledTicks());
                    LegacyChunkTicks<String> o = new LegacyChunkTicks<>(List.copyOf(refList));
                    n.unpack(5);
                    o.unpack(5);
                    eqQueue(n, o, "proto->level seed " + seed);
                }
            }
            if (p.count() != refList.size()) fail("proto count seed " + seed);
            checks++;
        }
    }

    // ------------------------------------------------------------------ chunk NBT fast path vs codec
    static void ioRun(long seed) throws Exception {
        Random r = new Random(seed);
        var reg = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        var codec = SavedTick.codec(reg.byNameCodec()).listOf();
        int cx = r.nextInt(4000) - 2000, cz = r.nextInt(4000) - 2000;
        List<SavedTick<net.minecraft.world.level.block.Block>> ticks = new ArrayList<>();
        int n = r.nextInt(r.nextBoolean() ? 3 : 200);
        for (int i = 0; i < n; i++) {
            ticks.add(new SavedTick<>(reg.byId(r.nextInt(reg.size())), randomPos(r, cx, cz), r.nextInt(2000) - 1000, PRI[r.nextInt(PRI.length)]));
        }
        // packed list built through a container
        LevelChunkTicks<net.minecraft.world.level.block.Block> c = new LevelChunkTicks<>(ticks);
        List<SavedTick<net.minecraft.world.level.block.Block>> packed = c.pack(0);
        if (!(packed instanceof PackedTickList)) fail("not packed");
        net.minecraft.nbt.CompoundTag fast = new net.minecraft.nbt.CompoundTag();
        if (!io.multipaper.shreddedpaper.ticks.PackedTickIO.write(fast, "block_ticks", reg, packed)) fail("fast write refused");
        net.minecraft.nbt.CompoundTag slow = new net.minecraft.nbt.CompoundTag();
        slow.store("block_ticks", codec, ticks);
        if (!fast.equals(slow)) fail("io write tag differs seed " + seed);
        if (!java.util.Arrays.equals(bytes(fast), bytes(slow))) fail("io write bytes differ seed " + seed + "\n" + fast + "\n" + slow);
        // read back: fast vs codec, also with foreign-chunk entries mixed in and broken entries
        net.minecraft.nbt.ListTag list = (net.minecraft.nbt.ListTag) slow.get("block_ticks");
        int mode = r.nextInt(6);
        if (mode == 1 && !list.isEmpty()) { // foreign chunk entry (filtered)
            net.minecraft.nbt.CompoundTag e = ((net.minecraft.nbt.CompoundTag) list.get(0)).copy();
            e.putInt("x", e.getIntOr("x", 0) + 16);
            list.add(e);
        } else if (mode == 2 && !list.isEmpty()) { // unknown id -> codec
            ((net.minecraft.nbt.CompoundTag) list.get(r.nextInt(list.size()))).putString("i", "minecraft:no_such_block");
        } else if (mode == 3 && !list.isEmpty()) { // short instead of int -> codec
            ((net.minecraft.nbt.CompoundTag) list.get(r.nextInt(list.size()))).putShort("t", (short) 5);
        } else if (mode == 4) {
            slow.remove("block_ticks");
        } else if (mode == 5 && !list.isEmpty()) { // priority out of range (clamped by byValue)
            ((net.minecraft.nbt.CompoundTag) list.get(r.nextInt(list.size()))).putInt("p", 9);
        }
        ChunkPos cp = new ChunkPos(cx, cz);
        List<SavedTick<net.minecraft.world.level.block.Block>> f = io.multipaper.shreddedpaper.ticks.PackedTickIO.read(slow, "block_ticks", reg, cp);
        List<SavedTick<net.minecraft.world.level.block.Block>> v = SavedTick.filterTickListForChunk(slow.readQuiet("block_ticks", codec).orElse(List.of()), cp);
        if (f == null) {
            if (mode != 2 && mode != 3) fail("fast read refused clean input seed " + seed + " mode " + mode);
        } else if (!PackedTicks.sameSaved(f, v)) {
            fail("io read differs seed " + seed + " mode " + mode + "\n" + f + "\n" + v);
        }
        checks++;
    }

    static byte[] bytes(net.minecraft.nbt.CompoundTag t) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.write(t, new java.io.DataOutputStream(b));
        return b.toByteArray();
    }

    static void fill(Object o) throws Exception {
        for (java.lang.reflect.Field f : o.getClass().getFields()) {
            Class<?> t = f.getType();
            if (t.getEnclosingClass() == o.getClass() && !java.lang.reflect.Modifier.isStatic(t.getModifiers()) && f.get(o) == null) {
                java.lang.reflect.Constructor<?> k = t.getDeclaredConstructor(o.getClass());
                k.setAccessible(true);
                Object v = k.newInstance(o);
                f.set(o, v);
                fill(v);
            }
        }
    }

    static void bootstrap() {
        try { // ShreddedPaper's config is read in static init of some registries
            java.lang.reflect.Method m = io.multipaper.shreddedpaper.config.ShreddedPaperConfiguration.class.getDeclaredMethod("set", io.multipaper.shreddedpaper.config.ShreddedPaperConfiguration.class);
            m.setAccessible(true);
            java.lang.reflect.Constructor<?> c = io.multipaper.shreddedpaper.config.ShreddedPaperConfiguration.class.getDeclaredConstructor();
            c.setAccessible(true);
            Object cfg = c.newInstance();
            fill(cfg);
            m.invoke(null, cfg);
        } catch (Exception e) { throw new RuntimeException(e); }
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    public static void main(String[] args) throws Exception {
        int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        int steps = args.length > 1 ? Integer.parseInt(args[1]) : 2000;
        bootstrap();
        System.out.println("ENABLED=" + PackedTicks.ENABLED + " VERIFY=" + PackedTicks.VERIFY);
        long start = System.nanoTime();
        int s0 = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        for (int s = s0; s < s0 + seeds; s++) {
            try {
            containerRun(s, steps);
            protoRun(s, steps / 4);
            levelRun(s, steps / 10);
            if (PackedTicks.ENABLED) ioRun(s);
            } catch (Throwable t) {
                try (java.io.PrintStream ps = new java.io.PrintStream(new java.io.FileOutputStream("fuzz-fail.txt", true))) {
                    ps.println("FAIL seed " + s);
                    t.printStackTrace(ps);
                }
                System.out.println("FAIL seed " + s + " " + String.valueOf(t).substring(0, Math.min(400, String.valueOf(t).length())));
                Runtime.getRuntime().halt(1);
            }
        }
        try (java.io.PrintStream ps = new java.io.PrintStream(new java.io.FileOutputStream("fuzz-ok.txt", true))) {
            ps.println("OK seeds " + s0 + ".." + (s0 + seeds - 1) + " steps=" + steps + " checks=" + checks);
        }
        System.out.println("OK seeds=" + seeds + " steps=" + steps + " checks=" + checks + " in " + (System.nanoTime() - start) / 1_000_000 + " ms");
    }
}
