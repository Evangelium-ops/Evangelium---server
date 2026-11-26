package net.minecraft.world.entity;

import io.evangelium.server.config.EvangeliumServerConfiguration;
import io.evangelium.server.util.DevSwitch;

/**
 * Evangelium - the entity movement speed-ups, always on (DevSwitch names for developer benchmarks only). All of them give the same
 * movement results.
 * -Devangeliumserver.verifyPhysics=true recomputes every shortcut the vanilla way and throws on a difference (tests only).
 */
public final class PhysicsOptimizations {
    private static final boolean AIR_WALK = DevSwitch.on("air-block-walk-skip");
    private static final boolean FLUID_COUNTS = DevSwitch.on("fluid-section-counts");
    private static final boolean SHAPES = DevSwitch.on("context-free-shapes");
    private static final boolean MEMO = DevSwitch.on("tick-thread-check-memo");
    private static final boolean NO_POS_LOCK = DevSwitch.on("no-pos-lock");
    private static final boolean FLOW = DevSwitch.on("flow-cache");
    private static final boolean WATER_WALK = DevSwitch.on("water-walk-skip");
    private static final boolean ON_POS = DevSwitch.on("on-pos-memo");
    public static final boolean VERIFY = Boolean.getBoolean("evangeliumserver.verifyPhysics");
    /** Tests only: deliberately break one shortcut (air, fluid, shape) to prove verifyPhysics catches it. */
    public static final String PLANT = System.getProperty("evangeliumserver.physicsPlantBug", "");

    private PhysicsOptimizations() {
    }

    private static volatile boolean selfTested;

    /**
     * verifyPhysics startup self-test: every BlockState with a context-free collision shape against the block's own answer for a set of
     * contexts (empty; descending or not; entity bottom far below, inside, on top of and above the block; holding scaffolding, light,
     * barrier, structure void, debug stick; colliding with fluids). Prints the counts and every mismatch; throws if there is one.
     */
    private static void selfTest() {
        final java.util.List<net.minecraft.world.phys.shapes.CollisionContext> contexts = new java.util.ArrayList<>();
        contexts.add(net.minecraft.world.phys.shapes.CollisionContext.empty());
        contexts.add(net.minecraft.world.phys.shapes.CollisionContext.emptyWithFluidCollisions());
        final net.minecraft.world.item.Item[] items = {net.minecraft.world.item.Items.AIR, net.minecraft.world.item.Items.SCAFFOLDING, net.minecraft.world.item.Items.LIGHT,
            net.minecraft.world.item.Items.BARRIER, net.minecraft.world.item.Items.STRUCTURE_VOID, net.minecraft.world.item.Items.DEBUG_STICK};
        for (final boolean descending : new boolean[]{false, true}) {
            for (final double bottom : new double[]{-Double.MAX_VALUE, -1.0, 0.0, 0.5, 0.99999, 1.0, 1.5, 2.0}) {
                for (final net.minecraft.world.item.Item item : items) {
                    for (final boolean fluid : new boolean[]{false, true}) {
                        for (final boolean placement : new boolean[]{false, true}) {
                            contexts.add(new net.minecraft.world.phys.shapes.EntityCollisionContext(descending, placement, bottom, new net.minecraft.world.item.ItemStack(item), fluid, null) { });
                        }
                    }
                }
            }
        }
        int states = 0, free = 0;
        final java.util.Set<String> mismatches = new java.util.TreeSet<>();
        for (final net.minecraft.world.level.block.state.BlockState state : net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY) {
            states++;
            final net.minecraft.world.phys.shapes.VoxelShape fast = state.evangelium$contextFreeShape;
            if (fast == null) continue;
            free++;
            for (final net.minecraft.world.phys.shapes.CollisionContext context : contexts) {
                for (final net.minecraft.core.BlockPos pos : new net.minecraft.core.BlockPos[]{net.minecraft.core.BlockPos.ZERO, new net.minecraft.core.BlockPos(4160, -61, 4160)}) {
                    final net.minecraft.world.phys.shapes.VoxelShape slow = state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, pos, context);
                    if (slow != fast && net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(slow, fast, net.minecraft.world.phys.shapes.BooleanOp.NOT_SAME)) {
                        mismatches.add(state.getBlock() + " " + state);
                    }
                }
            }
        }
        System.out.println("PHYS_SELFTEST states=" + states + " contextFree=" + free + " probeExcluded=" + net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase.evangelium$probeExcluded
            + " contexts=" + contexts.size() + " mismatches=" + mismatches.size() + " " + mismatches);
        if (!mismatches.isEmpty()) throw new IllegalStateException("context-free collision shapes differ: " + mismatches);
    }

    public static boolean airBlockWalkSkip() {
        return AIR_WALK && EvangeliumServerConfiguration.get() != null;
    }

    public static boolean fluidSectionCounts() {
        return FLUID_COUNTS && EvangeliumServerConfiguration.get() != null;
    }

    public static boolean contextFreeShapes() {
        if (VERIFY && !selfTested) { // first collision scan: registries are complete by now
            synchronized (PhysicsOptimizations.class) {
                if (!selfTested) { selfTested = true; selfTest(); }
            }
        }
        return SHAPES && EvangeliumServerConfiguration.get() != null;
    }

    public static boolean tickThreadCheckMemo() {
        return MEMO && EvangeliumServerConfiguration.get() != null;
    }

    public static boolean noPosLock() {
        return NO_POS_LOCK && EvangeliumServerConfiguration.get() != null;
    }

    public static boolean flowCache() {
        return FLOW && EvangeliumServerConfiguration.get() != null;
    }

    public static boolean waterWalkSkip() {
        return WATER_WALK && EvangeliumServerConfiguration.get() != null;
    }

    public static boolean onPosMemo() {
        return ON_POS && EvangeliumServerConfiguration.get() != null;
    }

    /** verifyPhysics: the context-free shape of a state equals what the block answers for this entity at this position. */
    public static void checkShape(net.minecraft.world.phys.shapes.VoxelShape fast, net.minecraft.world.level.block.state.BlockState state,
                                  net.minecraft.world.level.BlockGetter level, net.minecraft.core.BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext context) {
        net.minecraft.world.phys.shapes.VoxelShape slow = state.getCollisionShape(level, pos, context);
        if (fast != slow && net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(fast, slow, net.minecraft.world.phys.shapes.BooleanOp.NOT_SAME)) {
            throw new IllegalStateException("physics shortcut context-free shape differs from vanilla for " + state + " at " + pos + ": " + fast + " vs " + slow);
        }
    }

    /** The context-free shape used by the collision scans (a planted wrong shape in plant-bug tests). */
    public static net.minecraft.world.phys.shapes.VoxelShape shape(net.minecraft.world.phys.shapes.VoxelShape shape) {
        return PLANT.equals("shape") && !shape.isEmpty() ? net.minecraft.world.phys.shapes.Shapes.box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0) : shape;
    }

    public static void check(boolean ok, String what, Object entity) {
        if (!ok) throw new IllegalStateException("physics shortcut " + what + " differs from vanilla for " + entity);
    }
}
