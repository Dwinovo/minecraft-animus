package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.agent.tool.api.Arg;
import com.dwinovo.numen.agent.tool.api.NumenAction;
import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.agent.tool.api.ToolSchema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskRecord;
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
 * runtime bridge from the annotation authoring surface to the existing tool
 * machinery. Name / description / schema come from {@link ToolSchema}; calling
 * the tool binds the model's JSON arguments to the method's typed parameters,
 * injects engine context by type, and invokes the method.
 *
 * <h2>Where it runs — inferred, not declared</h2>
 * The author never tags a category. The adapter infers it from the signature:
 * <ul>
 *   <li>returns a {@link TaskRecord} → a body <em>world-action</em>: shipped to
 *       the server, where {@link #toTaskRecord} reflectively builds the record
 *       and the task queue runs it (the method takes a {@link ToolContext} for
 *       the call id / deadline);</li>
 *   <li>takes the live {@link NumenPlayer} body and returns a value → a
 *       server-side <em>query</em>: runs on the tick thread, replies in place.</li>
 * </ul>
 * Pure-client tools are added as more tools migrate; an unrecognised shape
 * raises a clear error at registration.
 */
public final class NumenActionTool implements NumenTool {

    private enum Kind { QUERY, WORLD, ASYNC, LOCAL }

    private enum SlotType { ARG, ENTITY, CONTEXT, CLIENT, REPLY }

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
    }

    private final Object holder;
    private final Method method;
    private final String name;
    private final String description;
    private final Map<String, Object> schema;
    private final Slot[] slots;
    private final Kind kind;

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
        boolean injectsContext = false;
        boolean injectsReply = false;
        List<Slot> plan = new ArrayList<>();
        for (Parameter p : method.getParameters()) {
            Arg arg = p.getAnnotation(Arg.class);
            if (arg != null) {
                String argName = arg.name().isEmpty() ? p.getName() : arg.name();
                plan.add(new Slot(SlotType.ARG, argName, p.getType(), listElement(p),
                        arg.required(), arg.nullable()));
            } else if (NumenPlayer.class.isAssignableFrom(p.getType())) {
                injectsEntity = true;
                plan.add(new Slot(SlotType.ENTITY, null, null, null, false, false));
            } else if (p.getType() == ToolContext.class) {
                injectsContext = true;
                plan.add(new Slot(SlotType.CONTEXT, null, null, null, false, false));
            } else if (p.getType() == java.util.function.Consumer.class) {
                injectsReply = true;
                plan.add(new Slot(SlotType.REPLY, null, null, null, false, false));
            } else if (p.getType() == ClientToolContext.class) {
                plan.add(new Slot(SlotType.CLIENT, null, null, null, false, false));
            } else {
                throw new IllegalArgumentException("@NumenAction " + name
                        + ": parameter " + p.getName() + " of type " + p.getType().getName()
                        + " is neither an @Arg nor an injectable context type");
            }
        }
        this.slots = plan.toArray(new Slot[0]);

        boolean returnsTaskRecord = TaskRecord.class.isAssignableFrom(method.getReturnType());
        boolean returnsValue = method.getReturnType() != void.class;
        // Where a tool runs is inferred from what it reaches for, not declared:
        if (injectsReply) {
            this.kind = Kind.ASYNC;          // budget-sliced server job; replies via the callback
        } else if (returnsTaskRecord) {
            this.kind = Kind.WORLD;          // body task, queued on the server
        } else if (injectsEntity && returnsValue) {
            this.kind = Kind.QUERY;          // synchronous read against the server body
        } else if (returnsValue) {
            this.kind = Kind.LOCAL;          // client-side bookkeeping; no server body
        } else {
            throw new IllegalArgumentException("@NumenAction " + name
                    + ": unsupported tool shape (a void method must take a reply Consumer)");
        }
        if (kind == Kind.QUERY && injectsContext) {
            throw new IllegalArgumentException("@NumenAction " + name
                    + ": a query tool cannot take a ToolContext (no body task)");
        }
    }

    @Override public String name() { return name; }
    @Override public String description() { return description; }
    @Override public Map<String, Object> parameterSchema() { return schema; }

    @Override
    public void invoke(ToolCall call) {
        // Client-side: a LOCAL tool runs here and completes; everything else is the
        // server body's job and ships over the network.
        if (kind == Kind.LOCAL) {
            String resultJson;
            try {
                resultJson = resultToString(reflectInvoke(
                        buildArgs(call.args(), null, null, call.ctx(), null)));
            } catch (RuntimeException ex) {
                resultJson = TaskResult.fail(ex.getMessage()).toJson();
            }
            call.complete(resultJson);
        } else {
            call.shipToServer();
        }
    }

    /**
     * Numen's own Minecraft server-side execution — called by the core network
     * transport ({@code ExecuteToolPayload}), NOT part of the {@link NumenTool}
     * contract. A read replies immediately, a sliced job replies later, a body
     * action enqueues a task whose result returns via the task lifecycle.
     */
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        switch (kind) {
            case QUERY ->
                reply.accept(resultToString(reflectInvoke(buildArgs(args, companion, null, null, null))));
            case ASYNC ->
                reflectInvoke(buildArgs(args, companion, null, null, reply));   // void; replies via `reply`
            case WORLD -> {
                long gameTime = companion.level().getGameTime();
                TaskRecord record = (TaskRecord) reflectInvoke(
                        buildArgs(args, null, new ToolContext(toolCallId, gameTime), null, null));
                companion.getTaskQueue().enqueue(record);   // result returns via the task lifecycle
            }
            case LOCAL -> throw new IllegalStateException(
                    "local tool " + name + " was shipped to the server");
        }
    }

    /** Offline arg validation (coerce + discard) for the benchmark; not part of the contract. */
    public void checkArgs(JsonObject args) {
        // Coerce every model argument and discard — throws IllegalArgumentException
        // on a missing/ill-typed arg, without executing the tool.
        for (Slot s : slots) {
            if (s.type == SlotType.ARG) {
                coerce(args, s.argName, s.argType, s.elemType, s.required, s.nullable);
            }
        }
    }

    private Object[] buildArgs(JsonObject args, NumenPlayer entity, ToolContext ctx,
                               ClientToolContext clientCtx, Consumer<String> reply) {
        Object[] argv = new Object[slots.length];
        for (int i = 0; i < slots.length; i++) {
            Slot s = slots[i];
            argv[i] = switch (s.type) {
                case ARG -> coerce(args, s.argName, s.argType, s.elemType, s.required, s.nullable);
                case ENTITY -> entity;
                case CONTEXT -> ctx;
                case CLIENT -> clientCtx;
                case REPLY -> reply;
            };
        }
        return argv;
    }

    private Object reflectInvoke(Object[] argv) {
        try {
            return method.invoke(holder, argv);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            // Argument-validation failures surface as a failed tool result; the
            // caller (payload handler / agent loop) converts IllegalArgumentException.
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
