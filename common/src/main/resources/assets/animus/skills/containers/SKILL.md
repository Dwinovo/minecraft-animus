---
name: containers
description: How to move items in/out of any container or machine GUI — chest, barrel, shulker, furnace, modded machine. The open → inspect_gui → click_slot → close_gui loop, batching a whole operation (a full craft, a deposit, an exact take) into ONE click_slot call, plus crafting/smelting and error recovery.
---

# Skill: containers

You move items through real GUIs, exactly like a player: open the block, look at the slots, click them, close. There is no "deposit this item" black box — you drive the menu yourself, which means it works for **any** container or machine (vanilla or modded) and you can **see and fix** what goes wrong.

## The loop

1. **Open** — `interact_at` with `button=right` on the container block (walk there first if needed; `interact_at` paths to it). This opens its GUI and leaves it open.
2. **Look** — `inspect_gui`. Lists every slot: `index: item xN`, which side (container vs your inventory), and `[output]` for take-only slots (a furnace result, a machine product). Also shows your cursor.
3. **Click** — `click_slot` to move items. Pass a **list** of clicks and do the whole job in one call (see below).
4. **Verify** — `inspect_gui` again if you need to confirm, especially after something unexpected.
5. **Close** — `close_gui` when done. (It also auto-closes if you walk away.)

## Moving items with click_slot

`click_slot` takes a **list of clicks** (`clicks=[{slot,type,button}, …]`) and runs them **in order, in one call**. This is your single biggest efficiency lever: **batch a whole operation into ONE call** instead of one click per round-trip. It returns each step's result (slot + cursor after it) and flags `[NO CHANGE]` when a slot refuses a move — so you see the entire sequence's effect at once and rarely need to re-inspect between clicks. Each click's `type`:

- **quick_move** — shift-click: sends that slot's whole stack to the other side, routed by the menu (a smeltable → a furnace's input, fuel → fuel, anything → a chest; a **crafting result → crafts repeatedly until the grid ingredients run out**). Use for "dump my cobblestone in" or "grab all the iron".
- **pickup** — `button=0` picks up / places the WHOLE stack onto/from your cursor; `button=1` grabs half / places exactly ONE (use button=1 to put one item per crafting cell).
- **swap** — `button` = hotbar index 0-8 (equip from a slot).
- **throw** — drop from the slot (`button=1` = one, `button=0` = whole stack).

**Deposit / take whole stacks** — batch the quick_moves: `click_slot clicks=[{slot:S1,type:quick_move},{slot:S2,type:quick_move},…]`.

**Exact count** — grab the stack, place N one at a time, drop the rest back, all in one batch:
```
click_slot clicks=[
  {slot:<source>, type:pickup, button:0},   # grab whole stack onto cursor
  {slot:<dest>,   type:pickup, button:1},   # place ONE  ┐ repeat
  {slot:<dest>,   type:pickup, button:1},   #            ┘ N times
  {slot:<source>, type:pickup, button:0}]   # drop the remainder back
```

## Reading machine progress

`inspect_gui` also shows a `data values: [...]` line — the menu's synced ints (the same numbers a real GUI uses to draw progress/fuel/energy bars), separate from the item slots. Meaning is machine-specific; for a **vanilla furnace** they are `[litTime, litDuration, cookProgress, cookTotal]`, so:
- cook % = `cookProgress / cookTotal` (the smelting arrow),
- still burning if `litTime > 0`.

So to check a furnace: `interact_at` it → `inspect_gui` → read the input count (slots) + the data values. The output slot filling up is also a clear "an item finished" signal.

## Crafting & smelting

Neither has a dedicated tool — you drive the GUI yourself, the same as any container.

