---
name: nether_entry
description: Acquire obsidian, build a Nether portal with place_block, ignite it with flint & steel via use_item, and enter the Nether with the right packlist.
---

# Skill: nether_entry

Phase 2 of the dragon route. Build a portal, ignite it, walk through. Actual Nether survival starts in `blaze_rods`.

## Done when

- A lit Nether portal stands at a known overworld location (**report its coordinates to your owner** — it's the way home)
- You are standing in the Nether with the packlist below intact

## Obsidian (need 10)

**Route A — natural (try this first, no bucket needed)**: obsidian forms wherever water touched lava, common in caves. `equip_item(diamond_pickaxe)` then just `auto_mine(obsidian, 10, radius=96)` — it finds AND digs on its own. Best odds: descend to Y ≈ -20…-50 first (big lava lakes live in the deepslate layer) and run the auto_mine from there. ~9.4s per block is normal.

**Route B — cast it yourself** when Route A finds nothing:

1. `craft(bucket)` (3 iron ingots).
2. **Find water**: `scan_blocks(["minecraft:water"], radius=48)` — water is landscape-scale, always scan at max radius, and look where water lives: rivers/lakes/oceans sit in LOW terrain, so scan from a valley or shoreline, not a hilltop. Matches carry `source: true/false` — a bucket only fills from a **source** cell.
3. Fill: `use_item(bucket, x, y, z)` on a source water cell.
4. **Find lava**: `scan_blocks(["minecraft:lava"], radius=48)` near Y -20…-50, or surface lava pools. You need `source: true` cells — **water over SOURCE lava = obsidian; water over flowing lava = cobblestone** (the classic waste).
5. Stand on solid ground beside the lava (never over it), `use_item(water_bucket, x, y, z)` aimed at a cell ABOVE the lava edge so the water flows across the sources. Each source it touches hardens to obsidian.
6. Scoop the water back (`use_item(bucket)` on the source you placed), `auto_mine(obsidian, ...)`, repeat until 10.

Tip: two water sources placed in a 2×2 pit make an infinite well — refill forever from the same spot.

## Portal build

- Frame: 4 wide × 5 tall, **corners omitted = exactly 10 obsidian**, standing vertically. Inner opening is 2×3 air.
- Pick flat ground near your base. Build the frame with `place_block` (two columns of 3 at the sides, two rows of 2 at top and bottom).
- **Flint & steel**: `craft(flint_and_steel)` = 1 iron ingot + 1 flint (flint from `auto_mine(gravel)`, ~10%/block).
- **Ignite**: `use_item(flint_and_steel, x, y, z)` aimed at an **empty air cell INSIDE the frame** (a bottom one), not at the obsidian. The fire lands in that cell and the portal forms.
- Enter: `move_to` the portal cell and stand in it until the dimension changes (`get_self_status` confirms).

## Packlist (verify with `get_self_status` before igniting)

| Item | Qty | Why |
|---|---|---|
| Cooked food | 32+ | Your healing |
| Diamond sword + bow | 1 + 1 | Equip for combat only — hold the pickaxe while travelling (navigation digs with the held tool) |
| Arrows | 32+ | `shoot` consumes them (~6 per blaze); run low → switch to melee + extra food |
| Diamond pickaxe (+ iron backup) | 1 + 1 | Obsidian, digging |
| Cobblestone | 64+ | Navigation scaffold — bridging lava lakes eats it |
| Gold helmet (worn) | 1 | Piglin truce; 5 gold ingots if you must craft one |
| Flint & steel | 1 | Re-light the portal if a ghast blows it out |

**Never place or use a bed in the Nether — beds explode there.**

## Nether ground rules

- **Don't dig straight down**; lava oceans sit under most terrain. Navigation bridges lava when it must — keep cobblestone stocked.
- **Water doesn't exist here**: buckets won't place.
- **Zombified piglins are pacifists until hit — and then they ALL swarm.** Never `hunt` them.
- **Ghasts** snipe from far; their fireballs can break the portal. On arrival, note the Nether-side portal coordinates (`get_self_status`) and report them to your owner. Overworld↔Nether coordinates map 8:1 horizontally.

## What to load next

Standing in the Nether, packlist intact → mark phase 2 `completed`, `load_skill(name="blaze_rods")`. Load `combat_basics` too if you haven't — blazes are the first real combat test.
