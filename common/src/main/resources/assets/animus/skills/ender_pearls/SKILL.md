---
name: ender_pearls
description: Hunt endermen for ≥12 ender pearls — warped forest (Nether) is the densest spot, overworld night works too. Pearls + blaze powder = eyes of ender.
---

# Skill: ender_pearls

Phase 4 of the dragon route. You need **12 pearls** for up to 12 eyes of ender (stronghold frames spawn ~10% pre-filled, but don't count on it).

## Done when

- `get_storage` shows **≥12 ender_pearl**

## Endermen, from your perspective

- **Your gaze does not anger them.** Look-aggro is a player-only mechanic — you're an entity. No pumpkin tricks needed; they turn hostile only when you hit them. (Warn your *owner* not to stare, though.)
- 40 HP, melee-only (~7 dmg), teleport when struck — often behind you. Expected; keep swinging.
- **They teleport away from arrows — `hunt` (melee), never `shoot`.**
- They teleport out of rain and away from water. Check `get_world_info`: raining → wait or hunt in the Nether instead. Don't fight next to water.
- Drop: 0–1 pearl per kill (avg 0.5) → expect **~24 kills** for 12 pearls.

## Where to hunt

| Spot | Density | Notes |
|---|---|---|
| **Warped forest (Nether)** | High — best choice | Teal forest; endermen everywhere, no rain, you're already in the Nether after phase 3 |
| Overworld at night | Low | Plains/desert, flat sight-lines; `get_world_info` to confirm darkness |
| Soul sand valley | Medium | Slow walking (soul sand), watch for ghasts |

## Hunting loop

1. `equip_item(diamond_sword)`, food check (`get_storage`).
2. `scan_nearby_entities` to confirm endermen around; reposition with `move_to` if the area is dry.
3. `hunt(enderman, 4)` in batches → `collect_items` for scattered pearls.
4. `get_self_status` between batches; HP ≤ 8 → disengage, eat.
5. Repeat until ≥12 pearls.

## Piglin bartering (fallback only)

Piglins drop ender pearls for gold ingots at ~2% per barter (~47 ingots per pearl on average). Only worth it if you looted a pile of gold; otherwise mining gold for this is slower than hunting. To barter: wear gold, drop ingots near a piglin, `collect_items` what it throws back.

## What to load next

≥12 pearls → mark phase 4 `completed`, then `load_skill(name="stronghold_finding")`. The endgame is two skills away.
