---
name: end_game_overview
description: High-level roadmap to defeat the Ender Dragon. Load this FIRST when the owner asks for any end-game / "kill the dragon" goal — it tells you which specialised skill to load for the current phase.
---

# Skill: end_game_overview

Your owner has asked you to **defeat the Ender Dragon** — the canonical end-game of vanilla Minecraft. This skill is the map; the other skills are the territory. Each phase below maps 1:1 to a specialised skill you load on demand.

## How to run the journey

The full path from "fresh world" to "dead dragon" spans dozens of actions across three dimensions. Don't plan it all in one turn:

1. **Use `todowrite`** to write the 6 mainline phases as a top-level todo list, phase 1 `in_progress`.
2. **Load the matching skill** with `load_skill` only when you actually start that phase — loading all skills up front wastes tokens.
3. **Verify each phase's "done when" with `get_self_status`** before marking it `completed` — never assume an item is in your inventory.
4. Keep exactly one phase `in_progress` at a time.

## The 6 mainline phases

These mirror the vanilla advancement chain, so your owner can track your progress on their own advancement screen.

| # | Phase | Skill to load | Done when | Vanilla advancement |
|---|---|---|---|---|
| 1 | Tier up to diamond | `tier_progression` | Diamond pickaxe + diamond sword, iron-or-better armor, bow + 32 arrows, 32+ cooked food, 64+ cobblestone | "Diamonds!" |
| 2 | Build a Nether portal, enter | `nether_entry` | Standing in the Nether with the packlist intact | "We Need to Go Deeper" |
| 3 | Farm blaze rods | `blaze_rods` | ≥7 blaze rods in inventory | "Into Fire" |
| 4 | Acquire ender pearls | `ender_pearls` | ≥12 ender pearls in inventory | — |
| 5 | Find the stronghold, activate the End portal | `stronghold_finding` | Standing at the activated End portal | "Eye Spy" |
| 6 | Kill the dragon | `dragon_combat` | Ender Dragon HP → 0 | "Free the End" |

One **support skill**: `combat_basics` — load before any combat-heavy phase (blazes, endermen, the dragon). HP management, hunt-vs-shoot choice, retreat rules.

## What you can do yourself

You have the full toolset: `move_to` (navigation digs, bridges and pillars on its own — but only digs what your held tool can harvest, so travel with a pickaxe in hand), `auto_mine` (finds and travels to blocks by id), `place_block`, `collect_items`, `craft` (auto-uses/places a crafting table), `load_furnace`/`check_furnace`/`collect_furnace` (smelting), `equip_item`, `eat_item` (your healing), `hunt` (melee), `shoot` (bow), `interact_at`/`interact_entity` (native crosshair use/attack on blocks, air, entities — flint & steel, ender eyes, levers, …), `locate_structure` (strongholds, fortresses, #village, …), `place_block`'s precise inverse `break_block`, inventory management (`deposit_items`/`take_items` for chests, `drop_items`, `wait` for furnace batches and nightfall), plus perception (`get_self_status` — HP, equipment AND full inventory in one call — `get_world_info`, `scan_blocks`, `scan_nearby_entities`, `inspect_block`).

The whole route is therefore yours to execute autonomously. The one thing you cannot do is operate GUI blocks (enchanting table, anvil, brewing stand) — if the owner offers to enchant your gear, accept; never plan to enchant yourself.

## When the owner narrows the goal

If the owner asks for something more focused — *"just get to the Nether"*, *"find a stronghold"* — skip irrelevant phases and load only the skill(s) you need. Phases assume the previous phase's inventory; check `get_self_status` and backfill gaps instead of blindly starting from phase 1.

## What to load next

Fresh world, no gear: `load_skill(name="tier_progression")`.
