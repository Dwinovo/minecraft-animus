package com.dwinovo.numen.agent.tool.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@code lookup_recipe} — look up the crafting recipe(s) for an output item, the way JEI does: iterate
 * the server's {@code RecipeManager} and read each recipe's OWN ingredient data (no per-recipe
 * adapters), so it works for vanilla and modded recipes alike. Returns the ingredients and, for shaped
 * recipes, the grid layout — for crafting the model just calls {@code craft}; for other stations it
 * loads the inputs with {@code transfer}. A read-only server query.
 */
public final class LookupRecipeTool implements NumenTool {

    /** Cap recipes per lookup — enough variants to choose from without a token bomb. */
    private static final int MAX_RECIPES = 4;

    @Override
    public String name() {
        return "lookup_recipe";
    }

    @Override
    public String description() {
        return "Look up how to make an item — like JEI. Returns every recipe whose output is this item, "
                + "across all stations: crafting (with the grid layout), smelting / blasting / smoking, "
                + "stonecutting, and smithing — each tagged [crafting] / [smelting] / [stonecutter] / "
                + "[smithing] / …. Then make it: [crafting] → transfer each ingredient into a grid cell "
                + "(your own 2x2, or a crafting table for 3x3); "
                + "[smelting] → open the furnace and transfer the input + fuel; [stonecutter] / "
                + "[smithing] → open the station and transfer the inputs. No recipe found = the item "
                + "is mined or traded, not made.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("item_id", Map.of("type", "string",
                "description", "Namespaced output item, e.g. minecraft:diamond_pickaxe."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("item_id"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public long defaultTimeoutTicks() {
        return 20;
    }

    @Override
    public boolean isQuery() {
        return true;
    }

    @Override
    public String executeQuery(JsonObject args, NumenPlayer entity) {
        if (!args.has("item_id") || args.get("item_id").isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: item_id");
        }
        Item target = ToolArgs.parseItem(args.get("item_id").getAsString());
        if (!(entity.level() instanceof ServerLevel level)) {
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
                    result = cr.assemble(CraftingInput.EMPTY, level.registryAccess());   // shaped/shapeless ignore input
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
                    result = cook.assemble(new SingleRecipeInput(ItemStack.EMPTY), level.registryAccess());   // ignores input
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
                    result = sc.assemble(new SingleRecipeInput(ItemStack.EMPTY), level.registryAccess());     // ignores input
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
                    // Empty input: a transform recipe (netherite upgrade etc.) still yields its result
                    // item; a cosmetic trim recipe yields the (empty) base, so it self-excludes.
                    result = sm.assemble(new SmithingRecipeInput(
                            ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY), level.registryAccess());
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

}
