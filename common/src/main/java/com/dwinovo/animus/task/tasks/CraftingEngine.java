package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reusable, entity-agnostic crafting logic shared by {@link CraftTaskGoal}.
 * Mirrors the role {@link BlockMiningProgress} plays for mining: the goal owns
 * lifecycle / pathing / table logistics, this class owns the recipe math.
 *
 * <h2>Reverse lookup</h2>
 * The LLM names a desired <em>output</em> item; vanilla's {@code RecipeManager}
 * only matches <em>inputs</em> to recipes. So we iterate every loaded
 * {@link CraftingRecipe} and keep the ones whose fixed result is the target.
 * "Fixed result" is read via {@code recipe.assemble(CraftingInput.EMPTY)} —
 * shaped/shapeless recipes ignore the input and return their result template,
 * so an empty input is enough to read the output without knowing the layout.
 * Recipes whose result depends on the input (transmute / special) throw or
 * return empty on an empty input and are simply skipped — they aren't
 * reverse-lookup-able by a static item id anyway.
 *
 * <h2>Material matching</h2>
 * {@link PlacementInfo#ingredients()} is the flat list of per-slot
 * {@link Ingredient}s the recipe needs (one item each), uniform across shaped
 * and shapeless. We greedily reserve one matching inventory stack per
 * ingredient; if every ingredient finds a home the craft is feasible.
 *
 * <h2>2×2 vs 3×3</h2>
 * A recipe fits the inventory's 2×2 grid (no crafting table) iff its bounding
 * box is ≤ 2×2 — shaped: width/height ≤ 2; shapeless: ≤ 4 ingredients.
 *
 * <p>Crafting is virtual: we consume ingredients straight from the entity's
 * {@link SimpleContainer} and deposit the result + any container remainders
 * (empty buckets, glass bottles) back into it. The "table" is a positional
 * gate enforced by the goal, not a real GUI.
 */
public final class CraftingEngine {

    private CraftingEngine() {}

    /** A resolved recipe choice plus the derived facts the goal needs. */
    public record Plan(CraftingRecipe recipe, boolean needsTable, ItemStack resultPreview) {}

    /**
     * Reverse-lookup a crafting recipe producing {@code target}. Among all
     * recipes that yield the target, prefer one the inventory can satisfy right
     * now; otherwise pick the recipe with the <em>fewest missing</em> ingredients
     * — so the "missing materials" report points the model at the path it's
     * closest to completing, not an arbitrary first recipe. This mirrors
     * Voyager's {@code failedCraftFeedback}, which scans all recipe variants and
     * reports the one with the smallest shortfall. {@code null} if nothing crafts
     * the target at all.
     */
    public static Plan findRecipe(ServerLevel level, Container inv, Item target) {
        List<CraftingRecipe> matches = new ArrayList<>();
        for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
            Recipe<?> recipe = holder.value();
            if (!(recipe instanceof CraftingRecipe cr)) continue;
            PlacementInfo info = cr.placementInfo();
            // Skip special / no-ingredient recipes (firework, map-clone, …):
            // they have no static material list to gather.
            if (info.isImpossibleToPlace() || info.ingredients().isEmpty()) continue;
            ItemStack out;
            try {
                out = cr.assemble(CraftingInput.EMPTY);
            } catch (RuntimeException ex) {
                continue;   // input-dependent result — not reverse-lookup-able
            }
            if (out.isEmpty() || out.getItem() != target) continue;
            matches.add(cr);
        }
        if (matches.isEmpty()) return null;

        CraftingRecipe chosen = matches.get(0);
        int fewestMissing = Integer.MAX_VALUE;
        for (CraftingRecipe cr : matches) {
            int missing = countMissing(inv, cr.placementInfo().ingredients());
            if (missing == 0) {           // craftable right now → take it outright
                chosen = cr;
                break;
            }
            if (missing < fewestMissing) {
                fewestMissing = missing;
                chosen = cr;
            }
        }
        return new Plan(chosen, needsTable(chosen), chosen.assemble(CraftingInput.EMPTY));
    }

    /** Does this recipe require a 3×3 grid (crafting table)? */
    public static boolean needsTable(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) {
            return shaped.getWidth() > 2 || shaped.getHeight() > 2;
        }
        return recipe.placementInfo().ingredients().size() > 4;
    }

    /** Can the inventory satisfy one craft of this recipe right now? */
    public static boolean canCraftOnce(Container inv, CraftingRecipe recipe) {
        return matchSlots(inv, recipe.placementInfo().ingredients()) != null;
    }

    /**
     * Human-readable list of the still-missing ingredients for one craft, e.g.
     * {@code "2x oak_planks, 1x stick"}. Empty string when the craft is
     * feasible. Used to populate the tool result so the LLM can decompose.
     */
    public static String describeMissing(Container inv, CraftingRecipe recipe) {
        List<Ingredient> ings = recipe.placementInfo().ingredients();
        int[] reserved = new int[inv.getContainerSize()];
        Map<String, Integer> missing = new LinkedHashMap<>();
        for (Ingredient ing : ings) {
            int slot = reserveOne(inv, ing, reserved);
            if (slot < 0) {
                missing.merge(describeIngredient(ing), 1, Integer::sum);
            } else {
                // Claim this slot so a second copy of the same ingredient (e.g. 2
                // planks for a stick) can't double-count it as satisfied. Without
                // this, a recipe needing 2 of X with only 1 X in the bag reports 0
                // missing — the bug that let half-feasible crafts look feasible.
                reserved[slot]++;
            }
        }
        if (missing.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : missing.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getValue()).append("x ").append(e.getKey());
        }
        return sb.toString();
    }

    /**
     * Perform exactly one craft: consume the matched ingredients from
     * {@code inv}, deposit the result and any leftover container items into the
     * entity's inventory (overflow drops at its feet). Returns the produced
     * result stack (for counting), or {@code null} if the inventory can no
     * longer satisfy the recipe.
     */
    public static ItemStack craftOnce(AnimusEntity entity, CraftingRecipe recipe) {
        SimpleContainer inv = entity.getInventory();
        List<Ingredient> ings = recipe.placementInfo().ingredients();
        int[] slots = matchSlots(inv, ings);
        if (slots == null) return null;

        // Reconstruct a grid input so getRemainingItems can map per-item
        // remainders (buckets → empty buckets, etc.). Layout is irrelevant to
        // both assemble (fixed result) and remainder mapping (per-item), so a
        // simple square padded with empties is sufficient.
        List<ItemStack> picked = new ArrayList<>(ings.size());
        for (int slot : slots) {
            picked.add(inv.getItem(slot).copyWithCount(1));
        }
        int dim = ings.size() > 4 ? 3 : (ings.size() > 1 ? 2 : 1);
        List<ItemStack> grid = new ArrayList<>(dim * dim);
        for (int k = 0; k < dim * dim; k++) {
            grid.add(k < picked.size() ? picked.get(k) : ItemStack.EMPTY);
        }
        CraftingInput input = CraftingInput.of(dim, dim, grid);

        ItemStack result = recipe.assemble(input);
        if (result.isEmpty()) return null;
        NonNullList<ItemStack> remainders = recipe.getRemainingItems(input);

        // Commit: consume one of each matched ingredient.
        for (int slot : slots) {
            inv.removeItem(slot, 1);
        }
        giveOrDrop(entity, result.copy());
        for (ItemStack rem : remainders) {
            if (!rem.isEmpty()) giveOrDrop(entity, rem.copy());
        }
        return result;
    }

    // ---- internals ----

    /**
     * Greedy assignment: for each ingredient, reserve one not-yet-claimed
     * matching stack. Returns the chosen slot per ingredient, or {@code null}
     * if any ingredient can't be matched.
     */
    private static int[] matchSlots(Container inv, List<Ingredient> ings) {
        int[] reserved = new int[inv.getContainerSize()];
        int[] chosen = new int[ings.size()];
        for (int i = 0; i < ings.size(); i++) {
            int slot = reserveOne(inv, ings.get(i), reserved);
            if (slot < 0) return null;
            reserved[slot]++;
            chosen[i] = slot;
        }
        return chosen;
    }

    /** First inventory slot with an unreserved stack satisfying {@code ing}, or -1. */
    private static int reserveOne(Container inv, Ingredient ing, int[] reserved) {
        for (int s = 0; s < inv.getContainerSize(); s++) {
            ItemStack st = inv.getItem(s);
            if (!st.isEmpty() && st.getCount() - reserved[s] > 0 && ing.test(st)) {
                return s;
            }
        }
        return -1;
    }

    /** How many of this recipe's per-slot ingredients the inventory can't cover. */
    private static int countMissing(Container inv, List<Ingredient> ings) {
        int[] reserved = new int[inv.getContainerSize()];
        int missing = 0;
        for (Ingredient ing : ings) {
            int slot = reserveOne(inv, ing, reserved);
            if (slot < 0) missing++;
            else reserved[slot]++;
        }
        return missing;
    }

    /**
     * Name an ingredient for the human-readable "missing" message. A single-item
     * ingredient is named directly; a <em>multi-item</em> ingredient is a
     * tag/category, so naming one arbitrary member (the old {@code findFirst()})
     * misleads the model — e.g. reporting "oak_planks" when any plank, including
     * the cherry it holds, would do. Instead name the category: a shared suffix
     * → "planks (any type)"; otherwise list a few members → "any of: cobblestone,
     * blackstone, …".
     */
    @SuppressWarnings("deprecation")  // Ingredient.items() is deprecated-for-removal but is
                                      // still the only stable way to enumerate an ingredient's
                                      // acceptable items (no public tag accessor on Ingredient).
    private static String describeIngredient(Ingredient ing) {
        List<String> paths = ing.items()
                .map(holder -> BuiltInRegistries.ITEM.getKey(holder.value()).getPath())
                .distinct()
                .toList();
        if (paths.isEmpty()) return "?";
        if (paths.size() == 1) return paths.get(0);
        String suffix = commonSuffixToken(paths);
        if (suffix != null) return suffix + " (any type)";
        return "any of: " + paths.stream().limit(4).collect(Collectors.joining(", "))
                + (paths.size() > 4 ? ", …" : "");
    }

    /**
     * The trailing {@code _}-segment shared by every path, or {@code null} if
     * they don't all share one. "oak_planks"/"cherry_planks" → "planks";
     * "oak_log"/"birch_log" → "log"; "cobblestone"/"blackstone" → null.
     */
    private static String commonSuffixToken(List<String> paths) {
        String token = null;
        for (String p : paths) {
            int u = p.lastIndexOf('_');
            String t = u < 0 ? p : p.substring(u + 1);
            if (token == null) token = t;
            else if (!token.equals(t)) return null;
        }
        return token;
    }

    /** Route a stack into the entity inventory; overflow becomes a ground item. */
    private static void giveOrDrop(AnimusEntity entity, ItemStack stack) {
        ItemStack leftover = entity.getInventory().addItem(stack);
        if (!leftover.isEmpty()) {
            ItemEntity ie = new ItemEntity(entity.level(),
                    entity.getX(), entity.getY() + 0.5, entity.getZ(), leftover);
            ie.setDefaultPickUpDelay();
            entity.level().addFreshEntity(ie);
        }
    }
}
