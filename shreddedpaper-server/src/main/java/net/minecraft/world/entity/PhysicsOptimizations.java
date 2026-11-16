package net.minecraft.world.entity;

import io.multipaper.shreddedpaper.config.ShreddedPaperConfiguration;

/**
 * Knowledge - switches of the entity movement speed-ups (shreddedpaper.yml optimizations section). A -Dshreddedpaper.<name>=true/false
 * property wins over the config. All of them give the same movement results; the switches exist to compare and to back out.
 * -Dshreddedpaper.verifyPhysics=true recomputes every shortcut the vanilla way and throws on a difference (tests only).
 */
public final class PhysicsOptimizations {
    private static final String AIR_WALK = System.getProperty("shreddedpaper.airBlockWalkSkip");
    private static final String FLUID_COUNTS = System.getProperty("shreddedpaper.fluidSectionCounts");
    private static final String SHAPES = System.getProperty("shreddedpaper.contextFreeShapes");
    private static final String MEMO = System.getProperty("shreddedpaper.tickThreadCheckMemo");
    private static final String NO_POS_LOCK = System.getProperty("shreddedpaper.noPosLock");
    private static final String FLOW = System.getProperty("shreddedpaper.flowCache");
    private static final String WATER_WALK = System.getProperty("shreddedpaper.waterWalkSkip");
    private static final String ON_POS = System.getProperty("shreddedpaper.onPosMemo");
    public static final boolean VERIFY = Boolean.getBoolean("shreddedpaper.verifyPhysics");
    /** Tests only: deliberately break one shortcut (air, fluid, shape) to prove verifyPhysics catches it. */
    public static final String PLANT = System.getProperty("shreddedpaper.physicsPlantBug", "");

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
            final net.minecraft.world.phys.shapes.VoxelShape fast = state.knowledge$contextFreeShape;
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
        System.out.println("PHYS_SELFTEST states=" + states + " contextFree=" + free + " probeExcluded=" + net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase.knowledge$probeExcluded
            + " contexts=" + contexts.size() + " mismatches=" + mismatches.size() + " " + mismatches);
        if (!mismatches.isEmpty()) throw new IllegalStateException("context-free collision shapes differ: " + mismatches);
    }

    public static boolean airBlockWalkSkip() {
        if (AIR_WALK != null) return Boolean.parseBoolean(AIR_WALK);
        ShreddedPaperConfiguration config = ShreddedPaperConfiguration.get();
        return config != null && config.optimizations.airBlockWalkSkip;
    }

    public static boolean fluidSectionCounts() {
        if (FLUID_COUNTS != null) return Boolean.parseBoolean(FLUID_COUNTS);
        ShreddedPaperConfiguration config = ShreddedPaperConfiguration.get();
        return config != null && config.optimizations.fluidSectionCounts;
    }

    public static boolean contextFreeShapes() {
        if (VERIFY && !selfTested) { // first collision scan: registries are complete by now
            synchronized (PhysicsOptimizations.class) {
                if (!selfTested) { selfTested = true; selfTest(); }
            }
        }
        if (SHAPES != null) return Boolean.parseBoolean(SHAPES);
        ShreddedPaperConfiguration config = ShreddedPaperConfiguration.get();
        return config != null && config.optimizations.contextFreeShapes;
    }

    public static boolean tickThreadCheckMemo() {
        if (MEMO != null) return Boolean.parseBoolean(MEMO);
        ShreddedPaperConfiguration config = ShreddedPaperConfiguration.get();
        return config != null && config.optimizations.tickThreadCheckMemo;
    }

    public static boolean noPosLock() {
        if (NO_POS_LOCK != null) return Boolean.parseBoolean(NO_POS_LOCK);
        ShreddedPaperConfiguration config = ShreddedPaperConfiguration.get();
        return config != null && config.optimizations.noPosLock;
    }

    public static boolean flowCache() {
        if (FLOW != null) return Boolean.parseBoolean(FLOW);
        ShreddedPaperConfiguration config = ShreddedPaperConfiguration.get();
        return config != null && config.optimizations.flowCache;
    }

    public static boolean waterWalkSkip() {
        if (WATER_WALK != null) return Boolean.parseBoolean(WATER_WALK);
        ShreddedPaperConfiguration config = ShreddedPaperConfiguration.get();
        return config != null && config.optimizations.waterWalkSkip;
    }

    public static boolean onPosMemo() {
        if (ON_POS != null) return Boolean.parseBoolean(ON_POS);
        ShreddedPaperConfiguration config = ShreddedPaperConfiguration.get();
        return config != null && config.optimizations.onPosMemo;
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
