package com.dwinovo.animus.anim.molang;

/**
 * Built-in Molang function dispatch. The compiler resolves a function name to
 * one of these integer ids at parse time so {@link MolangNode.FuncCall#eval}
 * can skip any name lookup.
 *
 * <h2>Bedrock conventions</h2>
 * {@code math.cos / math.sin} take <b>degrees</b> (not radians). All other
 * trig / numeric functions follow {@link java.lang.Math} semantics directly.
 *
 * <h2>Not yet supported</h2>
 * {@code math.random / math.die_roll / math.random_integer} are intentionally
 * left out — non-determinism is incompatible with the unit-tested
 * {@code part_visibility} evaluation model. If a random animation effect is
 * needed, it should be authored as keyframes rather than expressions.
 */
public final class MolangFn {

    public static final int FN_UNKNOWN = -1;

    public static final int FN_COS    = 0;
    public static final int FN_SIN    = 1;
    public static final int FN_LERP   = 2;
    public static final int FN_EXP    = 3;
    public static final int FN_CLAMP  = 4;
    public static final int FN_SQRT   = 5;
    public static final int FN_ABS    = 6;
    public static final int FN_FLOOR  = 7;
    public static final int FN_CEIL   = 8;
    public static final int FN_ROUND  = 9;
    public static final int FN_MIN    = 10;
    public static final int FN_MAX    = 11;
    public static final int FN_MOD    = 12;

    private static final double DEG_TO_RAD = Math.PI / 180.0;

    private MolangFn() {}

    public static double invoke(int fnId, MolangNode[] args, MolangContext ctx) {
        return switch (fnId) {
            case FN_COS   -> Math.cos(args[0].eval(ctx) * DEG_TO_RAD);
            case FN_SIN   -> Math.sin(args[0].eval(ctx) * DEG_TO_RAD);
            case FN_LERP  -> {
                double a = args[0].eval(ctx);
                double b = args[1].eval(ctx);
                double t = args[2].eval(ctx);
                yield a + (b - a) * t;
            }
            case FN_EXP   -> Math.exp(args[0].eval(ctx));
            case FN_CLAMP -> {
                double v  = args[0].eval(ctx);
                double lo = args[1].eval(ctx);
                double hi = args[2].eval(ctx);
                yield v < lo ? lo : (v > hi ? hi : v);
            }
            case FN_SQRT  -> Math.sqrt(args[0].eval(ctx));
            case FN_ABS   -> Math.abs(args[0].eval(ctx));
            case FN_FLOOR -> Math.floor(args[0].eval(ctx));
            case FN_CEIL  -> Math.ceil(args[0].eval(ctx));
            case FN_ROUND -> Math.round(args[0].eval(ctx));
            case FN_MIN   -> Math.min(args[0].eval(ctx), args[1].eval(ctx));
            case FN_MAX   -> Math.max(args[0].eval(ctx), args[1].eval(ctx));
            case FN_MOD   -> {
                double a = args[0].eval(ctx);
                double b = args[1].eval(ctx);
                yield b == 0.0 ? 0.0 : a % b;
            }
            default -> 0.0;
        };
    }

    public static int requiredArity(int fnId) {
        return switch (fnId) {
            case FN_COS, FN_SIN, FN_EXP,
                 FN_SQRT, FN_ABS, FN_FLOOR, FN_CEIL, FN_ROUND -> 1;
            case FN_MIN, FN_MAX, FN_MOD -> 2;
            case FN_LERP, FN_CLAMP -> 3;
            default -> -1;
        };
    }

    /** Case-insensitive resolution: {@code Math.sin} and {@code math.sin} both work. */
    public static int resolve(String name) {
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "math.cos"   -> FN_COS;
            case "math.sin"   -> FN_SIN;
            case "math.lerp"  -> FN_LERP;
            case "math.exp"   -> FN_EXP;
            case "math.clamp" -> FN_CLAMP;
            case "math.sqrt"  -> FN_SQRT;
            case "math.abs"   -> FN_ABS;
            case "math.floor" -> FN_FLOOR;
            case "math.ceil"  -> FN_CEIL;
            case "math.round" -> FN_ROUND;
            case "math.min"   -> FN_MIN;
            case "math.max"   -> FN_MAX;
            case "math.mod"   -> FN_MOD;
            default -> FN_UNKNOWN;
        };
    }
}
