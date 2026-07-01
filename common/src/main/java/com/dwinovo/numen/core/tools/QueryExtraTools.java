package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Query tools authored on the {@link NumenAction} surface — migrated faithfully
 * from the hand-written {@code NumenTool} classes they replace. Behaviour is
 * identical; only the wiring (auto-derived schema, reflective invoke, entity
 * injected by type) changed.
 */
public final class QueryExtraTools {

    // ---- scan_nearby_entities ----

    private static final int MAX_RESULTS = 20;
    private static final double MIN_RADIUS = 1.0;
    private static final double MAX_RADIUS = 64.0;

    public String scanNearbyEntities(
double radius,
String type_filter,
            NumenPlayer self) {
        radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
        String filter = readEnum("type_filter", type_filter,
                List.of("hostile", "passive", "player", "all"));

        AABB box = self.getBoundingBox().inflate(radius);
        List<Entity> raw = self.level().getEntities(self, box);

        List<ScoredEntity> matched = new ArrayList<>(raw.size());
        for (Entity e : raw) {
            String cat = categorise(e);
            if (!matches(filter, cat)) continue;
            matched.add(new ScoredEntity(e, cat, self.distanceTo(e)));
        }
        matched.sort(Comparator.comparingDouble(s -> s.distance));

        JsonArray entities = new JsonArray();
        int limit = Math.min(matched.size(), MAX_RESULTS);
        for (int i = 0; i < limit; i++) {
            ScoredEntity s = matched.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("id", s.entity.getId());
            o.addProperty("type", s.entity.getType().getDescriptionId());
            o.addProperty("category", s.category);
            JsonObject pos = new JsonObject();
            pos.addProperty("x", s.entity.getX());
            pos.addProperty("y", s.entity.getY());
            pos.addProperty("z", s.entity.getZ());
            o.add("position", pos);
            o.addProperty("distance", s.distance);
            if (s.entity instanceof LivingEntity le) {
                o.addProperty("hp", le.getHealth());
                o.addProperty("max_hp", le.getMaxHealth());
            }
            entities.add(o);
        }

        JsonObject root = new JsonObject();
        root.add("entities", entities);
        root.addProperty("total_found", matched.size());
        root.addProperty("truncated", matched.size() > MAX_RESULTS);
        root.addProperty("radius_searched", radius);
        root.addProperty("filter", filter);
        return root.toString();
    }

    private static String categorise(Entity e) {
        if (e instanceof Player) return "player";
        if (e instanceof Monster) return "hostile";
        return "passive";
    }

    private static boolean matches(String filter, String category) {
        if ("all".equals(filter)) return true;
        return filter.equals(category);
    }

    private record ScoredEntity(Entity entity, String category, double distance) {}

    private static String readEnum(String key, String value, List<String> allowed) {
        if (value == null) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        String v = value;
        if (!allowed.contains(v)) {
            throw new IllegalArgumentException(
                    "argument '" + key + "' must be one of " + allowed + ", got: " + v);
        }
        return v;
    }

    // ---- lookup_recipe ----

    /** Cap recipes per lookup — enough variants to choose from without a token bomb. */
    private static final int MAX_RECIPES = 4;

    public String lookupRecipe(
String item_id,
            NumenPlayer self) {
        Item target = ToolArgs.parseItem(item_id);
        if (!(self.level() instanceof ServerLevel level)) {
            return TaskResult.fail("recipe lookup needs a server level.").toJson();
        }
        String name = BuiltInRegistries.ITEM.getKey(target).getPath();

        List<String> recipes = new ArrayList<>();
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            if (recipes.size() >= MAX_RECIPES) {
                break;
            }
            Recipe<?> r = holder.value();
            if (r instanceof CraftingRecipe cr) {
                // 1.21.1 has no PlacementInfo; a special recipe (firework, map-clone, …) has no
                // static ingredient list — an empty list, or all-empty cells.
                if (cr.getIngredients().isEmpty() || cr.getIngredients().stream().allMatch(Ingredient::isEmpty)) {
                    continue;
                }
                ItemStack result;
                try {
                    result = cr.getResultItem(level.registryAccess());   // shaped/shapeless ignore input
                } catch (RuntimeException inputDependent) {
                    continue;
                }
                if (result.isEmpty() || result.getItem() != target) {
                    continue;
                }
                recipes.add("[crafting] " + format(cr, result));
            } else if (r instanceof AbstractCookingRecipe cook) {
                ItemStack result;
                try {
                    result = cook.getResultItem(level.registryAccess());   // ignores input
                } catch (RuntimeException inputDependent) {
                    continue;
                }
                if (result.isEmpty() || result.getItem() != target) {
                    continue;
                }
                recipes.add(formatCooking(cook, result));
            } else if (r instanceof StonecutterRecipe sc) {
                ItemStack result;
                try {
                    result = sc.getResultItem(level.registryAccess());     // ignores input
                } catch (RuntimeException inputDependent) {
                    continue;
                }
                if (result.isEmpty() || result.getItem() != target) {
                    continue;
                }
                recipes.add("[stonecutter] " + describeIngredient(sc.getIngredients().get(0))
                        + " -> makes " + result.getCount());
            } else if (r instanceof SmithingRecipe sm) {
                ItemStack result;
                try {
                    // A transform recipe (netherite upgrade etc.) yields its result item; a cosmetic
                    // trim recipe yields the (empty) base, so it self-excludes.
                    result = sm.getResultItem(level.registryAccess());
                } catch (RuntimeException inputDependent) {
                    continue;
                }
                if (result.isEmpty() || result.getItem() != target) {
                    continue;
                }
                recipes.add(formatSmithing(sm, result));
            }
        }

