package io.multipaper.shreddedpaper.threading;

import ca.spottedleaf.moonrise.common.util.WorldUtil;
import com.mojang.logging.LogUtils;
import io.multipaper.shreddedpaper.region.LevelChunkRegion;
import io.multipaper.shreddedpaper.region.RegionPos;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import org.bukkit.Bukkit;
import org.slf4j.Logger;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Knowledge - a tick budget per region. Every region still finishes each tick together with the others (one clock, so plugins see
 * nothing new), but a region that keeps taking longer than the budget (a lag machine, a giant farm) does only a share of its work per
 * tick: its mobs, items, block entities, random ticks and scheduled block ticks get every n-th tick, up to every 64th (slow motion in that region), so the
 * others no longer wait for it. Players, projectiles, what players ride, the dragon and the wither always tick. The share is set once a
 * second from the region's tick time and grows back step by step when the region is cheap again.
 * -Dshreddedpaper.regionBudgetMs (0 = off).
 */
public final class RegionBudget {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    public static final double BUDGET_MS = Double.parseDouble(System.getProperty("shreddedpaper.regionBudgetMs", "0"));
    /**
     * at most every 1024th tick (one tick in about 51 seconds): the last resort. A crowd costs more than its size (every mob in it looks
     * at all the others), so a big lag machine, or several sharing the budget, may need to go that far.
     */
    static final int MAX_DIVISOR = Integer.getInteger("shreddedpaper.regionBudgetMaxDivisor", 1024);
    /** seconds the region must stay cheap before its share is doubled */
    static final int CALM_SECONDS = Integer.getInteger("shreddedpaper.regionBudgetCalmSeconds", 3);
    static final String ALERT_PERMISSION = "shreddedpaper.regions.alerts";

    private static final int LOG_SIZE = 50;
    private static final Deque<String> LOG = new ArrayDeque<>();
    /** the regions running slower than normal right now */
    private static final Map<LevelChunkRegion, State> SLOW = new ConcurrentHashMap<>();

    private RegionBudget() {
    }

    public static boolean enabled() {
        return BUDGET_MS > 0;
    }

    /** Per region, only touched by the thread ticking that region. */
    public static final class State {
        int divisor = 1;
        long tick;
        long sumNanos;
        /** of sumNanos: the work slow motion divides (mobs, items, block entities, random and scheduled ticks), see sliced() */
        long sliceNanos;
        int ticks;
        int calm;
        int over;
        /** cheap seconds needed before one stage faster: doubles when a region that was just sped up has to be slowed again */
        int calmNeeded = CALM_SECONDS;
        long fasterAt;
        volatile double lastMs;
        volatile long slowSince;
        /** shown to the players in the region while it is slowed */
        net.kyori.adventure.bossbar.BossBar bar;

        public int divisor() {
            return this.divisor;
        }

        public double lastMs() {
            return this.lastMs;
        }

        public long slowSince() {
            return this.slowSince;
        }
    }

    // ------------------------------------------------------------------ the rules

