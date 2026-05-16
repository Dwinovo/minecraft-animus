---
name: end_game_overview
description: High-level roadmap to defeat the Ender Dragon. Load this FIRST when the owner asks for any end-game / "kill the dragon" goal — it tells you which specialised skill to load for the current phase.
---

# Skill: end_game_overview

You are an Animus. Your owner has asked you to **defeat the Ender Dragon** — the canonical end-game of vanilla Minecraft. This skill is the map; the other 7 skills are the territory. Always start here when the goal involves the dragon, because each phase below maps 1:1 to a specialised skill you'll load on demand.

## Why this skill exists

The full path from "fresh world" to "dead dragon" spans dozens of micro-actions across multiple dimensions. Trying to plan it all in one turn will exhaust your context and lose track of where you are. Instead:

1. **Read this overview** to know the shape of the journey.
2. **Use `todowrite`** to write the 6 mainline phases as a top-level todo list, with phase 1 `in_progress`.
3. **Load the matching specialised skill** for the current phase when you actually start that phase.
4. **Update `todowrite`** as each phase finishes; never have more than one phase `in_progress` simultaneously.

You don't load all 8 skills up front — that wastes tokens. Load them one at a time, just-in-time.

## The 6 mainline phases

In dependency order. Each phase is a single skill you load with `load_skill(name="...")`.

| # | Phase | Skill to load | Done when |
|---|---|---|---|
| 1 | **Tier up to diamond** | `tier_progression` | You have a diamond pickaxe, full diamond armor (ideal) or iron+diamond mix (acceptable), a diamond sword, and enough food. An enchanting table + 30 levels is a strong bonus. |
| 2 | **Build a Nether portal and enter the Nether** | `nether_entry` | You're standing in the Nether with the gear from phase 1, plus flint & steel and ~64 food in reserve. |
| 3 | **Farm blaze rods** | `blaze_rods` | You have ≥12 blaze rods in inventory (these grind to 24 blaze powder — enough for 12 eyes of ender, with safety margin). |
| 4 | **Acquire ender pearls** | `ender_pearls` | You have ≥12 ender pearls in inventory. |
| 5 | **Find a stronghold and activate the End portal** | `stronghold_finding` | You're standing on the activated End portal in a stronghold, ready to drop in. |
| 6 | **Kill the dragon** | `dragon_combat` | Ender Dragon HP → 0. The exit portal generates in the centre. |

Two **support skills** are not phases, you reach for them whenever they're relevant during any phase:

- `combat_basics` — load whenever you're about to engage hostile mobs (blazes, endermen, the dragon itself). HP / hunger / weapon priority, retreat windows, ranged-vs-melee tradeoffs.
- (this skill itself) `end_game_overview` — re-read when you've lost track of where in the plan you are.

## How to start, in concrete

When the owner says something like *"OK go kill the dragon"*, do exactly this on turn 1:

1. Call `todowrite` with a 6-item top-level plan:
   - `[in_progress, high]` "Phase 1: tier up to diamond gear"
   - `[pending,    high]` "Phase 2: enter the Nether"
   - `[pending,    high]` "Phase 3: farm 12 blaze rods"
   - `[pending,    high]` "Phase 4: acquire 12 ender pearls"
   - `[pending,    high]` "Phase 5: find a stronghold + activate End portal"
   - `[pending,    high]` "Phase 6: defeat the Ender Dragon"
2. Call `load_skill(name="tier_progression")` and follow that skill's instructions. It will tell you to add sub-todos for the wood → stone → iron → diamond ladder.
3. As phase 1 nears completion, mark phase 1 `completed` and phase 2 `in_progress`, then `load_skill(name="nether_entry")`. And so on.

## Honest status disclosure (current Animus capabilities)

You are running on an early version of the Animus mod. **The only world-action tool currently implemented is `move_to(x, y, z, speed)`.** All other actions described in the specialised skills (mining, crafting, attacking, placing blocks, eating, equipping items, opening containers, etc.) are not yet available as tools.

In the meantime, when a phase requires an action you can't actually perform:

- **Be transparent with your owner.** Output text saying e.g. *"I need to mine 3 cobblestones now, but the `mine_block` tool isn't available yet. Could you mine them for me and place them in your inventory? I'll continue from there."*
- **Do whatever you can** with `move_to` — escorting the player, pathfinding to coordinates, repositioning during combat.
- **Update `todowrite`** with `blocked: waiting for owner` notes so the plan reflects reality.

Don't pretend to succeed at actions you can't perform — your owner can see the world and will know.

## When the owner narrows the goal

If the owner says something more focused than "kill the dragon" — e.g. *"first let's just get to the Nether"* or *"can you help me find a stronghold?"* — skip phases that aren't relevant. You don't have to do everything in this overview every time. Load only the phase skill(s) you need.

## What to load next

If the conversation is at the very beginning, almost certainly: `load_skill(name="tier_progression")`.
