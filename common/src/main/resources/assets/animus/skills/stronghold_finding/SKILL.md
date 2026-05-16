---
name: stronghold_finding
description: Craft eyes of ender (blaze powder + ender pearl), triangulate the nearest stronghold by throwing eyes, dig down to the End portal room, and activate the portal with 12 eyes in the frame.
---

# Skill: stronghold_finding

Phase 5 of the dragon route. With 12 blaze rods and 12 pearls in hand, you craft 12 eyes of ender, find a stronghold, and activate the End portal. The dragon is one step away.

## Goal

You have completed this phase when:

- You're **standing on an activated End portal** (purple swirly blocks filling all 12 frame slots)
- The dragon-fight gear from earlier phases is still intact
- (Recommended) You have **2–4 extra eyes** stashed in case some break during tracking (20% break rate per throw)

## Core facts

### Eye of ender recipe and economy

- **Craft**: 1 blaze powder + 1 ender pearl = 1 eye (shapeless craft).
- **Blaze powder**: grind 1 blaze rod → 2 blaze powder. So 6 rods = 12 powder = 12 eyes. You should have 12 rods (24 powder) from `blaze_rods` — keep 12 powder spare for brewing if needed.
- **You need ≥12 eyes for the portal**, plus **2–4 for throwing/tracking** = recommend **crafting all 12 pearls into eyes** if you have 12 pearls (12 eyes → some may break, but you'll have enough; otherwise gather 2–4 more pearls first if you want full safety margin).

### Throwing mechanics (how to triangulate)

- **Right-click an eye of ender** to throw it.
- The eye flies **upward 12 blocks** then **straight toward the nearest stronghold** at horizontal distance, **briefly hovers**, then **falls**:
  - **80% chance** it survives the fall — pick it up to reuse
  - **20% chance** it shatters mid-flight (you lose the eye)
- **Tracking method (triangulation)**:
  1. **Throw eye #1** at point A. Note direction.
  2. **Walk 200+ blocks perpendicular** to that direction (e.g. if eye flew east, walk 200 blocks north).
  3. **Throw eye #2** at point B. Note new direction.
  4. **Project both lines on a map** — they intersect at the stronghold (~within 100 blocks).
- **Simpler "just walk that way" method**: throw an eye, walk 100 blocks in the direction it flew, throw another. Adjust. Repeat. Takes more eyes but easier without a map app.
- **Confirm you're close**: when the eye flies **downward**, the stronghold is directly below — start digging.

### Stronghold facts (Java 1.21+)

- **Total**: 128 strongholds per world, distributed across **8 concentric rings** centered on world spawn.
- **Ring 1**: 3 strongholds, distance 1,408–2,688 blocks from origin.
- **Ring 2**: 6 strongholds, 4,480–5,760 blocks.
- **...up through ring 8**: 22,656–23,936 blocks.
- **Generation depth**: typically **Y = 6 to Y = 50**. You'll dig down ~30–50 blocks from surface.
- **Structure**: stone-brick / mossy-stone-brick corridors, rooms, libraries, a chapel, and **one End portal room** with a fountain of lava under the portal frame and silverfish spawner.
- **End portal frame**: a 3×3 frame opening (with 12 frame blocks around it), already arranged. Each frame block has a **10% chance of generating with a pre-installed eye**. Expected: **1.2 eyes pre-installed**, so you'll need to insert ~10–11 yourself.

### Stronghold hazards

- **Silverfish spawner** in the portal room — tiny mobs that swarm when hit and break stone blocks to summon more. Block off the spawner immediately (4 blocks around it) or kill it fast with bow.
- **Lava under portal frame** — don't fall in. Bring blocks to bridge.
- **Wooden doors and barred openings** — easy to navigate but slow you down.

### Portal activation

- **Right-click a frame block with an eye of ender** to insert. Once all 12 are filled, the portal **automatically activates** — purple swirly fluid appears in the 3×3 opening, glass-shatter sound plays.
- **Falling into an active End portal** transports you to The End.
- **You cannot remove eyes once inserted** — be sure before you click.

## Recommended order

1. **Craft 12 eyes of ender** (12 pearls + 12 powder).
2. **Throw eye #1** from your current location. Watch direction carefully.
3. **Walk 100–200 blocks** in that direction (use direction the eye flew).
4. **Throw eye #2**. If still flying horizontally, repeat step 3.
5. **When the eye flies downward**, you're standing within ~30 blocks horizontal of the stronghold. **Dig straight down** (CAREFUL — bridge over any lava, and place a torch every few blocks to mark your shaft and let you climb back up). Aim for Y 30 first; widen if you don't hit stone bricks.
6. **Look for stone brick**. When you hit it, you're in the stronghold corridor system.
7. **Explore the corridors**, marking your path with torches (always-on-the-right convention works). Look for a room with a barred area — that's typically near the portal room.
8. **Reach the End portal room**. Block off the silverfish spawner first (4 blocks around it).
9. **Insert eyes into all 12 frame blocks**.
10. **Pause** before dropping in:
    - Verify you have the dragon-fight packlist (see `dragon_combat`)
    - Place a bed or set spawn point near the portal so respawn is convenient if you die
    - **Drop in.**

## Decision points

- **Triangulate carefully or wing-it?** For minimum eye loss, triangulate (2 eyes total). Wing-it method (throw, walk, throw, walk) uses 4–6 eyes but needs no map. Either works.
- **Take all 12 eyes into the stronghold?** Yes — even if some break in throwing, you need 12 in the frame.
- **Bring more than 12 eyes?** **Yes — bring 14–16** to allow for 2–3 throw breaks (20% rate). Worst case you have spares; you don't want to be 1 eye short with the stronghold found.
- **Sleep / set spawn near stronghold?** Strongly recommend. If you die in the End, you respawn at the overworld bed, can re-pack, and walk straight back through your active portal.

## Current tool mapping

- **What you can do today (with only `move_to`)**: walk to coordinates where the owner throws an eye, escort to the dig-down spot, navigate the stronghold corridors when the owner marks them for you.
- **What you cannot do yet**: crafting eyes, throwing them (and observing their flight direction is a perception task too — you can't see them), mining the dig-down shaft, inserting eyes into the frame. **Owner does all of this**; you can read direction from chat if owner reports it.
- **Future tools that will help**: `craft(eye_of_ender)`, `use_item_throw(target_direction)`, `perceive_projectile_direction`, `mine_block`, `place_block`, `use_item(target_block)`.

## What to load next

When you're standing on the activated portal, mark this phase `completed` and `load_skill(name="dragon_combat")` **before dropping in**. The dragon fight needs preparation in your head, not improvisation. Also `load_skill(name="combat_basics")` if not recently loaded — the dragon is a tougher test than blazes or endermen.
