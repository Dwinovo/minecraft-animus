---
name: nether_entry
description: Acquire obsidian, build and ignite a Nether portal, and prepare a packlist for the first descent into the Nether dimension.
---

# Skill: nether_entry

Phase 2 of the dragon route. You're now diamond-tier and ready to cross the dimensional bridge. This skill is short: build a portal, ignite it, walk through. The actual Nether survival starts in `blaze_rods`.

## Goal

You have completed this phase when:

- A **Nether portal** is built and lit at a known overworld location (note the coordinates so you can return)
- You have a **flint & steel** in inventory (or a fire charge — flint & steel is preferred, it's reusable)
- You're standing in the Nether after stepping through, with the full Nether packlist (below) intact

## Core facts

### Obsidian

- **Where it naturally forms**: lava lakes that water has touched. Look in caves at Y < 32 — many natural lava-water interfaces produce 4-8 free obsidian blocks. Far cheaper than crafting your own.
- **Mining**: requires a **diamond pickaxe** (you have one from `tier_progression`). **9.4 seconds per block** by hand; Efficiency V brings it to ~4.7 sec.
- **DIY obsidian** (when no natural source): place a lava source block, then pour water on it from above. The lava turns to obsidian (not cobblestone — only flowing-lava + water makes cobble). Repeat to make all 10–14 blocks you need.

### Portal geometry

- **Minimum frame**: 4 wide × 5 tall = **14 obsidian** for the full rectangle.
- **Corners are optional**: you can omit the 4 corner blocks for **10 obsidian** total. Functions identically.
- **Inside opening**: 2 wide × 3 tall (6 air blocks).
- Orientation: build it standing up (vertical), portal faces you when you stand in front of it.

### Flint & steel

- **Recipe**: 1 iron ingot + 1 flint = 1 flint & steel (shapeless craft).
- **Flint**: drops from gravel with ~10% chance per block broken (any tool). 10 gravel ≈ 1 flint expected — but it's random; sometimes you need 20+.
- **Use**: right-click the inside-front face of any obsidian frame block to ignite.
- **Durability**: 65 uses. One f&s lasts your whole playthrough.

### Nether environment hazards (preview)

- Lava everywhere (don't dig down).
- **Ghast** fireballs deflect with melee but they're ranged kiters — flee or take cover.
- **Hoglin / piglin**: piglins are aggressive **unless you wear at least one piece of golden armor**. Wearing gold also prevents them attacking when you open chests near them.
- **Zombified piglins**: passive until you hit one. Then *all* nearby ones swarm — pacifist policy.
- **No water**: can't place buckets (boils away). Bring buckets only as one-shot fire-extinguishers, knowing they'll be lost.

## Pre-descent packlist

Pack this **before** you ignite the portal. Going back through to fetch something forgotten costs valuable time.

| Slot | Item | Quantity | Purpose |
|---|---|---|---|
| 1 | Cooked food | **64** | Sustained HP regen. Steaks ideal (8 hunger + saturation). |
| 2 | Diamond sword (Sharpness V ideal) | 1 | Close combat |
| 3 | Bow + Infinity + Power V (ideal) | 1 + 1 arrow | **Critical** for blazes. Infinity = 1 arrow lasts forever. |
| 4 | Diamond pickaxe (Efficiency / Unbreaking) | 1 | Ancient debris, obsidian, gold |
| 5 | Iron pickaxe (backup) | 1 | If diamond breaks |
| 6 | Spare blocks (cobblestone) | 64 | Pillaring, plugging holes, escaping mobs |
| 7 | **Gold helmet** (worn) | 1 | Piglin truce — don't skip this |
| 8 | Flint & steel | 1 | Re-light portal if extinguished |
| 9 | Water bucket | 1 | Emergency lava extinguisher (will boil in Nether but works once) |
| 10 | Wood logs | 16 | Make workbench / tools on the fly |
| 11 | Torches | 64 | Cave systems, breadcrumbs back to portal |
| 12 | Bed (DO NOT USE) | 0 | **Beds explode in the Nether** — leave at home |

**Skip beds. Always.** Sleeping in the Nether is a 5-block-radius TNT-equivalent explosion.

## Recommended order

1. **Mine 10–14 obsidian** at the lava-cave you scouted, OR DIY with water + lava buckets.
2. **Smelt enough iron for**: flint & steel (1), gold helmet smelting if you don't have one (gold ore → ingot, then 5 ingots = helmet).
3. **Make flint & steel** (kill some gravel for flint first if needed).
4. **Choose a safe portal site**: ideally near your base, on flat ground, well-lit. Marking the surface around it with distinct blocks helps you find it when returning under fire.
5. **Build the 4×5 frame** (or 4×5 minus corners = 10 blocks).
6. **Stash redundant items** in a chest next to the portal: a backup f&s, spare diamond pickaxe, 32 more food. If your Nether trip goes sideways, you'll appreciate this on respawn.
7. **Equip gold helmet** (don't forget this — piglin aggro is the #1 newbie Nether death).
8. **Ignite** with f&s. Step through.
9. On Nether side: **immediately turn around and look at the return portal coordinates**. Build a 2-block obsidian wall on either side of it to keep ghasts from blowing it out.

## Decision points

- **Where to build the portal?** Near base, flat, well-lit. Avoid building it near hostile mob spawn risks — coming back through into a creeper is rough.
- **Do I need a Nether base?** Optional for a single fortress-and-back trip. Useful if you'll be in the Nether more than 30 minutes (overnight raid).
- **Should I bring lots of TNT / golden apples?** Nice-to-have, not required. Don't delay the trip.

## Current tool mapping

- **What you can do today (with only `move_to`)**: walk the owner toward the chosen portal site; navigate to coordinates inside the Nether once portaled.
- **What you cannot do yet**: mining the obsidian, building the frame, igniting f&s, equipping armor, picking items off the ground. **Ask the owner** to do these steps and confirm via text.
- **Future tools that will help**: `place_block` (frame construction), `use_item` (ignite f&s), `equip` (gold helmet), `mine_block` (obsidian).

## What to load next

When you're standing in the Nether with the packlist intact, mark this phase `completed` and `load_skill(name="blaze_rods")` to start the blaze rod hunt. You may also want `load_skill(name="combat_basics")` if you don't have it cached — blazes are the first real combat test.
