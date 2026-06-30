---
name: world_atlas
description: Every searchable structure and biome in MC 26.1 — exact registry ids with a one-line picture of each (what's there, dangers, loot), the classic id traps, family tags, and the structure-vs-biome routing rule. Load before locate_structure / locate_biome.
---

# Skill: world_atlas

`locate_structure` / `locate_biome` take EXACT registry ids (or `#tags`),
current dimension only. A guessed id costs a failed round — this is the
COMPLETE catalog (all 34 structures + all 65 biomes, extracted from the
26.1 registry): if an id isn't here, it doesn't exist in vanilla.

## Id traps — memorize these four

- Woodland mansion = `minecraft:mansion` (NOT woodland_mansion)
- Ocean monument = `minecraft:monument` (NOT ocean_monument)
- Jungle temple = `minecraft:jungle_pyramid` (NOT jungle_temple)
- "Any village" = the TAG `#minecraft:village` — the five concrete ids are
  `village_plains` / `village_desert` / `village_savanna` / `village_snowy` / `village_taiga`

## Structures — Overworld

| id | 一句话画像 |
|---|---|
| `stronghold` | 末地传送门所在;图书馆有附魔书。同心环放置,搜索必中、即时返回 |
| `village_plains` …等5个 / `#minecraft:village` | 床、农田、熔炉、村民交易、铁傀儡(铁);用 tag 搜最近的任意村庄 |
| `mineshaft` | 废弃矿井:铁轨、运输矿车箱(铁/煤/青金石/稀有附魔书),洞穴系统入口 |
| `mineshaft_mesa` | 恶地版矿井,生成在地表高度,木结构外露,极易进入 |
| `desert_pyramid` | 沙漠神殿:中心地板下 4 个宝箱(钻石/绿宝石几率)——**踩中间的压力板会引爆 TNT**,先挖侧面 |
| `jungle_pyramid` | 丛林神庙:绊线+发射器陷阱,2 箱小战利品,拉杆谜题 |
| `igloo` | 雪屋:地毯下藏地下室(酿造台、虚弱药水+金苹果=治疗僵尸村民) |
| `swamp_hut` | 沼泽女巫小屋:女巫+黑猫,炼药锅;无箱子 |
| `mansion` | 林地府邸:通常极远;唤魔者掉**不死图腾**,悦灵被关在里面;房间众多注意迷路 |
| `pillager_outpost` | 掠夺者前哨站:弩、旗帜队长(打死获不祥之兆——别带着进村庄!),笼子里有悦灵/铁傀儡 |
| `monument` | 海底神殿:守卫者(远程激光)+远古守卫者(挖掘疲劳诅咒);海绵房、8 个金块;**整体在水下,需排水战术,慎入** |
| `ocean_ruin_cold` / `ocean_ruin_warm` | 海底废墟(冷=石质/暖=砂岩):溺尸(可能持三叉戟)、藏宝图、附魔物 |
| `shipwreck` / `shipwreck_beached` | 沉船(水中/搁浅):藏宝图箱、铁/金锭、附魔书;搁浅版不用下水 |
| `buried_treasure` | 埋藏宝藏:**海洋之心**(潮涌核心材料)+铁金;在沙滩下,坐标处往下挖 |
| `ancient_city` | 远古城市(deep_dark,Y≈-52):附魔书(迅捷潜行)、回响碎片、恢复指南针;**监守者领地——潜行移动,别碰 sculk 尖啸体,打不过就跑** |
| `trail_ruins` | 小径遗迹:大部分埋在地下,刷怀疑的沙砾需要**刷子**;陶片、盔甲纹饰 |
| `trial_chambers` | 试炼密室(Y -40..-20):试炼刷怪笼连战,**旋风人(breeze rod)与重核(锤)唯一来源**;金库要试炼钥匙 |
| `ruined_portal` / `#minecraft:ruined_portal` | 废弃传送门:**现成黑曜石**+金质战利品;用 tag 搜任意变体(`_desert` `_jungle` `_swamp` `_mountain` `_ocean` `_nether`) |

## Structures — Nether / End

| id | 一句话画像 |
|---|---|
| `fortress` | 下界要塞:**烈焰人刷怪笼(烈焰棒=屠龙必需)**、凋灵骷髅(头)、下界疣;龙之路线第 3 阶段 |
| `bastion_remnant` | 堡垒遗迹:金块、下界合金升级模板几率;**猪灵蛮兵无视金盔直接开打**——比要塞危险 |
| `nether_fossil` | 下界化石:几块骨块,灵魂沙峡谷里的小可怜,基本不值得专程搜 |
| `end_city` | 末地城(仅外环岛,屠龙后经折跃门):潜影贝(壳=盒子)、**末地船上有鞘翅**;潜影弹会浮空,带牛奶或慢降 |

## Biomes — Overworld(按家族)

| id | 一句话画像 |
|---|---|
| `plains` / `sunflower_plains` | 平坦开阔:村庄、马、蜜蜂;向日葵版多向日葵(天然指南:花朝东) |
| `snowy_plains` / `ice_spikes` | 雪原:雪屋、兔子、北极熊;冰刺版=浮冰柱(打包冰) |
| `desert` | 沙子/仙人掌/枯木:沙漠村庄、神殿、水井;夜晚尸壳(不烧) |
| `forest` / `flower_forest` / `birch_forest` / `old_growth_birch_forest` | 橡树桦树基本盘;花林=全花种+蜜蜂(养蜂首选) |
| `dark_forest` | 黑森林:树冠遮天**白天也刷怪**;府邸的家;巨型蘑菇 |
| `pale_garden` | 苍白之园(黑森林变体):苍白橡木、树脂;**嘎枝(Creaking)只在夜间、打本体无效,要找心脏方块** |
| `taiga` / `snowy_taiga` / `old_growth_pine_taiga` / `old_growth_spruce_taiga` | 云杉系:狼、狐狸、甜浆果;老成林有巨木+灰化土,产蘑菇 |
| `savanna` / `savanna_plateau` / `windswept_savanna` | 金合欢/草黄:村庄、马、羊驼;风袭版地形破碎垂直落差大,小心摔 |
| `windswept_hills` / `windswept_gravelly_hills` / `windswept_forest` | 风袭丘陵:**绿宝石矿**、羊驼;砾石版=大量沙砾(燧石) |
| `jungle` / `sparse_jungle` / `bamboo_jungle` | 丛林:可可豆、西瓜、鹦鹉豹猫;竹林=熊猫+脚手架原料;丛林神庙在此 |
| `badlands` / `eroded_badlands` / `wooded_badlands` | 恶地:陶瓦色彩、**全高度额外金矿**、地表矿井;不刷被动动物 |
| `meadow` / `cherry_grove` | 山地草甸:蜜蜂、驴;樱花林:粉色、樱花木 |
| `grove` / `snowy_slopes` / `frozen_peaks` / `jagged_peaks` / `stony_peaks` | 山地带:山羊(角)、**细雪坑会陷进去冻伤(皮革靴免疫)**;石峰有绿宝石+煤铁裸露 |
| `swamp` / `mangrove_swamp` | 沼泽:史莱姆(夜)、女巫屋、黏土、蓝兰花;红树林版=泥巴+红树+青蛙 |
| `river` / `frozen_river` | 河流:**黏土、甘蔗、海带**,天然护城河;溺尸出没 |
| `beach` / `snowy_beach` / `stony_shore` | 滩涂:海龟(鳞甲)、埋藏宝藏多在沙滩;石岸=悬崖底碎石 |
| `ocean` / `deep_ocean` | 普通海:海带鱿鱼鳕鱼;**深海版才有海底神殿** |
| `warm_ocean` / `lukewarm_ocean` / `deep_lukewarm_ocean` | 暖海:**珊瑚礁**、热带鱼、河豚(酿水肺药水) |
| `cold_ocean` / `deep_cold_ocean` / `frozen_ocean` / `deep_frozen_ocean` | 冷/冻海:冰山、北极熊;水下废墟石质版 |
| `mushroom_fields` | 蘑菇岛:**唯一不刷敌对怪的群系**,哞菇;绝对安全的扎营地 |
| `dripstone_caves` / `lush_caves` | 洞穴群系:钟乳石(滴水收集岩浆!)+额外铜矿;繁茂洞穴=发光浆果(食物+光源)、美西螈、黏土 |
| `deep_dark` | 深暗之域(Y 极深):sculk、远古城市;**监守者——这不是战斗区域,是潜行区域** |

## Biomes — Nether / End

| id | 一句话画像 |
|---|---|
| `nether_wastes` | 下界基本盘:猪灵僵尸猪灵、岩浆海 |
| `crimson_forest` | 绯红森林:疣猪兽(**下界最稳食物来源**)、绯红木 |
| `warped_forest` | 诡异森林:**末影人密度最高(珍珠农场)**,几乎不刷别的敌对怪——下界最安全 |
| `soul_sand_valley` | 灵魂沙峡谷:骷髅+恶魂多发;灵魂沙/土(灵魂火把/营火),走得慢 |
| `basalt_deltas` | 玄武岩三角洲:岩浆怪海;玄武岩/黑石量产地;地形最碎,难走 |
| `the_end` | 主岛:**末影龙**;黑曜石柱+水晶 |
| `end_highlands` / `end_midlands` / `end_barrens` / `small_end_islands` | 外环岛(经折跃门):高地=紫颂果+末地城的家;贫瘠/小岛=虚空跳台,小心摔出世界 |
| `the_void` | 仅超平坦虚空预设存在,实战永远搜不到——见到这个名字说明搜错了 |

## 常用 tag

`#minecraft:is_forest` `#minecraft:is_ocean` `#minecraft:is_mountain`
`#minecraft:is_jungle` `#minecraft:is_badlands`(群系);
`#minecraft:village` `#minecraft:ruined_portal` `#minecraft:shipwreck`
`#minecraft:ocean_ruin` `#minecraft:mineshaft`(结构)。

## 分流口诀

- **结构**(有箱子/房间的建筑)→ `locate_structure`;**群系**(一片地形气候)→ `locate_biome`;
- 拿错类别没关系,失败消息会给修正调用;拼错 id 会收到 "did you mean…";
- 只搜**当前维度**:fortress/bastion 在下界,end_city 在末地,其余主世界;
- 数据包/模组加的注册表 id 同样合法。
