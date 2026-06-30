package com.dwinovo.numen.core.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny <em>explicit</em> builder for a tool's OpenAI-style JSON schema — the
 * {@code Map} a {@link com.dwinovo.numen.agent.tool.NumenTool#parameterSchema}
 * returns. No reflection, no magic: you state each field yourself and stay in
 * full control. A tool whose shape this doesn't cover just returns its own
 * {@code Map} (or a parsed JSON string) — the engine only wants the Map.
 */
public final class Schema {

    private Schema() {}

    /** A no-argument tool's schema: an empty object. */
    public static Map<String, Object> none() {
        return new Builder().build();
    }

    public static Builder object() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, Object> props = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        public Builder integer(String name, String desc) {
            props.put(name, base("integer", desc));
            required.add(name);
            return this;
        }

        public Builder integer(String name, String desc, int min, int max) {
            Map<String, Object> p = base("integer", desc);
            p.put("minimum", min);
            p.put("maximum", max);
            props.put(name, p);
            required.add(name);
            return this;
        }

        public Builder number(String name, String desc, double min, double max) {
            Map<String, Object> p = base("number", desc);
            p.put("minimum", min);
            p.put("maximum", max);
            props.put(name, p);
            required.add(name);
            return this;
        }

        public Builder string(String name, String desc) {
            props.put(name, base("string", desc));
            required.add(name);
            return this;
        }

        /** Optional string — dropped from {@code required}, so a missing value binds as null. */
        public Builder optionalString(String name, String desc) {
            props.put(name, base("string", desc));
            return this;
        }

        /** Optional bounded integer — dropped from {@code required}. */
        public Builder optionalInteger(String name, String desc, int min, int max) {
            Map<String, Object> p = base("integer", desc);
            p.put("minimum", min);
            p.put("maximum", max);
            props.put(name, p);
            return this;
        }

        /** Optional enum string — dropped from {@code required}. */
        public Builder optionalEnum(String name, String desc, String... values) {
            Map<String, Object> p = base("string", desc);
            p.put("enum", List.of(values));
            props.put(name, p);
            return this;
        }

        /** Optional array of strings — dropped from {@code required}. */
        public Builder optionalStringArray(String name, String desc) {
            Map<String, Object> items = new LinkedHashMap<>();
            items.put("type", "string");
            Map<String, Object> arr = new LinkedHashMap<>();
            arr.put("type", "array");
            arr.put("description", desc);
            arr.put("items", items);
            props.put(name, arr);
            return this;
        }

        /** Required array of strings, with a minimum length. */
        public Builder stringArray(String name, String desc, int minItems) {
            Map<String, Object> items = new LinkedHashMap<>();
            items.put("type", "string");
            Map<String, Object> arr = new LinkedHashMap<>();
            arr.put("type", "array");
            arr.put("description", desc);
            arr.put("items", items);
            if (minItems > 0) arr.put("minItems", minItems);
            props.put(name, arr);
            required.add(name);
            return this;
        }

        public Builder bool(String name, String desc) {
            props.put(name, base("boolean", desc));
            required.add(name);
            return this;
        }

        /** A required string constrained to a fixed set of values. */
        public Builder enumStr(String name, String desc, String... values) {
            Map<String, Object> p = base("string", desc);
            p.put("enum", List.of(values));
            props.put(name, p);
            required.add(name);
            return this;
        }

        /** A required array whose items are an object built by {@code item}. */
        public Builder objectArray(String name, String desc, java.util.function.Consumer<Builder> item) {
            Builder ib = new Builder();
            item.accept(ib);
            Map<String, Object> items = new LinkedHashMap<>();
            items.put("type", "object");
            items.put("properties", ib.props);
            items.put("required", List.copyOf(ib.required));
            items.put("additionalProperties", false);
            Map<String, Object> arr = new LinkedHashMap<>();
            arr.put("type", "array");
            arr.put("description", desc);
            arr.put("items", items);
            props.put(name, arr);
            required.add(name);
            return this;
        }

        public Map<String, Object> build() {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("type", "object");
            root.put("properties", props);
            root.put("required", List.copyOf(required));
            root.put("additionalProperties", false);
            return root;
        }

        private static Map<String, Object> base(String type, String desc) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", type);
            p.put("description", desc);
            return p;
        }
    }
}
