---
name: containers
description: How to move items in/out of any container or machine GUI — chest, barrel, shulker, furnace, modded machine. The open → inspect_gui → transfer → close_gui loop, depositing/taking/swapping with the transfer tool, crafting via the craft tool, smelting by loading a furnace, and error recovery.
---

# Skill: containers

You move items through real GUIs, exactly like a player: open the block, look at the slots, move items, close. There is no per-item black box — you drive the menu yourself with `transfer`, which works for **any** container or machine (vanilla or modded) and lets you **see and fix** what goes wrong.

## The loop

1. **Open** — `interact_at` with `button=right` on the container block (walk there first if needed; `interact_at` paths to it). This opens its GUI and leaves it open.
2. **Look** — `inspect_gui`. Lists every slot: `index: item xN`, which side (container vs your inventory), and `[output]` for take-only slots (a furnace result, a machine product).
3. **Move** — `transfer` to shift items. Pass a **list** and do the whole job in one call (see below).
4. **Verify** — the transfer result already tells you what happened; `inspect_gui` again only if you need to re-check.
5. **Close** — `close_gui` when done. (It also auto-closes if you walk away.)

## Moving items with transfer

`transfer` takes a **list of moves** (`moves=[{from, to?, count?}, …]`) and runs them **in order, in one call** — batch a whole operation into ONE call instead of a round-trip per item. Each move:

- **`from`** — the source slot (from `inspect_gui`).
- **`to`** — the destination slot. **OMIT it** to send the whole stack to the *other section*, routed by the menu (into a chest, out of one, a smeltable to a furnace's input). This is the easy bulk deposit/withdraw — you don't pick a slot. **Give it** to place in that EXACT slot: empty → moves there; same item → merges; **a different item → the two slots SWAP**.
- **`count`** — (needs `to`) move exactly that many instead of the whole stack.

The result spells out what actually happened to each move — how much moved, a merge, a swap, or *why nothing moved* (full / output-only slot) — so you rarely need to re-inspect.

**Deposit / take whole stacks** — omit `to`, one move per stack:
```
transfer moves=[{from:S1}, {from:S2}, …]      # each routes to the other section
```

**Exact count** — give `to` (a free slot) + `count`:
```
transfer moves=[{from:<iron>, to:<free>, count:10}]
```

**Swap two slots** — give `to` holding a different item (e.g. swap a tool into a slot):
```
transfer moves=[{from:<pickaxe>, to:<slot with junk>}]
```

## Reading machine progress

`inspect_gui` also shows a `data values: [...]` line — the menu's synced ints (the same numbers a real GUI uses to draw progress/fuel/energy bars), separate from the item slots. Meaning is machine-specific; for a **vanilla furnace** they are `[litTime, litDuration, cookProgress, cookTotal]`, so:
- cook % = `cookProgress / cookTotal` (the smelting arrow),
- still burning if `litTime > 0`.

So to check a furnace: `interact_at` it → `inspect_gui` → read the input count (slots) + the data values. The output slot filling up is also a clear "an item finished" signal.

## Crafting

**Use the `craft` tool — it does the whole thing for you** (look up the recipe, lay the ingredients into the grid, take the result back to your inventory). You don't touch slots.

- `craft item_id=<item> count=<n>` — makes `n` of the item (crafts until you have at least that many, or materials run out; it reports how many it made).
- **≤2×2 recipe** (planks, sticks, torches, a crafting table): NO table needed — done on your own 2×2 grid automatically. Nothing to open.
- **3×3 recipe** (most tools, etc.): a crafting table must be OPEN first — `interact_at button=right` a table, then `craft`. If you forget, craft tells you to open one.
- Short on materials → it crafts what it can and says so; `lookup_recipe <item>` to see what's missing.

*Example — a full wooden tool set:* `craft oak_planks count=8` → `craft stick count=4` → `craft crafting_table` → `interact_at` the table → `craft wooden_pickaxe`. No grid math, no slot moves.

## Smelting

Smelting is NOT crafting — there's no auto-tool, you load the furnace yourself (it's just two slots):
  1. `interact_at button=right` the furnace / blast furnace / smoker.
  2. Load the input: `transfer moves=[{from:<raw item>}]` — omit `to`, the menu routes it to the top input slot.
  3. Add fuel: `transfer moves=[{from:<coal>}]` — routes to the bottom fuel slot. **Fuel rule**: 1 coal/charcoal smelts 8 items; a log/plank ~1.5, so add ~⌈N/8⌉ coal.
  4. Wait. Poll with `inspect_gui` — `data values` show cook progress (`[litTime, litDuration, cookProgress, cookTotal]`); the output slot fills as items finish.
  5. `transfer moves=[{from:<output>}]` to collect (awards the smelting XP). `close_gui`.

## Modded machines (hand-load)

A custom modded machine has its own slots. For a single input, `transfer moves=[{from:<input>}]` (omit `to` — the menu routes it). For a machine with several specific input slots, `inspect_gui` then give an exact `to` per input: `transfer moves=[{from:<a>, to:<slotA>}, {from:<b>, to:<slotB>}]`. If a modded *crafting* grid isn't covered by `craft`, `inspect_gui` draws it as a 2D map of slot numbers — lay the recipe onto it cell-for-cell (smaller recipe → top-left) with one `transfer {from, to, count:1}` per non-empty cell.

## Common patterns

**Store everything of one type into the nearest chest:**
```
interact_at button=right x,y,z          (the chest)
inspect_gui                             (find your cobblestone stacks in "your inventory")
transfer moves=[{from:S1}, {from:S2}, …]   (all stacks route into the chest, one call)
close_gui
```

**Take 10 iron from a chest (exact):**
```
interact_at button=right x,y,z
inspect_gui                             (find the iron stack + a free inventory slot)
transfer moves=[{from:<iron>, to:<free>, count:10}]
close_gui
```

**Empty a furnace's output:** `inspect_gui` → the `[output]` slot → `transfer moves=[{from:<output>}]`.

## Error recovery (your advantage)

The transfer result tells you the outcome of every move, and you can always `inspect_gui` — you are never blind:

- **A move reported "nothing moved"** → the destination is full, or it's an `[output]` slot you can't put INTO. Try another slot or another container.
- **Chest is full** → `inspect_gui` shows no empty container slots. Find another chest (scan / known_blocks) or take something out first.
- **Got a swap you didn't want** → you gave a `to` that held a different item. Omit `to` to route instead, or pick an empty `to`.
- **"no GUI open"** → you didn't open one, or walked out of range and it closed. `interact_at` the block again.

Always `close_gui` (or walk away) when finished so you don't leave a menu hanging.
