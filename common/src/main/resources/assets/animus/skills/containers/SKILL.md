---
name: containers
description: How to move items in/out of any container or machine GUI — chest, barrel, shulker, furnace, modded machine. The open → inspect_gui → click_slot → close_gui loop, with the fast path for simple deposit/take and the precise path for exact counts, plus error recovery.
---

# Skill: containers

You move items through real GUIs, exactly like a player: open the block, look at the slots, click them, close. There is no "deposit this item" black box — you drive the menu yourself, which means it works for **any** container or machine (vanilla or modded) and you can **see and fix** what goes wrong.

## The loop

1. **Open** — `interact_at` with `button=right` on the container block (walk there first if needed; `interact_at` paths to it). This opens its GUI and leaves it open.
2. **Look** — `inspect_gui`. Lists every slot: `index: item xN`, which side (container vs your inventory), and `[output]` for take-only slots (a furnace result, a machine product). Also shows your cursor.
3. **Click** — `click_slot` to move items (see below).
4. **Verify** — `inspect_gui` again if you need to confirm, especially after something unexpected.
5. **Close** — `close_gui` when done. (It also auto-closes if you walk away.)

## Moving items with click_slot

- **Fast deposit / take (whole stack)** — `click_slot type=quick_move slot=<i>`. Shift-click: sends that slot's whole stack to the other side, routed by the menu (a smeltable goes to a furnace's input, fuel to fuel, anything to a chest). Use this for "dump my cobblestone in" or "grab all the iron".
- **Exact count** — `quick_move` moves the WHOLE stack. For an exact number, use pickup:
  1. `click_slot type=pickup button=0 slot=<source>` — picks the whole stack onto your cursor.
  2. `click_slot type=pickup button=1 slot=<dest>` — right-click places ONE from the cursor. Repeat for N.
  3. `click_slot type=pickup button=0 slot=<source>` — left-click the source again to drop the remainder back.
- **Equip from a slot** — `type=swap button=<hotbar 0-8>`.
- **Drop from a slot** — `type=throw button=1`.

`click_slot` returns the cursor + that slot after the click, so you can chain clicks without re-inspecting every time.

## Reading machine progress

`inspect_gui` also shows a `data values: [...]` line — the menu's synced ints (the same numbers a real GUI uses to draw progress/fuel/energy bars), separate from the item slots. Meaning is machine-specific; for a **vanilla furnace** they are `[litTime, litDuration, cookProgress, cookTotal]`, so:
- cook % = `cookProgress / cookTotal` (the smelting arrow),
- still burning if `litTime > 0`.

So to check a furnace: `interact_at` it → `inspect_gui` → read the input count (slots) + the data values. The output slot filling up is also a clear "an item finished" signal.

## Crafting & smelting

Neither has a dedicated tool — you drive the GUI yourself, the same as any container.

- **Crafting**:
  1. `lookup_recipe <item>` — get the recipe's ingredients + grid layout (reads the game's recipe data, so modded recipes work too, like JEI).
  2. Open a grid:
     - **≤2×2 recipe** (planks, sticks, torches, a crafting table itself): NO table needed — your own **2×2 grid is always available**. Just `inspect_gui` with nothing open: it shows your `InventoryMenu`, where the crafting grid is slots **1-4** and the result is slot **0**.
     - **3×3 recipe** (tools, etc.): `interact_at button=right` a crafting table, then `inspect_gui`.
  3. `click_slot type=pickup` each ingredient into its cell, matching the recipe grid (a W×H shaped recipe goes in the top-left of the grid; shapeless = place anywhere). Right-click (button=1) places one item at a time.
  4. `click_slot type=quick_move` the result slot (**0**) to take the output — repeat to craft more.
  5. For a table, `close_gui`. For your own 2×2 grid there's nothing to close; if you left items in slots 1-4, `click_slot` them back out.
- **Smelting** — a furnace is just another container you drive with these primitives:
  1. `interact_at button=right` the furnace.
  2. `click_slot type=quick_move` your raw item (the menu routes it to the input slot) and your fuel (routes to the fuel slot).
     - **Fuel rule**: 1 coal/charcoal smelts 8 items; a log/plank ~1.5. So for N items add about ⌈N/8⌉ coal. Don't overload — surplus fuel just sits in the slot.
  3. Wait. Poll with `inspect_gui` — the `data values` show cook progress (`[litTime, litDuration, cookProgress, cookTotal]`), and the output slot fills as items finish.
  4. `click_slot type=quick_move` the `[output]` slot to collect — this awards the smelting XP, same as a player.
  5. `close_gui`.

## Common patterns

**Store everything of one type into the nearest chest:**
```
interact_at button=right x,y,z         (the chest)
inspect_gui                            (find your cobblestone in "your inventory")
click_slot type=quick_move slot=<that slot>   (repeat per stack you hold)
close_gui
```

**Take 10 iron from a chest (exact):**
```
interact_at button=right x,y,z
inspect_gui                            (find the iron stack's slot in the container)
click_slot type=pickup button=0 slot=<iron>      (grab the stack)
click_slot type=pickup button=1 slot=<a free inventory slot>   (×10, places one each)
click_slot type=pickup button=0 slot=<iron>      (put the rest back)
close_gui
```

**Empty a furnace's output:** `inspect_gui` → the `[output]` slot → `click_slot type=quick_move slot=<output>`.

## Error recovery (your advantage)

Because you can `inspect_gui`, you are never blind:

- **A `quick_move` moved nothing** → that slot is full on the other side, or routing refused it. Inspect: is the destination full? Is it an `[output]` slot (can't put INTO it)? Try a different slot or another container.
- **Chest is full** → `inspect_gui` shows no empty container slots. Find another chest (scan / known_blocks) or take something out first.
- **Cursor not empty after you thought you were done** → you're still holding an item; place it back into a slot before `close_gui` (closing returns it to your inventory anyway, but tidy up if a specific slot matters).
- **"no GUI open"** → you didn't open one, or walked out of range and it closed. `interact_at` the block again.

Always `close_gui` (or walk away) when finished so you don't leave a menu hanging.
