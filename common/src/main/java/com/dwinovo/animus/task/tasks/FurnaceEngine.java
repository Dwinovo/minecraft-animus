package com.dwinovo.animus.task.tasks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reusable, entity-agnostic furnace logic shared by the smelting tools
 * ({@link LoadFurnaceTaskGoal} / {@link CheckFurnaceTaskGoal} /
 * {@link CollectFurnaceTaskGoal}). Mirrors the role {@link CraftingEngine} plays
 * for crafting: the goals own lifecycle / pathing / placement, this class owns
 * the recipe + fuel math and the block-entity slot reads/writes.
 *
 * <h2>Real furnace, not simulated</h2>
 * We feed the actual {@link AbstractFurnaceBlockEntity}'s slots and let vanilla's
 * {@code serverTick} do the smelting — it lights the block ({@code LIT}
 * blockstate → free fire/smoke particles + crackle sound), burns fuel, and
 * produces output over real vanilla time, with no entity needing to stand there.
 * That's exactly why smelting is asynchronous: load and walk away, the world
 * furnace keeps going; come back to collect.
 *
 * <h2>Slots</h2>
 * Vanilla furnace slot layout (stable across furnace/blast/smoker):
 * 0 = input, 1 = fuel, 2 = result.
 */
public final class FurnaceEngine {

    public static final int SLOT_INPUT = 0;
    public static final int SLOT_FUEL = 1;
    public static final int SLOT_RESULT = 2;

    /** Standard furnace cook time per item (ticks); recipes override via cookingTime(). */
    public static final int STANDARD_COOK_TIME = 200;

    private FurnaceEngine() {}

    /** The furnace (or blast furnace / smoker) block entity at {@code pos}, or null. */
    public static AbstractFurnaceBlockEntity furnaceAt(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity f ? f : null;
    }

    /**
     * The smelting recipe that consumes {@code input}, or null if the item can't
     * be smelted in a regular furnace. Iterates loaded recipes and matches on the
     * input (same reverse-from-vanilla approach as {@link CraftingEngine}).
     */
    public static SmeltingRecipe smeltingRecipe(ServerLevel level, ItemStack input) {
        if (input.isEmpty()) return null;
        SingleRecipeInput probe = new SingleRecipeInput(input);
        for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
            if (holder.value() instanceof SmeltingRecipe sr && sr.matches(probe, level)) {
                return sr;
            }
        }
        return null;
    }

    /** Result stack of smelting one {@code input}, for reporting to the model. */
    public static ItemStack smeltResult(ServerLevel level, SmeltingRecipe recipe, ItemStack input) {
        return recipe.assemble(new SingleRecipeInput(input));
    }

    /**
     * How many fuel items are needed to smelt {@code items} inputs, given the
     * per-item burn time of {@code fuelSample} and the recipe's cook time. Uses
     * vanilla data-driven burn values ({@code level.fuelValues()}) — no hardcoding.
     * Returns 0 if the sample isn't a valid fuel.
     */
    public static int fuelItemsNeeded(Level level, ItemStack fuelSample, int items, int cookTime) {
        int burnPerItem = level.fuelValues().burnDuration(fuelSample);
        if (burnPerItem <= 0) return 0;
        long totalTicks = (long) items * cookTime;
        return (int) Math.ceil((double) totalTicks / burnPerItem);
    }

    /** Is {@code stack} usable as furnace fuel (vanilla burn value > 0)? */
    public static boolean isFuel(Level level, ItemStack stack) {
        return level.fuelValues().isFuel(stack);
    }

    /** Is the furnace block currently lit (burning fuel)? Reads the synced blockstate. */
    public static boolean isLit(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT);
    }

    /**
     * Snapshot a furnace's slots + lit flag into the structured {@code data} map
     * the LLM reads (keys are flat scalars per {@link com.dwinovo.animus.task.TaskResult}).
     * Shared by check + collect so both report the same shape.
     */
    public static Map<String, Object> describe(Level level, BlockPos pos, AbstractFurnaceBlockEntity f) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("x", pos.getX());
        data.put("y", pos.getY());
        data.put("z", pos.getZ());
        data.put("lit", isLit(level, pos));
        putSlot(data, "input", f.getItem(SLOT_INPUT));
        putSlot(data, "fuel", f.getItem(SLOT_FUEL));
        putSlot(data, "output", f.getItem(SLOT_RESULT));
        // Rough remaining estimate: each queued input item is ~one standard smelt.
        int remaining = f.getItem(SLOT_INPUT).getCount();
        data.put("items_left_to_smelt", remaining);
        data.put("eta_seconds_approx", remaining * STANDARD_COOK_TIME / 20);
        return data;
    }

    private static void putSlot(Map<String, Object> data, String prefix, ItemStack stack) {
        if (stack.isEmpty()) {
            data.put(prefix + "_item", "empty");
            data.put(prefix + "_count", 0);
        } else {
            data.put(prefix + "_item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            data.put(prefix + "_count", stack.getCount());
        }
    }
}
