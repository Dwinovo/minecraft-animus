package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.entity.AnimusPlayer;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code place_recipe} — auto-fill the open recipe GUI for a target item, the way the vanilla recipe
 * book / JEI's "+" does. It drives {@link RecipeBookMenu#handlePlacement} (the native server-side
 * placement path), which moves the ingredients from the inventory into the menu's craft slots in the
 * correct shape — so the model never does the error-prone "map the recipe onto slot numbers" itself.
 *
 * <p>Because {@code handlePlacement} lives on {@link RecipeBookMenu} (not the crafting menu), one call
 * polymorphically covers the crafting table, the 2x2 inventory grid AND the furnace / blast furnace /
 * smoker — plus any mod menu that extends those. Custom machine menus (Mekanism &amp; co.) are NOT
 * recipe-book menus; for them the tool returns an actionable hint to use click_slot/quick_move instead
 * (their inputs are a few labelled slots the menu routes for you — no grid to lay out).
 */
public final class PlaceRecipeTool implements AnimusTool {

    @Override
    public String name() {
        return "place_recipe";
    }

    @Override
    public String description() {
        return "Auto-fill the crafting / smelting GUI you have open with the ingredients for an item — "
                + "like the recipe book or JEI's '+' button. Pulls the ingredients from your inventory "
                + "and lays them into the grid in the right shape FOR YOU (works on a crafting table, "
                + "your own 2x2 grid, and furnace / blast furnace / smoker). Open the right GUI first "
                + "(interact_at a table/furnace, or nothing for your 2x2), then call this. After it "
                + "places a crafting recipe, take the output with click_slot type=quick_move on the "
                + "result slot. Not enough materials → it tells you. A custom modded machine (not a "
                + "recipe-book GUI) → it tells you to load inputs with click_slot quick_move instead. "
                + "Prefer this over hand-placing ingredients with click_slot.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("item_id", Map.of("type", "string",
                "description", "Namespaced item you want to make, e.g. minecraft:oak_planks."));
        properties.put("craft_all", Map.of("type", List.of("boolean", "null"),
                "description", "true = fill the grid with whole stacks so a single quick_move on the "
                        + "result crafts as many as your materials allow; false (default) = one craft's "
                        + "worth."));
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
        boolean craftAll = args.has("craft_all") && !args.get("craft_all").isJsonNull()
                && args.get("craft_all").getAsBoolean();

        AbstractContainerMenu menu = entity.containerMenu;
        if (menu == null) {
            return "no GUI open. Open your 2x2 grid (inspect_gui), or interact_at a crafting table / "
                    + "furnace first.";
        }
        if (!(menu instanceof RecipeBookMenu rbMenu)) {
            return "the open GUI (" + menu.getClass().getSimpleName() + ") is a custom machine menu, not "
                    + "a recipe-book GUI, so it can't auto-fill. Load its inputs yourself: click_slot "
                    + "type=quick_move your input item (the menu routes it to the input slot); for a "
                    + "multi-input machine, inspect_gui then click_slot type=pickup each input into its "
                    + "slot.";
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            return "recipe placement needs a server level.";
        }

        RecipeBookType book = rbMenu.getRecipeBookType();
        RecipeType<?> wanted = recipeTypeFor(book);
        String station = stationName(book);

        // Find the recipe(s) of THIS station's type whose output is the target item. An ingredient is
        // usually a tag (e.g. "any log"), so a single recipe covers every variant — handlePlacement
        // pulls whatever matching item the inventory holds.
        RecipeBookMenu.PostPlaceAction action = null;
        boolean matched = false;
        Inventory inv = entity.getInventory();
        for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
            Recipe<?> recipe = holder.value();
            if (recipe.getType() != wanted) {
                continue;
            }
            ItemStack out;
            try {
                out = resultOf(recipe);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (out.isEmpty() || out.getItem() != target) {
                continue;
            }
            matched = true;
            // handlePlacement first returns any existing grid items to the inventory, then lays out the
            // recipe. NOTHING = it placed; PLACE_GHOST_RECIPE = not enough materials (would show a ghost).
            action = rbMenu.handlePlacement(craftAll, false, holder, level, inv);
            if (action == RecipeBookMenu.PostPlaceAction.NOTHING) {
                break;
            }
        }

        if (!matched) {
            return "no " + station + " recipe makes " + name + ". (Is the right GUI open? A 3x3 recipe "
                    + "needs a crafting table; a smelted item needs the matching furnace.)";
        }
        if (action != RecipeBookMenu.PostPlaceAction.NOTHING) {
            return "found the " + station + " recipe for " + name + ", but you don't have enough "
                    + "materials in your inventory to place it — gather the ingredients first "
                    + "(lookup_recipe " + name + " shows what's needed).";
        }

        if (book == RecipeBookType.CRAFTING) {
            int resultSlot = resultSlotIndex(menu);
            return "placed the " + name + " recipe into the crafting grid"
                    + (craftAll ? " (filled with whole stacks — one quick_move will craft them all)" : "")
                    + ". Take the output: click_slot type=quick_move slot " + resultSlot + ".";
        }
        return "loaded the input for " + name + " into the " + station + ". Make sure there's fuel "
                + "(click_slot type=quick_move a coal/charcoal — it routes to the fuel slot), then wait; "
                + "collect from the [output] slot.";
    }

    /** Output item of a crafting or single-item (cooking/stonecutter) recipe, without needing real input. */
    private static ItemStack resultOf(Recipe<?> recipe) {
        if (recipe instanceof CraftingRecipe cr) {
            return cr.assemble(CraftingInput.EMPTY);
        }
        if (recipe instanceof SingleItemRecipe sir) {
            return sir.assemble(new SingleRecipeInput(ItemStack.EMPTY));
        }
        return ItemStack.EMPTY;
    }

    private static RecipeType<?> recipeTypeFor(RecipeBookType book) {
        return switch (book) {
            case CRAFTING -> RecipeType.CRAFTING;
            case FURNACE -> RecipeType.SMELTING;
            case BLAST_FURNACE -> RecipeType.BLASTING;
            case SMOKER -> RecipeType.SMOKING;
        };
    }

    private static String stationName(RecipeBookType book) {
        return switch (book) {
            case CRAFTING -> "crafting";
            case FURNACE -> "furnace";
            case BLAST_FURNACE -> "blast furnace";
            case SMOKER -> "smoker";
        };
    }

    private static int resultSlotIndex(AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            if (slot instanceof ResultSlot) {
                return slot.index;
            }
        }
        return 0;
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
