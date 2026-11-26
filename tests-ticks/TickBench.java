import io.evangelium.server.ticks.LegacyChunkTicks;
import io.evangelium.server.ticks.LegacyLevelTicks;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;

/**
 * Region tick-scheduler micro-benchmark, packed LevelTicks vs the old LevelTicks/LevelChunkTicks (same JVM, alternating):
 *  clock: 64 chunks x N ticking "clocks" (each fired tick schedules itself again 1-4 ticks later, like observers/repeaters)
 *  unpack: chunks with 300 saved worldgen ticks each start ticking and drain (like a player walking into new chunks)
 * usage: java -cp ... TickBench [clocksPerChunk] [rounds]
 */
public class TickBench {
    static final String[] TYPES = {"observer", "repeater", "water", "lava", "comparator", "sand"};

    public static void main(String[] args) throws Exception {
        TickFuzz.bootstrap();
        int clocks = args.length > 0 ? Integer.parseInt(args[0]) : 400;
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 6;
        for (int r = 0; r < rounds; r++) {
            for (boolean packed : new boolean[] {r % 2 == 0, r % 2 != 0}) {
                long[] res = clock(packed, clocks);
                long[] un = unpack(packed);
                System.out.printf("round %d %-6s clock: %.1f ns/fired tick (%d fired) | unpack+drain: %.1f ns/tick (%d)%n", r, packed ? "packed" : "old",
                    res[0] / (double) res[1], res[1], un[0] / (double) un[1], un[1]);
            }
        }
    }

    interface Sched {
        void schedule(ScheduledTick<String> t);
        void tick(long g, java.util.function.BiConsumer<BlockPos, String> ticker);
    }

    static Sched make(boolean packed, List<ChunkPos> chunks, List<List<SavedTick<String>>> saved, long g) {
        if (packed) {
            LevelTicks<String> lt = new LevelTicks<>(p -> true);
            for (int i = 0; i < chunks.size(); i++) {
                LevelChunkTicks<String> c = saved == null ? new LevelChunkTicks<>() : new LevelChunkTicks<>(saved.get(i));
                lt.addContainer(chunks.get(i), c);
                c.unpack(g);
            }
            return new Sched() {
                public void schedule(ScheduledTick<String> t) { lt.schedule(t); }
                public void tick(long gt, java.util.function.BiConsumer<BlockPos, String> ticker) { lt.tick(gt, 65536, ticker); }
            };
        }
        LegacyLevelTicks<String> lt = new LegacyLevelTicks<>(p -> true);
        for (int i = 0; i < chunks.size(); i++) {
            LegacyChunkTicks<String> c = saved == null ? new LegacyChunkTicks<>() : new LegacyChunkTicks<>(saved.get(i));
            lt.addContainer(chunks.get(i), c);
            c.unpack(g);
        }
        return new Sched() {
            public void schedule(ScheduledTick<String> t) { lt.schedule(t); }
            public void tick(long gt, java.util.function.BiConsumer<BlockPos, String> ticker) { lt.tick(gt, 65536, ticker); }
        };
    }

    static long[] clock(boolean packed, int perChunk) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (int i = 0; i < 64; i++) chunks.add(new ChunkPos(i % 8, i / 8));
        Sched s = make(packed, chunks, null, 0);
        Random r = new Random(1);
        long[] sub = {0};
        long[] g = {0};
        for (ChunkPos cp : chunks) {
            for (int k = 0; k < perChunk; k++) {
                BlockPos p = new BlockPos((cp.x << 4) + r.nextInt(16), r.nextInt(64), (cp.z << 4) + r.nextInt(16));
                s.schedule(new ScheduledTick<>(TYPES[k % 2], p, 1 + r.nextInt(4), TickPriority.NORMAL, sub[0]++));
            }
        }
        long fired = 0;
        long[] f = {0};
        long t0 = System.nanoTime();
        for (int t = 1; t <= 400; t++) {
            g[0] = t;
            s.tick(t, (pos, type) -> {
                f[0]++;
                s.schedule(new ScheduledTick<>(type, pos, g[0] + 1 + (pos.getY() & 3), TickPriority.NORMAL, sub[0]++));
            });
        }
        long dt = System.nanoTime() - t0;
        return new long[] {dt, f[0]};
    }

    static long[] unpack(boolean packed) {
        List<ChunkPos> chunks = new ArrayList<>();
        List<List<SavedTick<String>>> saved = new ArrayList<>();
        Random r = new Random(2);
        int n = 0;
        for (int i = 0; i < 400; i++) {
            ChunkPos cp = new ChunkPos(i % 20, i / 20);
            chunks.add(cp);
            List<SavedTick<String>> l = new ArrayList<>();
            for (int k = 0; k < 300; k++) {
                l.add(new SavedTick<>(TYPES[2 + (k & 1)], new BlockPos((cp.x << 4) + r.nextInt(16), r.nextInt(60), (cp.z << 4) + r.nextInt(16)), 0, TickPriority.NORMAL));
            }
            n += l.size();
            saved.add(packed ? new LevelChunkTicks<>(l).pack(0) : l);
        }
        long t0 = System.nanoTime();
        Sched s = make(packed, chunks, saved, 10);
        long[] f = {0};
        for (int t = 10; t < 13; t++) s.tick(t, (pos, type) -> f[0]++);
        return new long[] {System.nanoTime() - t0, f[0]};
    }
}
