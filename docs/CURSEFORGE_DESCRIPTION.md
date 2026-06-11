# Animus — an AI companion that actually plays the game

Animus adds a tameable companion powered by a real LLM (bring your own API
key). Talk to it in natural language — any language — and it acts with its
own body in the world: it mines, crafts, smelts, builds, fights, stores loot,
and reports back in chat. No scripted behaviors; the model decides, a
27-tool body executes.

## Getting started

1. Tame one by feeding it food (bread, cooked meat, an apple — most foods work).
2. Right-click it to open the chat GUI, go to **Settings** and paste your LLM
   API key (OpenAI, DeepSeek, Kimi / Moonshot, MiniMax, Doubao / Volcengine
   and Qwen / DashScope are supported).
3. Say something like *"go mine 10 iron and smelt it"* — then watch.
4. Press **G** anytime to open the companion roster: see where your pets are,
   what they're doing, and chat with them from any distance or dimension.

## What it can do

- **Real work**: mining, crafting (auto-uses crafting tables), furnace
  smelting, precise block placement & removal, chest storage, item drops.
- **Real movement**: a terrain-modifying pathfinder that digs tunnels,
  bridges gaps, and pillars up on its own — "go to X" just works, including
  straight down to diamond level.
- **Real combat**: melee hunting and bow work, with built-in survival
  reflexes — it eats when hurt mid-fight and swims to shore instead of
  drowning, no AI round-trip needed.
- **Finding things**: locate any structure (fortresses, strongholds,
  villages, ancient cities…) with a server-friendly, lag-free search.
- **The long game**: ships with a complete, player-editable skill library
  for the entire Ender Dragon route — gear up, enter the Nether, farm blaze
  rods, hunt pearls, activate the End portal, fight the dragon.

## It remembers

Conversations persist across sessions and auto-compact when they grow long
(a manual Compact button too). It remembers the crafting tables, furnaces
and chests it has used — and walks back to them instead of crafting new
ones. While it works far away, its chunks stay loaded.

## Make it yours

- **Skills** are plain markdown in `config/animus/skills/` — edit them,
  write your own workflows, teach it your base's rules.
- **Custom models**: drop Bedrock-format models into
  `config/animus/models/` and switch skins in the GUI.

## Notes

- You need your own LLM API key; tokens are billed by your provider, and
  each player pays for their own companion. All game logic runs locally —
  only the conversation goes to your LLM provider.
- Works in singleplayer and on servers (install on both sides).

Source & issues: https://github.com/Dwinovo/minecraft-animus
