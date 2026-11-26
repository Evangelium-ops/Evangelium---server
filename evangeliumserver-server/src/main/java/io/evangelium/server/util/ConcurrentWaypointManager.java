package io.evangelium.server.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.waypoints.WaypointTransmitter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Evangelium: the locator bar (ServerWaypointManager) without one world-wide monitor around every call.
 * <p>
 * The vanilla-derived manager synchronized every method on one per-world lock, and every player's update walked every other player
 * inside it, so with the locator bar on the region threads of a crowded world queued behind each other (about 10% of a crowded tick).
 * Here:
 * <ul>
 * <li>the state of one pair (receiver, waypoint) is a {@link Pair} that is only changed under its own monitor, so updates of different
 * pairs run in parallel and the packets of one pair keep their order (add, updates, remove);</li>
 * <li>structural changes (players or waypoints added/removed, all connections broken by the gamerule) take a write lock; updates take
 * the read lock, so an update never races a removal (no connection left behind for a player that left the world);</li>
 * <li>the LOCATOR_BAR gamerule is read once per call (inside the lock, so breakAllConnections cannot be undone by an update that read
 * the old value), not once per pair;</li>
 * <li>optionally (-Devangeliumserver.waypointFarInterval=N ticks, default 0 = off: no gain measured with the locator bar on in the act benchmark, and dots would move less often) a pair whose two ends are more than
 * -Devangeliumserver.waypointFarDistance (default 128) blocks apart is updated at most every N ticks; a skipped pair makes the update
 * report "skipped", and the player retries at its next waypoint interval, so a far dot still ends where the player stopped.</li>
 * </ul>
 * Each pair decision (update, re-make when broken, create, remove) is exactly the old one. Switch: -Devangeliumserver.waypointManager=legacy.
 */
public class ConcurrentWaypointManager extends ServerWaypointManager {

    public ConcurrentWaypointManager(final net.minecraft.server.level.ServerLevel level) { // 26.3: ServerWaypointManager takes its level
        super(level);
    }

    public static final boolean ENABLED = !"legacy".equalsIgnoreCase(System.getProperty("evangeliumserver.waypointManager"));
    public static final int FAR_INTERVAL = Math.max(0, Integer.getInteger("evangeliumserver.waypointFarInterval", 0));
    private static final double FAR_DISTANCE = Double.parseDouble(System.getProperty("evangeliumserver.waypointFarDistance", "128"));
    private static final double FAR_DISTANCE_SQR = FAR_DISTANCE * FAR_DISTANCE;

    /** One (receiver, waypoint) pair. Fields only change under the pair's monitor. */
    private static final class Pair {
        WaypointTransmitter.Connection connection; // null: checked, no connection
        int lastTick = Integer.MIN_VALUE;           // server tick of the last check
    }

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    // written under the write lock only (copy on write), read under the read lock
    private volatile WaypointTransmitter[] waypointArray = new WaypointTransmitter[0];
    private volatile ServerPlayer[] playerArray = new ServerPlayer[0];
    private final Set<WaypointTransmitter> waypointSet = ConcurrentHashMap.newKeySet();
    private final Set<ServerPlayer> playerSet = ConcurrentHashMap.newKeySet();
    // receiver -> (waypoint -> pair); rows are made on demand under the read lock, removed under the write lock
    private final ConcurrentHashMap<ServerPlayer, ConcurrentHashMap<WaypointTransmitter, Pair>> rows = new ConcurrentHashMap<>();

    /** The gamerule of the receiver's world, read once per call (all receivers of one manager share a world). */
    private static final class RuleCache {
        net.minecraft.server.level.ServerLevel level;
        boolean enabled;

        boolean enabledFor(ServerPlayer player) {
            net.minecraft.server.level.ServerLevel level = player.level();
            if (level != this.level) {
                this.level = level;
                this.enabled = level.getGameRules().get(GameRules.LOCATOR_BAR);
            }
            return this.enabled;
        }
    }

    private ConcurrentHashMap<WaypointTransmitter, Pair> row(ServerPlayer player) {
        return this.rows.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
    }

    private static Pair pair(ConcurrentHashMap<WaypointTransmitter, Pair> row, WaypointTransmitter waypoint) {
        Pair pair = row.get(waypoint);
        return pair != null ? pair : row.computeIfAbsent(waypoint, k -> new Pair());
    }

    // ------------------------------------------------------------------ structural changes (write lock)