- **Crafting** — do the whole craft in essentially ONE `click_slot` call:
  1. `lookup_recipe <item>` — get the recipe's grid layout, drawn as an ascii grid (reads the game's recipe data, so modded recipes work too, like JEI).
  2. Open a grid:
     - **≤2×2 recipe** (planks, sticks, torches, a crafting table itself): NO table needed — your own **2×2 grid is always available**. Just `inspect_gui` with nothing open.
     - **3×3 recipe** (tools, etc.): `interact_at button=right` a crafting table, then `inspect_gui`.
  3. **Read the grid's slot numbers straight off `inspect_gui` — do NOT compute them.** `inspect_gui` draws the open crafting grid as a 2D map of slot numbers in the SAME shape as the recipe ascii, e.g. a 3×3 table:
     ```
     crafting grid 3x3 — ... take the result from slot 0:
       slot 1=-  |  slot 2=-  |  slot 3=-
       slot 4=-  |  slot 5=-  |  slot 6=-
       slot 7=-  |  slot 8=-  |  slot 9=-
     ```
     Lay the recipe onto this map cell-for-cell (a recipe smaller than the grid goes in the **top-left**), and click the slot number printed at each ingredient's position. No row-major / stride math — just match positions.
  4. **One batched call** lays the recipe out and takes the result. Per cell: `pickup button=0` to grab the source stack, `pickup button=1` to drop ONE into the cell, then `pickup button=0` back on the source to return the rest — then `quick_move` the result slot. Example, a pickaxe (planks across the top row, sticks down the middle) — read the grid map to learn the top row is slots 1/2/3 and the middle column is 5/8; planks in your slot P, sticks in slot K:
     ```
     click_slot clicks=[
       {slot:P,type:pickup,button:0}, {slot:1,type:pickup,button:1},
       {slot:2,type:pickup,button:1}, {slot:3,type:pickup,button:1},
       {slot:P,type:pickup,button:0},                      # planks done, rest returned
       {slot:K,type:pickup,button:0}, {slot:5,type:pickup,button:1},
       {slot:8,type:pickup,button:1}, {slot:K,type:pickup,button:0},
       {slot:0,type:quick_move}]                           # take the pickaxe
     ```
     **Place EVERY cell the recipe shows.** A 2×2 table needs all four cells filled; fill only two and you'll craft the wrong thing (e.g. a button).
  5. **Crafting a whole stack at once** (e.g. 64 planks → only fill once): put a FULL stack in each needed grid cell (`pickup button=0` dumps the whole source stack into the cell), then a single `quick_move` on the result slot — shift-click auto-crafts repeatedly, consuming the grid until it empties. One `quick_move` ≈ a whole stack of output.
  6. For a table, `close_gui`. For your own 2×2 grid there's nothing to close; if you left items in the grid, `click_slot` them back out (the same batched call can end by quick_move-ing leftover grid cells back to your inventory).
- **Smelting** — a furnace is just another container you drive with these primitives:
  1. `interact_at button=right` the furnace, then `inspect_gui` once for the slot indices.
  2. Load raw + fuel in one batch: `click_slot clicks=[{slot:<raw>,type:quick_move},{slot:<fuel>,type:quick_move}]` — the menu routes the smeltable to the input slot and the fuel to the fuel slot.
     - **Fuel rule**: 1 coal/charcoal smelts 8 items; a log/plank ~1.5. So for N items add about ⌈N/8⌉ coal. Don't overload — surplus fuel just sits in the slot.
  3. Wait. Poll with `inspect_gui` — the `data values` show cook progress (`[litTime, litDuration, cookProgress, cookTotal]`), and the output slot fills as items finish.
  4. `click_slot clicks=[{slot:<output>,type:quick_move}]` to collect — this awards the smelting XP, same as a player.
  5. `close_gui`.

## Common patterns

**Store everything of one type into the nearest chest:**
```
interact_at button=right x,y,z         (the chest)
inspect_gui                            (find your cobblestone stacks in "your inventory")
click_slot clicks=[{slot:S1,type:quick_move},{slot:S2,type:quick_move},…]   (all stacks, one call)
close_gui
```

**Take 10 iron from a chest (exact):**
```
interact_at button=right x,y,z
inspect_gui                            (find the iron stack's slot in the container)
click_slot clicks=[
  {slot:<iron>,type:pickup,button:0},                 (grab the stack)
  {slot:<free>,type:pickup,button:1} ×10,             (place one each)
  {slot:<iron>,type:pickup,button:0}]                 (put the rest back)
close_gui
```

**Empty a furnace's output:** `inspect_gui` → the `[output]` slot → `click_slot clicks=[{slot:<output>,type:quick_move}]`.

## Error recovery (your advantage)

Because you can `inspect_gui`, you are never blind:

- **A `quick_move` moved nothing** → that slot is full on the other side, or routing refused it. Inspect: is the destination full? Is it an `[output]` slot (can't put INTO it)? Try a different slot or another container.
- **Chest is full** → `inspect_gui` shows no empty container slots. Find another chest (scan / known_blocks) or take something out first.
- **Cursor not empty after you thought you were done** → you're still holding an item; place it back into a slot before `close_gui` (closing returns it to your inventory anyway, but tidy up if a specific slot matters).
- **"no GUI open"** → you didn't open one, or walked out of range and it closed. `interact_at` the block again.

Always `close_gui` (or walk away) when finished so you don't leave a menu hanging.
