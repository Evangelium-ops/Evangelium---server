package io.multipaper.shreddedpaper.ai;

import io.multipaper.shreddedpaper.config.ShreddedPaperConfiguration;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;

/**
 * Far mobs think less often (the idea of Pufferfish's dynamic activation of brain). Within {@code far-ai-start-distance} of a player a
 * mob is vanilla. Farther away its goal and target selectors run every N ticks, N growing with the distance (squared distance >>
 * far-ai-distance-shift, at most far-ai-max-interval); movement, looking, jumping, path following, sensing and a mob's own logic
 * still run every tick, so far mobs keep moving smoothly. Goal chances and timers are scaled to the spacing (Goal.reducedTickDelay), so
 * on average a far mob wanders, looks around and acts on vanilla's real-time schedule (farms keep their rates). Never slowed: a mob with a target, hurt,
 * in water or lava, leashed, riding or ridden, and the bosses.
 */
public final class FarAi {

    private FarAi() {
    }

    /** Ticks between goal and target thinking of the mob whose selectors run on this thread right now; 1 = vanilla timing. */
    private static final ThreadLocal<int[]> SPACING = ThreadLocal.withInitial(() -> new int[] {1});

    public static void enter(int spacing) {
        SPACING.get()[0] = spacing;
    }

    public static void exit() {
        SPACING.get()[0] = 1;
    }

    public static int spacing() {
        return SPACING.get()[0];
    }

    /** Ticks between this mob's goal and target thinking: 1 = every tick (vanilla). */
    public static int interval(Mob mob) {
        ShreddedPaperConfiguration.AiEngine c = ShreddedPaperConfiguration.get().aiEngine;
        if (!AiDiagnostics.engineOn() || !c.farAiEnabled || exempt(mob)) {
            return 1;
        }
        // the nearest player is looked up once a second per mob, staggered by id so not every mob does it in the same tick
        if (mob.farAiInterval == 0 || (mob.tickCount + mob.getId()) % 20 == 0) {
            mob.farAiInterval = compute(mob, c);
        }
        return mob.farAiInterval;
    }

    static int compute(Mob mob, ShreddedPaperConfiguration.AiEngine c) {
        if (!c.farAiExemptTypes.isEmpty()) {   // here, once a second per mob, not in the every-tick exempt check
            var key = net.minecraft.world.entity.EntityType.getKey(mob.getType());
            if (c.farAiExemptTypes.contains(key.toString()) || c.farAiExemptTypes.contains(key.getPath())) {
                return 1;
            }
        }
        int max = Math.clamp(c.farAiMaxInterval, 1, 40);
        Player nearest = mob.level().getNearestPlayer(mob, -1.0);
        if (nearest == null) {
            return max;
        }
        return forDistance(mob.distanceToSqr(nearest), c.farAiStartDistance, c.farAiDistanceShift, max);
    }

    /** The interval for a squared distance to the nearest player. */
    static int forDistance(double distanceSquared, int start, int shift, int max) {
        if (distanceSquared <= (double) start * start) {
            return 1;
        }
        long d2 = (long) Math.min(distanceSquared, Integer.MAX_VALUE);
        return (int) Math.clamp(d2 >> Math.clamp(shift, 4, 12), 1, max);
    }

    private static boolean exempt(Mob mob) {
        return mob.getTarget() != null || mob.hurtTime > 0 || mob.isInLiquid() || mob.isLeashed() || mob.isVehicle() || mob.isPassenger()
                || mob instanceof WitherBoss || mob instanceof EnderDragon || mob instanceof Warden;
    }
}
