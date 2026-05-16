---
name: combat_basics
description: Generic combat tactics — HP / hunger management, retreat windows, weapon priority, ranged vs melee tradeoffs. Reusable across blaze, enderman, dragon engagements.
---

# Skill: combat_basics

A support skill, not a phase. Load this whenever you're about to engage hostile mobs in any phase (`blaze_rods`, `ender_pearls`, `dragon_combat`). Covers the fundamentals that don't fit cleanly into per-enemy skills.

## Core constants

| Stat | Value |
|---|---|
| Max player HP | 20 (10 hearts) |
| Max hunger | 20 (10 drumsticks) |
| **Regen requires hunger ≥ 18** | Otherwise no passive regen — you stay at low HP indefinitely |
| Regen rate (when fed) | 1 HP / 4 sec → ~4 sec to recover 1 heart |
| Saturated regen | Faster (1 HP per ~0.5 sec) — only after eating a high-saturation food |
| Sprint cost | -1 hunger per 7 seconds while sprinting + jumping |
| Combat hunger | Most actions cost 0–1 hunger; jumping during sprint is the main drain |

## Food priority

When choosing what to eat during / before combat:

| Food | Hunger restored | Saturation | Notes |
|---|---|---|---|
| **Cooked porkchop / steak** | 8 (full) | 12.8 | **Best**. Restores all hunger + max saturation in 1 bite. |
| Golden carrot | 6 | 14.4 | Best saturation; great for marathon fights |
| Cooked salmon | 6 | 9.6 | Solid |
| Cooked mutton / chicken | 6 / 6 | 9.6 / 7.2 | Fine |
| Bread | 5 | 6 | Decent emergency |
| Carrot / potato | 3 / 1 | low | Filler |
| Sweet berries / melon slice | 2 / 2 | very low | Not for combat |
| Raw meat | low | low | Don't — food poisoning risk |

**Rule of thumb**: carry **at least 32 cooked steaks/porkchops** into any boss fight. Eat the moment hunger drops below 18 to keep regen active.

### When to eat

- **Hunger drops to 16 (8 drumsticks)**: eat 1.
- **HP drops to 12 or less (6 hearts)**: definitely eat, even if hunger is high — eating recovers some HP indirectly via the regen tick.
- **Mid-combat panic**: drop everything, eat. The 1.5-second eat animation is worth it; dying isn't.

## Retreat triggers

Don't fight to the last point — **retreat when any of these is true**:

- HP ≤ 5 (2.5 hearts) — no matter how close the kill is
- Hunger ≤ 4 (no regen possible at all)
- More than 3 mobs aggro on you simultaneously
- You're below ~10 HP and outnumbered
- Weapon durability < 5

**How to retreat**:

1. **Sprint away** at right angle to enemy direction (don't run straight backward — they predict that).
2. **Get cover** (corner, wall, hole, water source).
3. **Eat to 18+ hunger** for regen, wait until full HP.
4. **Re-engage** with full HP.

Death costs: respawn at bed (or world spawn), lose ALL inventory at death location (despawns in 5 min). Better to retreat 5 times than die once.

## Weapon priority

For Animus's dragon route:

| Weapon | Use for | Avoid for |
|---|---|---|
| **Bow** (Power V + Infinity) | Blazes (range avoids fireballs), end crystals, dragon (air shots) | Endermen (they teleport; melee better) |
| **Diamond sword** (Sharpness V) | Endermen, dragon perch melee, panic close-combat | Don't waste durability on weak mobs (zombies, skeletons) — iron sword fine |
| **Iron sword** | Common overworld mobs, fortress wither skeletons | Bosses (DPS too low) |
| **Snowballs** | Blazes only (3 dmg each, free ammo) | Anything else |
| **Splash water bottles** | Blazes (1 dmg + extinguish you) | Niche |
| **Ender pearl** (as weapon) | Reposition to end crystal, emergency dodge dragon | Costs 5 HP per throw — pricey |

## Range vs melee

- **Range when**: enemy hits hard (blaze 5 dmg/fireball × 3, dragon dive 10 dmg), enemy can be one-shot from cover, enemy moves slow (blaze hovers).
- **Melee when**: enemy short-range only (enderman, perched dragon), arrows would be wasted (small hitbox), DPS race favors melee (Sharpness V two-shots most things).

**Don't oscillate.** Pick one approach per engagement; switching mid-fight wastes turns.

## Armor / enchantment quick-reference

- **Protection IV all slots** = -64% incoming damage (well, technically up to 80% reduction with armor + enchants). Massive force multiplier.
- **Feather Falling IV on boots** = fall damage near-zero. Lifesaver in End and stronghold dig shafts.
- **Fire Protection IV** = ideal for Nether fortress (blazes set you on fire). Not stackable with Protection IV on same piece — pick one. For dragon route: Protection IV is better (general).
- **Water Breathing on helmet** = nice for ocean exploration, irrelevant for dragon route.

## Positioning rules

1. **Never fight on a 1-block edge** — one knockback = void death (especially End).
2. **Have an escape route** — at least one direction you can sprint without hitting a wall.
3. **Don't fight in lava-adjacent corridors** — Nether especially.
4. **High ground beats low ground** for ranged. Conversely, **enemies on high ground beat you ranged** unless you have cover.
5. **Block-pillar to dodge melee** — 2-block-tall pillar puts you out of zombie / enderman melee reach.

## What this skill is NOT

- Not enemy-specific tactics — see per-mob skills (`blaze_rods`, `ender_pearls`, `dragon_combat`).
- Not gear progression — see `tier_progression`.
- Not a substitute for armor + enchants. **Good tactics with bad gear still die**.

## Current tool mapping

- **What you can do today (with only `move_to`)**: kite an enemy (move to a position that makes them path through cover), advise the owner on retreat / engage / eat timing through chat.
- **What you cannot do yet**: attacking, eating, equipping. Combat is owner-driven; your value is the **rhythm calling and tactical reminders** drawn from this skill.
- **Future tools that will help**: `attack_entity`, `consume`, `equip`, `perceive_hp_status`.

## When to load this

- Before any combat engagement (re-load to refresh)
- After taking a death — re-read the retreat rules
- Combined with the per-mob skill for the upcoming fight
