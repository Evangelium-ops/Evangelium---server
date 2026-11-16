package net.minecraft.world.level.levelgen;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;

/**
 * Knowledge - density function compiler. Turns a NoiseChunk-wrapped density function tree into one generated hidden class, so
 * chunk generation stops walking the tree through megamorphic interface calls. Bit-identical by construction:
 * <ul>
 * <li>every node keeps its vanilla compute() AND fillArray() algorithm (same operations, same order, same calls into the
 * NoiseChunk caches, which stay the real objects and are called as leaves);</li>
 * <li>node-local math is not re-implemented: the generated code calls the node's own transform()/compute() methods on the
 * original objects, and only the plumbing between nodes (add, mul with its zero check, min/max with their bound check, range
 * choice, shifted noise) is generated, written like the vanilla source;</li>
 * <li>a subtree that only reads the position (noises, constants, gradients, the beardifier, blend markers) fills its array
 * with one fillAllDirectly-style pass instead of one pass per node: same values, as nothing in it has state.</li>
 * </ul>
 * Classes are cached by tree shape (node classes and operators, never values: all values are read from the original objects),
 * so each chunk only builds a small shape description and instantiates the cached class with its own objects. Anything not
 * understood stays a leaf call; any failure returns the interpreted tree.
 */
public final class DensityFunctionCompiler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ON = io.multipaper.shreddedpaper.util.DevSwitch.on("density-function-compiler");
    private static final int MAX_NODES = 4000;
    private static final int MAX_TEMPLATES = 20000;
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final Map<Node, Template> TEMPLATES = new ConcurrentHashMap<>();
    private static final MethodType CTOR_TYPE = MethodType.methodType(void.class, DensityFunction.class, Object[].class, double[].class);
    private static final MethodType CTOR_CALL = MethodType.methodType(CompiledDensityFunction.class, DensityFunction.class, Object[].class, double[].class);
    private static final Set<Class<?>> PURE_LEAVES = Set.of(
        DensityFunctions.Constant.class, DensityFunctions.YClampedGradient.class, DensityFunctions.Noise.class, DensityFunctions.Shift.class,
        DensityFunctions.ShiftA.class, DensityFunctions.ShiftB.class, BlendedNoise.class, DensityFunctions.EndIslandDensityFunction.class,
        DensityFunctions.BeardifierMarker.class, Beardifier.class, DensityFunctions.BlendAlpha.class, DensityFunctions.BlendOffset.class,
        NoiseChunk.ColumnNoise.class // Knowledge - column noise: a cache of a noise leaf, values only depend on the position
    );
    /** leaves whose vanilla fillArray is exactly contextProvider.fillAllDirectly(array, this) */
    private static final Set<Class<?>> FILL_DIRECTLY_LEAVES = Set.of(
        DensityFunctions.Noise.class, DensityFunctions.Shift.class, DensityFunctions.ShiftA.class, DensityFunctions.ShiftB.class,
        DensityFunctions.YClampedGradient.class, BlendedNoise.class, DensityFunctions.EndIslandDensityFunction.class, DensityFunctions.Spline.class,
        DensityFunctions.FindTopSurface.class, NoiseChunk.FlatCache.class, NoiseChunk.CacheAllInCell.class, NoiseChunk.BlendAlpha.class,
        NoiseChunk.BlendOffset.class, NoiseChunk.ColumnNoise.class
    );
    private static volatile boolean failureLogged;

    private DensityFunctionCompiler() {
    }

    /** test hook: when set, wins over DevSwitch */
    static volatile Boolean forced;

    public static boolean enabled() {
        Boolean forced = DensityFunctionCompiler.forced;
        if (forced != null) {
            return forced;
        }
        return ON && io.multipaper.shreddedpaper.config.ShreddedPaperConfiguration.get() != null;
    }

    /** every function of a NoiseChunk-wrapped router compiled (the caller decides whether the compiler is on) */
    static NoiseRouter compileAll(NoiseRouter router) {
        return new NoiseRouter(
            compileAlways(router.barrierNoise()), compileAlways(router.fluidLevelFloodednessNoise()), compileAlways(router.fluidLevelSpreadNoise()),
            compileAlways(router.lavaNoise()), compileAlways(router.temperature()), compileAlways(router.vegetation()), compileAlways(router.continents()),
            compileAlways(router.erosion()), compileAlways(router.depth()), compileAlways(router.ridges()), compileAlways(router.preliminarySurfaceLevel()),
            compileAlways(router.finalDensity()), compileAlways(router.veinToggle()), compileAlways(router.veinRidged()), compileAlways(router.veinGap())
        );
    }

    /** the compiled form of a NoiseChunk-wrapped function, or the function itself when it is a single leaf or cannot be compiled */
    public static DensityFunction compile(DensityFunction function) {
        if (!enabled()) {
            return function;
        }
        return compileAlways(function);
    }

    static DensityFunction compileAlways(DensityFunction function) {
        try {
            Builder builder = new Builder();
            Node root = builder.build(function);
            if (root instanceof Leaf) {
                return function;
            }
            Template template = TEMPLATES.get(root);
            if (template == null) {
                if (TEMPLATES.size() >= MAX_TEMPLATES) {
                    return function;
                }
                template = TEMPLATES.computeIfAbsent(root, DensityFunctionCompiler::generate);
            }
            if (template.constructor == null) {
                return function;
            }
            return (CompiledDensityFunction) template.constructor.invokeExact(function, builder.objects.toArray(), builder.bounds.toDoubleArray());
        } catch (TooBig e) {
            return function;
        } catch (Throwable t) {
            if (!failureLogged) {
                failureLogged = true;
                LOGGER.warn("Density function compiler failed, using the interpreted function", t);
            }
            return function;
        }
    }

    public static int templateCount() {
        return TEMPLATES.size();
    }

    // ---- mapping a router once per NoiseChunk --------------------------------------------------------------------------------

    /**
     * function.mapAll(visitor) with each child already mapped by childMapper (a per-NoiseChunk identity memo). Vanilla maps a shared
     * subtree again at every reference (10,829 NoiseChunk.wrap calls for 137 results in the overworld) and wrap() then finds the
     * equal result it made the first time; mapping each node once gives the same canonical objects in the same creation order.
     * The node's own mapAll still runs (children are stand-ins that return their mapped result), so bounds, MulOrAdd ranges and
     * TwoArgumentSimpleFunction.create behave exactly as before. Unknown types (and leaves, splines) use their mapAll directly.
     */
    public static DensityFunction mapOnce(DensityFunction function, java.util.function.UnaryOperator<DensityFunction> childMapper, DensityFunction.Visitor visitor) {
        DensityFunction copy;
        Class<?> type = function.getClass();
        if (type == DensityFunctions.Ap2.class) {
            DensityFunctions.Ap2 f = (DensityFunctions.Ap2) function;
            DensityFunction a = done(childMapper.apply(f.argument1()));
            copy = new DensityFunctions.Ap2(f.type(), a, done(childMapper.apply(f.argument2())), f.minValue(), f.maxValue());
        } else if (type == DensityFunctions.MulOrAdd.class) {
            DensityFunctions.MulOrAdd f = (DensityFunctions.MulOrAdd) function;
            copy = new DensityFunctions.MulOrAdd(f.specificType(), done(childMapper.apply(f.input())), f.minValue(), f.maxValue(), f.argument());
        } else if (type == DensityFunctions.Mapped.class) {
            DensityFunctions.Mapped f = (DensityFunctions.Mapped) function;
            copy = new DensityFunctions.Mapped(f.type(), done(childMapper.apply(f.input())), f.minValue(), f.maxValue());
        } else if (type == DensityFunctions.Clamp.class) {
            DensityFunctions.Clamp f = (DensityFunctions.Clamp) function;
            copy = new DensityFunctions.Clamp(done(childMapper.apply(f.input())), f.minValue(), f.maxValue());
        } else if (type == DensityFunctions.RangeChoice.class) {
            DensityFunctions.RangeChoice f = (DensityFunctions.RangeChoice) function;
            DensityFunction in = done(childMapper.apply(f.input()));
            DensityFunction yes = done(childMapper.apply(f.whenInRange()));
            copy = new DensityFunctions.RangeChoice(in, f.minInclusive(), f.maxExclusive(), yes, done(childMapper.apply(f.whenOutOfRange())));
        } else if (type == DensityFunctions.ShiftedNoise.class) {
            DensityFunctions.ShiftedNoise f = (DensityFunctions.ShiftedNoise) function;
            DensityFunction x = done(childMapper.apply(f.shiftX()));
            DensityFunction y = done(childMapper.apply(f.shiftY()));
            copy = new DensityFunctions.ShiftedNoise(x, y, done(childMapper.apply(f.shiftZ())), f.xzScale(), f.yScale(), f.noise());
        } else if (type == DensityFunctions.WeirdScaledSampler.class) {
            DensityFunctions.WeirdScaledSampler f = (DensityFunctions.WeirdScaledSampler) function;
            copy = new DensityFunctions.WeirdScaledSampler(done(childMapper.apply(f.input())), f.noise(), f.rarityValueMapper());
        } else if (type == DensityFunctions.BlendDensity.class) {
            copy = new DensityFunctions.BlendDensity(done(childMapper.apply(((DensityFunctions.BlendDensity) function).input())));
        } else if (type == DensityFunctions.Marker.class) {
            DensityFunctions.Marker f = (DensityFunctions.Marker) function;
            copy = new DensityFunctions.Marker(f.type(), done(childMapper.apply(f.wrapped())));
        } else if (type == DensityFunctions.HolderHolder.class) {
            copy = new DensityFunctions.HolderHolder(new net.minecraft.core.Holder.Direct<>(done(childMapper.apply(((DensityFunctions.HolderHolder) function).function().value()))));
        } else if (type == DensityFunctions.FindTopSurface.class) {
            DensityFunctions.FindTopSurface f = (DensityFunctions.FindTopSurface) function;
            DensityFunction density = done(childMapper.apply(f.density()));
            copy = new DensityFunctions.FindTopSurface(density, done(childMapper.apply(f.upperBound())), f.lowerBound(), f.cellHeight());
        } else if (type == DensityFunctions.Spline.class) {
            // Spline.mapAll with Coordinate.mapAll inlined: new Coordinate(new Holder.Direct<>(function.value().mapAll(visitor))), memoized
            return visitor.apply(new DensityFunctions.Spline(((DensityFunctions.Spline) function).spline().mapAll(
                coordinate -> new DensityFunctions.Spline.Coordinate(new net.minecraft.core.Holder.Direct<>(childMapper.apply(coordinate.function().value())))
            )));
        } else {
            copy = function;
        }
        return copy.mapAll(visitor);
    }

    private static DensityFunction done(DensityFunction mapped) {
        return new Done(mapped);
    }

    /** a child that is already mapped: only mapAll is ever called on it, by its parent's mapAll */
    private record Done(DensityFunction mapped) implements DensityFunction {
        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return this.mapped;
        }

        @Override
        public double compute(DensityFunction.FunctionContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void fillArray(double[] array, DensityFunction.ContextProvider contextProvider) {
            throw new UnsupportedOperationException();
        }

        @Override
        public double minValue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public double maxValue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException();
        }
    }

    // ---- shape description ------------------------------------------------------------------------------------------------

    /** tree shape; records give structural equals/hashCode, so equal shapes share one generated class */
    sealed interface Node permits Leaf, Bin, Xform, CtxXform, Range, Shifted {
        boolean pure();
    }

    /** a node called as it is: compute()/fillArray() on the original object (virtual when the class is reachable by name) */
    record Leaf(Class<?> type, boolean direct, boolean pure) implements Node {
    }

    /** Ap2: op = TwoArgumentSimpleFunction.Type ordinal (ADD, MUL, MIN, MAX) */
    record Bin(int op, Node a, Node b, boolean pure) implements Node {
    }

    /** PureTransformer (Clamp, Mapped, MulOrAdd): kind = Mapped.Type or MulOrAdd.Type ordinal, -1 for Clamp (transform() call) */
    record Xform(Class<?> type, int kind, Node in, boolean pure) implements Node {
    }

    /** TransformerWithContext (WeirdScaledSampler, BlendDensity): transform(context, double) of the original object */
    record CtxXform(Class<?> type, Node in, boolean pure) implements Node {
    }

    record Range(Node in, Node yes, Node no, boolean pure) implements Node {
    }

    record Shifted(Node x, Node y, Node z, boolean pure) implements Node {
    }

    private static final class TooBig extends RuntimeException {
        TooBig() {
            super(null, null, false, false);
        }
    }

    private static final int ADD = 0, MUL = 1, MIN = 2, MAX = 3;

    /** walks the tree in pre-order; every node's object goes to objects, MIN/MAX bounds to bounds, in the order generate() reads them */
    static final class Builder {
        final List<Object> objects = new ArrayList<>();
        final DoubleArrayList bounds = new DoubleArrayList();

        Node build(DensityFunction function) {
            if (this.objects.size() >= MAX_NODES) {
                throw new TooBig();
            }
            this.objects.add(function);
            Class<?> type = function.getClass();
            if (type == DensityFunctions.Ap2.class) {
                DensityFunctions.Ap2 ap2 = (DensityFunctions.Ap2) function;
                int op = ap2.type().ordinal();
                if (op == MIN) {
                    this.bounds.add(ap2.argument2().minValue());
                } else if (op == MAX) {
                    this.bounds.add(ap2.argument2().maxValue());
                }
                Node a = this.build(ap2.argument1());
                Node b = this.build(ap2.argument2());
                return new Bin(op, a, b, a.pure() && b.pure());
            } else if (type == DensityFunctions.MulOrAdd.class || type == DensityFunctions.Mapped.class || type == DensityFunctions.Clamp.class) {
                int kind = function instanceof DensityFunctions.Mapped mapped ? mapped.type().ordinal()
                    : function instanceof DensityFunctions.MulOrAdd mulOrAdd ? mulOrAdd.specificType().ordinal() : -1;
                Node in = this.build(((DensityFunctions.PureTransformer) function).input());
                return new Xform(type, kind, in, in.pure());
            } else if (type == DensityFunctions.WeirdScaledSampler.class || type == DensityFunctions.BlendDensity.class) {
                Node in = this.build(((DensityFunctions.TransformerWithContext) function).input());
                return new CtxXform(type, in, in.pure());
            } else if (type == DensityFunctions.RangeChoice.class) {
                DensityFunctions.RangeChoice range = (DensityFunctions.RangeChoice) function;
                Node in = this.build(range.input());
                Node yes = this.build(range.whenInRange());
                Node no = this.build(range.whenOutOfRange());
                return new Range(in, yes, no, in.pure() && yes.pure() && no.pure());
            } else if (type == DensityFunctions.ShiftedNoise.class) {
                DensityFunctions.ShiftedNoise shifted = (DensityFunctions.ShiftedNoise) function;
                Node x = this.build(shifted.shiftX());
                Node y = this.build(shifted.shiftY());
                Node z = this.build(shifted.shiftZ());
                return new Shifted(x, y, z, x.pure() && y.pure() && z.pure());
            } else {
                return new Leaf(type, directlyCallable(type), PURE_LEAVES.contains(type));
            }
        }
    }

    private static final Map<Class<?>, Boolean> DIRECT = new ConcurrentHashMap<>();

    private static boolean directlyCallable(Class<?> type) {
        return DIRECT.computeIfAbsent(type, t -> {
            if (t.isHidden() || t.getClassLoader() != DensityFunction.class.getClassLoader()) {
                return false;
            }
            try {
                LOOKUP.accessClass(t);
                return true;
            } catch (IllegalAccessException e) {
                return false;
            }
        });
    }

    // ---- code generation --------------------------------------------------------------------------------------------------

    record Template(MethodHandle constructor) {
    }

    private static Template generate(Node root) {
        try {
            byte[] bytes = new Generator(root).generate();
            MethodHandles.Lookup hidden = LOOKUP.defineHiddenClass(bytes, true);
            MethodHandle constructor = hidden.findConstructor(hidden.lookupClass(), CTOR_TYPE).asType(CTOR_CALL);
            return new Template(constructor);
        } catch (Throwable t) {
            if (!failureLogged) {
                failureLogged = true;
                LOGGER.warn("Density function compiler could not generate a class, using the interpreted function", t);
            }
            return new Template(null);
        }
    }

    private static final String SELF = "net/minecraft/world/level/levelgen/CompiledDensityFunction$Generated";
    private static final String BASE = Type.getInternalName(CompiledDensityFunction.class);
    private static final String DF = Type.getInternalName(DensityFunction.class);
    private static final String CTX = Type.getInternalName(DensityFunction.FunctionContext.class);
    private static final String PROVIDER = Type.getInternalName(DensityFunction.ContextProvider.class);
    private static final String CTX_D = "L" + CTX + ";";
    private static final String PROVIDER_D = "L" + PROVIDER + ";";
    private static final String COMPUTE_D = "(" + CTX_D + ")D";
    private static final String FILL_D = "([D" + PROVIDER_D + ")V";
    // node methods are private instance methods: a hidden class cannot name itself in a descriptor
    private static final String NODE_COMPUTE_D = COMPUTE_D;
    private static final String NODE_FILL_D = FILL_D;

    static final class Generator {
        private final Node root;
        private final Map<Node, Integer> slots = new IdentityHashMap<>();
        private final Map<Node, Integer> boundSlots = new IdentityHashMap<>();
        private final List<Node> order = new ArrayList<>();
        private final List<Integer> directIds = new ArrayList<>();
        private final Set<Node> fillNeeded = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        private int boundCount;
        private boolean fusedMode; // Knowledge - cell fusion: emitting the k<slot> compute methods of the fused cell loop
        private ClassWriter cw;

        Generator(Node root) {
            this.root = root;
            this.number(root);
        }

        /** same pre-order as Builder.build */
        private void number(Node node) {
            this.slots.put(node, this.order.size());
            this.order.add(node);
            switch (node) {
                case Bin bin -> {
                    if (bin.op() == MIN || bin.op() == MAX) {
                        this.boundSlots.put(node, this.boundCount++);
                    }
                    this.number(bin.a());
                    this.number(bin.b());
                }
                case Xform x -> this.number(x.in());
                case CtxXform x -> this.number(x.in());
                case Range r -> {
                    this.number(r.in());
                    this.number(r.yes());
                    this.number(r.no());
                }
                case Shifted s -> {
                    this.number(s.x());
                    this.number(s.y());
                    this.number(s.z());
                }
                case Leaf leaf -> {
                }
            }
        }

        private static boolean hasField(Node node) {
            return !(node instanceof Bin);
        }

        private static String fieldType(Node node) {
            return switch (node) {
                case Leaf leaf -> leaf.direct() ? Type.getDescriptor(leaf.type()) : "L" + DF + ";";
                case Xform x -> Type.getDescriptor(x.type());
                case CtxXform x -> Type.getDescriptor(x.type());
                case Range r -> Type.getDescriptor(DensityFunctions.RangeChoice.class);
                case Shifted s -> Type.getDescriptor(DensityFunctions.ShiftedNoise.class);
                case Bin b -> throw new IllegalStateException();
            };
        }

        byte[] generate() {
            this.cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected String getCommonSuperClass(String type1, String type2) {
                    return "java/lang/Object"; // only ever asked for unrelated types, which the code never merges
                }
            };
            this.cw.visit(Opcodes.V21, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, SELF, null, BASE, null);
            for (Node node : this.order) {
                if (hasField(node)) {
                    this.cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "o" + this.slots.get(node), fieldType(node), null, null).visitEnd();
                }
            }
            for (int i = 0; i < this.boundCount; i++) {
                this.cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "b" + i, "D", null, null).visitEnd();
            }
            this.constructor();

            // compute paths: every composite node gets c<slot>; fill paths only where reached
            for (Node node : this.order) {
                if (!(node instanceof Leaf)) {
                    this.computeMethod(node);
                }
            }
            this.markFill(this.root);
            for (Node node : this.order) {
                if (this.fillNeeded.contains(node)) {
                    this.fillMethod(node);
                }
            }

            MethodVisitor mv = this.cw.visitMethod(Opcodes.ACC_PUBLIC, "compute", COMPUTE_D, null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, SELF, "c" + this.slots.get(this.root), NODE_COMPUTE_D, false);
            mv.visitInsn(Opcodes.DRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();

            mv = this.cw.visitMethod(Opcodes.ACC_PUBLIC, "fillArray", FILL_D, null, null);
            mv.visitCode();
            if (!(this.root instanceof Leaf) && !this.root.pure() && cellFusable(this.root)) {
                // Knowledge - cell fusion: while a cell is filled (provider = the chunk itself, fillingCell) every node of this tree is a
                // pure function of the in-cell position (interpolators read their cell corners), so the whole tree is computed block by
                // block in one loop, like a pure subtree, instead of one array pass per node: the same values (see cellFusable)
                Label normal = new Label();
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
                mv.visitLdcInsn(Type.getObjectType(CHUNK));
                mv.visitJumpInsn(Opcodes.IF_ACMPNE, normal);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitTypeInsn(Opcodes.CHECKCAST, CHUNK);
                mv.visitFieldInsn(Opcodes.GETFIELD, CHUNK, "fillingCell", "Z");
                mv.visitJumpInsn(Opcodes.IFEQ, normal);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitTypeInsn(Opcodes.CHECKCAST, CHUNK);
                mv.visitFieldInsn(Opcodes.GETFIELD, CHUNK, "cellFusion", "Z");
                mv.visitJumpInsn(Opcodes.IFEQ, normal);
                this.fusedMode = true;
                this.directFill(mv, this.root);
                this.fusedMode = false;
                mv.visitLabel(normal);
            }
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, SELF, "f" + this.slots.get(this.root), NODE_FILL_D, false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();

            if (!(this.root instanceof Leaf) && !this.root.pure() && cellFusable(this.root)) {
                // Knowledge - cell fusion: the compute methods of the fused cell loop (same code, interpolators as cellValue())
                this.fusedMode = true;
                for (Node node : this.order) {
                    if (!(node instanceof Leaf)) {
                        this.computeMethod(node);
                    }
                }
                this.fusedMode = false;
            }
            this.computeNodeMethod();
            this.cw.visitEnd();
            return this.cw.toByteArray();
        }

        private void constructor() {
            MethodVisitor mv = this.cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", CTOR_TYPE.toMethodDescriptorString(), null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, BASE, "<init>", "(L" + DF + ";)V", false);
            for (Node node : this.order) {
                if (hasField(node)) {
                    int slot = this.slots.get(node);
                    String desc = fieldType(node);
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitVarInsn(Opcodes.ALOAD, 2);
                    pushInt(mv, slot);
                    mv.visitInsn(Opcodes.AALOAD);
                    mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getType(desc).getInternalName());
                    mv.visitFieldInsn(Opcodes.PUTFIELD, SELF, "o" + slot, desc);
                }
            }
            for (int i = 0; i < this.boundCount; i++) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                pushInt(mv, i);
                mv.visitInsn(Opcodes.DALOAD);
                mv.visitFieldInsn(Opcodes.PUTFIELD, SELF, "b" + i, "D");
            }
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private void loadField(MethodVisitor mv, Node node) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, SELF, "o" + this.slots.get(node), fieldType(node));
        }

        /** pushes node.compute(context); pushContext pushes the context (evaluated exactly once) */
        private void emitCompute(MethodVisitor mv, Node node, Runnable pushContext) {
            if (this.fusedMode && isInterpolator(node)) {
                // Knowledge - cell fusion: in the fused cell loop an interpolator's compute(chunk) is its cell lerp, see NoiseInterpolator.cellValue
                this.loadField(mv, node);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(NoiseChunk.NoiseInterpolator.class), "cellValue", "()D", false);
                return;
            }
            if (node instanceof Leaf leaf) {
                this.loadField(mv, node);
                pushContext.run();
                if (leaf.direct()) {
                    String owner = Type.getInternalName(leaf.type());
                    mv.visitMethodInsn(leaf.type().isInterface() ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, owner, "compute", COMPUTE_D, leaf.type().isInterface());
                } else {
                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, DF, "compute", COMPUTE_D, true);
                }
            } else {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                pushContext.run();
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, SELF, (this.fusedMode ? "k" : "c") + this.slots.get(node), NODE_COMPUTE_D, false);
            }
        }

        /** node.fillArray(array in local arrayLocal, provider in local 2) */
        private void emitFill(MethodVisitor mv, Node node, int arrayLocal) {
            if (node instanceof Leaf leaf && !this.fillNeeded.contains(node)) {
                this.loadField(mv, node);
                mv.visitVarInsn(Opcodes.ALOAD, arrayLocal);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                if (leaf.direct()) {
                    String owner = Type.getInternalName(leaf.type());
                    mv.visitMethodInsn(leaf.type().isInterface() ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, owner, "fillArray", FILL_D, leaf.type().isInterface());
                } else {
                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, DF, "fillArray", FILL_D, true);
                }
            } else {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, arrayLocal);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, SELF, "f" + this.slots.get(node), NODE_FILL_D, false);
            }
        }

        private void computeMethod(Node node) {
            MethodVisitor mv = this.cw.visitMethod(Opcodes.ACC_PRIVATE, (this.fusedMode ? "k" : "c") + this.slots.get(node), NODE_COMPUTE_D, null, null);
            mv.visitCode();
            Runnable ctx = () -> mv.visitVarInsn(Opcodes.ALOAD, 1);
            switch (node) {
                case Bin bin -> {
                    // vanilla Ap2.compute: d = argument1; ADD d + a2, MUL d == 0 ? 0 : d * a2, MIN d < a2.min ? d : min(d, a2), MAX mirrored
                    this.emitCompute(mv, bin.a(), ctx);
                    if (bin.op() == ADD) {
                        this.emitCompute(mv, bin.b(), ctx);
                        mv.visitInsn(Opcodes.DADD);
                        mv.visitInsn(Opcodes.DRETURN);
                    } else {
                        mv.visitVarInsn(Opcodes.DSTORE, 2);
                        Label other = new Label();
                        mv.visitVarInsn(Opcodes.DLOAD, 2);
                        if (bin.op() == MUL) {
                            mv.visitInsn(Opcodes.DCONST_0);
                            mv.visitInsn(Opcodes.DCMPL);
                            mv.visitJumpInsn(Opcodes.IFNE, other);
                            mv.visitInsn(Opcodes.DCONST_0);
                            mv.visitInsn(Opcodes.DRETURN);
                            mv.visitLabel(other);
                            mv.visitVarInsn(Opcodes.DLOAD, 2);
                            this.emitCompute(mv, bin.b(), ctx);
                            mv.visitInsn(Opcodes.DMUL);
                            mv.visitInsn(Opcodes.DRETURN);
                        } else {
                            this.loadBound(mv, bin);
                            if (bin.op() == MIN) {
                                mv.visitInsn(Opcodes.DCMPG);
                                mv.visitJumpInsn(Opcodes.IFGE, other);
                            } else {
                                mv.visitInsn(Opcodes.DCMPL);
                                mv.visitJumpInsn(Opcodes.IFLE, other);
                            }
                            mv.visitVarInsn(Opcodes.DLOAD, 2);
                            mv.visitInsn(Opcodes.DRETURN);
                            mv.visitLabel(other);
                            mv.visitVarInsn(Opcodes.DLOAD, 2);
                            this.emitCompute(mv, bin.b(), ctx);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", bin.op() == MIN ? "min" : "max", "(DD)D", false);
                            mv.visitInsn(Opcodes.DRETURN);
                        }
                    }
                }
                case Xform x -> {
                    this.emitCompute(mv, x.in(), ctx);
                    mv.visitVarInsn(Opcodes.DSTORE, 2);
                    this.emitTransform(mv, x, 2, 4);
                    mv.visitInsn(Opcodes.DRETURN);
                }
                case CtxXform x -> {
                    this.loadField(mv, node);
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    this.emitCompute(mv, x.in(), ctx);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(x.type()), "transform", "(" + CTX_D + "D)D", false);
                    mv.visitInsn(Opcodes.DRETURN);
                }
                case Range r -> {
                    // vanilla RangeChoice.compute: d >= minInclusive && d < maxExclusive ? whenInRange : whenOutOfRange
                    this.emitCompute(mv, r.in(), ctx);
                    mv.visitVarInsn(Opcodes.DSTORE, 2);
                    Label out = new Label();
                    this.emitRangeTest(mv, node, 2, out);
                    this.emitCompute(mv, r.yes(), ctx);
                    mv.visitInsn(Opcodes.DRETURN);
                    mv.visitLabel(out);
                    this.emitCompute(mv, r.no(), ctx);
                    mv.visitInsn(Opcodes.DRETURN);
                }
                case Shifted s -> {
                    // vanilla ShiftedNoise.compute: noise.getValue(x * xzScale + shiftX, y * yScale + shiftY, z * xzScale + shiftZ)
                    String sn = Type.getInternalName(DensityFunctions.ShiftedNoise.class);
                    String holder = Type.getInternalName(DensityFunction.NoiseHolder.class);
                    this.loadField(mv, node);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, sn, "noise", "()L" + holder + ";", false);
                    String[] axes = {"blockX", "blockY", "blockZ"};
                    String[] scales = {"xzScale", "yScale", "xzScale"};
                    Node[] shifts = {s.x(), s.y(), s.z()};
                    for (int i = 0; i < 3; i++) {
                        mv.visitVarInsn(Opcodes.ALOAD, 1);
                        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, CTX, axes[i], "()I", true);
                        mv.visitInsn(Opcodes.I2D);
                        this.loadField(mv, node);
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, sn, scales[i], "()D", false);
                        mv.visitInsn(Opcodes.DMUL);
                        this.emitCompute(mv, shifts[i], ctx);
                        mv.visitInsn(Opcodes.DADD);
                    }
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, holder, "getValue", "(DDD)D", false);
                    mv.visitInsn(Opcodes.DRETURN);
                }
                case Leaf leaf -> throw new IllegalStateException();
            }
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        /**
         * pushes transform(value in local valueLocal), written like DensityFunctions.Mapped.transform / MulOrAdd.transform
         * (temp local tempLocal); Clamp calls its own transform (Mth.clamp with its bounds)
         */
        private void emitTransform(MethodVisitor mv, Xform x, int v, int temp) {
            if (x.type() == DensityFunctions.Mapped.class) {
                Label otherwise = new Label(), done = new Label();
                switch (DensityFunctions.Mapped.Type.values()[x.kind()]) {
                    case ABS -> { // Math.abs(value)
                        mv.visitVarInsn(Opcodes.DLOAD, v);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "abs", "(D)D", false);
                    }
                    case SQUARE -> { // value * value
                        mv.visitVarInsn(Opcodes.DLOAD, v);
                        mv.visitVarInsn(Opcodes.DLOAD, v);
                        mv.visitInsn(Opcodes.DMUL);
                    }
                    case CUBE -> { // value * value * value
                        mv.visitVarInsn(Opcodes.DLOAD, v);
                        mv.visitVarInsn(Opcodes.DLOAD, v);
                        mv.visitInsn(Opcodes.DMUL);
                        mv.visitVarInsn(Opcodes.DLOAD, v);
                        mv.visitInsn(Opcodes.DMUL);
                    }
                    case HALF_NEGATIVE, QUARTER_NEGATIVE -> { // value > 0.0 ? value : value * 0.5 (0.25)
                        mv.visitVarInsn(Opcodes.DLOAD, v);
                        mv.visitInsn(Opcodes.DCONST_0);
                        mv.visitInsn(Opcodes.DCMPL);
                        mv.visitJumpInsn(Opcodes.IFLE, otherwise);
                        mv.visitVarInsn(Opcodes.DLOAD, v);
                        mv.visitJumpInsn(Opcodes.GOTO, done);
                        mv.visitLabel(otherwise);
                        mv.visitVarInsn(Opcodes.DLOAD, v);
                        mv.visitLdcInsn(x.kind() == DensityFunctions.Mapped.Type.HALF_NEGATIVE.ordinal() ? 0.5 : 0.25);
                        mv.visitInsn(Opcodes.DMUL);
                        mv.visitLabel(done);
                    }
                    case INVERT -> { // 1.0 / value
                        mv.visitInsn(Opcodes.DCONST_1);
                        mv.visitVarInsn(Opcodes.DLOAD, v);
                        mv.visitInsn(Opcodes.DDIV);
                    }
                    case SQUEEZE -> { // d = Mth.clamp(value, -1.0, 1.0); d / 2.0 - d * d * d / 24.0
                        mv.visitVarInsn(Opcodes.DLOAD, v);
                        mv.visitLdcInsn(-1.0);
                        mv.visitInsn(Opcodes.DCONST_1);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/util/Mth", "clamp", "(DDD)D", false);
                        mv.visitVarInsn(Opcodes.DSTORE, temp);
                        mv.visitVarInsn(Opcodes.DLOAD, temp);
                        mv.visitLdcInsn(2.0);
                        mv.visitInsn(Opcodes.DDIV);
                        mv.visitVarInsn(Opcodes.DLOAD, temp);
                        mv.visitVarInsn(Opcodes.DLOAD, temp);
                        mv.visitInsn(Opcodes.DMUL);
                        mv.visitVarInsn(Opcodes.DLOAD, temp);
                        mv.visitInsn(Opcodes.DMUL);
                        mv.visitLdcInsn(24.0);
                        mv.visitInsn(Opcodes.DDIV);
                        mv.visitInsn(Opcodes.DSUB);
                    }
                }
            } else if (x.type() == DensityFunctions.MulOrAdd.class) { // MUL: value * argument, ADD: value + argument
                mv.visitVarInsn(Opcodes.DLOAD, v);
                this.loadField(mv, x);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(DensityFunctions.MulOrAdd.class), "argument", "()D", false);
                mv.visitInsn(x.kind() == DensityFunctions.MulOrAdd.Type.MUL.ordinal() ? Opcodes.DMUL : Opcodes.DADD);
            } else {
                this.loadField(mv, x);
                mv.visitVarInsn(Opcodes.DLOAD, v);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(x.type()), "transform", "(D)D", false);
            }
        }

        /** jumps to out unless (local d >= minInclusive && d < maxExclusive), NaN goes out like in Java */
        private void emitRangeTest(MethodVisitor mv, Node range, int local, Label out) {
            String rc = Type.getInternalName(DensityFunctions.RangeChoice.class);
            mv.visitVarInsn(Opcodes.DLOAD, local);
            this.loadField(mv, range);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, rc, "minInclusive", "()D", false);
            mv.visitInsn(Opcodes.DCMPL);
            mv.visitJumpInsn(Opcodes.IFLT, out);
            mv.visitVarInsn(Opcodes.DLOAD, local);
            this.loadField(mv, range);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, rc, "maxExclusive", "()D", false);
            mv.visitInsn(Opcodes.DCMPG);
            mv.visitJumpInsn(Opcodes.IFGE, out);
        }

        private void loadBound(MethodVisitor mv, Node bin) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, SELF, "b" + this.boundSlots.get(bin), "D");
        }

        /** a node whose vanilla fillArray is fillAllDirectly(this), or a composite whose values only depend on the position */
        private static boolean fillsDirectly(Node node) {
            if (node instanceof Leaf leaf) {
                return leaf.direct() && FILL_DIRECTLY_LEAVES.contains(leaf.type());
            }
            return node instanceof Shifted || node.pure();
        }

        /**
         * Knowledge - cell fusion: every leaf is a pure leaf, an interpolator (in a cell fill: the lerp of its corners at the in-cell
         * position) or a FlatCache (a table by x/z); then each node's value at a block only depends on the block, and computing the
         * tree per block gives what the per-node array fills give (same compute()/fill() operations per value, as for pure subtrees).
         */
        private static boolean cellFusable(Node node) {
            return switch (node) {
                case Leaf leaf -> leaf.pure() || isInterpolator(leaf) || leaf.type() == NoiseChunk.FlatCache.class;
                case Bin bin -> cellFusable(bin.a()) && cellFusable(bin.b());
                case Xform x -> cellFusable(x.in());
                case CtxXform x -> cellFusable(x.in());
                case Range r -> cellFusable(r.in()) && cellFusable(r.yes()) && cellFusable(r.no());
                case Shifted s -> cellFusable(s.x()) && cellFusable(s.y()) && cellFusable(s.z());
            };
        }

        private static boolean isInterpolator(Node node) {
            return node instanceof Leaf leaf && leaf.type() == NoiseChunk.NoiseInterpolator.class;
        }

        private void markFill(Node node) {
            if ((node instanceof Leaf && !fillsDirectly(node) && !isInterpolator(node)) || !this.fillNeeded.add(node)) {
                return;
            }
            if (isInterpolator(node)) {
                this.directIds.add(this.slots.get(node));
                return;
            }
            if (fillsDirectly(node)) {
                this.directIds.add(this.slots.get(node));
                return;
            }
            switch (node) {
                case Bin bin -> {
                    this.markFill(bin.a());
                    if (bin.op() == ADD) {
                        this.markFill(bin.b());
                    }
                }
                case Xform x -> this.markFill(x.in());
                case CtxXform x -> this.markFill(x.in());
                case Range r -> this.markFill(r.in());
                default -> {
                }
            }
        }

        private void fillMethod(Node node) {
            MethodVisitor mv = this.cw.visitMethod(Opcodes.ACC_PRIVATE, "f" + this.slots.get(node), NODE_FILL_D, null, null);
            mv.visitCode();
            // locals: 0 self, 1 array, 2 provider, 3 i, 4-5 d, 6-7 bound, 8 temp array
            Runnable forIndex = () -> {
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitVarInsn(Opcodes.ILOAD, 3);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PROVIDER, "forIndex", "(I)" + CTX_D, true);
            };
            if (isInterpolator(node)) {
                // NoiseInterpolator.fillArray: fillingCell ? provider.fillAllDirectly(array, this) : wrapped().fillArray(array, provider)
                String interpolator = Type.getInternalName(NoiseChunk.NoiseInterpolator.class);
                Label notFilling = new Label();
                this.loadField(mv, node);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, interpolator, "noiseChunk", "()L" + CHUNK + ";", false);
                mv.visitFieldInsn(Opcodes.GETFIELD, CHUNK, "fillingCell", "Z");
                mv.visitJumpInsn(Opcodes.IFEQ, notFilling);
                // Knowledge - cell interpolation: the interpolator fills the whole cell itself when it can (same values, see fillCell)
                Label slowFill = new Label();
                this.loadField(mv, node);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, interpolator, "fillCell", "([D" + PROVIDER_D + ")Z", false);
                mv.visitJumpInsn(Opcodes.IFEQ, slowFill);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitLabel(slowFill);
                this.directFill(mv, node);
                mv.visitLabel(notFilling);
                this.loadField(mv, node);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, interpolator, "fillArray", FILL_D, false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
                return;
            }
            if (fillsDirectly(node)) {
                this.directFill(mv, node);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
                return;
            }
            switch (node) {
                case Bin bin -> {
                    this.emitFill(mv, bin.a(), 1);
                    if (bin.op() == ADD) {
                        // vanilla: double[] doubles = new double[array.length]; argument2.fillArray(doubles); array[i] += doubles[i]
                        mv.visitVarInsn(Opcodes.ALOAD, 1);
                        mv.visitInsn(Opcodes.ARRAYLENGTH);
                        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_DOUBLE);
                        mv.visitVarInsn(Opcodes.ASTORE, 8);
                        this.emitFill(mv, bin.b(), 8);
                        this.loop(mv, () -> {
                            mv.visitVarInsn(Opcodes.ALOAD, 1);
                            mv.visitVarInsn(Opcodes.ILOAD, 3);
                            mv.visitInsn(Opcodes.DUP2);
                            mv.visitInsn(Opcodes.DALOAD);
                            mv.visitVarInsn(Opcodes.ALOAD, 8);
                            mv.visitVarInsn(Opcodes.ILOAD, 3);
                            mv.visitInsn(Opcodes.DALOAD);
                            mv.visitInsn(Opcodes.DADD);
                            mv.visitInsn(Opcodes.DASTORE);
                        });
                    } else {
                        if (bin.op() != MUL) {
                            this.loadBound(mv, bin);
                            mv.visitVarInsn(Opcodes.DSTORE, 6);
                        }
                        this.loop(mv, () -> {
                            mv.visitVarInsn(Opcodes.ALOAD, 1);
                            mv.visitVarInsn(Opcodes.ILOAD, 3);
                            mv.visitInsn(Opcodes.DALOAD);
                            mv.visitVarInsn(Opcodes.DSTORE, 4);
                            mv.visitVarInsn(Opcodes.ALOAD, 1);
                            mv.visitVarInsn(Opcodes.ILOAD, 3);
                            Label other = new Label();
                            Label done = new Label();
                            mv.visitVarInsn(Opcodes.DLOAD, 4);
                            if (bin.op() == MUL) {
                                // array[i] = d == 0.0 ? 0.0 : d * argument2.compute(forIndex(i))
                                mv.visitInsn(Opcodes.DCONST_0);
                                mv.visitInsn(Opcodes.DCMPL);
                                mv.visitJumpInsn(Opcodes.IFNE, other);
                                mv.visitInsn(Opcodes.DCONST_0);
                                mv.visitJumpInsn(Opcodes.GOTO, done);
                                mv.visitLabel(other);
                                mv.visitVarInsn(Opcodes.DLOAD, 4);
                                this.emitCompute(mv, bin.b(), forIndex);
                                mv.visitInsn(Opcodes.DMUL);
                            } else {
                                // array[i] = d < bound ? d : Math.min(d, argument2.compute(forIndex(i))), max mirrored
                                mv.visitVarInsn(Opcodes.DLOAD, 6);
                                if (bin.op() == MIN) {
                                    mv.visitInsn(Opcodes.DCMPG);
                                    mv.visitJumpInsn(Opcodes.IFGE, other);
                                } else {
                                    mv.visitInsn(Opcodes.DCMPL);
                                    mv.visitJumpInsn(Opcodes.IFLE, other);
                                }
                                mv.visitVarInsn(Opcodes.DLOAD, 4);
                                mv.visitJumpInsn(Opcodes.GOTO, done);
                                mv.visitLabel(other);
                                mv.visitVarInsn(Opcodes.DLOAD, 4);
                                this.emitCompute(mv, bin.b(), forIndex);
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", bin.op() == MIN ? "min" : "max", "(DD)D", false);
                            }
                            mv.visitLabel(done);
                            mv.visitInsn(Opcodes.DASTORE);
                        });
                    }
                }
                case Xform x -> {
                    // PureTransformer.fillArray: input.fillArray(array); array[i] = transform(array[i])
                    this.emitFill(mv, x.in(), 1);
                    this.loop(mv, () -> {
                        mv.visitVarInsn(Opcodes.ALOAD, 1);
                        mv.visitVarInsn(Opcodes.ILOAD, 3);
                        mv.visitInsn(Opcodes.DALOAD);
                        mv.visitVarInsn(Opcodes.DSTORE, 4);
                        mv.visitVarInsn(Opcodes.ALOAD, 1);
                        mv.visitVarInsn(Opcodes.ILOAD, 3);
                        this.emitTransform(mv, x, 4, 6);
                        mv.visitInsn(Opcodes.DASTORE);
                    });
                }
                case CtxXform x -> {
                    // TransformerWithContext.fillArray: input.fillArray(array); array[i] = transform(forIndex(i), array[i])
                    this.emitFill(mv, x.in(), 1);
                    this.loop(mv, () -> {
                        mv.visitVarInsn(Opcodes.ALOAD, 1);
                        mv.visitVarInsn(Opcodes.ILOAD, 3);
                        this.loadField(mv, node);
                        forIndex.run();
                        mv.visitVarInsn(Opcodes.ALOAD, 1);
                        mv.visitVarInsn(Opcodes.ILOAD, 3);
                        mv.visitInsn(Opcodes.DALOAD);
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(x.type()), "transform", "(" + CTX_D + "D)D", false);
                        mv.visitInsn(Opcodes.DASTORE);
                    });
                }
                case Range r -> {
                    // RangeChoice.fillArray: input.fillArray(array); array[i] = in range ? whenInRange(forIndex(i)) : whenOutOfRange(forIndex(i))
                    this.emitFill(mv, r.in(), 1);
                    this.loop(mv, () -> {
                        mv.visitVarInsn(Opcodes.ALOAD, 1);
                        mv.visitVarInsn(Opcodes.ILOAD, 3);
                        mv.visitInsn(Opcodes.DALOAD);
                        mv.visitVarInsn(Opcodes.DSTORE, 4);
                        Label out = new Label();
                        Label next = new Label();
                        this.emitRangeTest(mv, node, 4, out);
                        mv.visitVarInsn(Opcodes.ALOAD, 1);
                        mv.visitVarInsn(Opcodes.ILOAD, 3);
                        this.emitCompute(mv, r.yes(), forIndex);
                        mv.visitInsn(Opcodes.DASTORE);
                        mv.visitJumpInsn(Opcodes.GOTO, next);
                        mv.visitLabel(out);
                        mv.visitVarInsn(Opcodes.ALOAD, 1);
                        mv.visitVarInsn(Opcodes.ILOAD, 3);
                        this.emitCompute(mv, r.no(), forIndex);
                        mv.visitInsn(Opcodes.DASTORE);
                        mv.visitLabel(next);
                    });
                }
                default -> throw new IllegalStateException();
            }
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private static final String CHUNK = Type.getInternalName(NoiseChunk.class);
        private static final String SLICE = Type.getInternalName(NoiseChunk.SliceFillingContextProvider.class);

        /**
         * provider.fillAllDirectly(array, node) written out for NoiseChunk and its slice filler, the same field writes in the same order
         * as NoiseChunk.fillAllDirectly and SliceFillingContextProvider.fillAllDirectly, with the node computed directly;
         * other providers go through CompiledDensityFunction.fillDirect. Locals: 3 chunk, 4 y or i, 5 x, 6 z, 7 index.
         */
        private void directFill(MethodVisitor mv, Node node) {
            Runnable ctx = () -> mv.visitVarInsn(Opcodes.ALOAD, 3);
            Label slice = new Label();
            Label other = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
            mv.visitLdcInsn(Type.getObjectType(CHUNK));
            mv.visitJumpInsn(Opcodes.IF_ACMPNE, slice);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitTypeInsn(Opcodes.CHECKCAST, CHUNK);
            mv.visitVarInsn(Opcodes.ASTORE, 3);
            // arrayIndex = 0; for (y = cellHeight - 1; y >= 0; y--) { inCellY = y; for (x < cellWidth) { inCellX = x; for (z < cellWidth) { inCellZ = z; values[arrayIndex++] = compute } } }
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitFieldInsn(Opcodes.PUTFIELD, CHUNK, "arrayIndex", "I");
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitFieldInsn(Opcodes.GETFIELD, CHUNK, "cellHeight", "I");
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.ISUB);
            mv.visitVarInsn(Opcodes.ISTORE, 4);
            Label yTest = new Label(), yEnd = new Label(), xTest = new Label(), xEnd = new Label(), zTest = new Label(), zEnd = new Label();
            mv.visitLabel(yTest);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitJumpInsn(Opcodes.IFLT, yEnd);
            this.putChunkInt(mv, "inCellY", 4);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 5);
            mv.visitLabel(xTest);
            mv.visitVarInsn(Opcodes.ILOAD, 5);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitFieldInsn(Opcodes.GETFIELD, CHUNK, "cellWidth", "I");
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, xEnd);
            this.putChunkInt(mv, "inCellX", 5);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 6);
            mv.visitLabel(zTest);
            mv.visitVarInsn(Opcodes.ILOAD, 6);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitFieldInsn(Opcodes.GETFIELD, CHUNK, "cellWidth", "I");
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, zEnd);
            this.putChunkInt(mv, "inCellZ", 6);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitFieldInsn(Opcodes.GETFIELD, CHUNK, "arrayIndex", "I");
            mv.visitVarInsn(Opcodes.ISTORE, 7);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitVarInsn(Opcodes.ILOAD, 7);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IADD);
            mv.visitFieldInsn(Opcodes.PUTFIELD, CHUNK, "arrayIndex", "I"); // bumped before compute, as in NoiseChunk
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ILOAD, 7);
            this.emitCompute(mv, node, ctx);
            mv.visitInsn(Opcodes.DASTORE);
            mv.visitIincInsn(6, 1);
            mv.visitJumpInsn(Opcodes.GOTO, zTest);
            mv.visitLabel(zEnd);
            mv.visitIincInsn(5, 1);
            mv.visitJumpInsn(Opcodes.GOTO, xTest);
            mv.visitLabel(xEnd);
            mv.visitIincInsn(4, -1);
            mv.visitJumpInsn(Opcodes.GOTO, yTest);
            mv.visitLabel(yEnd);
            mv.visitInsn(Opcodes.RETURN);

            // slice filler: for (i = 0; i < cellCountY + 1; i++) { cellStartBlockY = (i + cellNoiseMinY) * cellHeight; interpolationCounter++; inCellY = 0; arrayIndex = i; values[i] = compute }
            mv.visitLabel(slice);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitTypeInsn(Opcodes.INSTANCEOF, SLICE);
            mv.visitJumpInsn(Opcodes.IFEQ, other);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitTypeInsn(Opcodes.CHECKCAST, SLICE);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SLICE, "noiseChunk", "()L" + CHUNK + ";", false);
            mv.visitVarInsn(Opcodes.ASTORE, 3);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 4);
            Label iTest = new Label(), iEnd = new Label();
            mv.visitLabel(iTest);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitFieldInsn(Opcodes.GETFIELD, CHUNK, "cellCountY", "I");
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IADD);
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, iEnd);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitFieldInsn(Opcodes.GETFIELD, CHUNK, "cellNoiseMinY", "I");
            mv.visitInsn(Opcodes.IADD);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitFieldInsn(Opcodes.GETFIELD, CHUNK, "cellHeight", "I");
            mv.visitInsn(Opcodes.IMUL);
            mv.visitFieldInsn(Opcodes.PUTFIELD, CHUNK, "cellStartBlockY", "I");
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitInsn(Opcodes.DUP);
            mv.visitFieldInsn(Opcodes.GETFIELD, CHUNK, "interpolationCounter", "J");
            mv.visitInsn(Opcodes.LCONST_1);
            mv.visitInsn(Opcodes.LADD);
            mv.visitFieldInsn(Opcodes.PUTFIELD, CHUNK, "interpolationCounter", "J");
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitFieldInsn(Opcodes.PUTFIELD, CHUNK, "inCellY", "I");
            this.putChunkInt(mv, "arrayIndex", 4);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            this.emitCompute(mv, node, ctx);
            mv.visitInsn(Opcodes.DASTORE);
            mv.visitIincInsn(4, 1);
            mv.visitJumpInsn(Opcodes.GOTO, iTest);
            mv.visitLabel(iEnd);
            mv.visitInsn(Opcodes.RETURN);

            mv.visitLabel(other);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            pushInt(mv, this.slots.get(node));
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SELF, "fillDirect", "([D" + PROVIDER_D + "I)V", false);
            mv.visitInsn(Opcodes.RETURN);
        }

        private void putChunkInt(MethodVisitor mv, String field, int local) {
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitVarInsn(Opcodes.ILOAD, local);
            mv.visitFieldInsn(Opcodes.PUTFIELD, CHUNK, field, "I");
        }

        /** for (i = 0; i < array.length; i++) body */
        private void loop(MethodVisitor mv, Runnable body) {
            Label test = new Label();
            Label end = new Label();
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 3);
            mv.visitLabel(test);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitInsn(Opcodes.ARRAYLENGTH);
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, end);
            body.run();
            mv.visitIincInsn(3, 1);
            mv.visitJumpInsn(Opcodes.GOTO, test);
            mv.visitLabel(end);
        }

        private void computeNodeMethod() {
            MethodVisitor mv = this.cw.visitMethod(Opcodes.ACC_PROTECTED, "computeNode", "(I" + CTX_D + ")D", null, null);
            mv.visitCode();
            int[] keys = this.directIds.stream().mapToInt(Integer::intValue).sorted().distinct().toArray();
            Label fail = new Label();
            if (keys.length > 0) {
                Label[] labels = new Label[keys.length];
                for (int i = 0; i < keys.length; i++) {
                    labels[i] = new Label();
                }
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                mv.visitLookupSwitchInsn(fail, keys, labels);
                for (int i = 0; i < keys.length; i++) {
                    mv.visitLabel(labels[i]);
                    this.emitCompute(mv, this.order.get(keys[i]), () -> mv.visitVarInsn(Opcodes.ALOAD, 2));
                    mv.visitInsn(Opcodes.DRETURN);
                }
            }
            mv.visitLabel(fail);
            mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false);
            mv.visitInsn(Opcodes.ATHROW);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private static void pushInt(MethodVisitor mv, int value) {
            if (value >= -1 && value <= 5) {
                mv.visitInsn(Opcodes.ICONST_0 + value);
            } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
                mv.visitIntInsn(Opcodes.BIPUSH, value);
            } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
                mv.visitIntInsn(Opcodes.SIPUSH, value);
            } else {
                mv.visitLdcInsn(value);
            }
        }
    }
}
