---
name: blaze_rods
description: Locate a Nether fortress, shoot blazes at their spawner, and collect ≥7 blaze rods (each grinds into 2 blaze powder; 12 eyes of ender need 12 powder = 6 rods, +1 margin).
---

# Skill: blaze_rods

Phase 3 of the dragon route. Eyes of ender need blaze powder; `locate_stronghold` means you waste zero eyes on throwing, so **7 rods (14 powder) is enough** with margin.

## Done when

- `get_storage` shows **≥7 blaze_rod**
- You're back at the Nether portal (or another safe spot), ready for phase 4

## Finding a fortress

- Fortresses are **nether brick** (dark red-brown) bridges and corridors. Blackstone with gold = bastion — different structure, avoid; its piglin brutes attack on sight.
- Pick a cardinal direction and travel (`move_to` long hops), calling `scan_blocks(nether_bricks)` periodically. Fortresses appear roughly every few hundred blocks; expect a few minutes of travel.
- Track your portal's coordinates so you can navigate home.

## Blazes

- 20 HP, fly/hover, volley of 3 fireballs (~5 dmg each + sets you on fire) every ~3s at line of sight, fire-immune.
- They spawn from **blaze spawners**: small fortress rooms with a caged spawner block, plus naturally on fortress bridges.
- **Use `shoot`, not `hunt`** — walking into melee eats fireball volleys. Budget ~6 arrows per blaze.

## Farming loop

1. Find the spawner room (`scan_blocks(spawner)` inside the fortress helps).
2. `equip_item(bow)` → `shoot(blaze, 3)` in small batches.
3. `collect_items` — rods drop on the floor; grab them before they burn in nearby lava... rods are fire-immune items, but lava destroys them. Don't let drops land in lava.
4. `get_self_status` between batches: HP ≤ 8 → `move_to` out of spawner range, eat, return.
5. Repeat until `get_storage` shows ≥7 rods. Drop rate is 0–1 per kill (avg 0.5) → expect **~14 kills**, more if unlucky.

**Do not mine the spawner** — you need it spawning blazes until the count is met. (You *may* `place_block` to wall off excess sight-lines if too many blazes volley at once.)

## Hazards

- **Wither skeletons** roam fortress corridors; their hits apply Wither (damage over time). `hunt` them one at a time or stay out of reach.
- Fortress bridges have no railings; knockback over the edge usually lands in lava. Fight away from edges (`combat_basics` positioning rules).

## What to load next

≥7 rods banked → mark phase 3 `completed`, then `load_skill(name="ender_pearls")`. Warped forests (teal trees, dense endermen) are worth noting on your way out — phase 4 can use them.
