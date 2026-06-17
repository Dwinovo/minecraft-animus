package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.entity.AnimusPlayer;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;

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
 * recipes, the grid layout — the model then places them itself with the GUI primitives (inspect_gui +
 * click_slot) in an open crafting table. A read-only server query.
 */
public final class LookupRecipeTool implements AnimusTool {

    /** Cap recipes per lookup — enough variants to choose from without a token bomb. */
    private static final int MAX_RECIPES = 4;

    @Override
    public String name() {
        return "lookup_recipe";
    }

    @Override
    public String description() {
        return "Look up how to make an item — like JEI. Returns every recipe whose output is this item: "
                + "crafting recipes (with the grid layout) AND smelting / blasting / smoking recipes "
                + "(with the input + station), each tagged [crafting] / [smelting] / …. Then make it: "
                + "open the matching station (crafting table / furnace / your 2x2 grid) and call "
                + "place_recipe to auto-fill the ingredients. No recipe found = the item is mined or "
                + "traded, not made.";
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
    public String executeQuery(JsonObject args, AnimusPlayer entity) {
        if (!args.has("item_id") || args.get("item_id").isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: item_id");
        }
        Item target = readItem(args.get("item_id").getAsString());
        if (!(entity.level() instanceof ServerLevel level)) {
            return "recipe lookup needs a server level.";
        }
        String name = BuiltInRegistries.ITEM.getKey(target).getPath();

        List<String> recipes = new ArrayList<>();
        for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
            if (recipes.size() >= MAX_RECIPES) {
                break;
            }
            Recipe<?> r = holder.value();
            if (r instanceof CraftingRecipe cr) {
                PlacementInfo info = cr.placementInfo();
                if (info.isImpossibleToPlace() || info.ingredients().isEmpty()) {
                    continue;   // special / no static ingredient list (firework, map-clone, …)
                }
                ItemStack result;
                try {
                    result = cr.assemble(CraftingInput.EMPTY);   // shaped/shapeless ignore input
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
                    result = cook.assemble(new SingleRecipeInput(ItemStack.EMPTY));   // ignores input
                } catch (RuntimeException inputDependent) {
                    continue;
                }
                if (result.isEmpty() || result.getItem() != target) {
                    continue;
                }
                recipes.add(formatCooking(cook, result));
            }
        }

        if (recipes.isEmpty()) {
            return "no recipe for " + name + " — it's obtained another way (mine it, or trade), not "
                    + "crafted or smelted.";
        }
        return "recipe(s) for " + name + ":\n\n" + String.join("\n\n", recipes) + "\n\n"
                + "To make it: open the matching station — interact_at a crafting table or furnace / "
                + "blast furnace / smoker (small crafting recipes also fit your own 2x2 grid) — then call "
                + "place_recipe " + name + " to auto-fill the ingredients for you, and click_slot "
                + "type=quick_move the result slot to take the output. (Smelting: also add fuel and wait.) "
                + "Only hand-place with click_slot if place_recipe can't (a custom modded machine).";
    }

    private static String format(CraftingRecipe recipe, ItemStack result) {
        int count = result.getCount();
        if (recipe instanceof ShapedRecipe shaped) {
            int w = shaped.getWidth();
            int h = shaped.getHeight();
            List<Optional<Ingredient>> cells = shaped.getIngredients();   // row-major, gaps = empty
            StringBuilder sb = new StringBuilder("shaped " + w + "x" + h + ", makes " + count + ":");
            for (int r = 0; r < h; r++) {
                sb.append("\n  ");
                for (int c = 0; c < w; c++) {
                    Optional<Ingredient> ing = cells.get(r * w + c);
                    sb.append(ing.map(LookupRecipeTool::describeIngredient).orElse("."));
                    if (c < w - 1) {
                        sb.append(" | ");
                    }
                }
            }
            return sb.toString();
        }
        // Shapeless: order doesn't matter, place anywhere.
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Ingredient ing : recipe.placementInfo().ingredients()) {
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
                : "smelting (furnace)";
        return "[" + station + "] " + describeIngredient(recipe.input()) + " -> makes "
                + result.getCount() + " (" + recipe.cookingTime() + " ticks)";
    }

    /** Name an ingredient: a single item directly, a shared-suffix tag as "planks (any)", else a few
     *  members — so a category ingredient doesn't mislead the model into one specific item. */
    @SuppressWarnings("deprecation")   // Ingredient.items() is the only stable item enumeration
    private static String describeIngredient(Ingredient ing) {
        List<String> paths = ing.items()
                .map(h -> BuiltInRegistries.ITEM.getKey(h.value()).getPath())
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

    private static Item readItem(String id) {
        Identifier rl = Identifier.tryParse(id);
        if (rl == null) {
            throw new IllegalArgumentException("not a valid item id: " + id);
        }
        Item item = BuiltInRegistries.ITEM.getValue(rl);
        if (item == null || item == Items.AIR) {
            throw new IllegalArgumentException("unknown item: " + id);
        }
        return item;
    }
}
