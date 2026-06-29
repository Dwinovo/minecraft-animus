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

    private enum Kind { QUERY, WORLD }

    private enum SlotType { ARG, ENTITY, CONTEXT }

    /** One method parameter: either a model argument or an injected context value. */
    private static final class Slot {
        final SlotType type;
        final String argName;     // ARG only
        final Class<?> argType;   // ARG only
        final boolean required;   // ARG only

        Slot(SlotType type, String argName, Class<?> argType, boolean required) {
            this.type = type;
            this.argName = argName;
            this.argType = argType;
            this.required = required;
        }
    }

    private final Object holder;
    private final Method method;
    private final String name;
    private final String description;
    private final Map<String, Object> schema;
    private final long timeoutTicks;
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
        this.timeoutTicks = action.timeoutTicks();

        boolean injectsEntity = false;
        boolean injectsContext = false;
        List<Slot> plan = new ArrayList<>();
        for (Parameter p : method.getParameters()) {
            Arg arg = p.getAnnotation(Arg.class);
            if (arg != null) {
                String argName = arg.name().isEmpty() ? p.getName() : arg.name();
                plan.add(new Slot(SlotType.ARG, argName, p.getType(), arg.required()));
            } else if (NumenPlayer.class.isAssignableFrom(p.getType())) {
                injectsEntity = true;
                plan.add(new Slot(SlotType.ENTITY, null, null, false));
            } else if (p.getType() == ToolContext.class) {
                injectsContext = true;
                plan.add(new Slot(SlotType.CONTEXT, null, null, false));
            } else {
                throw new IllegalArgumentException("@NumenAction " + name
                        + ": parameter " + p.getName() + " of type " + p.getType().getName()
                        + " is neither an @Arg nor an injectable context type");
            }
        }
        this.slots = plan.toArray(new Slot[0]);

        boolean returnsTaskRecord = TaskRecord.class.isAssignableFrom(method.getReturnType());
        boolean returnsValue = method.getReturnType() != void.class;
        if (returnsTaskRecord) {
            this.kind = Kind.WORLD;
        } else if (injectsEntity && returnsValue) {
            this.kind = Kind.QUERY;
        } else {
            throw new IllegalArgumentException("@NumenAction " + name
                    + ": tool shape not yet supported by the adapter (server query or body task only)");
        }
        if (kind == Kind.QUERY && injectsContext) {
            throw new IllegalArgumentException("@NumenAction " + name
                    + ": a query tool cannot take a ToolContext (no body task)");
        }
    }

    @Override public String name() { return name; }
    @Override public String description() { return description; }
    @Override public Map<String, Object> parameterSchema() { return schema; }
    @Override public long defaultTimeoutTicks() { return timeoutTicks; }

    @Override public boolean isQuery() { return kind == Kind.QUERY; }

    @Override
    public String executeQuery(JsonObject args, NumenPlayer entity) {
        Object result = invoke(buildArgs(args, entity, null));
        return resultToString(result);
    }

    @Override
    public TaskRecord toTaskRecord(String toolCallId, JsonObject args, long currentGameTime) {
        Object result = invoke(buildArgs(args, null, new ToolContext(toolCallId, currentGameTime)));
        return (TaskRecord) result;
    }

    private Object[] buildArgs(JsonObject args, NumenPlayer entity, ToolContext ctx) {
        Object[] argv = new Object[slots.length];
        for (int i = 0; i < slots.length; i++) {
            Slot s = slots[i];
            argv[i] = switch (s.type) {
                case ARG -> coerce(args, s.argName, s.argType, s.required);
                case ENTITY -> entity;
                case CONTEXT -> ctx;
            };
        }
        return argv;
    }

    private Object invoke(Object[] argv) {
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
    private Object coerce(JsonObject args, String key, Class<?> type, boolean required) {
        JsonElement el = args.get(key);
        if (el == null || el.isJsonNull()) {
            if (required) throw new IllegalArgumentException("missing required argument: " + key);
            return null;   // nullable arg → boxed null
        }
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
}
