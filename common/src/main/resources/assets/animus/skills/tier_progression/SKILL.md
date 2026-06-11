---
name: tier_progression
description: Progress from nothing → wood → stone → iron → diamond tier with concrete tool workflows (mining, crafting, smelting, food, bow + arrows). The foundation for every subsequent phase of the dragon route.
---

# Skill: tier_progression

Phase 1 of the dragon route. You need diamond tools before you can mine obsidian and survive the Nether. Skip nothing here — under-geared Nether trips end in a lost inventory.

## Done when (verify with `get_storage` / `get_self_status`)

- **Diamond pickaxe** (required for obsidian) and **diamond sword**, equipped as appropriate
- **Iron-or-better armor** worn (full iron is fine; diamond chestplate first if diamonds allow)
- **Bow + 64 arrows** (blazes and the dragon's crystals must be shot)
- **32+ cooked food** (cooked_beef / cooked_porkchop preferred)
- **64+ cobblestone** kept in inventory at all times — navigation consumes it as scaffold when bridging/pillaring

## Tool tier chain

`mine_block` checks your held tool: a too-low tier breaks the block with **no drop**. `equip_item` the right pickaxe before mining, and `inspect_block` when unsure.

The same rule gates navigation: **`move_to` only digs through blocks your held tool can harvest.** Descending into stone with a sword in hand fails with "no path" — travel with the pickaxe in your main hand; switch to a weapon only for the fight, then switch back.

| Tier | Unlocks mining | Recipe |
|---|---|---|
| Hand | logs, dirt, gravel | — |
| Wooden pickaxe | stone, coal | 3 planks + 2 sticks |
| Stone pickaxe | iron, lapis | 3 cobblestone + 2 sticks |
| Iron pickaxe | diamond, gold, redstone | 3 iron ingots + 2 sticks |
| Diamond pickaxe | obsidian | 3 diamonds + 2 sticks |

## Where ores live (1.21+ worldgen)

Below Y 0 every ore is its **deepslate variant** — always pass both ids to `mine_block` / `scan_blocks` (e.g. `diamond_ore` *and* `deepslate_diamond_ore`).

| Resource | Target Y | Notes |
|---|---|---|
| Coal | Y 90–136 | Surface hillsides are fastest |
| Iron | Y 16 (or mountain surface Y 200+) | Drops `raw_iron`, smelt it |
| **Diamond** | **Y -58 to -59** | Highest density; lava pools at this depth — mine carefully |

## Recommended order

1. **Wood**: `mine_block` 8+ logs (any `*_log`; hand works) → `craft` planks → sticks → `wooden_pickaxe`. `craft` finds or places a crafting table by itself; a table it places stays in the world — remember its coordinates and reuse it.
2. **Stone**: `equip_item(wooden_pickaxe)` → `mine_block(stone, 20)` (drops cobblestone) → `craft` `stone_pickaxe`, `stone_sword`, `furnace`.
3. **Food**: `hunt` cows/pigs/chickens (6+) → `load_furnace(raw_beef…, coal or planks)` → do other work → `collect_furnace`. Always cook; raw meat barely heals.
4. **Iron**: descend (`move_to(x, 16, z)` — navigation digs its own way down) → `equip_item(stone_pickaxe)` → `mine_block(iron_ore, deepslate_iron_ore, 10+)` → smelt `raw_iron` → `craft` `iron_pickaxe`, `iron_sword`, then armor as ingots allow (helmet 5, chestplate 8, leggings 7, boots 4).
5. **Diamonds**: `move_to(x, -58, z)` → `equip_item(iron_pickaxe)` → `mine_block(deepslate_diamond_ore, diamond_ore, 5+)`. Minimum 5 (pickaxe 3 + sword 2); 8+ if you also want a chestplate later. Watch HP near lava.
6. **Diamond gear**: `craft` `diamond_pickaxe` + `diamond_sword`. Keep the pickaxe in hand for travel and mining; equip the sword only when a fight starts.
7. **Bow + arrows**: bow = 3 sticks + 3 string (`hunt` spiders at night for string); arrows = 1 flint + 1 stick + 1 feather → 4 (flint drops from `mine_block(gravel)` at ~10%, feathers from chickens). Target 64 arrows.
8. **Top up**: 32+ cooked food, 64+ cobblestone. Re-run `get_storage` against the "done when" list.

## Enchanting

You cannot operate an enchanting table (GUI block). If your owner offers to enchant your gear — Sharpness on the sword, Power on the bow, Efficiency on the pickaxe — accept before moving on; it meaningfully raises dragon-fight odds. Never plan an enchanting step for yourself.

## What to load next

Checklist verified → mark phase 1 `completed` in `todowrite`, then `load_skill(name="nether_entry")`.
