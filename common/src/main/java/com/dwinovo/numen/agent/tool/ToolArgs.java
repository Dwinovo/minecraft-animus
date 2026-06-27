package com.dwinovo.numen.agent.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Shared argument readers for {@link NumenTool} implementations. Every tool hand-parses
 * the same handful of JSON-arg shapes — a required int, a nullable coordinate, a namespaced
 * item id — so the readers lived as private copies in a dozen tools. This collapses them
 * into one place with one set of error messages.
 *
 * <p>All throw {@link IllegalArgumentException} on malformed input; the payload handler and
 * agent loop turn that into a {@code success:false} tool result the model can read and correct.
 */
public final class ToolArgs {

    private ToolArgs() {}

    // ---- integers ----

    /** A required integer arg. */
    public static int requireInt(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        try {
            return args.get(key).getAsInt();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("argument '" + key + "' must be an integer");
        }
    }

    /** A required integer, clamped into {@code [min, max]}. */
    public static int requireInt(JsonObject args, String key, int min, int max) {
        return Math.clamp(requireInt(args, key), min, max);
    }

    /** A nullable integer arg: {@code null} when absent or JSON null. */
    public static Integer optionalInt(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        try {
            return args.get(key).getAsInt();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("argument '" + key + "' must be an integer or null");
        }
    }

    /** An optional integer that falls back to {@code fallback} when absent. */
    public static int optionalInt(JsonObject args, String key, int fallback) {
        Integer v = optionalInt(args, key);
        return v != null ? v : fallback;
    }

    // ---- doubles ----

    /** A required numeric arg. */
    public static double requireDouble(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        try {
            return args.get(key).getAsDouble();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("argument '" + key + "' must be a number");
        }
    }

    /** A required numeric arg, clamped into {@code [min, max]}. */
    public static double requireDouble(JsonObject args, String key, double min, double max) {
        return Math.clamp(requireDouble(args, key), min, max);
    }

    /** A nullable numeric arg: {@code null} when absent or JSON null. */
    public static Double optionalDouble(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        try {
            return args.get(key).getAsDouble();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("argument '" + key + "' must be a number or null");
        }
    }

    // ---- items ----

    /** A required namespaced item id under {@code key}. */
    public static Item requireItem(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        return parseItem(args.get(key).getAsString());
    }

    /** An optional namespaced item id under {@code key}: {@code null} when absent. */
    public static Item optionalItem(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        return parseItem(args.get(key).getAsString());
    }

    /** Parse a raw namespaced item id (e.g. {@code minecraft:diamond}) into a real item. */
    public static Item parseItem(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            throw new IllegalArgumentException("not a valid item id: " + id);
        }
        Item item = BuiltInRegistries.ITEM.get(rl);
        if (item == null || item == Items.AIR) {
            throw new IllegalArgumentException("unknown item: " + id);
        }
        return item;
    }

    /**
     * A lenient set of items from a string array under {@code key}: unparseable or unknown
     * ids are skipped, and an absent / non-array arg yields an empty set — the "match
     * everything" filter the collect/scan tools rely on.
     */
    public static Set<Item> itemSet(JsonObject args, String key) {
        Set<Item> out = new LinkedHashSet<>();
        if (!args.has(key) || !args.get(key).isJsonArray()) {
            return out;
        }
        for (JsonElement el : args.getAsJsonArray(key)) {
            if (el == null || el.isJsonNull()) continue;
            ResourceLocation id = ResourceLocation.tryParse(el.getAsString());
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                out.add(BuiltInRegistries.ITEM.get(id));
            }
        }
        return out;
    }

    // ---- positions ----

    /**
     * An optional block coordinate from {@code x}/{@code y}/{@code z}: all three present →
     * that position; none present → {@code null} (the caller auto-picks); a partial set is
     * an error.
     */
    public static BlockPos optionalPos(JsonObject args) {
        boolean hasX = args.has("x") && !args.get("x").isJsonNull();
        boolean hasY = args.has("y") && !args.get("y").isJsonNull();
        boolean hasZ = args.has("z") && !args.get("z").isJsonNull();
        if (!hasX && !hasY && !hasZ) return null;
        if (!(hasX && hasY && hasZ)) {
            throw new IllegalArgumentException("give all three of x/y/z to target a position, or none");
        }
        return new BlockPos(requireInt(args, "x"), requireInt(args, "y"), requireInt(args, "z"));
    }
}
