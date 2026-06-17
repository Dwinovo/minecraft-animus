---
name: containers
description: How to move items in/out of any container or machine GUI — chest, barrel, shulker, furnace, modded machine. The open → inspect_gui → click_slot → close_gui loop, batching deposits/takes into ONE click_slot call, crafting via the craft tool, smelting by loading a furnace by hand, and error recovery.
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
- **throw** — drop from the slot (`button=0` = one, `button=1` = the whole stack); only works when your cursor is empty.

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

## Crafting

**Use the `craft` tool — it does the whole click sequence for you** (look up the recipe, lay the ingredients into the grid, take the result back to your inventory). You don't touch slots.

- `craft item_id=<item> count=<n>` — makes `n` of the item (crafts until you have at least that many, or materials run out; it reports how many it made).
- **≤2×2 recipe** (planks, sticks, torches, a crafting table): NO table needed — done on your own 2×2 grid automatically. Nothing to open.
- **3×3 recipe** (most tools, etc.): a crafting table must be OPEN first — `interact_at button=right` a table, then `craft`. If you forget, craft tells you to open one.
- Short on materials → it crafts what it can and says so; `lookup_recipe <item>` to see what's missing.

*Example — a full wooden tool set:* `craft oak_planks count=8` → `craft stick count=4` → `craft crafting_table` → `interact_at` the table → `craft wooden_pickaxe`. No grid math, no slot clicks.

## Smelting

Smelting is NOT crafting — there's no auto-tool, you load the furnace by hand (it's just two slots):
  1. `interact_at button=right` the furnace / blast furnace / smoker.
  2. Load the input: `click_slot clicks=[{slot:<raw item>,type:quick_move}]` — the menu routes it to the top input slot.
  3. Add fuel: `click_slot clicks=[{slot:<coal>,type:quick_move}]` — routes to the bottom fuel slot. **Fuel rule**: 1 coal/charcoal smelts 8 items; a log/plank ~1.5, so add ~⌈N/8⌉ coal.
  4. Wait. Poll with `inspect_gui` — `data values` show cook progress (`[litTime, litDuration, cookProgress, cookTotal]`); the output slot fills as items finish.
  5. `click_slot clicks=[{slot:<output>,type:quick_move}]` to collect (awards the smelting XP). `close_gui`.

## Modded machines (hand-load)

A custom modded machine has its own slots — load it by hand: `click_slot type=quick_move` your input (the menu routes it to the input slot); for a multi-input machine, `inspect_gui` then `click_slot type=pickup` each input into its slot. If a modded *crafting* grid isn't covered by `craft`, `inspect_gui` draws it as a 2D map of slot numbers — lay the recipe ascii onto it cell-for-cell (smaller recipe → top-left) and fill every NON-EMPTY cell the recipe shows (a 1-ingredient recipe = just ONE cell; don't fill the rest).

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
