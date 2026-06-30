---
name: dragon_combat
description: Final boss. End arena layout, crystal destruction with shoot (caged ones via auto-pillar + auto_mine), dragon attack patterns and the perch melee window, HP discipline over a long fight.
---

# Skill: dragon_combat

Phase 6 — the final boss. The Ender Dragon has 200 HP and heals from the end crystals while any survive. Mistakes here cost the whole run, mostly via the void.

## Done when

The dragon's HP reaches 0: death animation plays, ~the exit portal opens in the central bedrock fountain, a dragon egg appears on top. Tell your owner congratulations.

## Packlist (verify with `get_self_status` BEFORE entering the portal)

- Diamond sword + bow, **32+ arrows** (10 crystals + air shots + misses)
- **128+ cobblestone** — the spawn platform is often ~100 blocks from the island and navigation bridges the gap with your blocks
- **32+ cooked food**, plus a golden_apple if you have one (emergency heal)
- Armor on, sword in hand (`get_self_status` to confirm)

## The arena

- You arrive on a small obsidian platform out in the void. The **central island** (end stone, Y≈60) holds everything; `move_to` toward (0, 62, 0) — navigation bridges across. **Falling into the void destroys you and everything you carry.** Fight near the island centre, never at the rim.
- **10 obsidian pillars** ring the centre, each topped by an **end crystal**. The 2 tallest crystals sit inside iron-bar cages.
- Intact crystals continuously heal the dragon — damaging it before they're gone is wasted effort. **Crystals first, always.**

## Step 1 — the 8 open crystals

`equip_item(bow)` → `shoot(end_crystal, 8)`. The tool finds each crystal, walks into line of sight and fires; crystals die to one arrow and **explode** — range is exactly why `shoot` is mandatory here, never approach one.

## Step 2 — the 2 caged crystals

Per caged pillar:

1. `move_to(pillar_top_x, top_y + 1, pillar_top_z)` — navigation pillars up the side on its own (this is what the spare cobblestone is for).
2. `auto_mine(iron_bars)` to open the cage.
3. `move_to` back down/away 8+ blocks, then `shoot(end_crystal, 1)` — never pop a crystal at point-blank; the explosion hits hard.

While you're up high, the dragon may strafe the pillar — if `get_self_status` shows falling HP, finish the bars and get down first.

## Step 3 — kill the dragon

Two modes, alternating:

- **Flying**: `shoot(ender_dragon)`. Head shots take full damage, body shots are reduced — accept slow progress.
- **Perched** (it lands on the central fountain periodically, more often at low HP): `equip_item(diamond_sword)` → `hunt(ender_dragon)` — the melee window does the real damage. Back off (`move_to` 10+ blocks sideways) when it takes off again.

### Its attacks and your answers

| Attack | Effect | Answer |
|---|---|---|
| Dive/charge | ~10 dmg + heavy knockback | Stay near the island centre so knockback can't reach the void |
| Dragon's breath | Lingering purple cloud, ~3 dmg/s | `move_to` sideways immediately; **never stand or fight in purple** |
| Wing buffet (perched) | ~5 dmg + knockback | Expected cost of the melee window; eat between perches |

### HP discipline

This is a long fight. Between every tool call: `get_self_status`; **HP ≤ 10 → disengage to centre, `eat_item`, only then re-engage.** The dragon doesn't rush you — patience is free, death isn't.

## After the kill

- The exit portal (bedrock fountain, centre) returns you to the overworld spawn — `move_to` into it when your owner is ready.
- The dragon egg on the fountain is a trophy your owner may want; it teleports when punched, so leave its extraction to them.
- Mark the entire endgame plan `completed` in `todowrite`.

## If you die

Your run ends where your body fell. If your owner recovers your gear, re-verify the packlist (`get_self_status`), reload this skill, and walk back in through the still-active stronghold portal — the dragon keeps whatever damage it already took.
