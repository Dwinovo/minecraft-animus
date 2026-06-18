# Animus

**LLM-powered AI companions for Minecraft — real players in a fake body, with a brain that runs on your machine and bills your own API key.**

Animus lets you summon a companion, then simply *talk to it*. Tell it "go mine me a stack of iron," "build a hut here," "follow me and kill anything hostile," or "smelt these and put the bars in that chest" — a large language model plans the work and drives the companion through it: pathfinding across terrain, mining, building, fighting, crafting, and using containers, all on its own.

> Animus is for **MC 26.1.2** and ships for both **Fabric** and **NeoForge** from a single codebase. Java 25.

---

## What makes it different

Most "AI companion" or "bot" mods bolt behavior onto a custom mob. Animus takes a different bet:

- **The body is a real player, not a mob.** A companion is a server-side *fake `ServerPlayer`* (Carpet-style). Because it's a genuine player, it interacts with the world through real player code — so it "just works" with arbitrary mods, redstone, mob AI, containers, and game mechanics, instead of needing per-feature adapters. Chunk-loading and `.dat` persistence come for free.
- **The brain runs on *your* client.** The agent loop talks to the LLM from the owner's machine using the owner's API key. There is no server-side LLM and no shared billing — each player pays for their own companion's thinking. Dedicated servers never make an API call.
- **Pathfinding is Baritone-grade, from scratch.** A self-contained A\* pathfinder reimplements Baritone's cost model and movement set 1:1 (no Baritone dependency), then drives the fake player by simulating movement inputs so vanilla physics does the walking.
- **No third-party SDKs.** LLM transport is the JDK's built-in `java.net.http.HttpClient` + Gson. Nothing else.

---

## How it works

```
   You (owner's client)                         Server
   ────────────────────                         ──────
   chat box ─▶ EntityAgentLoop ──▶ LLM API
                    │  (your key, your model)
                    │  tool calls
                    ▼
              ExecuteToolPayload ───────────▶ TaskQueue on the AnimusPlayer
                    ▲                              │  CompanionTickDispatcher
                    │                              ▼
              TaskResultPayload ◀──────────  CompanionTask  → pathfinding,
                                                            mining, combat,
                                                            container use…
```

1. **You type** a message in the companion's chat panel.
2. The **client-side agent loop** sends the conversation + tool schemas to your chosen LLM, streams the reply, and dispatches whatever tools the model called.
3. Read-only tools (status, scans, recipe lookups) answer immediately; **world-action tools** are shipped to the server as a packet, queued on the companion's body, and run by a per-tick task dispatcher.
4. Results flow back to your client and into the conversation, so the model sees what happened and decides the next step. It can chain many actions per request; you can hit **Stop** at any time.
5. History is **auto-compacted** (summarized) as it approaches the context window, so long sessions keep working.

---

## The companion

- **Summon** with `/animus player summon <name>`. Summoning is idempotent per (owner, name) — re-summoning wakes a dormant companion instead of spawning duplicates.
- **Persists like a player.** State lives in a vanilla `playerdata/*.dat` (position, inventory, health, owner); a world-saved registry tracks the roster. Companions return on owner login.
- **Owned by you, across dimensions.** Ownership is stored as your UUID and checked UUID-wise (vanilla `getOwner()` is level-scoped and breaks across dimensions, so Animus avoids it).
- **Recoverable death.** When a companion dies, vanilla death runs normally (drops, keepInventory, grave mods all work); the agent loop suspends, and the body respawns at the owner after a short delay. Cross-dimension travel feeds the brain an ambient world event.
- **Shows up as a player** in the tab list / player count, and renders with its own skin.

---

## Talking to it (UI)

Press **`G`** to open the roster (or jump straight to chat if you have only one companion). The companion panel has three tabs:

