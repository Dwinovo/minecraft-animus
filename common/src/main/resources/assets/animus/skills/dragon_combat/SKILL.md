---
name: dragon_combat
description: Final boss. End arena layout, end crystal destruction order (low → high, no-cage → caged), dragon attack patterns (head charge / wings / breath), HP / recovery management, and the kill conclusion.
---

# Skill: dragon_combat

Phase 6 — the final boss. The Ender Dragon has 200 HP, regenerates while end crystals are intact, and has three distinct attack patterns. This is the longest skill because mistakes here cost you the entire run.

## Goal

The Ender Dragon's HP reaches 0. It plays its death animation (purple beams, slow fall to bedrock), drops a **dragon egg** on the exit portal pillar (decorative trophy), and a **gateway portal** appears in the centre allowing return to overworld.

## Core facts

### The arena

- **Your spawn location**: a 5×5 obsidian platform at coords roughly **(100, 49, 0)**, ~100 blocks from the central island. You're surrounded by empty void / floor of obsidian.
- **The central island**: a roughly circular flat-topped plateau, **about 100 blocks across**, at Y ≈ 60. Made of end stone with a tall obsidian fountain in the centre.
- **You spawn off the island** — you need to **either bridge across or pillar across** to reach it. Bring 64+ blocks (cobblestone, dirt, end stone if you've harvested it).
- **The void**: falling off the island = instant death + lost inventory + respawn at world spawn (or your set spawn bed). **Build edge fences if you fight near the perimeter.**

### End crystals

- **Count**: **10 crystals**, one on top of each of the 10 obsidian pillars surrounding the central island.
- **Pillar heights**: vary from ~50 to ~80 blocks tall.
- **Cage state**:
  - **2 pillars** (the tallest, typically labeled "Tower 1" and "Tower 2" in community guides) have **iron bar cages** around the crystal. Cages have small gaps but block most arrows.
  - **8 pillars** have **exposed crystals** — directly arrow-able from the ground.
- **Crystal effect**: each intact crystal beams a white connection to the dragon. **While that beam is alive, it heals the dragon by 1 HP every 0.5 seconds** (2 HP/sec total per crystal). With 10 alive, that's 20 HP/sec regen — you cannot outdamage it. **You must destroy crystals first.**
- **Destroying a crystal**: 1 arrow = pop. The explosion does up to 10 damage to nearby entities (including the dragon if it's circling — bonus damage).

### The dragon's stats

- **HP**: 200 (100 hearts)
- **Damage immunities**: none significant — diamond sword + Sharpness V (8.75 dmg) hits clean. Bow Power V arrows hit for 7.5–10.
- **Hitbox**: head (front) and body (centre). **Only the head takes full damage from arrows; body shots are reduced**.
- **Two phases** in vanilla 1.21:
  - **Phase 1**: Flying around, doing the three attack patterns. Most of the fight.
  - **Phase 2**: Once you've destroyed most crystals and dropped HP below ~25%, the dragon will land more often on the central fountain to "perch" — a melee window.

### The three dragon attacks

| Attack | When | Damage | Counter |
|---|---|---|---|
| **Head charge** | Periodic flyby — dragon dives at you head-first | **10 dmg** + knockback (can punch you off the island) | **Don't be on the perimeter when this comes**. Stay near the centre. Block with shield if you have one. |
| **Wing slap** | Close-range, when dragon is landed or low | 5 dmg + knockback | Maintain melee window during landed-perch phase only; back off when wings flare. |
| **Dragon breath** | Mid-range arc-cone purple cloud | **3 dmg/sec for 3 sec = 9 dmg total** + persisting cloud | **Move sideways immediately**. The cloud lingers — don't walk back into it. |

The dragon **does not have a direct ranged attack** beyond breath. No fireballs.

### Healing windows for the dragon

- **Crystals**: the main threat — destroyed first.
- **Perch on fountain (phase 2)**: regenerates ~1 HP/sec while perched. But this is also your best melee window — trade hits.

## Recommended fight plan

### Step 0 (before dropping in)

Verify packlist:

- **Diamond sword (Sharpness V ideal)** — for landed-perch melee
- **Bow (Power V + Infinity ideal)** + at least 1 arrow — for **crystals** and air shots
- **Full diamond armor (Protection IV ideal)** + **Feather Falling IV boots** (huge for the End — falls happen)
- **64+ blocks** for bridging and pillaring (cobble is fine — soft, but cheap)
- **32+ cooked food**
- **Water bucket** (3 — for putting out fires, breaking falls; but **don't try to place water in the End** during peak fight, it auto-removes here just like Nether actually NO — End allows water unlike Nether)
- **Optional but powerful**: golden apples (panic heal), splash potions of healing, ender pearls (emergency reposition)

### Step 1: bridge to the central island

1. Drop into End portal. You spawn on the obsidian platform.
2. **Pillar up 2 blocks** immediately so head-charges miss you (5×5 platform is dangerously low).
3. **Bridge across** in a straight line toward the central island. Bridge 1 block wide is fine, 2 blocks wide is safer.
4. **As you cross, dragon will start dive-bombing**. Crouch on the bridge to avoid being knocked off; build walls on the bridge sides if it gets hairy.
5. **Reach the island. Place blocks around the perimeter to fence yourself in.**

### Step 2: destroy crystals (the most important phase)

The order matters — **low pillars first** so you can deal with them with bow shots from ground level. Then deal with caged ones.

1. **From the island's edge, walk a clockwise (or counter-clockwise — doesn't matter) ring around the perimeter.**
2. At each pillar, **shoot the crystal at the top with your bow**. 1 arrow = pop. Visual: orange flash + small explosion + the white beam vanishes.
3. Skip the **2 caged crystals** on your first pass — they need different handling.
4. After 8 crystals popped: **dragon's regen is gone**. You can now actually damage it.

### Step 3: handle the caged crystals

Two options:

**Option A (clean)**:
- Climb the caged pillar (pillar up alongside it with cobble), break iron bars at top, jump down (you have Feather Falling IV).
- 1 arrow at point-blank.

**Option B (faster, riskier)**:
- Wait for the dragon to circle near the caged pillar. It often clips through the cage.
- Throw an **ender pearl** at the crystal — pearls explode crystals on impact. **You take 5 dmg from pearl teleport** — eat afterwards.

### Step 4: damage the dragon

Now with **no regen** active:

1. **Bow during flight**: aim at the head as it circles. ~7–10 dmg per Power V arrow. Lead the target — arrows are slow.
2. **Melee during perch (phase 2)**:
   - Dragon will perch on the centre obsidian fountain.
   - Run to it, hit the head 3–5 times with Sharpness V diamond sword (8.75 dmg each).
   - **Back off the moment wings flare or breath puffs** — wing slap or breath cloud incoming.
   - Repeat.

### Step 5: cleanup

- Dragon HP → 0 triggers the death animation (~20 seconds): purple beams from the head, slow rise to bedrock fountain, explodes into light.
- **You earn ~12,000 XP** (be near the spawn point to absorb it).
- **Dragon egg** spawns on top of the centre fountain (decorative).
- **Exit portal** opens in the centre (small bedrock frame with stars — jump in to return to overworld).
- **Gateway portals** start appearing at the perimeter (1-time use; for outer-end exploration).

## Common mistakes / how to avoid

| Mistake | Why it kills | Fix |
|---|---|---|
| Bridging without pillar-up | Head-charge knocks you into void | Always be 2+ blocks above bridge floor |
| Trying to fight dragon with crystals still alive | 20 HP/sec regen — impossible | Crystals first, always |
| Standing in dragon breath cloud | 3 dmg/sec drains your buffer fast | Sidestep the moment you see purple |
| Melee with bare iron sword | DPS too low, you'll die before HP drops | At least diamond + Sharpness V |
| Forgetting to fence the perimeter | One mistimed dive = death | Place 1-block walls at the island edge |
| Sleeping in the End (it's allowed, but...) | Wait — sleep IS allowed in the End. But it doesn't pass time. Skip it. | Just don't bother |

## Decision points

- **Bridge vs ender pearl across to island?** Bridge is safer. Pearl is faster but loses 5 HP per throw. Use pearl only if dragon dive is imminent and you need to relocate fast.
- **Take all 8 ground crystals first, or alternate ground + caged?** All 8 ground first — they're faster to kill, max stop the regen drop.
- **Should I bring a bed for "bed bomb" cheese strategy?** Beds **explode** in the End just like the Nether — placing a bed near the dragon's perch and detonating deals ~150 dmg in one go (only 1 hit needed for sub-50 HP). It's the speedrun cheese. **Allowed in vanilla**, but risky (you'll lose your bed and might die). Optional.
- **Re-engage if I die?** Yes — respawn at overworld bed, restock, walk through your stronghold portal again. The dragon stays at its current HP (resets to full if you re-summon with 4 end crystals + corner placements, but that's a separate operation).

## Current tool mapping

- **What you can do today (with only `move_to`)**: bridge-cross with owner; reposition to centre of island; chase dragon perch coords when owner reports them.
- **What you cannot do yet**: shooting crystals, melee attacking dragon, eating food, drinking potions, placing fences, throwing pearls. **Owner does all combat**. Your value here: calling out the crystal destruction order from the list above, warning when dragon is incoming (audible-cue based — dragon roar is distinctive), reminding to eat at low HP.
- **Future tools that will help**: `attack_entity(target=end_crystal, weapon=bow)`, `attack_entity(target=ender_dragon, weapon=sword)`, `consume(food)`, `use_item_throw(ender_pearl, target)`.

## What to load next

If dragon dies: you're **done**. Mark the entire end-game plan completed. Tell your owner congratulations.

If dragon lives and you died: respawn, restock at overworld base, `load_skill(name="dragon_combat")` again (this skill) to re-read tactics, walk back through the stronghold portal. The dragon will still be at the HP you left it (no reset).
