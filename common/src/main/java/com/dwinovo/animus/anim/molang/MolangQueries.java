package com.dwinovo.animus.anim.molang;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Static registry mapping Molang identifiers ({@code entity.*}, {@code query.*},
 * {@code variable.*}) to integer slot indices. The compiler uses these slots
 * to skip string lookup at evaluation time; each {@link MolangContext} holds
 * the per-namespace value array indexed by these slots.
 *
 * <h2>Namespace semantics</h2>
 * <ul>
 *   <li>{@code entity.*} — driven by the mod from the current entity state
 *       (e.g. {@code entity.task}, {@code entity.health_percent}). Slot set is
 *       fixed at static-init time; new entries require a code change.</li>
 *   <li>{@code query.*} — vanilla-derived state independent of any particular
 *       Animus concept (e.g. {@code query.anim_time}, {@code query.ground_speed}).
 *       Also code-fixed.</li>
 *   <li>{@code variable.*} — author-defined values referenced from a
 *       {@code render_controllers.json} or animation file. Slots are allocated
 *       lazily by the compiler on first reference, so packs can introduce new
 *       variable names without touching the mod.</li>
 * </ul>
 *
 * <h2>Aliases</h2>
 * Bedrock convention abbreviates {@code entity.} as {@code e.},
 * {@code query.} as {@code q.}, and {@code variable.} as {@code v.}. All four
 * forms resolve here.
 *
 * <h2>Slot table size</h2>
 * Each namespace is capped at {@link MolangContext#SLOTS_PER_NAMESPACE}. The
 * cap is generous (64) — built-in slots are a handful, and packs rarely use
 * more than a dozen {@code variable.*} entries. Overflow logs a warning and
 * yields {@code -1} (treated as "unknown" by the compiler).
 */
public final class MolangQueries {

    public enum Namespace {
        ENTITY, QUERY, VARIABLE;

        static @Nullable Namespace forPrefix(String prefix) {
            return switch (prefix) {
                case "entity", "e" -> ENTITY;
                case "query", "q" -> QUERY;
                case "variable", "v" -> VARIABLE;
                default -> null;
            };
        }
    }

    public record Resolution(Namespace namespace, int slot) {}

    private static final Map<String, Integer> ENTITY_SLOTS = new LinkedHashMap<>();
    private static final Map<String, Integer> QUERY_SLOTS = new LinkedHashMap<>();
    private static final Map<String, Integer> VARIABLE_SLOTS = new LinkedHashMap<>();

    /**
     * Built-in slot constants. Defined here so callers can index without
     * re-resolving. Mod-provided runtime state lives under {@code query.*}
     * to align with Bedrock convention — pack authors writing
     * {@code render_controllers.json} expect that namespace. {@code entity.*}
     * is reserved for future Animus-specific state that doesn't fit the
     * Bedrock {@code query} contract.
     */
    public static final int QUERY_ANIM_TIME       = registerQuery("anim_time");
    public static final int QUERY_GROUND_SPEED    = registerQuery("ground_speed");
    public static final int QUERY_TASK            = registerQuery("task");
    public static final int QUERY_IS_ON_GROUND    = registerQuery("is_on_ground");
    public static final int QUERY_IS_IN_WATER     = registerQuery("is_in_water");
    public static final int QUERY_HEALTH          = registerQuery("health");
    public static final int QUERY_MAX_HEALTH      = registerQuery("max_health");
    public static final int QUERY_SCALE           = registerQuery("scale");
    public static final int QUERY_BODY_Y_ROTATION = registerQuery("body_y_rotation");
    public static final int QUERY_HEAD_Y_ROTATION = registerQuery("head_y_rotation");

    private MolangQueries() {}

    /**
     * Register a slot under {@code entity.*}. Idempotent — returns the existing
     * slot when the name was already registered.
     */
    public static int registerEntity(String name) {
        return registerInto(ENTITY_SLOTS, name, "entity");
    }

    public static int registerQuery(String name) {
        return registerInto(QUERY_SLOTS, name, "query");
    }

    public static int registerVariable(String name) {
        return registerInto(VARIABLE_SLOTS, name, "variable");
    }

    private static int registerInto(Map<String, Integer> map, String name, String nsLabel) {
        Integer existing = map.get(name);
        if (existing != null) return existing;
        int slot = map.size();
        if (slot >= MolangContext.SLOTS_PER_NAMESPACE) {
            // Soft-fail: caller's compiler will turn this into Const(0).
            return -1;
        }
        map.put(name, slot);
        return slot;
    }

    /**
     * Resolve a fully-qualified Molang identifier (e.g. {@code "entity.task"},
     * {@code "v.foo"}). {@code variable.*} references that don't yet have a
     * slot are allocated on the fly — packs can use any name without prior
     * declaration.
     *
     * @return {@code null} when the prefix isn't a known namespace, the name
     *         lacks a dot, or the namespace has run out of slot capacity.
     */
    public static @Nullable Resolution resolve(String identifier) {
        int dot = identifier.indexOf('.');
        if (dot <= 0 || dot >= identifier.length() - 1) return null;
        String prefix = identifier.substring(0, dot);
        String name = identifier.substring(dot + 1);
        Namespace ns = Namespace.forPrefix(prefix);
        if (ns == null) return null;

        Map<String, Integer> map = mapFor(ns);
        Integer slot = map.get(name);
        if (slot == null) {
            // Lazy allocation only for variable.* — entity / query are mod-controlled.
            if (ns != Namespace.VARIABLE) return null;
            int allocated = registerVariable(name);
            if (allocated < 0) return null;
            return new Resolution(ns, allocated);
        }
        return new Resolution(ns, slot);
    }

    public static int entitySlotCount()   { return ENTITY_SLOTS.size(); }
    public static int querySlotCount()    { return QUERY_SLOTS.size(); }
    public static int variableSlotCount() { return VARIABLE_SLOTS.size(); }

    private static Map<String, Integer> mapFor(Namespace ns) {
        return switch (ns) {
            case ENTITY -> ENTITY_SLOTS;
            case QUERY -> QUERY_SLOTS;
            case VARIABLE -> VARIABLE_SLOTS;
        };
    }

    /**
     * 32-bit FNV-1a hash. Used to encode string literals into doubles so
     * Molang comparison operators ({@code == != < > <= >=}) work uniformly
     * on numeric and string values.
     *
     * <p>Encoded as the signed-int reinterpretation of the hash bits, cast to
     * double. Two strings collide iff their FNV-1a hashes match, which is
     * vanishingly unlikely for typical task / mood enum values (under 1 in
     * 4 billion per pair). Bedrock vanilla uses an FNV-1a-like scheme for the
     * same reason; see <a href="https://wiki.bedrock.dev/scripting/molang">
     * Bedrock Molang wiki</a>.
     */
    public static double hashString(String s) {
        int h = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x01000193;
        }
        return (double) h;
    }
}