    /**
     * The divisor for the next second: one step slower per second while over, one step faster after enough cheap seconds.
     * sliceMs is the part of avgMs that slow motion divides; the rest (the players there, chunks loading and generating around
     * them, packets, tasks) costs the same at any speed, so a region whose time is mostly that is never slowed for it.
     */
    static int next(State s, double avgMs, double sliceMs, double budgetMs, double shareMs, int maxDivisor, int calmSeconds) {
        long now = System.currentTimeMillis();
        double slice = Math.max(0, Math.min(sliceMs, avgMs));
        double rest = avgMs - slice;
        // slow motion is worth it only when the divided work is a real share of what the region would cost at full speed (in a
        // slowed region it looks small exactly because it is divided)
        double fullSlice = slice * s.divisor;
        boolean helps = fullSlice >= (rest + fullSlice) * 0.3;
        if (s.divisor == 1) {
            // full speed: lagging is over the budget, and only for work slow motion can divide (a player exploring, whose time is
            // chunk generation, is never slowed for it)
            if (avgMs > budgetMs && helps) {
                s.calm = 0;
                // one slow second (chunks loading around a player who just arrived) is left alone; two in a row, or one far over, is not
                if (++s.over < 2 && avgMs < budgetMs * 4) {
                    return 1;
                }
                s.over = 0;
                if (now - s.fasterAt < 20_000L) {
                    s.calmNeeded = Math.min(16, s.calmNeeded * 2);   // sped up too early: wait longer before the next try
                }
                return 2;
            }
            s.over = 0;
            if (now - s.fasterAt > 120_000L) {
                s.calmNeeded = calmSeconds;   // two calm minutes at full speed: forgiven
            }
            return 1;
        }
        if (!helps) {
            // slowed, but what costs is not the divided work (any more): slow motion only costs the players there, release it
            s.over = 0;
            s.calm = 0;
            return s.divisor >> 1;
        }
        // slowed: the divided part keeps to the region's share of the budget (regions near each other share it, see decide)
        if (slice > shareMs) {
            s.calm = 0;
            if (++s.over < 2 && slice < shareMs * 4) {
                return s.divisor;
            }
            s.over = 0;
            if (now - s.fasterAt < 20_000L) {
                s.calmNeeded = Math.min(16, s.calmNeeded * 2);
            }
            // in stages (half speed, a quarter, ...) one per second rather than straight down to what it needs: the players there
            // see it coming, and a region only just over the budget never goes further than it has to
            return Math.min(maxDivisor, s.divisor << 1);
        }
        s.over = 0;
        // one stage faster doubles the divided part; it goes faster only when that stays well under the limit of that stage, so a
        // region between 40% and 100% of its share stays where it is (no flipping between two stages)
        int faster = s.divisor >> 1;
        double limit = faster > 1 ? shareMs : budgetMs;
        double cost = faster > 1 ? 2 * slice : rest + 2 * slice;
        if (cost < limit * 0.8) {
            int need = cost < limit * 0.2 ? 1 : s.calmNeeded;   // clearly cheap (a killed lag machine): one stage a second
            if (++s.calm >= need) {
                s.calm = 0;
                s.fasterAt = now;
                return faster;
            }
            return s.divisor;
        }
        s.calm = 0;
        return s.divisor;
    }

    /** Whether something with this key (an entity id, a block or chunk position) ticks in this tick of a divided region. */
    static boolean ticks(State s, long key) {
        return s.divisor <= 1 || Math.floorMod(key + s.tick, (long) s.divisor) == 0;
    }

    // ------------------------------------------------------------------ called while a region ticks

    public static void begin(LevelChunkRegion region) {
        if (enabled()) {
            region.budget.tick++;
        }
    }

    private static State current() {
        LevelChunkRegion region = ShreddedPaperChunkTicker.getCurrentlyTickingRegion();
        return region == null ? null : region.budget;
    }

    /** Scheduled block and fluid ticks and the like: run in this tick? */
    public static boolean ticksRegionWork() {
        State s = enabled() ? current() : null;
        return s == null || ticks(s, 0);
    }

    public static boolean ticks(net.minecraft.core.BlockPos pos) {
        State s = enabled() ? current() : null;
        return s == null || ticks(s, pos.asLong() * 31 + (pos.asLong() >>> 32));
    }

    public static boolean ticks(net.minecraft.world.level.ChunkPos pos) {
        State s = enabled() ? current() : null;
        return s == null || ticks(s, pos.x * 31L + pos.z);
    }

    public static boolean ticks(Entity entity) {
        State s = enabled() ? current() : null;
        return s == null || s.divisor <= 1 || alwaysTicks(entity) || ticks(s, entity.getId());
    }

