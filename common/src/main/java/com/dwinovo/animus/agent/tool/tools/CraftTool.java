package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.entity.AnimusPlayer;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code craft} — make an item on a crafting grid, the whole flow in one call. It does for the model
 * exactly the CLICKS it would otherwise fumble by hand: look up the recipe, pick each ingredient out
 * of the inventory and drop one into its grid cell, then shift-click the result back into the
 * inventory — repeated up to {@code count}. Drives the menu's native {@code clicked} handler, so it
 * works on any crafting grid (vanilla 2x2/3x3 AND a modded table with a {@code CraftingContainer}).
 *
 * <p>≤2x2 recipes are made on the player's OWN 2x2 grid, no GUI needed. A 3x3 recipe needs the bigger
 * grid, so a crafting table must be open ({@code interact_at} it first) — otherwise we say so. Short
 * on materials → it reports how many it managed.
 */
public final class CraftTool implements AnimusTool {

    @Override
    public String name() {
        return "craft";
    }

    @Override
    public String description() {
        return "Craft an item into your inventory — give the item id and how many you want. We do the "
                + "whole click sequence for you (place the ingredients, take the result). Small recipes "
                + "(2x2: planks, sticks, torches, a crafting table…) are made on your own grid "
                + "automatically — no GUI needed. A 3x3 recipe (most tools, etc.) needs a crafting "
                + "table: interact_at one first, then craft. Short on materials → it reports how many it "
                + "made. No crafting recipe for the item → it's smelted / mined / traded instead.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("item_id", Map.of("type", "string",
                "description", "Namespaced item to craft, e.g. minecraft:stick."));
        properties.put("count", Map.of("type", List.of("integer", "null"),
                "description", "How many you want (default 1). Crafts until you have at least this "
                        + "many, or materials run out; may overshoot by one recipe's yield."));
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
        String name = BuiltInRegistries.ITEM.getKey(target).getPath();
        int count = (args.has("count") && !args.get("count").isJsonNull())
                ? Math.max(1, args.get("count").getAsInt()) : 1;
        if (!(entity.level() instanceof ServerLevel level)) {
            return "crafting needs a server level.";
        }

        // Every crafting recipe whose output is the target.
        List<CraftingRecipe> candidates = new ArrayList<>();
        for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
            if (!(holder.value() instanceof CraftingRecipe cr)) continue;
            ItemStack out;
            try {
                out = cr.assemble(CraftingInput.EMPTY);
            } catch (RuntimeException dependent) {
                continue;
            }
            if (!out.isEmpty() && out.getItem() == target) candidates.add(cr);
        }
        if (candidates.isEmpty()) {
            return "no crafting recipe for " + name + " — it's obtained another way (mine / smelt / "
                    + "trade), not crafted.";
        }

        boolean tableOpen = entity.containerMenu instanceof CraftingMenu;
        CraftingRecipe recipe = null;
        for (CraftingRecipe c : candidates) {
            if (tableOpen || fitsTwoByTwo(c)) { recipe = c; break; }
        }
        if (recipe == null) {
            return name + " needs a 3x3 grid — interact_at a crafting table first, then craft again.";
        }

        AbstractContainerMenu menu = tableOpen ? entity.containerMenu : entity.inventoryMenu;
        Grid grid = Grid.of(menu);
        if (grid == null) {
            return "the open GUI has no crafting grid; open a crafting table (or use your 2x2 grid).";
        }

        returnGrid(menu, entity, grid);   // start from a clean grid
        int crafted = 0;
        int guard = count + 64;           // never spin even if a recipe yields 0
        while (crafted < count && guard-- > 0) {
            if (!placeOnce(menu, entity, recipe, grid)) break;     // out of materials
            ItemStack result = menu.slots.get(grid.resultSlot).getItem();
            if (result.isEmpty()) break;                           // grid didn't form the recipe
            int per = result.getCount();
            menu.clicked(grid.resultSlot, 0, ContainerInput.QUICK_MOVE, entity);   // take it to the inventory
            crafted += per;
        }
        returnGrid(menu, entity, grid);   // tidy any leftover ingredients back

        if (crafted == 0) {
            return "couldn't craft " + name + " — not enough materials. lookup_recipe " + name
                    + " to see what's needed.";
        }
        if (crafted < count) {
            return "crafted " + crafted + "/" + count + " " + name + " — ran out of materials; the "
                    + crafted + " made are in your inventory.";
        }
        return "crafted " + crafted + " " + name + " — in your inventory.";
    }

    /** Lay one set of the recipe's ingredients into the grid, one click-place per cell. Returns false
     *  (and leaves nothing half-placed worth worrying about — {@code returnGrid} cleans up) if an
     *  ingredient isn't in the inventory. */
    private static boolean placeOnce(AbstractContainerMenu menu, AnimusPlayer entity,
                                     CraftingRecipe recipe, Grid grid) {
        Map<Integer, Ingredient> cells = new LinkedHashMap<>();   // grid position -> ingredient
        if (recipe instanceof ShapedRecipe s) {
            int w = s.getWidth();
            int h = s.getHeight();
            if (w > grid.width || h > grid.height) return false;
            List<Optional<Ingredient>> ings = s.getIngredients();
            for (int r = 0; r < h; r++) {
                for (int c = 0; c < w; c++) {
                    Optional<Ingredient> ing = ings.get(r * w + c);    // recipe stride = recipe width
                    if (ing.isPresent()) cells.put(r * grid.width + c, ing.get());   // grid stride = grid width
                }
            }
        } else {
            List<Ingredient> ings = recipe.placementInfo().ingredients();
            if (ings.size() > grid.cells.length) return false;
            for (int i = 0; i < ings.size(); i++) cells.put(i, ings.get(i));
        }
        for (Map.Entry<Integer, Ingredient> e : cells.entrySet()) {
            Slot cell = grid.cells[e.getKey()];
            if (cell == null) return false;
            int src = findIngredient(menu, entity, e.getValue());
            if (src < 0) return false;                              // missing this ingredient
            menu.clicked(src, 0, ContainerInput.PICKUP, entity);   // grab the source stack
            menu.clicked(cell.index, 1, ContainerInput.PICKUP, entity);   // drop ONE into the cell
            menu.clicked(src, 0, ContainerInput.PICKUP, entity);   // return the remainder
        }
        return true;
    }

    /** Menu slot index of an inventory stack that satisfies {@code ing}, or -1. */
    private static int findIngredient(AbstractContainerMenu menu, AnimusPlayer entity, Ingredient ing) {
        for (Slot s : menu.slots) {
            if (s.container == entity.getInventory() && !s.getItem().isEmpty() && matches(ing, s.getItem())) {
                return s.index;
            }
        }
        return -1;
    }

    @SuppressWarnings("deprecation")   // items() is the stable item enumeration of an Ingredient
    private static boolean matches(Ingredient ing, ItemStack stack) {
        return ing.items().anyMatch(h -> h.value() == stack.getItem());
    }

    /** Shift any ingredients still sitting in the craft grid back to the inventory. */
    private static void returnGrid(AbstractContainerMenu menu, AnimusPlayer entity, Grid grid) {
        for (Slot cell : grid.cells) {
            if (cell != null && !cell.getItem().isEmpty()) {
                menu.clicked(cell.index, 0, ContainerInput.QUICK_MOVE, entity);
            }
        }
    }

    private static boolean fitsTwoByTwo(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe s) {
            return s.getWidth() <= 2 && s.getHeight() <= 2;
        }
        return recipe.placementInfo().ingredients().size() <= 4;
    }

    /** The crafting grid of a menu: the {@link CraftingContainer} cells (row-major) + the result slot. */
    private record Grid(Slot[] cells, int width, int height, int resultSlot) {
        static Grid of(AbstractContainerMenu menu) {
            int width = 0, height = 0, resultSlot = -1;
            Slot[] cells = null;
            for (Slot s : menu.slots) {
                if (s instanceof ResultSlot) { resultSlot = s.index; continue; }
                if (s.container instanceof CraftingContainer cc) {
                    if (cells == null) {
                        width = cc.getWidth();
                        height = cc.getHeight();
                        cells = new Slot[width * height];
                    }
                    int pos = s.getContainerSlot();
                    if (pos >= 0 && pos < cells.length) cells[pos] = s;
                }
            }
            return (cells == null || resultSlot < 0) ? null : new Grid(cells, width, height, resultSlot);
        }
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
