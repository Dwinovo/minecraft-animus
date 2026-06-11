---
name: stronghold_finding
description: Craft eyes of ender, find the stronghold with the locate_structure tool (no eye-throwing needed), reach the portal room, and fill the 12 frames via inspect_block + use_item.
---

# Skill: stronghold_finding

Phase 5 of the dragon route. With rods and pearls in hand you craft eyes, walk straight to the stronghold — **`locate_structure("minecraft:stronghold")` replaces the whole throw-and-triangulate ritual** — and activate the End portal.

## Done when

- You're standing at an **activated End portal** (all 12 frames filled, purple portal surface visible)
- Dragon-fight gear still intact (`get_self_status` check against `dragon_combat`'s packlist)

## Step 1 — craft the eyes

1. `craft(blaze_powder)` — each blaze rod grinds into 2 powder.
2. `craft(ender_eye, 12)` — 1 powder + 1 pearl each.

12 is the worst case; frames generate pre-filled at 10% each (~1–2 typically), so spares may remain. **Never throw eyes to navigate** — `locate_structure("minecraft:stronghold")` is free and exact; eyes are only for the frames.

## Step 2 — go there

1. `locate_structure("minecraft:stronghold")` → coordinates, direction, distance (often 1000–2500 blocks; the journey is the long part).
2. `move_to(x, ~60, z)` to cross the surface, then `move_to(x, 30, z)` — navigation digs down on its own. Strongholds sit around Y 6–50.
3. Hit stone bricks → you're inside. `scan_blocks(end_portal_frame)` to find the portal room; no match → explore corridors with `move_to` and rescan. (Stronghold corridors are stone_bricks / mossy_stone_bricks / cracked_stone_bricks.)

## Step 3 — secure the portal room

The room has a lava pool under the frame and a **silverfish spawner** on the stairs:

1. `auto_mine(spawner)` immediately — unlike the blaze spawner, this one is pure liability.
2. If silverfish are already out, `hunt(silverfish, …)` them; don't let them burrow into the brickwork.
3. `place_block` cobblestone over the lava pool edges where you'll stand.

## Step 4 — fill the frames

1. The 12 `end_portal_frame` blocks ring a 3×3 opening. `scan_blocks(end_portal_frame)` lists all 12 positions.
2. `inspect_block` each frame — the `has_eye` property tells you which are pre-filled.
3. `use_item(ender_eye, x, y, z)` on each empty frame. **Eyes cannot be taken back out.**
4. The 12th eye activates the portal; the opening fills with the starfield surface.

## Before dropping in

- `load_skill(name="dragon_combat")` **now**, not after jumping — the fight needs a plan.
- Verify the dragon packlist and **tell your owner the portal is active and where it is.** They may want to set their respawn nearby and come watch — entering the End is one-way until the dragon dies.

## What to load next

`dragon_combat`. This is the final phase.