    /** What players feel at once (themselves, their arrows and pearls, what they ride) and the boss fights. */
    static boolean alwaysTicks(Entity entity) {
        // dying mobs too: their death animation is a tick count, and a killed lag machine should be gone in a second, not in a minute;
        // and experience orbs, so the orbs of a killed machine merge and reach the players at once
        return entity instanceof Player || entity instanceof Projectile || entity instanceof EnderDragon || entity instanceof EnderDragonPart
                || entity instanceof net.minecraft.world.entity.ExperienceOrb
                || (entity instanceof net.minecraft.world.entity.LivingEntity living && living.isDeadOrDying())
                || entity instanceof WitherBoss || (entity.isVehicle() && entity.hasPassenger(e -> e instanceof Player));
    }

    /** Time the region's thread spent on work slow motion divides (one phase of the region tick, measured around it). */
    public static void sliced(long nanos) {
        State s = enabled() ? current() : null;
        if (s != null) {
            s.sliceNanos += nanos;
        }
    }

    /** Time inside a sliced phase that is not divided after all (the players' own entity tick). */
    public static void notSliced(long nanos) {
        sliced(-nanos);
    }

    /** After the region's tick: once a second the share is set from the time it took. */
    public static void end(LevelChunkRegion region, long nanos) {
        if (!enabled()) {
            return;
        }
        State s = region.budget;
        s.sumNanos += nanos;
        // once a second: after 20 ticks, or sooner when the ticks are so slow that 20 of them take longer than a second
        if (++s.ticks < 20 && s.sumNanos < 1_000_000_000L) {
            return;
        }
        double avg = s.sumNanos / 1e6 / s.ticks;
        double slice = s.sliceNanos / 1e6 / s.ticks;
        s.sumNanos = 0;
        s.sliceNanos = 0;
        s.ticks = 0;
        s.lastMs = avg;
        try {
            decide(region, s, avg, slice);
        } finally {
            bossBars(region, s);
        }
    }

    private static void decide(LevelChunkRegion region, State s, double avg, double slice) {
        int before = s.divisor;
        // regions near each other cannot tick at the same time (a region locks its neighbours while it ticks), so lag machines in
        // several regions add up: the slowed regions share one budget
        int slow = SLOW.size() + (SLOW.containsKey(region) ? 0 : 1);
        s.divisor = next(s, avg, slice, BUDGET_MS, BUDGET_MS / Math.max(1, slow), MAX_DIVISOR, CALM_SECONDS);
        if (s.divisor == before) {
            return;
        }
        if (s.divisor > 1) {
            if (before == 1) {
                s.slowSince = System.currentTimeMillis();
            }
            SLOW.put(region, s);
        } else {
            SLOW.remove(region);
        }
        report(region, s, before, avg);
    }

    // ------------------------------------------------------------------ telling the players there

    /** the slowed region's bar each player sees right now (a player object is new at every login, so it is forgotten with it) */
    private static final Map<net.minecraft.server.level.ServerPlayer, net.kyori.adventure.bossbar.BossBar> SHOWN =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Once a second, on the region's thread: its players see its bar while it is slowed, and lose a bar of a region they left. */
    private static void bossBars(LevelChunkRegion region, State s) {
        net.kyori.adventure.bossbar.BossBar bar = null;
        if (s.divisor > 1) {
            Component name = Component.text("This area is slowed down to save server resources (" + speed(s.divisor) + ")", NamedTextColor.RED);
            if (s.bar == null) {
                s.bar = net.kyori.adventure.bossbar.BossBar.bossBar(name, 1f / s.divisor, net.kyori.adventure.bossbar.BossBar.Color.RED,
                        net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS);
            } else {
                s.bar.name(name).progress(1f / s.divisor);
            }
            bar = s.bar;
        }
        for (net.minecraft.server.level.ServerPlayer player : region.getPlayers()) {
            net.kyori.adventure.bossbar.BossBar old = SHOWN.get(player);
            if (old == bar) {
                continue;
            }
            org.bukkit.entity.Player bukkit = player.getBukkitEntity();
            if (old != null) {
                bukkit.hideBossBar(old);
            }
            if (bar != null) {
                bukkit.showBossBar(bar);
                SHOWN.put(player, bar);
            } else {
                SHOWN.remove(player);
            }
        }
    }

