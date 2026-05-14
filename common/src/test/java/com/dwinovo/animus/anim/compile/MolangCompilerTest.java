package com.dwinovo.animus.anim.compile;

import com.dwinovo.animus.anim.molang.MolangContext;
import com.dwinovo.animus.anim.molang.MolangNode;
import com.dwinovo.animus.anim.molang.MolangQueries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MolangCompilerTest {

    private MolangContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new MolangContext();
    }

    private double eval(String expr) {
        return MolangCompiler.compile(expr).eval(ctx);
    }

    @Test
    void numberLiteral() {
        assertEquals(3.14, eval("3.14"), 1e-9);
        assertEquals(0.0, eval("0"), 0);
        assertEquals(42.0, eval("42"), 0);
    }

    @Test
    void arithmeticAndPrecedence() {
        assertEquals(7.0, eval("3 + 4"), 1e-9);
        assertEquals(14.0, eval("2 + 3 * 4"), 1e-9);
        assertEquals(20.0, eval("(2 + 3) * 4"), 1e-9);
        assertEquals(1.0, eval("5 % 2"), 1e-9);
        // div by zero soft-fails to 0 per Bedrock convention
        assertEquals(0.0, eval("5 / 0"), 0);
        assertEquals(0.0, eval("5 % 0"), 0);
    }

    @Test
    void unaryNegation() {
        assertEquals(-5.0, eval("-5"), 0);
        assertEquals(5.0, eval("--5"), 0);
        assertEquals(-9.0, eval("-(3 + 6)"), 0);
    }

    @Test
    void comparison() {
        assertEquals(1.0, eval("3 == 3"), 0);
        assertEquals(0.0, eval("3 == 4"), 0);
        assertEquals(1.0, eval("3 != 4"), 0);
        assertEquals(1.0, eval("3 < 4"), 0);
        assertEquals(0.0, eval("4 < 4"), 0);
        assertEquals(1.0, eval("4 <= 4"), 0);
        assertEquals(1.0, eval("5 > 4"), 0);
        assertEquals(1.0, eval("4 >= 4"), 0);
    }

    @Test
    void logicalOps() {
        assertEquals(1.0, eval("1 && 1"), 0);
        assertEquals(0.0, eval("1 && 0"), 0);
        assertEquals(1.0, eval("0 || 1"), 0);
        assertEquals(0.0, eval("0 || 0"), 0);
        assertEquals(1.0, eval("!0"), 0);
        assertEquals(0.0, eval("!5"), 0);
        assertEquals(1.0, eval("!(3 == 4)"), 0);
    }

    @Test
    void logicalShortCircuit() {
        // Right operand triggers a div-by-zero (which would resolve to 0, but the
        // important property is that the parser doesn't fail and the result is correct).
        assertEquals(0.0, eval("0 && (1 / 0)"), 0);
        assertEquals(1.0, eval("1 || (1 / 0)"), 0);
    }

    @Test
    void ternary() {
        assertEquals(10.0, eval("1 ? 10 : 20"), 0);
        assertEquals(20.0, eval("0 ? 10 : 20"), 0);
        assertEquals(7.0, eval("3 < 4 ? 7 : 9"), 0);
    }

    @Test
    void mathFunctions() {
        assertEquals(0.0, eval("math.sin(0)"), 1e-9);
        assertEquals(1.0, eval("math.cos(0)"), 1e-9);
        assertEquals(0.0, eval("math.sin(180)"), 1e-9);
        assertEquals(5.0, eval("math.sqrt(25)"), 1e-9);
        assertEquals(5.0, eval("math.abs(-5)"), 1e-9);
        assertEquals(3.0, eval("math.floor(3.7)"), 1e-9);
        assertEquals(4.0, eval("math.ceil(3.2)"), 1e-9);
        assertEquals(1.0, eval("math.lerp(0, 2, 0.5)"), 1e-9);
        assertEquals(2.0, eval("math.clamp(5, 0, 2)"), 1e-9);
        assertEquals(3.0, eval("math.min(3, 7)"), 1e-9);
        assertEquals(7.0, eval("math.max(3, 7)"), 1e-9);
    }

    @Test
    void caseInsensitiveFunction() {
        assertEquals(1.0, eval("Math.cos(0)"), 1e-9);
        assertEquals(1.0, eval("math.cos(0)"), 1e-9);
    }

    @Test
    void stringLiteralHashing() {
        double h = eval("'none'");
        assertEquals(MolangQueries.hashString("none"), h, 0);
        // Double-quoted string is equivalent.
        assertEquals(MolangQueries.hashString("play_music"), eval("\"play_music\""), 0);
    }

    @Test
    void taskEquality() {
        ctx.querySlots[MolangQueries.QUERY_TASK] = MolangQueries.hashString("none");
        assertEquals(1.0, eval("query.task == 'none'"), 0);
        assertEquals(0.0, eval("query.task == 'play_music'"), 0);
        assertEquals(1.0, eval("query.task != 'play_music'"), 0);
    }

    @Test
    void namespaceAliases() {
        ctx.querySlots[MolangQueries.QUERY_ANIM_TIME] = 2.5;
        assertEquals(2.5, eval("query.anim_time"), 0);
        assertEquals(2.5, eval("q.anim_time"), 0);
        ctx.querySlots[MolangQueries.QUERY_TASK] = 42.0;
        assertEquals(42.0, eval("query.task"), 0);
        assertEquals(42.0, eval("q.task"), 0);
    }

    @Test
    void lazyVariableSlotAllocation() {
        // variable.* is allocated on first compile reference; afterwards both
        // long-form and short-form lookups hit the same slot.
        int slot = MolangQueries.registerVariable("custom_test_var");
        ctx.variableSlots[slot] = 99.0;
        assertEquals(99.0, eval("variable.custom_test_var"), 0);
        assertEquals(99.0, eval("v.custom_test_var"), 0);
    }

    @Test
    void unknownIdentifiersDegradeToZero() {
        // entity.* and query.* are mod-controlled; unknown names return Const(0).
        assertEquals(0.0, eval("query.nonexistent_var"), 0);
        assertEquals(0.0, eval("entity.nonexistent_thing"), 0);
        assertEquals(0.0, eval("math.nonexistent_fn(1, 2)"), 0);
    }

    @Test
    void emptyAndBlankInputReturnsZero() {
        assertEquals(0.0, eval(""), 0);
        assertEquals(0.0, eval("   "), 0);
        assertEquals(0.0, MolangCompiler.compile(null).eval(ctx), 0);
    }

    @Test
    void compoundTaskExpression() {
        // Real-world part_visibility expression shape.
        ctx.querySlots[MolangQueries.QUERY_TASK] = MolangQueries.hashString("idle");
        assertEquals(1.0, eval("query.task == 'idle' || query.task == 'play_music'"), 0);
        assertEquals(0.0, eval("query.task == 'attack' && query.task == 'idle'"), 0);
        // Show-guitar pattern: guitar bone visible only while playing music.
        ctx.querySlots[MolangQueries.QUERY_TASK] = MolangQueries.hashString("play_music");
        assertEquals(1.0, eval("query.task == 'play_music'"), 0);
        ctx.querySlots[MolangQueries.QUERY_TASK] = MolangQueries.hashString("none");
        assertEquals(0.0, eval("query.task == 'play_music'"), 0);
    }

    @Test
    void astTypesArePreserved() {
        // Compile-time AST shape is part of the contract — the renderer expects
        // specific node types, e.g. Cmp for `==`, so missing parser dispatch
        // shows up here first.
        MolangNode n = MolangCompiler.compile("query.task == 'none'");
        assertEquals(MolangNode.Cmp.class, n.getClass());

        n = MolangCompiler.compile("1 ? 2 : 3");
        assertEquals(MolangNode.Ternary.class, n.getClass());

        n = MolangCompiler.compile("'none'");
        assertEquals(MolangNode.Const.class, n.getClass());

        n = MolangCompiler.compile("query.task");
        assertEquals(MolangNode.QueryVar.class, n.getClass());
    }
}
