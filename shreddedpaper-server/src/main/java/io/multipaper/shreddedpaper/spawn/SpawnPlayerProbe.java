package io.multipaper.shreddedpaper.spawn;

import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Evangelium: the "distance to the nearest player" of every step of one natural spawn attempt, from one player search per attempt.
 *
 * <p>A natural spawn attempt (NaturalSpawner.spawnCategoryForPosition) walks up to 3 groups of up to 4 random steps around its start
 * position, all at the start's height and at most {@link #STEP_REACH} blocks from it on each horizontal axis, and asks for the nearest
 * player (no distance bound) at every step. With many players that search was half of all spawning work. Only the distance is used.
 *
 * <p>The distance is only compared with fixed limits (24 blocks, the categories' despawn distances, 128 in PatrollingMonster), the largest
 * being {@link #FAR}. Every step point is within r = 20 * sqrt(2) of the start, so one bounded search collects every player within
 * FAR + r (+1 block margin) of the start; their positions are copied once and every step takes the minimum over the copy, with the same
 * formula as Entity.distanceToSqr. A step with a player within FAR gets vanilla's value bit for bit (that player and every nearer one
 * are in the copy); a step with nobody within FAR gets a value above FAR^2, which every test treats the same as the exact one. (Players
 * are not moved by spawning; vanilla, single threaded, also sees every player standing still during a spawn attempt.) Nobody eligible
 * in the world = vanilla's search finds nobody at every step = the attempt does nothing.
 *
 * <p>-Dshreddedpaper.spawnProbe=false: vanilla search at every step. -Dshreddedpaper.verifySpawnProbe=true: every step also runs the
 * vanilla search and differences are counted and logged.
 */
public final class SpawnPlayerProbe {

    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("shreddedpaper.spawnProbe", "true"));
    public static final boolean VERIFY = Boolean.getBoolean("shreddedpaper.verifySpawnProbe");
    private static final java.util.concurrent.atomic.AtomicLong VERIFIED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong MISMATCHES = new java.util.concurrent.atomic.AtomicLong();
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getClassLogger();

    /** Largest horizontal offset of a step from the start point on one axis: 4 steps of at most 5 blocks. */
    public static final int STEP_REACH = 20;
    private static final double REACH = STEP_REACH * Math.sqrt(2.0);

    private static final ThreadLocal<SpawnPlayerProbe> LOCAL = ThreadLocal.withInitial(SpawnPlayerProbe::new);

    /** This thread's probe, or a fresh one if it is in use (a spawn started from inside a spawn, e.g. by a plugin event). */
    public static SpawnPlayerProbe get() {
        final SpawnPlayerProbe probe = LOCAL.get();
        return probe.level == null ? probe : new SpawnPlayerProbe();
    }

    private double[] px = new double[8], py = new double[8], pz = new double[8];
    private int n;
    private final java.util.function.Consumer<Player> adder = this::add;
    private ServerLevel level;
    private Predicate<Entity> predicate;

    /**
     * The largest distance any spawn rule compares the nearest-player distance with: 24 blocks (too close), each category's despawn
     * distance (64 or 128), PatrollingMonster.removeWhenFarAway (128). Farther than this from every player, all those tests give the
     * same answer whatever the exact distance is.
     */
    static final double FAR = Math.max(128.0, java.util.Arrays.stream(net.minecraft.world.entity.MobCategory.values()).mapToInt(net.minecraft.world.entity.MobCategory::getDespawnDistance).max().orElse(128));
    static final double FAR_SQR = FAR * FAR;
    /** Test hook for the verify mode: a too small search reach (wrong distances for steps whose nearest player is 40..128 away). */
    private static final boolean PLANT = Boolean.getBoolean("shreddedpaper.spawnProbePlant");

    /** Collect the candidates for an attempt starting at (x, y, z) (block centre). False: no eligible player in the level at all. */
    public boolean begin(ServerLevel level, double x, double y, double z, Predicate<Entity> predicate) {
        this.level = level;
        this.predicate = predicate;
        this.n = 0;
        // every player within FAR of some step point (steps are within REACH of the start); others only matter as "somebody exists"
        level.knowledgePlayerIndex.forPlayersWithin(x, y, z, PLANT ? 40.0 : FAR + REACH + 1.0, predicate, this.adder);
        if (this.n == 0) {
            for (Player player : level.players()) {   // vanilla's nearest search returns null only when nobody passes the predicate
                if (predicate == null || predicate.test(player)) {
                    return true;
                }
            }
            this.end();
            return false;
        }
        return true;
    }

    private void add(Player player) {
        if (this.n == this.px.length) {
            final int len = this.n * 2;
            this.px = java.util.Arrays.copyOf(this.px, len);
            this.py = java.util.Arrays.copyOf(this.py, len);
            this.pz = java.util.Arrays.copyOf(this.pz, len);
        }
        this.px[this.n] = player.getX();
        this.py[this.n] = player.getY();
        this.pz[this.n] = player.getZ();
        this.n++;
    }

    /**
     * Squared distance from (x, y, z) to the nearest player, for vanilla's tests: exactly nearestPlayer.distanceToSqr(x, y, z) whenever
     * that is at most FAR^2; otherwise some value above FAR^2 (every test then answers as for the exact value). Only after begin == true.
     */
    public double nearestDistanceSqr(double x, double y, double z) {
        double best = Double.POSITIVE_INFINITY;
        final double[] px = this.px, py = this.py, pz = this.pz;
        for (int i = 0, len = this.n; i < len; i++) {
            final double dx = px[i] - x;
            final double dy = py[i] - y;
            final double dz = pz[i] - z;
            final double d = dx * dx + dy * dy + dz * dz;
            if (d < best) {
                best = d;
            }
        }
        if (VERIFY) {
            final Player vanilla = this.level.getNearestPlayer(x, y, z, -1.0, this.predicate);
            final double v = vanilla == null ? -1.0 : vanilla.distanceToSqr(x, y, z);
            final long checks = VERIFIED.incrementAndGet();
            final boolean same = v <= FAR_SQR ? Double.doubleToLongBits(v) == Double.doubleToLongBits(best) : best > FAR_SQR;
            if (!same) {
                if (MISMATCHES.incrementAndGet() <= 50) {
                    LOGGER.warn("spawn player probe mismatch at {} {} {}: probe {} vanilla {} (candidates {})", x, y, z, best, v, this.n);
                }
            }
            if ((checks & 0xFFFFF) == 0) {
                LOGGER.info("spawn player probe verified {} steps, {} mismatches", checks, MISMATCHES.get());
            }
        }
        return best;
    }

    /** Drop the references (the level must not be kept alive by a thread local). */
    public void end() {
        this.n = 0;
        this.level = null;
        this.predicate = null;
    }
}
