---
name: combat_basics
description: Generic combat tactics for the Animus entity — HP management via eating, hunt (melee) vs shoot (bow) choice, arrow budgeting, retreat rules, aggro pitfalls. Reusable across blaze, enderman and dragon engagements.
---

# Skill: combat_basics

A support skill, not a phase. Load before engaging hostile mobs in any phase. It covers the fundamentals that don't fit into per-enemy skills.

## Your body, not a player's

You are an entity, not a player. The differences matter:

- **No hunger bar.** `eat_item` heals you directly; healing scales with the food's nutrition. There is no "stay above 18 hunger for regen" rule — when HP is low, eat.
- **No XP, no enchanting, no shields, no potions you can brew.** Your force multipliers are gear tier, food supply, and picking the right tool (`hunt` vs `shoot`).
- **Endermen don't care where you look.** Gaze-aggro is a player-only mechanic. They turn hostile only when you hit them.

## Pre-fight checklist

1. `get_self_status` — HP, main hand, dimension.
2. `equip_item` the right weapon (sword for `hunt`; bow for `shoot` — `shoot` fails without a bow in hand and arrows in inventory). **After the fight, re-equip your pickaxe** — navigation digs with the held tool, and a sword in hand makes stone impassable.
3. `get_storage` — enough food? enough arrows? Restock first, not mid-fight.

## Food

Healing scales with nutrition, so carry the dense stuff:

- **Best**: `cooked_beef` / `cooked_porkchop` (nutrition 8). Carry 16+ into any fight, 32+ for the dragon.
- **Emergency**: `golden_apple` — heals plus Regeneration and Absorption. Save for "HP critical mid-boss".
- **Acceptable**: cooked chicken/mutton/salmon, bread. **Never raw meat** — weak healing.

Eating is a real timed action (~1.6s of chewing before the heal lands). Eat *before* you're nearly dead.

**During `hunt`/`shoot` you auto-eat on your own** when HP drops below ~40% — the task result reports what was eaten and your post-fight HP. Your job is logistics, not micromanagement: keep dense food stocked, top up with `eat_item` before/after fights, and treat "NO food in inventory" in a combat result as an order to restock immediately.

## hunt vs shoot

| Situation | Tool | Why |
|---|---|---|
| Blazes | `shoot` | Their fireballs (5 dmg + fire) punish you on the walk-in; kill from range |
| End crystals | `shoot` | They explode — must be destroyed at range |
| Dragon flying | `shoot` | Can't melee what's airborne |
| Dragon perched | `hunt` | Melee window; arrows during perch are wasteful |
| Endermen | `hunt` | They teleport away from arrows; melee is the only reliable kill |
| Common mobs (zombies, skeletons, spiders, food animals) | `hunt` | Don't spend arrows |

**Arrows are consumed** — budget ~6 per blaze, ~20 spare for crystals/dragon. Craft more: 1 flint + 1 stick + 1 feather = 4 arrows.

## Retreat rules

Both `hunt` and `shoot` run until done, so decide *between* tool calls: after each combat call, `get_self_status`, then —

- **HP ≤ 8**: stop fighting. `move_to` a point 20+ blocks away (behind terrain if possible), eat back to full, re-engage.
- **Weapon about to break / out of arrows**: disengage, restock.
- Dying loses everything you carry. Retreating five times is cheaper than dying once.

## Positioning

1. **Never fight at a void or cliff edge** (the End especially) — knockback kills harder than damage.
2. **Avoid lava-adjacent corridors** in the Nether; knockback into lava is the #1 way to lose your inventory.
3. Prefer flat, open ground for `hunt`; the pathfinder closes distance best there.

## Aggro pitfalls

- **Zombified piglins group-aggro**: hit one and every one nearby swarms you. Never `hunt` them.
- **Piglins** attack players without gold armor. Keep a gold helmet equipped in the Nether and remind your owner to wear gold too.
- **Endermen** retaliate when hit and teleport behind you — expected; keep swinging.
- **Wither skeletons** inflict the Wither effect (damage over time that eating won't outpace) — kill fast or keep distance.

## What this skill is NOT

Per-enemy tactics live in `blaze_rods`, `ender_pearls`, `dragon_combat`. Gear progression lives in `tier_progression`. Good tactics with bad gear still die.