    // ------------------------------------------------------------------ telling the staff

    /** The block in the middle of the region, for messages and /regions. */
    public static String where(LevelChunkRegion region) {
        RegionPos pos = region.getRegionPos();
        int half = RegionPos.REGION_SIZE * 8;
        return WorldUtil.getWorldName(region.getLevel()) + " x " + ((pos.x << RegionPos.REGION_SHIFT) * 16 + half) + " z "
                + ((pos.z << RegionPos.REGION_SHIFT) * 16 + half);
    }

    private static String speed(int divisor) {
        double tps = 20.0 / divisor;
        return divisor <= 1 ? "full speed" : "1/" + divisor + " speed (" + (tps >= 1 ? String.valueOf((int) tps) : String.format("%.2f", tps)) + " TPS)";
    }

    /** The two most common entity types and the block entity count: where to look for the lag machine. */
    private static String contents(LevelChunkRegion region) {
        Map<EntityType<?>, Integer> counts = new HashMap<>();
        region.forEachTickingEntity(e -> counts.merge(e.getType(), 1, Integer::sum));
        List<Map.Entry<EntityType<?>, Integer>> top = new ArrayList<>(counts.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(2, top.size()); i++) {
            sb.append(top.get(i).getValue()).append(' ').append(EntityType.getKey(top.get(i).getKey()).getPath()).append(", ");
        }
        return sb.append(region.tickingBlockEntities.size()).append(" block entities").toString();
    }

    private static void report(LevelChunkRegion region, State s, int before, double avg) {
        String where = where(region);
        String text;
        NamedTextColor color;
        if (s.divisor > before) {
            text = "Region near " + where + " is lagging (" + String.format("%.1f", avg) + " ms per tick, budget " + (int) BUDGET_MS
                    + " ms): now at " + speed(s.divisor) + ". Most: " + contents(region) + ".";
            color = NamedTextColor.RED;
        } else if (s.divisor > 1) {
            text = "Region near " + where + " is getting cheaper (" + String.format("%.1f", avg) + " ms per tick): now at " + speed(s.divisor) + ".";
            color = NamedTextColor.GOLD;
        } else {
            text = "Region near " + where + " is back to full speed.";
            color = NamedTextColor.GREEN;
        }
        synchronized (LOG) {
            LOG.addLast(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + text);
            while (LOG.size() > LOG_SIZE) {
                LOG.removeFirst();
            }
        }
        LOGGER.warn(text);
        Component message = Component.text("[Regions] ", NamedTextColor.DARK_RED).append(Component.text(text, color)
                .hoverEvent(HoverEvent.showText(Component.text("Click to teleport there")))
                .clickEvent(ClickEvent.runCommand(teleport(region))));
        // players are told from the main thread, never from a region's tick thread
        MinecraftServer.getServer().execute(() -> {
            for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission(ALERT_PERMISSION)) {
                    p.sendMessage(message);
                }
            }
        });
    }

    public static String teleport(LevelChunkRegion region) {
        RegionPos pos = region.getRegionPos();
        int half = RegionPos.REGION_SIZE * 8;
        return "/execute in " + region.getLevel().dimension().identifier() + " run tp @s " + ((pos.x << RegionPos.REGION_SHIFT) * 16 + half)
                + " ~ " + ((pos.z << RegionPos.REGION_SHIFT) * 16 + half);
    }

    // ------------------------------------------------------------------ /regions

    public static Collection<Map.Entry<LevelChunkRegion, State>> slowRegions() {
        return new ArrayList<>(SLOW.entrySet());
    }

    public static List<String> log() {
        synchronized (LOG) {
            return new ArrayList<>(LOG);
        }
    }

    /** A region that unloads while slow is forgotten. */
    public static void forget(LevelChunkRegion region) {
        SLOW.remove(region);
    }
}