- **Chat** — a scrollable transcript on the left and a live **plan panel** on the right (the model's current to-do list). Tool calls stream a spinner while running and fold into a tidy "N steps · …" summary when a batch finishes — click to expand.
- **Items** — a read-only "character sheet" styled like the vanilla inventory: a live mouse-following 3D portrait, armor + offhand, the 2×2 crafting grid, segmented heart/hunger vitals, and the full backpack + hotbar.
- **Settings** — pick your LLM **provider**, paste your **API key** (masked, with a reveal toggle), and set the **model** and **base URL**.

A left-edge **HUD** shows a small avatar per companion: idle ones tuck away to a thin gold sliver; when a companion speaks, its avatar and a speech bubble slide out together.

The whole UI uses a hand-authored "Cottage" neobrutalist sprite theme (thick borders, warm palette, real GUI textures — no procedurally drawn chrome).

---

## What it can do — the tool set

The model has **27 tools**. The companion figures out *how* (pathing, tool selection, reach) on its own; you express *intent*.

**Movement & navigation**
- `move_to` — pathfind to coordinates (bridges, digs, jumps as needed)
- `locate_structure` — find the nearest structure (e.g. village, stronghold)
- `locate_biome` — find the nearest biome

**Mining & building**
- `auto_mine` — "gather N of these block types" — finds, paths to, mines, repeats
- `break_block` — remove one exact block
- `place_block` — place a block from inventory at an exact spot (with optional facing)

**Combat (native player combat)**
- `hunt` — melee N mobs of given types (real attack cooldown, weapon mods, crits)
- `shoot` — kill N mobs with a bow (charges, leads moving targets)

**Inventory & items**
- `collect_items` — pick up nearby drops
- `drop_items` — drop items (hand off to the owner / shed junk)
- `equip_item` — wear/wield gear (better tools speed mining; armor/weapons help in fights)
- `eat_item` — eat food to heal (real timed action)

**Containers & interaction**
- `interact_at` — aim at and use a block/air (open chests, furnaces, doors, levers…)
- `interact_entity` — follow and use a moving entity (villagers, animals…)
- `transfer` — move items between slots of an open GUI by intent (deposit/take/merge/swap)
- `inspect_gui` — read the open container's slots, cursor, and machine progress
- `close_gui` — close the container

**Crafting & recipes**
- `lookup_recipe` — JEI-style recipe lookup across crafting/smelting/stonecutter/smithing
- (the companion *crafts by hand-placing* items into the grid via `transfer` + `interact_at`)

**Perception & status**
- `get_self_status` — full self snapshot (HP, hunger, position, biome, equipment, inventory…)
- `get_owner_status` — your HP/hunger/position/held item/distance
- `get_world_info` — dimension, time, light, weather
- `scan_blocks` — find blocks of given types in a radius
- `scan_nearby_entities` — list nearby entities (filter hostile/passive/player)
- `inspect_block` — detailed read of one block (hardness, correct-tool, mining time, reach…)

**Planning & meta**
- `todowrite` — the model's own to-do list (shown live in the plan panel)
- `load_skill` — load a Markdown "skill" (a saved workflow) on demand
- `wait` — idle deliberately (smelting/night/timers) instead of burning tokens polling

### Skills

Skills are Markdown workflows in `config/animus/skills/<name>/SKILL.md` (YAML front-matter + body). They're advertised to the model as a table of contents and loaded full-text only when relevant via `load_skill`, keeping the prompt lean.

---

## LLM providers & setup

1. Install the mod (Fabric or NeoForge) and launch once to generate the config.
2. Open **`G` → Settings** (or run `/animus settings`), choose a provider, and paste your API key. You can also edit the config file directly.

Supported providers (OpenAI-compatible wire format, custom adapters per vendor):

| Provider | Config id(s) |
|---|---|
| OpenAI *(default)* | `openai` |
| DeepSeek (preserves reasoning round-trip) | `deepseek` |
| Moonshot / Kimi | `moonshot`, `kimi` |
| MiniMax | `minimax` |
| Doubao (Volcengine Ark) | `volcengine`, `doubao`, `ark` |
| Qwen (DashScope) | `dashscope`, `qwen`, `tongyi`, `aliyun` |

Config fields: `provider`, `apiKey`, `model` (default `gpt-5-2-mini`), `baseUrl` (optional override), `systemPrompt`. Stored per game directory — **Fabric:** `config/animus.json`; **NeoForge:** `config/animus-common.toml`. Streaming + token-usage accounting are on by default.

---

## Commands

| Command | What it does |
|---|---|
| `/animus player summon <name>` | Summon (or wake) a companion |
| `/animus player despawn <name>` | Permanently dismiss a companion |
| `/animus settings` | Open the settings screen on your client |
| `/animus reset` | Reset the client-side agent loops |

---

## Build & project layout

A standard Gradle **multiloader** project — shared code in `common/`, thin loader modules in `fabric/` and `neoforge/`, build logic in `buildSrc/`. The `common` module compiles against vanilla and reaches loader-specific functionality through a small `Services` abstraction (`IAnimusConfig`, `INetworkChannel`, `IPlatformHelper`) resolved via `ServiceLoader`.

```bash
./gradlew build                 # build both loaders
./gradlew :fabric:runClient     # run the Fabric client
./gradlew :neoforge:runClient   # run the NeoForge client
```

**Tech stack:** Java 25 · Minecraft 26.1.2 · Fabric Loader 0.19.2 / Fabric API 0.148.2 · NeoForge 26.1.2.50-beta · Mixin 0.8.5 + MixinExtras · LLM transport via `java.net.http.HttpClient` + Gson (no third-party HTTP/LLM libraries).

Only three small mixins are used (a menu-data accessor and two diagnostic logging hooks) — the design deliberately leans on the fake-player approach instead of invasive mixins.

---

## License (source-available, non-commercial)

- **Code** is licensed under **[PolyForm Noncommercial 1.0.0](LICENSE)** — you may use, modify, and redistribute the source for any noncommercial purpose, with attribution.
- **Art assets** (textures, sprites, sounds — anything under `assets/` that is not source code) are licensed under **[CC BY-NC 4.0](LICENSE-ART)** — you may use and create derivative works for noncommercial purposes, with attribution.
- **Commercial use of either requires a separate license from the author.**

This is *source-available*, not OSI-approved "open source" (the OSI definition forbids non-commercial restrictions). Please use the term "source-available" when describing this project.

---

*Built on the [MultiLoader Template](https://github.com/jaredlll08/MultiLoader-Template).*
