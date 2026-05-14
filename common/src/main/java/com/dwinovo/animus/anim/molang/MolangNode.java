package com.dwinovo.animus.anim.molang;

/**
 * Compiled Molang expression. Each node is a tiny pure-function: given a
 * {@link MolangContext}, return a double. The AST is built once at resource
 * load by {@link com.dwinovo.animus.anim.compile.MolangCompiler} and shared
 * between all entities playing the animation so parsed expressions stay
 * cached with a small surface area.
 *
 * <h2>Truthiness</h2>
 * Molang has no boolean type — every comparison and logical operator returns
 * {@code 0.0} (false) or {@code 1.0} (true), and every condition treats
 * non-zero as true. This matches Bedrock semantics exactly.
 *
 * <h2>String values</h2>
 * String literals from source ({@code 'none'}, {@code "play_music"}) are
 * hashed to doubles at compile time via {@link MolangQueries#hashString} and
 * stored as {@link Const} nodes. Runtime slot fills for {@code entity.*}
 * string-valued state ({@code entity.task = "play_music"}) use the same hash
 * function, so equality comparison reduces to {@code Math.abs(l-r) &lt; 1e-6}.
 *
 * <h2>Soft failure</h2>
 * Unknown identifiers / functions / unparseable input log a warning at compile
 * time and yield {@code Const(0)} rather than throwing. The animation still
 * plays and the affected channel just stays at zero. This keeps a single bad
 * expression from breaking the entire pipeline.
 */
public sealed interface MolangNode {

    double eval(MolangContext ctx);

    /** Numeric literal — also used for compiled string-literal hashes. */
    record Const(double value) implements MolangNode {
        @Override public double eval(MolangContext ctx) { return value; }
    }

    /** {@code entity.*} variable lookup by pre-resolved slot. */
    record EntityVar(int slot) implements MolangNode {
        @Override public double eval(MolangContext ctx) { return ctx.entitySlots[slot]; }
    }

    /** {@code query.*} variable lookup by pre-resolved slot. */
    record QueryVar(int slot) implements MolangNode {
        @Override public double eval(MolangContext ctx) { return ctx.querySlots[slot]; }
    }

    /** {@code variable.*} variable lookup by pre-resolved slot. */
    record VariableVar(int slot) implements MolangNode {
        @Override public double eval(MolangContext ctx) { return ctx.variableSlots[slot]; }
    }

    /** Unary negation ({@code -x}). */
    record Neg(MolangNode arg) implements MolangNode {
        @Override public double eval(MolangContext ctx) { return -arg.eval(ctx); }
    }

    /** Boolean negation ({@code !x}). Returns {@code 1.0} when {@code x == 0}, else {@code 0.0}. */
    record Not(MolangNode arg) implements MolangNode {
        @Override public double eval(MolangContext ctx) { return arg.eval(ctx) == 0.0 ? 1.0 : 0.0; }
    }

    record Add(MolangNode l, MolangNode r) implements MolangNode {
        @Override public double eval(MolangContext ctx) { return l.eval(ctx) + r.eval(ctx); }
    }

    record Sub(MolangNode l, MolangNode r) implements MolangNode {
        @Override public double eval(MolangContext ctx) { return l.eval(ctx) - r.eval(ctx); }
    }

    record Mul(MolangNode l, MolangNode r) implements MolangNode {
        @Override public double eval(MolangContext ctx) { return l.eval(ctx) * r.eval(ctx); }
    }

    record Div(MolangNode l, MolangNode r) implements MolangNode {
        @Override public double eval(MolangContext ctx) {
            double rv = r.eval(ctx);
            return rv == 0.0 ? 0.0 : l.eval(ctx) / rv;
        }
    }

    /** Modulo ({@code l % r}). Bedrock semantics: {@code r == 0} yields {@code 0}. */
    record Mod(MolangNode l, MolangNode r) implements MolangNode {
        @Override public double eval(MolangContext ctx) {
            double rv = r.eval(ctx);
            return rv == 0.0 ? 0.0 : l.eval(ctx) % rv;
        }
    }

    /** Comparison: {@code == != &lt; &lt;= &gt; &gt;=}. */
    enum CmpOp { EQ, NE, LT, LE, GT, GE }

    record Cmp(CmpOp op, MolangNode l, MolangNode r) implements MolangNode {
        private static final double EPSILON = 1e-6;

        @Override public double eval(MolangContext ctx) {
            double lv = l.eval(ctx);
            double rv = r.eval(ctx);
            return switch (op) {
                case EQ -> Math.abs(lv - rv) < EPSILON ? 1.0 : 0.0;
                case NE -> Math.abs(lv - rv) >= EPSILON ? 1.0 : 0.0;
                case LT -> lv <  rv ? 1.0 : 0.0;
                case LE -> lv <= rv ? 1.0 : 0.0;
                case GT -> lv >  rv ? 1.0 : 0.0;
                case GE -> lv >= rv ? 1.0 : 0.0;
            } ;
        }
    }

    /** Logical AND. Short-circuits — {@code r} is not evaluated when {@code l == 0}. */
    record And(MolangNode l, MolangNode r) implements MolangNode {
        @Override public double eval(MolangContext ctx) {
            return (l.eval(ctx) != 0.0 && r.eval(ctx) != 0.0) ? 1.0 : 0.0;
        }
    }

    /** Logical OR. Short-circuits — {@code r} is not evaluated when {@code l != 0}. */
    record Or(MolangNode l, MolangNode r) implements MolangNode {
        @Override public double eval(MolangContext ctx) {
            return (l.eval(ctx) != 0.0 || r.eval(ctx) != 0.0) ? 1.0 : 0.0;
        }
    }

    /** Ternary {@code cond ? then : else}. Java {@code ?:} already short-circuits. */
    record Ternary(MolangNode cond, MolangNode then, MolangNode els) implements MolangNode {
        @Override public double eval(MolangContext ctx) {
            return cond.eval(ctx) != 0.0 ? then.eval(ctx) : els.eval(ctx);
        }
    }

    /** Function call with pre-resolved function id (see {@link MolangFn}). */
    record FuncCall(int fnId, MolangNode[] args) implements MolangNode {
        @Override public double eval(MolangContext ctx) {
            return MolangFn.invoke(fnId, args, ctx);
        }
    }
}
