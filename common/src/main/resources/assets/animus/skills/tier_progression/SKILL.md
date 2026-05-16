---
name: tier_progression
description: Progress from wood → stone → iron → diamond tier, including the smelter / crafting table / enchanting table chain. The foundation for every subsequent phase of the dragon route.
---

# Skill: tier_progression

The first phase of the dragon-slayer route. You need full diamond gear (or a serviceable diamond+iron mix) before you can mine obsidian and step into the Nether. Skip nothing here — under-geared trips to the Nether get you killed by blazes and ghasts.

## Goal

You have completed this phase when **all** of the following are true:

- **Diamond pickaxe** in inventory (required to mine obsidian)
- **Diamond sword** in inventory (Sharpness V if you reached the enchanting table)
- **Iron or diamond armor** on all four slots (full diamond + Protection IV is the ambitious target)
- **64+ cooked food** (cooked beef / porkchop / chicken — bread is acceptable but inferior)
- **At least one stack of dirt / cobblestone** for emergency block placement
- **Optional but strongly recommended:** an enchanting table + level 30 XP, with key gear pre-enchanted

## Core facts

### Tool tier chain (each tier requires the previous tier's pickaxe)

| Tier | Mineable target | Crafting need |
|---|---|---|
| Hand | Wood logs | — |
| **Wood pickaxe** | Cobblestone, coal, basic stone | 3 planks + 2 sticks |
| **Stone pickaxe** | Iron ore, lapis lazuli, redstone | 3 cobblestone + 2 sticks |
| **Iron pickaxe** | Diamond ore, gold ore, emerald | 3 iron ingots + 2 sticks |
| **Diamond pickaxe** | Obsidian, ancient debris (Nether) | 3 diamonds + 2 sticks |

A pickaxe one tier below cannot harvest the next-tier ore — it'll break the block and drop nothing.

### Where to find what (Java edition 1.21+ generation)

| Resource | Best Y level | Biome notes |
|---|---|---|
| Coal | Y 0–256 (most common Y 90–136) | Any |
| Iron ore | Y -64 → Y 256 (peaks at Y 16 and Y 232) | Mountains: surface iron at Y 232 |
| Gold ore | Y -64 → Y 32 (peaks Y -16) | Badlands biomes have surface gold |
| Lapis lazuli | Y -64 → Y 64 (peaks Y 0) | Any |
| **Diamond** | **Y -58 to -59** (deepslate layer) | Any. Avoid Y -59 lava lakes |
| Redstone | Y -64 → Y 16 (peaks Y -58) | Any |

**Diamond layer Y -58 / -59 is the single highest-density seam.** Branch-mine 2 blocks high, 1 block wide, leaving 2 blocks between branches.

### Food chain

- **Hand-mining trees → apples**: 1/200 leaf decay drops apple. Useless early.
- **Animals**: cow / pig / chicken / sheep. Kill, cook in furnace (10 sec, 1 coal per 8 items). Cooked beef and porkchop both give 8 hunger + saturation. Always cook — raw meat has food poisoning risk.
- **Crops**: wheat (from seeds, takes ~30 min), carrot, potato. Bread = 3 wheat. Slower than meat early.
- **Sustained**: build a wheat farm + sheep pen near base for renewable supply.

### Fuel priority

1. **Coal**: 80 sec burn (smelts 8 items). Most common early fuel.
2. **Charcoal**: same as coal, but renewable — smelt 1 log → 1 charcoal. Use this once you have a stable log supply.
3. **Lava bucket**: smelts 100 items, but you need an iron bucket first. Late-game furnace fuel.

### Armor priority

- **Full iron** is the realistic mid-game target. Don't wait for full diamond before exploring.
- **Mix**: iron helmet + diamond chestplate + diamond leggings + iron boots is a cheap upgrade if diamond is tight (chest + legs absorb the most damage).
- **Full diamond**: 24 diamond. Get there before Nether, ideally.

### Enchanting table (huge force multiplier)

- **Recipe**: 4 obsidian + 2 diamond + 1 book (3 sugar cane + 1 leather).
- **Power up**: surround with **15 bookshelves** (1 block gap) for tier-30 enchants. Bookshelf = 6 planks + 3 books = 6 planks + 9 sugar cane + 3 leather.
- **XP source for level 30**: mining ore (especially diamond / coal) and smelting are stable XP. ~30 mob kills also works.
- **Priority enchants for the dragon route**:
  - **Bow**: Power V + Infinity (essential — see `combat_basics` and `dragon_combat`)
  - **Diamond sword**: Sharpness V (+12.5 damage per hit)
  - **Diamond pickaxe**: Efficiency V (faster obsidian — 9.4 sec per block becomes 4.7 sec) + Unbreaking III
  - **Armor**: Protection IV on all four pieces; Feather Falling IV on boots is a lifesaver in the End

## Recommended order

1. **Punch trees → 8+ logs → workbench → wooden pickaxe** (~2 min)
2. **Mine 20+ cobblestone → stone pickaxe + stone sword + furnace** (~3 min)
3. **Kill 4+ cows/pigs/chickens, cook the meat** — get to 16+ cooked food before continuing
4. **Find a cave or strip-mine to Y ≈ 50 → iron ore ≥ 6** → smelt → **iron pickaxe + iron sword + at least iron helmet + iron chestplate**
5. **Descend to Y -58 → diamond ≥ 8** (3 for pickaxe, 3 for sword, 2 spare). Bring water bucket to escape lava.
6. **Collect lapis (≥15) on the way down** for the enchanting table.
7. **Build enchanting table + 15 bookshelves**, level up to 30 mining/killing, enchant.
8. **Cook 64+ food** before moving on.

## Decision points

- **How much diamond before Nether?** Minimum: pickaxe + sword. Ideal: + full armor. Don't sink 4 hours mining 40 diamonds if you've already got pickaxe+sword+chestplate — Nether trip can find more (diamond armor trim, ancient debris, etc.).
- **Bow + enchant table — before or after Nether?** **Before.** You'll need Power V bow to kill blazes safely, and bow + ranged is critical for the dragon's crystals.
- **Skip enchanting?** Possible but reduces dragon-kill probability significantly. Plan ~30 extra mins for it.

## Current tool mapping

- **What you can do today (with only `move_to`)**: navigate to coordinates the owner marks for you (e.g. "go to my mining base at -100 64 200").
- **What you cannot do yet**: mining, crafting, smelting, equipping, picking up items. For these, **ask your owner to do them** and report back via text. Update `todowrite` sub-items with `blocked: owner action needed` until the corresponding tool ships.
- **Future tools that will speed this up**: `mine_block` (turns step 2/4/5 into single calls), `craft` (steps 1/2/4/5/7), `equip` (step 4/5), `consume` (food).

## What to load next

When the gear checklist at the top is satisfied, mark this phase `completed` in `todowrite` and `load_skill(name="nether_entry")`.