        if (recipes.isEmpty()) {
            return TaskResult.ok("no recipe for " + name + " — it's obtained another way (mine it, or "
                    + "trade), not crafted or smelted.").toJson();
        }
        return TaskResult.ok("recipe(s) for " + name + ":\n\n" + String.join("\n\n", recipes) + "\n\n"
                + "To make it —\n"
                + "• [crafting]: open the grid (2x2 = your own, inspect_gui with nothing open; 3x3 = "
                + "interact_at a crafting table), then transfer each ingredient into its cell per the "
                + "layout above — one item per cell (count:1), matched top-left — and transfer the "
                + "result slot out (no `to`). Repeat to make more.\n"
                + "• [smelting|blasting|smoking]: interact_at the furnace, then transfer the input and "
                + "the fuel with NO `to` — the menu routes each to its slot. Wait, then transfer the "
                + "output back out.\n"
                + "• [stonecutter]: interact_at it, transfer the input (no `to` routes it in), take the "
                + "output. [smithing]: interact_at it, inspect_gui, then transfer template + base + "
                + "addition each into its own slot (give `to`).").toJson();
    }

    private static String format(CraftingRecipe recipe, ItemStack result) {
        int count = result.getCount();
        if (recipe instanceof ShapedRecipe shaped) {
            int w = shaped.getWidth();
            int h = shaped.getHeight();
            var cells = shaped.getIngredients();   // 1.21.1: NonNullList<Ingredient>, gaps = Ingredient.EMPTY
            StringBuilder sb = new StringBuilder("shaped " + w + "x" + h + ", makes " + count + ":");
            for (int r = 0; r < h; r++) {
                sb.append("\n  ");
                for (int c = 0; c < w; c++) {
                    Ingredient ing = cells.get(r * w + c);
                    sb.append(ing.isEmpty() ? "." : describeIngredient(ing));
                    if (c < w - 1) {
                        sb.append(" | ");
                    }
                }
            }
            return sb.toString();
        }
        // Shapeless: order doesn't matter, place anywhere.
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) continue;
            counts.merge(describeIngredient(ing), 1, Integer::sum);
        }
        String list = counts.entrySet().stream()
                .map(e -> e.getValue() + "x " + e.getKey())
                .collect(Collectors.joining(", "));
        return "shapeless, makes " + count + ": " + list + " (place anywhere in the grid)";
    }

    /** A cooking recipe (one input → one output) labelled by its station. */
    private static String formatCooking(AbstractCookingRecipe recipe, ItemStack result) {
        RecipeType<?> type = recipe.getType();
        String station = type == RecipeType.BLASTING ? "blasting (blast furnace)"
                : type == RecipeType.SMOKING ? "smoking (smoker)"
                : type == RecipeType.CAMPFIRE_COOKING ? "campfire"
                : "smelting (furnace)";
        return "[" + station + "] " + describeIngredient(recipe.getIngredients().get(0)) + " -> makes "
                + result.getCount() + " (" + recipe.getCookingTime() + " ticks)";
    }

    /** A smithing recipe (smithing table). 1.21.1's SmithingRecipe exposes only is*Ingredient(stack)
     *  tests — no ingredient getters — so we can't enumerate the inputs; describe the station + result. */
    private static String formatSmithing(SmithingRecipe recipe, ItemStack result) {
        return "[smithing] (smithing table: template + base + addition) -> makes " + result.getCount();
    }

    /** Name an ingredient: a single item directly, a shared-suffix tag as "planks (any)", else a few
     *  members — so a category ingredient doesn't mislead the model into one specific item. */
    private static String describeIngredient(Ingredient ing) {
        List<String> paths = java.util.Arrays.stream(ing.getItems())   // 1.21.1: getItems() -> ItemStack[]
                .map(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath())
                .distinct()
                .toList();
        if (paths.isEmpty()) {
            return "?";
        }
        if (paths.size() == 1) {
            return paths.get(0);
        }
        String suffix = commonSuffixToken(paths);
        if (suffix != null) {
            return suffix + "(any)";
        }
        return "any[" + paths.stream().limit(3).collect(Collectors.joining("/"))
                + (paths.size() > 3 ? "/…" : "") + "]";
    }

    private static String commonSuffixToken(List<String> paths) {
        String token = null;
        for (String p : paths) {
            int u = p.lastIndexOf('_');
            String t = u < 0 ? p : p.substring(u + 1);
            if (token == null) {
                token = t;
            } else if (!token.equals(t)) {
                return null;
            }
        }
        return token;
    }

    // ---- inspect_block_storage ----

    public String inspectBlockStorage(int x,
int y,
int z,
                                      NumenPlayer self) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = self.level().getBlockState(pos);
        String coord = x + "," + y + "," + z;
        if (state.isAir()) {
            return TaskResult.fail("block at " + coord + " is air — nothing to read.").toJson();
        }
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String caps = Services.CAPS.describe(self.level(), pos);
        if (caps == null || caps.isBlank()) {
            return TaskResult.ok(id + " at " + coord + " exposes no item/fluid/energy storage "
                    + "(not a machine/tank/battery, or it keeps its state elsewhere). "
                    + "If it has a GUI, right-click it then use inspect_gui.").toJson();
        }
        return TaskResult.ok(id + " at " + coord + ":\n" + caps).toJson();
    }
}
