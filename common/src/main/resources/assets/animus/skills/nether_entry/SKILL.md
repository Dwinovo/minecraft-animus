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

Mine it from a **ruined portal** — a structure that's just standing obsidian, no lava-casting. This is the only route: casting your own (water over lava) leaves every fresh obsidian block touching lava, and I refuse to mine fluid-adjacent blocks (it would flood or burn the dig), so a cast wall is unminable by design.

1. `locate_structure("#minecraft:ruined_portal")` — searches the whole family and returns the nearest. **Skip `ruined_portal_ocean`** (underwater) if the result names it; re-search or pick a land one — I can't dive.
2. `equip_item(diamond_pickaxe)` (obsidian needs diamond), `move_to` the portal coordinates.
3. `auto_mine(obsidian, 10, radius=24)` — it digs the frame's obsidian on its own. ~9.4s per block is normal.

Notes:
- A portal's frame mixes plain **obsidian** with **crying obsidian** (purple particles). Crying obsidian is a *different block and useless for a portal frame* — `auto_mine(obsidian)` already ignores it, so a single portal may yield fewer than 10. If you come up short, `locate_structure("#minecraft:ruined_portal")` again for the next nearest and top up.
- If `auto_mine` reports it skipped blocks "against water or lava", that portal sits in a wet/lava pocket — relocate to a cleaner one rather than fighting the fluid.

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
