package com.dwinovo.animus.anim.compile;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.anim.molang.MolangFn;
import com.dwinovo.animus.anim.molang.MolangNode;
import com.dwinovo.animus.anim.molang.MolangQueries;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser that turns a Molang expression string into a
 * {@link MolangNode} AST. Run once at resource load — the AST is shared
 * between all entities playing the animation.
 *
 * <h2>Grammar</h2>
 * <pre>
 *   expr      = ternary
 *   ternary   = or ('?' expr ':' expr)?
 *   or        = and ('||' and)*
 *   and       = cmp ('&amp;&amp;' cmp)*
 *   cmp       = add (cmp_op add)?
 *   cmp_op    = '==' | '!=' | '&lt;=' | '&gt;=' | '&lt;' | '&gt;'
 *   add       = mul (('+' | '-') mul)*
 *   mul       = unary (('*' | '/' | '%') unary)*
 *   unary     = ('-' | '!') unary | primary
 *   primary   = NUMBER | STRING | identifier ('(' args? ')')? | '(' expr ')'
 *   args      = expr (',' expr)*
 *   STRING    = '\'' [^']* '\'' | '"' [^"]* '"'
 *   identifier = [a-zA-Z_][a-zA-Z0-9_.]*
 * </pre>
 *
 * <h2>String literals</h2>
 * {@code 'play_music'} compiles to {@code Const(hashString("play_music"))}.
 * Runtime slot fills for string-valued state (e.g. {@code entity.task}) use
 * the same {@link MolangQueries#hashString} so {@code entity.task == 'play_music'}
 * reduces to a numeric equality.
 *
 * <h2>Soft failure</h2>
 * Unknown variables / functions / unparseable input log a warning and yield
 * {@code Const(0)} rather than throwing. The animation still plays — the
 * affected channel just stays at zero. This keeps a single bad expression
 * from breaking the entire pipeline.
 */
public final class MolangCompiler {

    private final String src;
    private int pos;

    private MolangCompiler(String src) {
        this.src = src;
    }

    /** Compiles {@code input}; returns {@code Const(0)} on parse failure. */
    public static MolangNode compile(String input) {
        if (input == null || input.isBlank()) return new MolangNode.Const(0);
        try {
            MolangCompiler p = new MolangCompiler(input.trim());
            MolangNode root = p.parseExpr();
            p.skipWhitespace();
            if (p.pos != p.src.length()) {
                Constants.LOG.warn("[animus-anim] trailing input in molang '{}' at {}", input, p.pos);
                return new MolangNode.Const(0);
            }
            return root;
        } catch (RuntimeException ex) {
            Constants.LOG.warn("[animus-anim] failed to parse molang '{}': {}", input, ex.getMessage());
            return new MolangNode.Const(0);
        }
    }

    // ───────────────────────── grammar productions ─────────────────────────

    private MolangNode parseExpr() {
        return parseTernary();
    }

    private MolangNode parseTernary() {
        MolangNode cond = parseOr();
        skipWhitespace();
        if (peekChar() == '?') {
            pos++;
            MolangNode thenBranch = parseExpr();
            skipWhitespace();
            expect(':');
            MolangNode elseBranch = parseExpr();
            return new MolangNode.Ternary(cond, thenBranch, elseBranch);
        }
        return cond;
    }

    private MolangNode parseOr() {
        MolangNode left = parseAnd();
        while (true) {
            skipWhitespace();
            if (peekChar() == '|' && peekChar(1) == '|') {
                pos += 2;
                left = new MolangNode.Or(left, parseAnd());
            } else break;
        }
        return left;
    }

    private MolangNode parseAnd() {
        MolangNode left = parseCmp();
        while (true) {
            skipWhitespace();
            if (peekChar() == '&' && peekChar(1) == '&') {
                pos += 2;
                left = new MolangNode.And(left, parseCmp());
            } else break;
        }
        return left;
    }

    private MolangNode parseCmp() {
        MolangNode left = parseAdd();
        skipWhitespace();
        char c = peekChar();
        char d = peekChar(1);
        MolangNode.CmpOp op = null;
        int width = 0;
        if (c == '=' && d == '=') { op = MolangNode.CmpOp.EQ; width = 2; }
        else if (c == '!' && d == '=') { op = MolangNode.CmpOp.NE; width = 2; }
        else if (c == '<' && d == '=') { op = MolangNode.CmpOp.LE; width = 2; }
        else if (c == '>' && d == '=') { op = MolangNode.CmpOp.GE; width = 2; }
        else if (c == '<') { op = MolangNode.CmpOp.LT; width = 1; }
        else if (c == '>') { op = MolangNode.CmpOp.GT; width = 1; }

        if (op == null) return left;
        pos += width;
        MolangNode right = parseAdd();
        return new MolangNode.Cmp(op, left, right);
    }

    private MolangNode parseAdd() {
        MolangNode left = parseMul();
        while (true) {
            skipWhitespace();
            char c = peekChar();
            if (c == '+') { pos++; left = new MolangNode.Add(left, parseMul()); }
            else if (c == '-') { pos++; left = new MolangNode.Sub(left, parseMul()); }
            else break;
        }
        return left;
    }

    private MolangNode parseMul() {
        MolangNode left = parseUnary();
        while (true) {
            skipWhitespace();
            char c = peekChar();
            if (c == '*') { pos++; left = new MolangNode.Mul(left, parseUnary()); }
            else if (c == '/') { pos++; left = new MolangNode.Div(left, parseUnary()); }
            else if (c == '%') { pos++; left = new MolangNode.Mod(left, parseUnary()); }
            else break;
        }
        return left;
    }

    private MolangNode parseUnary() {
        skipWhitespace();
        char c = peekChar();
        if (c == '-') {
            pos++;
            return new MolangNode.Neg(parseUnary());
        }
        if (c == '!' && peekChar(1) != '=') {
            // '!=' is a cmp op handled by parseCmp; only consume bare '!' here.
            pos++;
            return new MolangNode.Not(parseUnary());
        }
        return parsePrimary();
    }

    private MolangNode parsePrimary() {
        skipWhitespace();
        char c = peekChar();
        if (c == '(') {
            pos++;
            MolangNode inner = parseExpr();
            skipWhitespace();
            expect(')');
            return inner;
        }
        if (c == '\'' || c == '"') {
            return parseString(c);
        }
        if (isDigit(c) || c == '.') {
            return parseNumber();
        }
        if (isIdentStart(c)) {
            String id = parseIdent();
            skipWhitespace();
            if (peekChar() == '(') {
                pos++;
                List<MolangNode> args = parseArgList();
                expect(')');
                return makeFnCall(id, args);
            }
            return makeVarRef(id);
        }
        throw new RuntimeException("unexpected '" + c + "' at " + pos);
    }

    private List<MolangNode> parseArgList() {
        List<MolangNode> args = new ArrayList<>(3);
        skipWhitespace();
        if (peekChar() == ')') return args;
        args.add(parseExpr());
        while (true) {
            skipWhitespace();
            if (peekChar() == ',') { pos++; args.add(parseExpr()); }
            else break;
        }
        return args;
    }

    private MolangNode parseNumber() {
        int start = pos;
        while (pos < src.length() && (isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
            pos++;
        }
        String num = src.substring(start, pos);
        try {
            return new MolangNode.Const(Double.parseDouble(num));
        } catch (NumberFormatException ex) {
            throw new RuntimeException("bad number '" + num + "' at " + start);
        }
    }

    private MolangNode parseString(char quote) {
        pos++; // skip opening quote
        int start = pos;
        while (pos < src.length() && src.charAt(pos) != quote) pos++;
        if (pos >= src.length()) {
            throw new RuntimeException("unterminated string starting at " + (start - 1));
        }
        String literal = src.substring(start, pos);
        pos++; // skip closing quote
        return new MolangNode.Const(MolangQueries.hashString(literal));
    }

    private String parseIdent() {
        int start = pos;
        while (pos < src.length() && isIdentPart(src.charAt(pos))) {
            pos++;
        }
        return src.substring(start, pos);
    }

    private MolangNode makeFnCall(String name, List<MolangNode> args) {
        int fnId = MolangFn.resolve(name);
        if (fnId == MolangFn.FN_UNKNOWN) {
            Constants.LOG.warn("[animus-anim] unknown molang function '{}'; using 0", name);
            return new MolangNode.Const(0);
        }
        int required = MolangFn.requiredArity(fnId);
        if (args.size() != required) {
            Constants.LOG.warn("[animus-anim] molang '{}' expects {} arg(s) but got {}; using 0",
                    name, required, args.size());
            return new MolangNode.Const(0);
        }
        return new MolangNode.FuncCall(fnId, args.toArray(new MolangNode[0]));
    }

    private MolangNode makeVarRef(String name) {
        MolangQueries.Resolution r = MolangQueries.resolve(name);
        if (r == null) {
            Constants.LOG.warn("[animus-anim] unknown molang variable '{}'; using 0", name);
            return new MolangNode.Const(0);
        }
        return switch (r.namespace()) {
            case ENTITY -> new MolangNode.EntityVar(r.slot());
            case QUERY -> new MolangNode.QueryVar(r.slot());
            case VARIABLE -> new MolangNode.VariableVar(r.slot());
        };
    }

    // ───────────────────────── lexer primitives ─────────────────────────

    private void expect(char c) {
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw new RuntimeException("expected '" + c + "' at " + pos);
        }
        pos++;
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private char peekChar() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private char peekChar(int offset) {
        int idx = pos + offset;
        return idx < src.length() ? src.charAt(idx) : '\0';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || isDigit(c) || c == '.';
    }
}
