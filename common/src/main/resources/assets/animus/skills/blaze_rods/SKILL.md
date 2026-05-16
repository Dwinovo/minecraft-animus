---
name: blaze_rods
description: Locate a Nether fortress, find a blaze spawner, and farm 12 blaze rods. Required for eyes of ender (rods grind 1→2 into blaze powder, and 12 eyes need 12 powder = 6 rods minimum, but 12 is a safer target).
---

# Skill: blaze_rods

Phase 3 of the dragon route. You need ≥12 blaze rods to grind into 24 blaze powder (12 for eyes of ender, 12 spare for brewing if you go that route). Blazes are dangerous, but they're entirely predictable once you understand them.

## Goal

You have completed this phase when:

- You have **≥12 blaze rods in inventory**
- You're back at the Nether portal (or at a safe spot) ready to either return to overworld or proceed to ender pearl hunting

## Core facts

### Blaze rod drop math

| Setup | Drop range per kill | Expected per kill | Kills for 12 rods |
|---|---|---|---|
| No looting | 0–1 (50% chance) | **0.5** | **~24** |
| Looting I | 0–2 | 0.83 | ~14 |
| Looting II | 0–3 | 1.17 | ~10 |
| **Looting III** | **0–4** | **2.0** | **~6** |

**Strongly consider crafting a Looting III sword before fortress.** It cuts the kill count by 4×. You need: an enchanting table (you should have one from `tier_progression`), a sword, level 30, and a bit of RNG to land Looting III on the first try (or use a grindstone + book + Looting III enchant from a librarian villager).

### Nether fortress identification

- **Construction**: nether brick (dark red-brown bricks). Often crosses with other structure types in MC 1.21 (bastion remnants), so watch the material — **nether brick = fortress; blackstone with gold blocks = bastion**.
- **Layout**: open bridges + walls + interior corridors + small towers. Multi-level.
- **Blaze spawner location**: inside or near a small enclosed room with stairs going up. Spawner is a barred cage emitting tiny rotating blaze figures.
- **Locating one**: just walk in any cardinal direction. Fortresses generate roughly every 200–400 blocks in the Nether. You may need 5–10 minutes of travel; a Nether road on the Y=90 ceiling is faster but riskier.
- **In the Nether, Y = const → overworld Y × 8**, so cover ground fast at Y 90+ using ghast-cleared sky paths.

### Blaze combat

- **Stats**: 20 HP (10 hearts), 3 charged fireballs per attack volley (every ~3 seconds in line of sight), 48-block max range.
- **Fireball damage**: 5 each, fire damage. Lights you on fire (additional ticks).
- **Resistant to**: fire (immune), so don't bother with fire aspect / flame bow.
- **Weak to**: snowballs (3 damage each — surprisingly viable), splash water bottles (1 damage), powdered snow (5 damage per tick, but rare in Nether).
- **Best damage tool**: **bow + Power V** = 1–2 shot. Approach behind cover.

### Recommended kill rhythm

1. From cover, peek + fire one arrow (peek + retreat to break line of sight before they shoot back).
2. Wait 2 seconds for the fireball volley you provoked to pass.
3. Repeat. ~6 arrows per blaze.

If you must melee (out of arrows): rush in, hit twice with diamond sword (10 + 10 dmg from Sharpness V = one-shot most blazes), retreat behind cover before next volley.

## Recommended order

1. **Pre-fortress prep at overworld base** (if you haven't): enchant a sword with Looting III. Worth the detour.
2. **Enter Nether** (you're already past `nether_entry`).
3. **Pick a direction and walk** until you spot dark nether brick architecture. Mark your portal location with bright blocks before leaving sight.
4. **Approach the fortress carefully**. Wither skeletons (3-block tall, holding stone sword) live here — they cause wither effect; don't get hit.
5. **Find the blaze spawner**: usually in or near a small interior room. Listen for blaze hissing.
6. **Set up a safe kill spot**: stand 4–5 blocks from the spawner with cover (a single nether brick column between you and the spawner blocks fireballs). Or **block the spawner sight-lines with 4 blocks** to limit how many blazes can fire at once.
7. **Engage**: bow them down from cover. Pick up rods (they drop on the floor — be careful of lava nearby).
8. **Repeat** until rod count ≥ 12. Looting III: ~6 kills. No looting: ~24 kills (be patient — and replenish food).
9. **Optional but useful while there**: grab nether wart from the fortress' wart garden if you find one (for potions, especially Slow Falling for the End).
10. **Return to portal**, restock, head to overworld for `ender_pearls`.

## Decision points

- **Should I build a blaze farm with the spawner?** For 12 rods, no — overkill. For long-term play (XP grinding, lots of brewing), yes — block off the spawner with slabs to limit drops to one direction, then funnel.
- **Can I survive without bow?** Yes, but very risky. Diamond sword with Sharpness V two-shots blazes — you need to be willing to take 5–10 fire damage per engagement.
- **What if no fortress within 1000 blocks?** Go back, try a different direction. Fortress is RNG-generated; some Nether instances are sparse.

## Current tool mapping

- **What you can do today (with only `move_to`)**: navigate the owner to fortress coordinates once spotted; reposition during a fight (kite away from fireballs).
- **What you cannot do yet**: attacking, shooting bow, picking up rod drops, equipping armor, eating food mid-fight. **Owner does all combat**; you can spot for them, suggest tactics from this skill, and call out when blazes are about to fire (every ~3 sec rhythm).
- **Future tools that will help**: `attack_entity(target=blaze, weapon=bow)`, `pick_up_item`, `consume(food)`.

## What to load next

When 12+ rods are in inventory, mark this phase `completed` and `load_skill(name="ender_pearls")` to gather the second half of the eye-of-ender recipe.

If combat felt sketchy, `load_skill(name="combat_basics")` for tactic refresh — endermen and the dragon are next, both demand HP discipline.
