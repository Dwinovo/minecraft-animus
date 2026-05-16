---
name: ender_pearls
description: Acquire 12 ender pearls. Three routes — overworld endermen at night (primary), End-tier endermen later (secondary), and piglin bartering (tertiary, expensive). Pearls combine with blaze powder into eyes of ender.
---

# Skill: ender_pearls

Phase 4 of the dragon route. You need ≥12 ender pearls. Most likely you'll be back in the overworld for this (endermen are densest there at night), but a partial Nether bartering supplement is viable.

## Goal

You have completed this phase when:

- You have **≥12 ender pearls in inventory**
- (Recommended) You also have a **pumpkin or carved pumpkin** to wear as a helmet for safe enderman engagement next time

## Core facts

### Drop math

- **Enderman**: 0–1 pearl per kill (50% chance). With Looting III: 0–4 (avg 2.0).
- **Expected kills for 12 pearls** (no looting): **~24**. With Looting III: **~6**.
- **Piglin bartering**: gold ingot → various items. **Ender pearl appears 2.13% of the time** (4 to 8 pearls per bartering hit when it does land). Expected: ~47 ingots = 12 pearls. **Expensive** — only use as a supplement.

### Where to find endermen

| Location | Spawn density | Notes |
|---|---|---|
| **Overworld at night** | Low (1–3 per 100 blocks) | Dark, flat, biomes (plains, desert at night) |
| **Soul sand valley (Nether)** | Medium | Worth a side-trip while you're already in Nether |
| **Warped forest (Nether)** | High | Best Nether spot — endermen everywhere |
| **The End** | Extremely high | Don't go yet — they swarm |

**Strategy**: hunt overworld endermen at night with the gear you have. Get 4-8 pearls. If still short, stop by warped forest in Nether on the way home from `blaze_rods` for the rest.

### The "looking at enderman" problem

Endermen become hostile and teleport-attack you if you **look directly at their head** (within ~64 blocks). Counters:

1. **Wear a carved pumpkin as helmet** — disables the trigger. The mask gives slight vision penalty but worth it. Carve a pumpkin with shears.
2. **Aim crosshair at their legs/feet** rather than the head when looking around them.
3. **Sneak** — they sometimes don't notice you at distance.

### Combat with endermen

- **20 HP**, can teleport to escape damage (1 HP threshold).
- **Melee only** (no ranged attack). Hits for 7 dmg, ~2 reach.
- **Water counter**: stepping into water source damages them 1/sec + they teleport away. Annoying for kills but useful as a panic button.
- **Rain counter**: in rain, they teleport randomly. Don't fight endermen in rain — they vanish.
- **The 3-block trick**: stand on a 3-block-high pillar. Endermen are 2.9 blocks tall — they can't melee you from below. They'll teleport behind you on top of your pillar (they always try to flank). Place blocks above your head to block teleport-up.

### Recommended kill rhythm

1. From inside a 1×1 hole (2 blocks deep), with a pumpkin on, look at the enderman.
2. It charges, can't reach you (you're in a hole), enters its "stuck" animation.
3. Hit its legs from the hole with diamond sword. Two hits with Sharpness V.
4. Pick up the pearl.

Or simpler: on flat ground, pumpkin on, sword equipped, **strafe-attack** — circle the enderman, hit, retreat to break melee range, repeat.

## Piglin bartering route (optional supplement)

If you couldn't hit 12 from endermen alone:

1. **Equip 1+ gold armor piece** (gold helmet from `nether_entry` works). Piglins won't aggro.
2. **Throw or right-click-give gold ingots** to adult piglins. Each ingot triggers a 6-second "inspection" animation — piglin drops a random trade item.
3. **Possible drops**: ender pearls (2.13%), gilded blackstone, soul speed boots, splash potion of fire resistance, obsidian, crying obsidian, and many junk items.
4. **Expected**: ~47 ingots per 1 pearl on average. **At least 50 ingots needed for 1 pearl**.

If you have a gold farm or stumbled on a piglin bastion treasure stash with 30+ ingots, this is supplementary income. If you don't, **don't bother** — too expensive in mining time.

## Recommended order

1. **Craft a carved pumpkin helmet** (shears + pumpkin → carved → equip as helmet slot).
2. **Wait for night** (or sleep until day cycle ends, but you're in Nether or no bed — just walk).
3. **Hunt endermen on flat overworld terrain**, ideally plains or desert (clear sight lines). Use the 3-block pillar or 1×1 hole trick.
4. **Collect 6–8 pearls** from overworld.
5. **If still short**: brief Nether detour into warped forest for the remaining pearls. Warped forest has so many endermen you can clear 6 pearls in a couple of minutes.
6. **Verify**: open inventory, count ≥12 pearls.

## Decision points

- **Pumpkin or no?** Always pumpkin. Looking-aggro is the leading cause of newbie death in this phase.
- **Should I build an enderman farm in the End for unlimited pearls?** No — that requires you to be in the End already, which requires the pearls. Chicken-and-egg.
- **Can I skip pearls and rush the dragon another way?** No. There's no alternative — eye of ender (= pearl + blaze powder) is the only way to find and activate the End portal.
- **Risk it raining mid-hunt?** Stop, take cover, wait it out. Don't try to fight in rain.

## Current tool mapping

- **What you can do today (with only `move_to`)**: relocate the owner during a fight (kite to a 3-block pillar location), patrol for endermen at night, escort home with pearls collected.
- **What you cannot do yet**: attacking, equipping pumpkin, picking up pearls. **Owner does combat and looting**; you advise on rhythm and warn about head-aim aggro.
- **Future tools that will help**: `attack_entity(target=enderman, ...)`, `equip(slot=head, item=carved_pumpkin)`, `pick_up_item`.

## What to load next

When 12+ pearls are in inventory, mark this phase `completed` and `load_skill(name="stronghold_finding")` to craft eyes and triangulate the End portal.
