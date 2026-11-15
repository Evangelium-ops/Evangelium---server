package net.minecraft.world.level.levelgen;

import com.mojang.logging.LogUtils;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.block.state.BlockState;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;

/**
 * Knowledge - surface rule compiler. SurfaceSystem.buildSurface asks the chunk's surface rule tree (sequences, condition tests,
 * block results) for every stone block near the surface; walking that tree costs one megamorphic interface call per node.
 * This turns the applied tree into one generated hidden class per tree shape: sequences become straight-line code, a test
 * becomes an if, a block result becomes a return, a "not" becomes a negation. Bit-identical by construction:
 * <ul>
 * <li>every condition stays the real condition object of this chunk's tree (with its own lazy per-block/per-column cache), and
 * the generated code calls its test() in exactly the order and with exactly the short-circuits of the vanilla walk;</li>
 * <li>rule kinds it does not know (bandlands, plugin rules) stay calls to their own tryApply.</li>
 * </ul>
 * Calls go to the exact class of each condition, so the JIT can inline them. Classes are cached by tree shape (node kinds and
 * leaf classes, never values); any failure returns the original tree.
 */
public final class SurfaceRuleCompiler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final int MAX_NODES = 20000;
    private static final int MAX_TEMPLATES = 1000;
    private static final int SPLIT_NODES = 40; // sequences bigger than this get their own method
    private static final int METHOD_NODES = 300; // and no method inlines more nodes than this, far below the JIT's 8000 byte limit
    private static final Map<String, MethodHandle> TEMPLATES = new ConcurrentHashMap<>();
    private static final MethodHandle NONE = MethodHandles.constant(Object.class, null);
    private static final MethodType CTOR_CALL = MethodType.methodType(SurfaceRules.SurfaceRule.class, Object[].class);
    private static final String SELF = "net/minecraft/world/level/levelgen/SurfaceRuleCompiler$Generated";
    private static final String RULE = Type.getInternalName(SurfaceRules.SurfaceRule.class);
    private static final String CONDITION = Type.getInternalName(SurfaceRules.Condition.class);
    private static final String STATE = Type.getDescriptor(BlockState.class);
    private static final String APPLY = "(III)" + STATE;
    private static volatile boolean failureLogged;

    private SurfaceRuleCompiler() {
    }

    // ---- tree description -------------------------------------------------------------------------------------------------

    sealed interface Node permits Seq, Test, Not, Leaf {
    }

    record Seq(List<Node> rules, int size) implements Node {
    }

    record Test(Node condition, Node then, int size) implements Node {
    }

    record Not(Node target) implements Node {
    }

    /** a field of the generated class: a block result (state), a condition or a rule called through its own method */
    record Leaf(int field, Class<?> type, int kind) implements Node {
        static final int KIND_STATE = 0, KIND_CONDITION = 1, KIND_RULE = 2;
    }

    private static final class Walk {
        final List<Object> args = new ArrayList<>();
        final List<Class<?>> types = new ArrayList<>();
        final StringBuilder key = new StringBuilder(4096);
        int nodes;

        Node rule(SurfaceRules.SurfaceRule rule) {
            if (++this.nodes > MAX_NODES) throw new TooBig();
            if (rule instanceof SurfaceRules.SequenceRule(List<SurfaceRules.SurfaceRule> rules)) {
                this.key.append("S(");
                List<Node> children = new ArrayList<>(rules.size());
                int size = 1;
                for (SurfaceRules.SurfaceRule child : rules) {
                    Node node = this.rule(child);
                    children.add(node);
                    size += size(node);
                }
                this.key.append(')');
                return new Seq(children, size);
            }
            if (rule instanceof SurfaceRules.TestRule(SurfaceRules.Condition condition, SurfaceRules.SurfaceRule followup)) {
                this.key.append("T(");
                Node test = this.condition(condition);
                this.key.append(',');
                Node then = this.rule(followup);
                this.key.append(')');
                return new Test(test, then, 1 + size(test) + size(then));
            }
            if (rule instanceof SurfaceRules.StateRule(BlockState state) && state != null) {
                this.key.append('K');
                return this.leaf(state, BlockState.class, Leaf.KIND_STATE);
            }
            Class<?> type = accessible(rule.getClass()) ? rule.getClass() : SurfaceRules.SurfaceRule.class;
            this.key.append("R[").append(type.getName()).append(']');
            return this.leaf(rule, type, Leaf.KIND_RULE);
        }

        Node condition(SurfaceRules.Condition condition) {
            if (++this.nodes > MAX_NODES) throw new TooBig();
            if (condition instanceof SurfaceRules.NotCondition(SurfaceRules.Condition target)) {
                this.key.append('!');
                return new Not(this.condition(target));
            }
            Class<?> type = accessible(condition.getClass()) ? condition.getClass() : SurfaceRules.Condition.class;
            this.key.append("C[").append(type.getName()).append(']');
            return this.leaf(condition, type, Leaf.KIND_CONDITION);
        }

        Leaf leaf(Object value, Class<?> type, int kind) {
            this.args.add(value);
            this.types.add(type);
            return new Leaf(this.args.size() - 1, type, kind);
        }
    }

    private static final class TooBig extends RuntimeException {
        TooBig() {
            super(null, null, false, false);
        }
    }

    static int size(Node node) {
        return switch (node) {
            case Seq s -> s.size();
            case Test t -> t.size();
            case Not n -> 1 + size(n.target());
            case Leaf l -> 1;
        };
    }

    static boolean accessible(Class<?> type) {
        if (type.isHidden() || type.getClassLoader() != SurfaceRules.class.getClassLoader()) {
            return false;
        }
        try {
            LOOKUP.accessClass(type);
            return true;
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    // ---- entry ------------------------------------------------------------------------------------------------------------

    /** the applied rule of one chunk's context as generated code, or the rule itself when anything is not supported */
    public static SurfaceRules.SurfaceRule compile(SurfaceRules.SurfaceRule rule) {
        try {
            Walk walk = new Walk();
            Node root = walk.rule(rule);
            if (root instanceof Leaf) {
                return rule; // nothing to gain
            }
            String key = walk.key.toString();
            MethodHandle ctor = TEMPLATES.get(key);
            if (ctor == null) {
                if (TEMPLATES.size() >= MAX_TEMPLATES) {
                    return rule;
                }
                ctor = TEMPLATES.computeIfAbsent(key, k -> generate(root, walk.types));
            }
            if (ctor == NONE) {
                return rule;
            }
            return (SurfaceRules.SurfaceRule) ctor.invokeExact(walk.args.toArray());
        } catch (TooBig e) {
            return rule;
        } catch (Throwable t) {
            logFailure(t);
            return rule;
        }
    }

    static int templateCount() {
        return TEMPLATES.size();
    }

    private static void logFailure(Throwable t) {
        if (!failureLogged) {
            failureLogged = true;
            LOGGER.warn("Surface rule compiler failed, using the normal surface rules", t);
        }
    }

    private static MethodHandle generate(Node root, List<Class<?>> types) {
        try {
            byte[] bytes = new Generator(types).generate(root);
            MethodHandles.Lookup hidden = LOOKUP.defineHiddenClass(bytes, true);
            return hidden.findConstructor(hidden.lookupClass(), MethodType.methodType(void.class, Object[].class)).asType(CTOR_CALL);
        } catch (Throwable t) {
            logFailure(t);
            return NONE;
        }
    }

    // ---- code generation --------------------------------------------------------------------------------------------------

    private static final class Generator {
        final List<Class<?>> types;
        final List<Node> pending = new ArrayList<>();
        int inline; // nodes inlined into the method being written
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object"; // locals only ever hold a BlockState
            }
        };

        Generator(List<Class<?>> types) {
            this.types = types;
        }

        byte[] generate(Node root) {
            this.cw.visit(Opcodes.V21, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, SELF, null, "java/lang/Object", new String[]{RULE});
            for (int i = 0; i < this.types.size(); i++) {
                this.cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "f" + i, Type.getDescriptor(this.types.get(i)), null, null).visitEnd();
            }
            MethodVisitor mv = this.cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "([Ljava/lang/Object;)V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            for (int i = 0; i < this.types.size(); i++) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitLdcInsn(i);
                mv.visitInsn(Opcodes.AALOAD);
                mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(this.types.get(i)));
                mv.visitFieldInsn(Opcodes.PUTFIELD, SELF, "f" + i, Type.getDescriptor(this.types.get(i)));
            }
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();

            this.method("tryApply", Opcodes.ACC_PUBLIC, root);
            for (int i = 0; i < this.pending.size(); i++) {
                this.method("m" + i, Opcodes.ACC_PRIVATE, this.pending.get(i));
            }
            this.cw.visitEnd();
            return this.cw.toByteArray();
        }

        void method(String name, int access, Node body) {
            MethodVisitor mv = this.cw.visitMethod(access, name, APPLY, null, null);
            mv.visitCode();
            this.inline = 0;
            this.rule(mv, body, true);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        /** code that returns the rule's block if it gives one and falls through if it gives null */
        void rule(MethodVisitor mv, Node node, boolean top) {
            if (!top && !(node instanceof Leaf) && (node instanceof Seq && size(node) > SPLIT_NODES || this.inline + size(node) > METHOD_NODES)) {
                int index = this.pending.size();
                this.pending.add(node);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                this.coords(mv);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, SELF, "m" + index, APPLY, false);
                this.returnIfNonNull(mv);
                return;
            }
            switch (node) {
                case Seq s -> {
                    for (Node child : s.rules()) {
                        this.rule(mv, child, false);
                    }
                }
                case Test t -> {
                    this.inline++;
                    Label skip = new Label();
                    this.condition(mv, t.condition());
                    mv.visitJumpInsn(Opcodes.IFEQ, skip);
                    this.rule(mv, t.then(), false);
                    mv.visitLabel(skip);
                }
                case Leaf l when l.kind() == Leaf.KIND_STATE -> {
                    this.inline++;
                    this.field(mv, l);
                    mv.visitInsn(Opcodes.ARETURN);
                }
                case Leaf l -> {
                    this.inline++;
                    this.field(mv, l);
                    this.coords(mv);
                    boolean itf = l.type().isInterface();
                    mv.visitMethodInsn(itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, Type.getInternalName(l.type()), "tryApply", APPLY, itf);
                    this.returnIfNonNull(mv);
                }
                case Not n -> throw new IllegalStateException("not a rule");
            }
        }

        /** pushes the condition's boolean */
        void condition(MethodVisitor mv, Node node) {
            switch (node) {
                case Not n -> {
                    this.inline++;
                    this.condition(mv, n.target());
                    mv.visitInsn(Opcodes.ICONST_1);
                    mv.visitInsn(Opcodes.IXOR);
                }
                case Leaf l -> {
                    this.inline++;
                    this.field(mv, l);
                    boolean itf = l.type().isInterface();
                    mv.visitMethodInsn(itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, itf ? CONDITION : Type.getInternalName(l.type()), "test", "()Z", itf);
                }
                default -> throw new IllegalStateException("not a condition");
            }
        }

        void field(MethodVisitor mv, Leaf l) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, SELF, "f" + l.field(), Type.getDescriptor(l.type()));
        }

        void coords(MethodVisitor mv) {
            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
        }

        void returnIfNonNull(MethodVisitor mv) {
            Label skip = new Label();
            mv.visitVarInsn(Opcodes.ASTORE, 4);
            mv.visitVarInsn(Opcodes.ALOAD, 4);
            mv.visitJumpInsn(Opcodes.IFNULL, skip);
            mv.visitVarInsn(Opcodes.ALOAD, 4);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitLabel(skip);
        }
    }
}