    @Override
    public void trackWaypoint(WaypointTransmitter waypoint) {
        this.lock.writeLock().lock();
        try {
            if (this.waypointSet.add(waypoint)) {
                WaypointTransmitter[] old = this.waypointArray;
                WaypointTransmitter[] next = Arrays.copyOf(old, old.length + 1);
                next[old.length] = waypoint;
                this.waypointArray = next;
            }
            RuleCache rules = new RuleCache();
            for (ServerPlayer serverPlayer : this.playerArray) {
                this.createConnection(serverPlayer, waypoint, pair(this.row(serverPlayer), waypoint), rules);
            }
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    @Override
    public void untrackWaypoint(WaypointTransmitter waypoint) {
        this.lock.writeLock().lock();
        try {
            for (Map.Entry<ServerPlayer, ConcurrentHashMap<WaypointTransmitter, Pair>> entry : this.rows.entrySet()) {
                Pair pair = entry.getValue().remove(waypoint);
                if (pair != null && pair.connection != null) {
                    pair.connection.disconnect();
                    pair.connection = null;
                }
            }
            if (this.waypointSet.remove(waypoint)) {
                ArrayList<WaypointTransmitter> next = new ArrayList<>(Arrays.asList(this.waypointArray));
                next.remove(waypoint);
                this.waypointArray = next.toArray(new WaypointTransmitter[0]);
            }
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    @Override
    public void addPlayer(ServerPlayer player) {
        this.lock.writeLock().lock();
        try {
            if (this.playerSet.add(player)) {
                ServerPlayer[] old = this.playerArray;
                ServerPlayer[] next = Arrays.copyOf(old, old.length + 1);
                next[old.length] = player;
                this.playerArray = next;
            }
            ConcurrentHashMap<WaypointTransmitter, Pair> row = this.row(player);
            RuleCache rules = new RuleCache();
            for (WaypointTransmitter waypointTransmitter : this.waypointArray) {
                this.createConnection(player, waypointTransmitter, pair(row, waypointTransmitter), rules);
            }

            if (player.isTransmittingWaypoint()) {
                this.trackWaypoint((WaypointTransmitter) player);
            }
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    @Override
    public void removePlayer(ServerPlayer player) {
        this.lock.writeLock().lock();
        try {
            ConcurrentHashMap<WaypointTransmitter, Pair> row = this.rows.remove(player);
            if (row != null) {
                for (Pair pair : row.values()) {
                    if (pair.connection != null) {
                        pair.connection.disconnect();
                        pair.connection = null;
                    }
                }
            }
            this.untrackWaypoint((WaypointTransmitter) player);
            if (this.playerSet.remove(player)) {
                ArrayList<ServerPlayer> next = new ArrayList<>(Arrays.asList(this.playerArray));
                next.remove(player);
                this.playerArray = next.toArray(new ServerPlayer[0]);
            }
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    @Override
    public void breakAllConnections() {
        this.lock.writeLock().lock();
        try {
            for (ConcurrentHashMap<WaypointTransmitter, Pair> row : this.rows.values()) {
                for (Pair pair : row.values()) {
                    if (pair.connection != null) {
                        pair.connection.disconnect();
                        pair.connection = null;
                    }
                }
            }
            this.rows.clear();
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    // ------------------------------------------------------------------ updates (read lock, per-pair monitor)

    @Override
    public void remakeConnections(WaypointTransmitter waypoint) {
        this.lock.readLock().lock();
        try {
            RuleCache rules = new RuleCache();
            for (ServerPlayer serverPlayer : this.playerArray) {
                Pair pair = pair(this.row(serverPlayer), waypoint);
                synchronized (pair) {
                    this.createConnection(serverPlayer, waypoint, pair, rules);
                }
            }
        } finally {
            this.lock.readLock().unlock();
        }
    }

    @Override
    public void updateWaypoint(WaypointTransmitter waypoint) {
        this.updateTransmitter(waypoint, false); // other transmitters (mobs) and commands never retry: no deferral
    }

    /** {@link #updateWaypoint}; true when far pairs were left for later (the caller should call again after a while). */
    public boolean updateWaypointSkipping(WaypointTransmitter waypoint) {
        return this.updateTransmitter(waypoint, true);
    }

    private boolean updateTransmitter(WaypointTransmitter waypoint, boolean mayDefer) {
        this.lock.readLock().lock();
        try {
            if (!this.waypointSet.contains(waypoint)) {
                return false;
            }
            final int now = MinecraftServer.currentTick;
            final RuleCache rules = new RuleCache();
            boolean skipped = false;
            // every receiver with a pair for this waypoint (the old column: update those with a connection; create only for tracked
            // players, as the old loop over the players without a connection did) ...
            for (Map.Entry<ServerPlayer, ConcurrentHashMap<WaypointTransmitter, Pair>> entry : this.rows.entrySet()) {
                ServerPlayer serverPlayer = entry.getKey();
                Pair pair = entry.getValue().get(waypoint);
                if (pair != null) {
                    skipped |= this.process(serverPlayer, waypoint, pair, rules, now, this.playerSet.contains(serverPlayer), mayDefer);
                }
            }
            // ... and the tracked players without a pair yet
            for (ServerPlayer serverPlayer : this.playerArray) {
                ConcurrentHashMap<WaypointTransmitter, Pair> row = this.row(serverPlayer);
                if (!row.containsKey(waypoint)) {
                    skipped |= this.process(serverPlayer, waypoint, pair(row, waypoint), rules, now, true, mayDefer);
                }
            }
            return skipped;
        } finally {
            this.lock.readLock().unlock();
        }
    }

    @Override
    public void updatePlayer(ServerPlayer player) {
        this.updateReceiver(player, false); // the gamerule switch: everything at once
    }

    /** {@link #updatePlayer}; true when far pairs were left for later (the caller should call again after a while). */
    public boolean updatePlayerSkipping(ServerPlayer player) {
        return this.updateReceiver(player, true);
    }

    private boolean updateReceiver(ServerPlayer player, boolean mayDefer) {
        this.lock.readLock().lock();
        try {
            final int now = MinecraftServer.currentTick;
            final RuleCache rules = new RuleCache();
            final ConcurrentHashMap<WaypointTransmitter, Pair> row = this.row(player);
            boolean skipped = false;
            // the pairs this player has (as the old row), then the tracked waypoints it has no pair with yet
            for (Map.Entry<WaypointTransmitter, Pair> entry : row.entrySet()) {
                skipped |= this.process(player, entry.getKey(), entry.getValue(), rules, now, this.waypointSet.contains(entry.getKey()), mayDefer);
            }
            for (WaypointTransmitter waypointTransmitter : this.waypointArray) {
                if (!row.containsKey(waypointTransmitter)) {
                    skipped |= this.process(player, waypointTransmitter, pair(row, waypointTransmitter), rules, now, true, mayDefer);
                }
            }
            return skipped;
        } finally {
            this.lock.readLock().unlock();
        }
    }

    @Override
    public Set<WaypointTransmitter> transmitters() {
        return Set.copyOf(this.waypointSet);
    }

    /**
     * One pair: the old updateConnection when it has a connection, else the old createConnection if {@code mayCreate} (the old loops
     * only created connections for tracked waypoints / tracked players). Returns true when the pair was left for later.
     */
    private boolean process(ServerPlayer player, WaypointTransmitter waypoint, Pair pair, RuleCache rules, int now, boolean mayCreate, boolean mayDefer) {
        if (player == waypoint) {
            return false;
        }
        synchronized (pair) {
            if (pair.connection == null && !mayCreate) {
                return false;
            }
            if (mayDefer && FAR_INTERVAL > 0 && pair.lastTick != Integer.MIN_VALUE && now - pair.lastTick < FAR_INTERVAL
                && waypoint instanceof Entity source && source.distanceToSqr(player) > FAR_DISTANCE_SQR) {
                return true;
            }
            pair.lastTick = now;
            if (pair.connection != null) {
                this.updateConnection(player, waypoint, pair, rules);
            } else {
                this.createConnection(player, waypoint, pair, rules);
            }
        }
        return false;
    }

    // the two vanilla pair decisions, unchanged (caller holds the pair's monitor or the write lock)

    private void createConnection(ServerPlayer player, WaypointTransmitter waypoint, Pair pair, RuleCache rules) {
        if (player != waypoint) {
            if (rules.enabledFor(player)) {
                Optional<WaypointTransmitter.Connection> made = waypoint.makeWaypointConnectionWith(player);
                if (made.isPresent()) {
                    WaypointTransmitter.Connection connection = made.get();
                    pair.connection = connection;
                    connection.connect();
                } else {
                    WaypointTransmitter.Connection connection = pair.connection;
                    pair.connection = null;
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        }
    }

    private void updateConnection(ServerPlayer player, WaypointTransmitter waypoint, Pair pair, RuleCache rules) {
        if (player != waypoint) {
            if (rules.enabledFor(player)) {
                WaypointTransmitter.Connection connection = pair.connection;
                if (!connection.isBroken()) {
                    connection.update();
                } else {
                    Optional<WaypointTransmitter.Connection> made = waypoint.makeWaypointConnectionWith(player);
                    if (made.isPresent()) {
                        WaypointTransmitter.Connection connection1 = made.get();
                        connection1.connect();
                        pair.connection = connection1;
                    } else {
                        connection.disconnect();
                        pair.connection = null;
                    }
                }
            }
        }
    }

    /** For tests: the connections as (receiver, waypoint) -> connection class, like the old table. */
    public Map<String, String> debugSnapshot() {
        Map<String, String> out = new java.util.TreeMap<>();
        this.lock.readLock().lock();
        try {
            this.rows.forEach((player, row) -> row.forEach((waypoint, pair) -> {
                synchronized (pair) {
                    if (pair.connection != null) {
                        out.put(player.getScoreboardName() + "<-" + waypoint, pair.connection.getClass().getSimpleName());
                    }
                }
            }));
        } finally {
            this.lock.readLock().unlock();
        }
        return out;
    }
}
