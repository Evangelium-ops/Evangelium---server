package io.evangelium.server.player;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Evangelium: "who is the nearest player" without walking every player of the world.
 *
 * <p>Vanilla answers getNearestPlayer, the nearest-player target search of mobs and a few "is a player near" checks by testing every
 * player of the world, reading each one's game mode and position (a pointer chase per player). Natural spawning asks this for every
 * spawn attempt and every despawnable mob asks it every tick, so with 120 players this was about a fifth of all region work.
 *
 * <p>This keeps a packed copy of the players' horizontal positions (one double[] the CPU walks in a few hundred nanoseconds) and only
 * looks at the real player objects within reach. The answer is exactly vanilla's:
 * <ul>
 *   <li>a player's live position is never more than {@link #DRIFT} blocks (horizontally) from its copy: {@link #moved} throws the copy
 *       away as soon as a player gets {@link #REINDEX} blocks from its copied position, and joins, leaves and world changes do too;</li>
 *   <li>candidates are the players whose copy is within the needed reach plus {@link #DRIFT}; every other player is provably out of
 *       reach, so they would have lost (or failed the range test) in vanilla's loop too;</li>
 *   <li>candidates are tested live, with vanilla's predicate, distance and "first strictly nearer wins" rule, in the same order as the
 *       world's player list (the copy is taken from it and thrown away whenever it changes), so even ties resolve the same;</li>
 *   <li>when the search has no bound and nobody was found within {@link #REACH} blocks, the vanilla loop runs (far from every player).</li>
 * </ul>
 * -Devangeliumserver.nearbyPlayerIndex=false turns it off (vanilla loops).
 */
public final class NearbyPlayerIndex {

    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("evangeliumserver.nearbyPlayerIndex", "true"));
    /** Test hook -Devangeliumserver.verifyPlayerIndex=true: every answer is also computed the vanilla way and differences are logged. */
    static final boolean VERIFY = Boolean.getBoolean("evangeliumserver.verifyPlayerIndex");
    public static final java.util.concurrent.atomic.AtomicLong VERIFIED = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong MISMATCHES = new java.util.concurrent.atomic.AtomicLong();
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getClassLogger();

    private static void verify(String what, Object fast, Object vanilla) {
        VERIFIED.incrementAndGet();
        if (fast != vanilla && !(fast instanceof List<?> a && a.equals(vanilla)) && !(fast instanceof Boolean b && b.equals(vanilla))) {
            if (MISMATCHES.incrementAndGet() <= 50) {
                LOGGER.warn("nearby player index mismatch in {}: index {} vanilla {}", what, fast, vanilla);
            }
        }
        if ((VERIFIED.get() & 0xFFFFF) == 0) {
            LOGGER.info("nearby player index verified {} answers, {} mismatches", VERIFIED.get(), MISMATCHES.get());
        }
    }
    /** A copy is re-taken once a player is this far (horizontally) from where the copy has him. */
    static final double REINDEX = 16.0;
    /** How far a live position can be from its copy: REINDEX, plus the same again for a move racing with the copy being taken. */
    static final double DRIFT = 2 * REINDEX + 1.0;
    /** Unbounded searches trust a result nearer than this; farther (or nothing) falls back to the full loop. */
    static final double REACH = 128.0;

    private static final VarHandle EPOCH;

    static {
        try {
            EPOCH = MethodHandles.lookup().findVarHandle(NearbyPlayerIndex.class, "epoch", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final class Snapshot {
        final int epoch;
        final ServerPlayer[] players;
        final double[] xz;

        Snapshot(int epoch, ServerPlayer[] players, double[] xz) {
            this.epoch = epoch;
            this.players = players;
            this.xz = xz;
        }
    }

    private final List<ServerPlayer> players;
    private volatile int epoch;
    private volatile @Nullable Snapshot snapshot;

    public NearbyPlayerIndex(List<ServerPlayer> players) {
        this.players = players;
    }

    /** The player list changed (join, leave, world change) or a player moved away from his copy. */
    public void invalidate() {
        EPOCH.getAndAdd(this, 1);
    }

    /** Called for every position change of a player (Entity.setPosRaw). */
    public static void moved(ServerPlayer player, double x, double z) {
        double dx = x - player.evangeliumIndexX;
        double dz = z - player.evangeliumIndexZ;
        if (!(dx * dx + dz * dz <= REINDEX * REINDEX) && player.level() instanceof ServerLevel level) {   // NaN (never copied) re-takes too
            level.evangeliumPlayerIndex.invalidate();
        }
    }

    private Snapshot snapshot() {
        Snapshot s = this.snapshot;
        if (s != null && s.epoch == this.epoch) {
            return s;
        }
        synchronized (this) {
            int e = this.epoch;   // read before the positions: a move during the copy bumps it and the next caller copies again
            s = this.snapshot;
            if (s != null && s.epoch == e) {
                return s;
            }
            ServerPlayer[] ps = this.players.toArray(new ServerPlayer[0]);
            double[] xz = new double[ps.length * 2];
            for (int i = 0; i < ps.length; i++) {
                double px = ps[i].getX();
                double pz = ps[i].getZ();
                xz[2 * i] = px;
                xz[2 * i + 1] = pz;
                ps[i].evangeliumIndexX = px;
                ps[i].evangeliumIndexZ = pz;
            }
            s = new Snapshot(e, ps, xz);
            this.snapshot = s;
            return s;
        }
    }

    // ------------------------------------------------------------------------------------------------ EntityGetter.getNearestPlayer

    public @Nullable Player getNearestPlayer(double x, double y, double z, double distance, @Nullable Predicate<Entity> predicate) {
        Player r = this.getNearestPlayer0(x, y, z, distance, predicate, ENABLED);
        if (VERIFY && ENABLED) verify("getNearestPlayer", r, this.getNearestPlayer0(x, y, z, distance, predicate, false));
        return r;
    }

    private @Nullable Player getNearestPlayer0(double x, double y, double z, double distance, @Nullable Predicate<Entity> predicate, boolean fast) {
        if (fast) {
            final boolean bounded = distance >= 0.0 && distance <= REACH;
            final double reach = (bounded ? distance : REACH) + DRIFT;
            final double reach2 = reach * reach;
            final Snapshot s = this.snapshot();
            final double[] xz = s.xz;
            double d = -1.0;
            Player player = null;
            for (int i = 0, n = s.players.length; i < n; i++) {
                double dx = xz[2 * i] - x;
                double dz = xz[2 * i + 1] - z;
                if (dx * dx + dz * dz >= reach2) {
                    continue;
                }
                Player player1 = s.players[i];
                if (predicate == null || predicate.test(player1)) {
                    double d1 = player1.distanceToSqr(x, y, z);
                    if ((distance < 0.0 || d1 < distance * distance) && (d == -1.0 || d1 < d)) {
                        d = d1;
                        player = player1;
                    }
                }
            }
            if (bounded || (player != null && d < REACH * REACH)) {
                return player;
            }
        }
        // vanilla (EntityGetter.getNearestPlayer)
        double d = -1.0;
        Player player = null;
        for (Player player1 : this.players) {
            if (predicate == null || predicate.test(player1)) {
                double d1 = player1.distanceToSqr(x, y, z);
                if ((distance < 0.0 || d1 < distance * distance) && (d == -1.0 || d1 < d)) {
                    d = d1;
                    player = player1;
                }
            }
        }
        return player;
    }

    // ------------------------------------------------------------------------------------- ServerEntityGetter.getNearestEntity(players)

    public @Nullable Player getNearestTargetedPlayer(ServerLevel level, TargetingConditions conditions, @Nullable LivingEntity source, double x, double y, double z) {
        Player r = this.getNearestTargetedPlayer0(level, conditions, source, x, y, z, ENABLED);
        if (VERIFY && ENABLED) verify("getNearestTargetedPlayer", r, this.getNearestTargetedPlayer0(level, conditions, source, x, y, z, false));
        return r;
    }

    private @Nullable Player getNearestTargetedPlayer0(ServerLevel level, TargetingConditions conditions, @Nullable LivingEntity source, double x, double y, double z, boolean fast) {
        final double range = conditions.evangeliumRange();
        if (fast && source != null && range > 0.0) {
            // TargetingConditions.test fails every player farther from the source than max(range * visibility, 2). 26.3: visibility comes
            // from the MOB_VISIBILITY item component, whose codec allows up to 10
            // shortcut: bound by the codec maximum (always correct, filters less); look at worn items instead if the index shows up in profiles
            final double maxVisibility = 10.0;
            final double reach = Math.max(range * maxVisibility, 2.0) + DRIFT + 1.0;
            final double reach2 = reach * reach;
            final double sx = source.getX();
            final double sz = source.getZ();
            final Snapshot s = this.snapshot();
            final double[] xz = s.xz;
            double d = -1.0;
            Player best = null;
            for (int i = 0, n = s.players.length; i < n; i++) {
                double dx = xz[2 * i] - sx;
                double dz = xz[2 * i + 1] - sz;
                if (dx * dx + dz * dz >= reach2) {
                    continue;
                }
                Player p = s.players[i];
                if (conditions.test(level, source, p)) {
                    double d1 = p.distanceToSqr(x, y, z);
                    if (d == -1.0 || d1 < d) {
                        d = d1;
                        best = p;
                    }
                }
            }
            return best;
        }
        // vanilla (ServerEntityGetter.getNearestEntity)
        double d = -1.0;
        Player best = null;
        for (Player p : this.players) {
            if (conditions.test(level, source, p)) {
                double d1 = p.distanceToSqr(x, y, z);
                if (d == -1.0 || d1 < d) {
                    d = d1;
                    best = p;
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------------ ServerEntityGetter.getNearbyPlayers

    public List<Player> getNearbyPlayers(ServerLevel level, TargetingConditions conditions, LivingEntity source, AABB area) {
        List<Player> r = this.getNearbyPlayers0(level, conditions, source, area, ENABLED);
        if (VERIFY && ENABLED) verify("getNearbyPlayers", r, this.getNearbyPlayers0(level, conditions, source, area, false));
        return r;
    }

    private List<Player> getNearbyPlayers0(ServerLevel level, TargetingConditions conditions, LivingEntity source, AABB area, boolean fast) {
        List<Player> list = new ArrayList<>();
        if (fast) {
            final Snapshot s = this.snapshot();
            final double[] xz = s.xz;
            final double minX = area.minX - DRIFT, maxX = area.maxX + DRIFT, minZ = area.minZ - DRIFT, maxZ = area.maxZ + DRIFT;
            for (int i = 0, n = s.players.length; i < n; i++) {
                double px = xz[2 * i];
                double pz = xz[2 * i + 1];
                if (px < minX || px > maxX || pz < minZ || pz > maxZ) {
                    continue;
                }
                Player player = s.players[i];
                if (area.contains(player.getX(), player.getY(), player.getZ()) && conditions.test(level, source, player)) {
                    list.add(player);
                }
            }
            return list;
        }
        for (Player player : this.players) {
            if (area.contains(player.getX(), player.getY(), player.getZ()) && conditions.test(level, source, player)) {
                list.add(player);
            }
        }
        return list;
    }

    // ------------------------------------------------------------------------------------------------ natural spawning (SpawnPlayerProbe)

    /** Every player that passes the predicate and is within {@code reach} (3D, live position) of (x, y, z), in no particular order. */
    public void forPlayersWithin(double x, double y, double z, double reach, @Nullable Predicate<Entity> predicate, java.util.function.Consumer<Player> out) {
        final double reach2 = reach * reach;
        if (ENABLED) {
            final double outer = reach + DRIFT;
            final double outer2 = outer * outer;
            final Snapshot s = this.snapshot();
            final double[] xz = s.xz;
            for (int i = 0, n = s.players.length; i < n; i++) {
                double dx = xz[2 * i] - x;
                double dz = xz[2 * i + 1] - z;
                if (dx * dx + dz * dz >= outer2) {
                    continue;
                }
                Player player = s.players[i];
                if ((predicate == null || predicate.test(player)) && player.distanceToSqr(x, y, z) <= reach2) {
                    out.accept(player);
                }
            }
            return;
        }
        for (Player player : this.players) {
            if ((predicate == null || predicate.test(player)) && player.distanceToSqr(x, y, z) <= reach2) {
                out.accept(player);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ "is a player near" checks

    /** EntityGetter.hasNearbyAlivePlayerThatAffectsSpawning (spawners). */
    public boolean hasNearbyAlivePlayerThatAffectsSpawning(double x, double y, double z, double range) {
        return this.hasNearby(x, y, z, range, EntitySelector.PLAYER_AFFECTS_SPAWNING);
    }

    /** EntityGetter.hasNearbyAlivePlayer. */
    public boolean hasNearbyAlivePlayer(double x, double y, double z, double range) {
        return this.hasNearby(x, y, z, range, player -> EntitySelector.NO_SPECTATORS.test(player) && EntitySelector.LIVING_ENTITY_STILL_ALIVE.test(player)
                && EntitySelector.notAfk.test((Player) player));
    }

    private boolean hasNearby(double x, double y, double z, double range, Predicate<Entity> test) {
        boolean r = this.hasNearby0(x, y, z, range, test, ENABLED);
        if (VERIFY && ENABLED) verify("hasNearby", r, this.hasNearby0(x, y, z, range, test, false));
        return r;
    }

    private boolean hasNearby0(double x, double y, double z, double range, Predicate<Entity> test, boolean fast) {
        if (fast && range >= 0.0) {
            final double reach = range + DRIFT;
            final double reach2 = reach * reach;
            final Snapshot s = this.snapshot();
            final double[] xz = s.xz;
            for (int i = 0, n = s.players.length; i < n; i++) {
                double dx = xz[2 * i] - x;
                double dz = xz[2 * i + 1] - z;
                if (dx * dx + dz * dz >= reach2) {
                    continue;
                }
                Player player = s.players[i];
                if (test.test(player) && player.distanceToSqr(x, y, z) < range * range) {
                    return true;
                }
            }
            return false;
        }
        for (Player player : this.players) {
            if (test.test(player)) {
                double d = player.distanceToSqr(x, y, z);
                if (range < 0.0 || d < range * range) {
                    return true;
                }
            }
        }
        return false;
    }
}
