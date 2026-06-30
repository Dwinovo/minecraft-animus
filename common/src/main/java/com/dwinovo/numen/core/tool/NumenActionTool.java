package com.dwinovo.numen.core.tool;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.agent.tool.ClientToolContext;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.tool.api.Arg;
import com.dwinovo.numen.agent.tool.api.NumenAction;
import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.agent.tool.api.ToolSchema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.task.TaskRecord;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A {@link NumenTool} backed by a reflected {@link NumenAction} method — the
 * runtime bridge from the annotation authoring surface to the tool machinery.
 * Name / description / schema come from {@link ToolSchema}; calling the tool binds
 * the model's JSON arguments to the {@link Arg}-annotated parameters, injects
 * whatever context the engine can provide for the rest, and invokes the method.
 *
 * <h2>One contract, no categories</h2>
 * There is no "kind" of tool. A method does <em>whatever it wants</em> and the
 * adapter never rejects a signature. Everything below is just convenience over
 * the open {@link ToolCall} contract — pick what fits, ignore the rest:
 *
 * <ul>
 *   <li><b>Inject by type</b> (any non-{@code @Arg} parameter): a
 *       {@link ToolCall} (the raw handle — complete whenever, from any thread,
 *       ship your own packets, proxy elsewhere), a {@code Consumer<String>}
 *       reply (sugar for {@link ToolCall#complete}), a {@link ToolContext}
 *       (call id / deadline), or the live {@link NumenPlayer} body. Ask for none,
 *       some, or all. An unrecognised type is injected as {@code null} (a warning
 *       is logged) — never a registration failure.</li>
 *   <li><b>Finish however you like</b>: return a value and the adapter completes
 *       the call with it; return {@code void} and complete it yourself through the
 *       injected reply / {@link ToolCall}; or return a {@link TaskRecord} to hand
 *       the work to the body's task queue (a convenience some packs use — not a
 *       requirement).</li>
 * </ul>
 *
 * <p>The only thing inferred is <em>where</em> the method has to run, and that is
 * mechanical, not a taxonomy: a method that asks for the {@link NumenPlayer} body
 * or returns a {@link TaskRecord} must run on the server (that is where the body
 * and its task queue live), so the call ships there; otherwise it runs on the
 * agent (client) thread. A tool that wants server work on its own terms simply
 * takes a {@link ToolCall} and calls {@link ToolCall#shipToServer} (or sends its
 * own packets) — the adapter does not get in the way.
 */
public final class NumenActionTool implements NumenTool {

    private enum SlotType { ARG, ENTITY, CONTEXT, REPLY, CALL, CLIENT, IGNORED }

    /** One method parameter: either a model argument or an injected context value. */
    private static final class Slot {
        final SlotType type;
        final String argName;     // ARG only
        final Class<?> argType;   // ARG only
        final Class<?> elemType;  // ARG only — element class for a List<> arg, else null
        final boolean required;   // ARG only — listed in the schema's required[]
        final boolean nullable;   // ARG only — type permits null

        Slot(SlotType type, String argName, Class<?> argType, Class<?> elemType,
             boolean required, boolean nullable) {
            this.type = type;
            this.argName = argName;
            this.argType = argType;
            this.elemType = elemType;
            this.required = required;
            this.nullable = nullable;
        }

        static Slot inject(SlotType type) {
            return new Slot(type, null, null, null, false, false);
        }
    }

    private final Object holder;
    private final Method method;
    private final String name;
    private final String description;
    private final Map<String, Object> schema;
    private final Slot[] slots;

    /** Mechanical routing (not a category): must this run where the body lives? */
    private final boolean needsServer;
    private final boolean returnsTaskRecord;
    private final boolean returnsValue;

    public NumenActionTool(Object holder, Method method) {
        NumenAction action = method.getAnnotation(NumenAction.class);
        if (action == null) {
            throw new IllegalArgumentException(method + " is not annotated with @NumenAction");
        }
        this.holder = holder;
        this.method = method;
        method.setAccessible(true);
        this.name = ToolSchema.actionName(method);
        this.description = action.description();
        this.schema = ToolSchema.schemaFor(method);

        boolean injectsEntity = false;
        List<Slot> plan = new ArrayList<>();
        for (Parameter p : method.getParameters()) {
            Arg arg = p.getAnnotation(Arg.class);
            if (arg != null) {
                String argName = arg.name().isEmpty() ? p.getName() : arg.name();
                plan.add(new Slot(SlotType.ARG, argName, p.getType(), listElement(p),
                        arg.required(), arg.nullable()));
            } else if (NumenPlayer.class.isAssignableFrom(p.getType())) {
                injectsEntity = true;
                plan.add(Slot.inject(SlotType.ENTITY));
            } else if (p.getType() == ToolContext.class) {
                plan.add(Slot.inject(SlotType.CONTEXT));
            } else if (p.getType() == Consumer.class) {
                plan.add(Slot.inject(SlotType.REPLY));
            } else if (p.getType() == ToolCall.class) {
                plan.add(Slot.inject(SlotType.CALL));
            } else if (p.getType() == ClientToolContext.class) {
                plan.add(Slot.inject(SlotType.CLIENT));
            } else {
                // Never block registration over a shape we don't recognise — bind null
                // and let the author do what they want (they have the ToolCall handle).
                Constants.LOG.warn("@NumenAction {}: parameter '{}' of type {} is not an "
                        + "injectable context type — binding null", name, p.getName(), p.getType().getName());
                plan.add(Slot.inject(SlotType.IGNORED));
            }
        }
        this.slots = plan.toArray(new Slot[0]);
        this.returnsTaskRecord = TaskRecord.class.isAssignableFrom(method.getReturnType());
        this.returnsValue = method.getReturnType() != void.class && !returnsTaskRecord;
        // The body and its task queue live on the server: a tool that reaches for
        // the body or hands back a TaskRecord must run there. Nothing else to infer.
        this.needsServer = injectsEntity || returnsTaskRecord;
    }

    @Override public String name() { return name; }
    @Override public String description() { return description; }
    @Override public Map<String, Object> parameterSchema() { return schema; }

    @Override
    public void invoke(ToolCall call) {
        if (needsServer) {
            CoreServerTools.ship(call);   // core's own packet; completes when the result returns
            return;
        }
        // Runs on the agent (client) thread. The method finishes the call: by
        // returning a value (we complete with it) or by completing it itself via
        // the injected ToolCall / reply.
        try {
            Object result = reflectInvoke(buildArgs(call.args(), null,
                    new ToolContext(call.id(), 0L), call::complete, call));
            if (returnsValue) call.complete(resultToString(result));
        } catch (RuntimeException ex) {
            call.complete(TaskResult.fail(ex.getMessage()).toJson());
        }
    }

    /**
     * Server-side execution — called by the core network transport
     * ({@code ExecuteToolPayload}) when a call ships to the body; NOT part of the
     * {@link NumenTool} contract. Same open shape as {@link #invoke}: the method
     * returns a value (replied here), returns a {@link TaskRecord} (enqueued, its
     * result returning via the task lifecycle), or completes itself via the
     * injected {@code reply}.
     */
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        long gameTime = companion.level().getGameTime();
        Object result = reflectInvoke(
                buildArgs(args, companion, new ToolContext(toolCallId, gameTime), reply, null));
        if (returnsTaskRecord) {
            // result returns via core's task lifecycle (per-companion queue, ticked by CompanionTickDispatcher)
            com.dwinovo.numen.core.task.CompanionTickDispatcher.queueFor(companion.getUUID())
                    .enqueue((TaskRecord) result);
        } else if (returnsValue) {
            reply.accept(resultToString(result));
        }
        // void → the method already replied (or shipped its own work).
    }

    /** Offline arg validation (coerce + discard) for the benchmark; not part of the contract. */
    public void checkArgs(JsonObject args) {
        for (Slot s : slots) {
            if (s.type == SlotType.ARG) {
                coerce(args, s.argName, s.argType, s.elemType, s.required, s.nullable);
            }
        }
    }

    private Object[] buildArgs(JsonObject args, NumenPlayer entity, ToolContext ctx,
                               Consumer<String> reply, ToolCall call) {
        Object[] argv = new Object[slots.length];
        for (int i = 0; i < slots.length; i++) {
            Slot s = slots[i];
            argv[i] = switch (s.type) {
                case ARG -> coerce(args, s.argName, s.argType, s.elemType, s.required, s.nullable);
                case ENTITY -> entity;
                case CONTEXT -> ctx;
                case REPLY -> reply;
                case CALL -> call;
                case CLIENT -> call != null ? call.ctx() : null;
                case IGNORED -> null;
            };
        }
        return argv;
    }

    private Object reflectInvoke(Object[] argv) {
        try {
            return method.invoke(holder, argv);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("cannot invoke @NumenAction " + name, ex);
        }
    }

    private String resultToString(Object result) {
        if (result instanceof String s) return s;
        if (result instanceof TaskResult tr) return tr.toJson();
        if (result == null) {
            throw new IllegalStateException("@NumenAction " + name + " returned null");
        }
        return result.toString();
    }

    /** Coerce one JSON argument to the method parameter's Java type. */
    private Object coerce(JsonObject args, String key, Class<?> type, Class<?> elemType,
                          boolean required, boolean nullable) {
        JsonElement el = args.get(key);
        boolean absent = el == null || el.isJsonNull();

        // List args: List<record> binds element objects to records; List<String> the
        // simple case. An absent OPTIONAL list is the empty list (matches the old
        // itemSet "absent → match everything" lenient shape).
        if (List.class.isAssignableFrom(type)) {
            List<Object> out = new ArrayList<>();
            if (absent) {
                if (required && !nullable) throw new IllegalArgumentException("missing required argument: " + key);
                return out;
            }
            if (el.isJsonArray()) {
                for (JsonElement e : el.getAsJsonArray()) {
                    if (e == null || e.isJsonNull()) continue;
                    out.add(elemType != null && elemType.isRecord()
                            ? buildRecord(elemType, e.getAsJsonObject())
                            : e.getAsString());
                }
            }
            return out;
        }

        if (absent) {
            // Mandatory only when it's in `required` AND not nullable; otherwise null binds.
            if (required && !nullable) throw new IllegalArgumentException("missing required argument: " + key);
            return null;   // optional / nullable → boxed null
        }
        return coerceScalar(el, type, key);
    }

    private Object coerceScalar(JsonElement el, Class<?> type, String key) {
        try {
            if (type == String.class) return el.getAsString();
            if (type == int.class || type == Integer.class) return el.getAsInt();
            if (type == long.class || type == Long.class) return el.getAsLong();
            if (type == double.class || type == Double.class) return el.getAsDouble();
            if (type == float.class || type == Float.class) return el.getAsFloat();
            if (type == boolean.class || type == Boolean.class) return el.getAsBoolean();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("argument '" + key + "' has the wrong type");
        }
        throw new IllegalArgumentException("unsupported @Arg type for '" + key + "': " + type.getName());
    }

    /** Build a record element from a JSON object, one canonical-constructor arg per component. */
    private Object buildRecord(Class<?> recordClass, JsonObject obj) {
        java.lang.reflect.RecordComponent[] comps = recordClass.getRecordComponents();
        Object[] vals = new Object[comps.length];
        Class<?>[] types = new Class[comps.length];
        for (int i = 0; i < comps.length; i++) {
            java.lang.reflect.RecordComponent rc = comps[i];
            Arg arg = rc.getAnnotation(Arg.class);
            String field = (arg != null && !arg.name().isEmpty()) ? arg.name() : rc.getName();
            boolean req = arg == null || arg.required();
            boolean nul = arg != null && arg.nullable();
            types[i] = rc.getType();
            JsonElement fe = obj.get(field);
            if (fe == null || fe.isJsonNull()) {
                if (req && !nul) throw new IllegalArgumentException("missing field: " + field);
                vals[i] = null;
            } else {
                vals[i] = coerceScalar(fe, rc.getType(), field);
            }
        }
        try {
            java.lang.reflect.Constructor<?> ctor = recordClass.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return ctor.newInstance(vals);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalArgumentException(
                    "cannot build " + recordClass.getSimpleName() + ": " + ex.getMessage());
        }
    }

    /** Element class of a {@code List<T>} parameter, or null if not a parameterized list. */
    private static Class<?> listElement(Parameter p) {
        if (!List.class.isAssignableFrom(p.getType())) return null;
        if (p.getParameterizedType() instanceof java.lang.reflect.ParameterizedType pt
                && pt.getActualTypeArguments().length == 1
                && pt.getActualTypeArguments()[0] instanceof Class<?> elem) {
            return elem;
        }
        return null;
    }
}
