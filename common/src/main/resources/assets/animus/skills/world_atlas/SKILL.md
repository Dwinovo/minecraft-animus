---
name: world_atlas
description: Exact registry ids, home dimensions and "why go there" for every searchable structure and biome — load before locate_structure / locate_biome when unsure of an id. Includes the classic id traps (mansion, monument, jungle_pyramid, #village).
---

# Skill: world_atlas

`locate_structure` / `locate_biome` take EXACT registry ids (or `#tags`). A
guessed id costs a failed round — this is the authoritative list (MC 26.1).

## Id traps — memorize these four

- Woodland mansion = `minecraft:mansion` (NOT woodland_mansion)
- Ocean monument = `minecraft:monument` (NOT ocean_monument)
- Jungle temple = `minecraft:jungle_pyramid` (NOT jungle_temple)
- "Any village" = the TAG `#minecraft:village` — villages are five separate
  structures (`village_plains/_desert/_savanna/_snowy/_taiga`)

## Structures — Overworld

| id | why go |
|---|---|
| `stronghold` | THE End portal. Always findable (ring placement, instant answer) |
| `#minecraft:village` | beds, food, crops, iron golem iron, trading |
| `mineshaft` | rails, chest minecarts: iron/coal/lapis; easy cave access |
| `desert_pyramid` | chest loot incl. diamonds/emeralds — beware the TNT floor under the center |
| `jungle_pyramid` | small loot, dispenser traps |
| `mansion` | very far away usually; totem of undying from evokers |
| `monument` | guardians (hostile, ranged); sponges, gold; underwater — needs drainage tactics |
| `ancient_city` | deep dark, Y≈-52: enchanted books, but the WARDEN lives here — sneak or die |
| `trial_chambers` | Y -40..-20: trial spawners, breeze rods, vaults (heavy core/mace) — combat gauntlet |
| `trail_ruins` | buried; suspicious gravel — needs brush |
| `pillager_outpost` | crossbows, Bad Omen captain |
| `igloo` / `swamp_hut` / `buried_treasure` / `shipwreck` / `ocean_ruin_cold` / `ocean_ruin_warm` | minor loot (treasure maps, hearts of the sea) |
| `#minecraft:ruined_portal` | obsidian shortcut + gold loot; spawns in BOTH overworld and nether |

## Structures — Nether / End

| id | why go |
|---|---|
| `fortress` | blaze rods (dragon route phase 3), wither skeletons, nether wart |
| `bastion_remnant` | gold/piglin loot; shares placement grid with fortress (2:3 odds) |
| `nether_fossil` | bone blocks, soul sand valley decoration |
| `end_city` | elytra + shulker shells (post-dragon); the End's outer islands ONLY |

## Biomes — by purpose (locate_biome)

| 想要什么 | biome id |
|---|---|
| 末影人/珍珠 | `warped_forest`(下界,密度最高) |
| 府邸所在 | `dark_forest` |
| 沙漠系(神殿/村庄/仙人掌) | `desert` |
| 平坦好走/马 | `plains` |
| 竹子/熊猫/丛林木 | `jungle` 或 `bamboo_jungle` |
| 绝对安全扎营(不刷怪) | `mushroom_fields` |
| 蜜蜂/花 | `flower_forest` |
| 雪/冰 | `snowy_plains`、`ice_spikes`、`frozen_peaks` |
| 山羊/绿宝石矿 | `jagged_peaks`、`stony_peaks` |
| 大型垂直洞穴/杜鹃 | `lush_caves`、`dripstone_caves` |
| 监守者(勿去) | `deep_dark` |
| 下界四象限 | `nether_wastes`、`crimson_forest`、`soul_sand_valley`(骷髅/灵魂沙)、`basalt_deltas`(岩浆怪) |
| 海洋家族 | `#minecraft:is_ocean`(冷暖深浅 8 种,统一用 tag 搜) |

常用 tag:`#minecraft:is_forest`、`#minecraft:is_ocean`、`#minecraft:is_mountain`、
`#minecraft:is_jungle`、`#minecraft:is_badlands`。

## 分流口诀

- **结构**(有箱子/房间的建筑物)→ `locate_structure`;**群系**(一片地形/气候)→ `locate_biome`。
- 拿错类别没关系——失败消息会给出修正后的调用,照抄即可。
- 两个工具都只搜**当前维度**:fortress/bastion 在下界,end_city 在末地,其余在主世界。
- 任何数据包/模组加的注册表 id 同样合法。
