# AGENTS.md

> 面向 AI 编码代理的项目说明。简体中文。

## 项目愿景（Animus）

`minecraft-animus` 的目标是打造一个 **完全由 LLM 驱动的 Minecraft 实体**：

1. 模组只包含 **一个实体**。该实体的行为不通过传统硬编码 Goal/Behavior 决定，而是由外部 LLM 决策。
2. 使用 vanilla **`Goal` + `GoalSelector`** 系统作为执行层：每个原子任务（如 `move_to`）= 一个 `Goal` 子类。**当前阶段强制串行**：`TaskQueue` 是单 FIFO，所有 `LlmTaskGoal.canUse()` 都 peek 同一个队头，只有 toolName 匹配的 goal 能 `pollMatching` 走它；其它 LLM goal 全部 idle 等候。LLM 任务统一注册在 priority 0，自动消除抢占。**未来真要并行**（比如"边走边看"），改的是 `TaskQueue` 拆成 per-channel 队列 + `LlmTaskGoal` 重新引入 `setFlags(...)` 调用，**不是 `Goal.Flag` 本身**——`Goal.Flag` 这套机制 vanilla 自然支持跨 flag 并行，目前只是被单队列 FIFO 兜底成串行。
3. 将原子任务 **映射为 ToolCall**：每个工具 = LLM 视角的 schema + Task 翻译器。Tool 和 Task 严格分离 —— Tool 描述 LLM 看到什么，Task 描述世界里发生什么。一对一不是硬约束（一个 Tool 可以发多个 Task，反之亦然）。
4. 结合 **Skill** 与 **MCP**（Model Context Protocol）扩展能力：Skill = 编排好的任务链（对 LLM 暴露为单个 tool，内部按序执行原子 task，失败时回报具体到步），MCP 用于把外部上下文 / 工具喂给 Agent。
5. 感知层（周期 sensor → perception 快照）作为 LLM 的 **观测输入**：先用一个 100 行的 `Perception` POJO（Phase-2 实现），不引入 vanilla Brain 的 Memory/MemoryModuleType 注册机制（成本过高）。

设计原则：**Goal 是执行层；LLM 是决策层；ToolCall 是它们之间唯一的接口。** 不要在 Goal 里写策略，也不要在 LLM prompt 里写底层位移/路径逻辑——两边各司其职。

**为什么不用 Brain**：Brain 的 Activity/Schedule/Memory 模型是为"被动 AI"（村民日程、Warden 状态机）设计的，命令式调度需要伪造 memory 状态。Goal 生命周期（canUse → start → tick → stop）和我们 TaskRecord 的状态机（PENDING → RUNNING → SUCCESS/FAILED/TIMEOUT/CANCELLED）几乎天然 1:1 对齐，由 [`LlmTaskGoal`](common/src/main/java/com/dwinovo/animus/task/LlmTaskGoal.java) 这个 bridge 就能把两者粘起来。详见该类注释。

## ToolCall 清单（LLM 能力面）

> 这是 LLM 通过 tool_call 能做的全部事情。注册于 [`CommonClass.registerTools()`](common/src/main/java/com/dwinovo/animus/CommonClass.java)，实现在 `common/.../agent/tool/tools/`。每个工具 = 一份 LLM 看到的 schema + 一个把它翻译成世界行为的 Task。**共 20 个。**

**行动类（改变世界 / 实体状态）**

| 工具 | 参数 | 作用 |
|---|---|---|
| `move_to` | `x, y, z, speed` | 走到坐标。**自研地形改造寻路**——会搭桥/垫脚/搭柱/挖障碍/下挖。到目标 ~2 格内算成功；不可达/被挡/超时则失败。 |
| `mine_block` | `block_ids[], count, radius?` | **意图级采集**：给方块类型和数量,实体自己扫描→自研寻路走过去(搭桥/挖障碍)→挖进背包→重复,直到够数或挖空。不需要坐标。够不到部分如实回报实际数量。 |
| `craft` | `item_id, count` | **意图级合成**(对标 Voyager)：给物品 id 和数量,实体反查配方→从**自己背包**凑齐材料→3×3 配方自动走到附近工作台(或摆一个自带的,**摆出的留在原地不回收、并回报坐标供复用**)→合成。不指定网格/工作台。**不递归**:中间产物缺了就失败并回报缺口(如 `missing 3x oak_planks`),让 LLM 自己先合前置。如实回报实际产出数。 |
| `equip_item` | `item_id, slot?` | 把背包里的物品**装到身上**才真正生效:工具/武器进主手(加速 `mine_block`、提升近战),护甲进对应槽。省略 slot 按物品类型自动归位;原槽位物品换回背包。这是 `craft` 的价值落点(合出来的镐不装等于没用)。 |
| `hunt` | `entity_ids[], count, radius?` | **意图级战斗**(`mine_block` 的怪物同构体)：给怪物类型和数量,实体自己扫描→**自研地形寻路**追击(搭桥/挖墙/跳过去)→近战击杀→吸取掉落→重复,直到够数或刷空。不需要坐标/id。够不到部分如实回报实际击杀数。 |
| `shoot` | `entity_ids[], count, radius?` | **意图级远程战斗**(`hunt` 的远程版)：给类型和数量,实体自己扫描→寻路靠到弓程+视线内→**拉弓射箭**直到打掉→重复。目标**可含非生命体**(末影水晶 `end_crystal` 必须远程炸;烈焰人远程怪)。**前置门槛**:主手须持弓且背包有箭,缺一上来就指导性失败。 |
| `collect_items` | `item_ids[]?, radius?` | 把附近散落的掉落物**主动走过去捡起**(自研寻路逐个走到、靠自动吸取生效)。可选 `item_ids` 只捡指定物品(省略=全捡)。采集/狩猎后扫干净一片地。回报捡了多少。 |
| `load_furnace` | `input_id, count, fuel_id` | **异步熔炼-装料**：找附近熔炉(或摆一个自带的)→寻路过去→把输入+燃料放进**真实 `FurnaceBlockEntity`** 的格子→**立即返回**炉子坐标。之后 vanilla `serverTick` 自己烧(真实时间+火焰特效,实体不必在场)。燃料类型由 LLM 指定,所需数量由实体按 vanilla `fuelValues` 算。不可熔炼/燃料无效/缺料/无炉且无可摆都给指导性失败。`count≤64`(一个输入槽)。 |
| `check_furnace` | `x, y, z` | **远程只读**查熔炉工作状态:是否点燃、输入/燃料/产物槽内容、待烧数、约 ETA。不寻路、不在场也能查——用它判断 `load_furnace` 起的烧制好了没,再决定要不要走回去收。未来可推广到别的工作方块。 |
| `collect_furnace` | `x, y, z` | 寻路到熔炉→把产物槽掏进背包→返回取走数 + 剩余状态(还在烧几个、背包满了剩多少在炉里)。坐标来自 `load_furnace`。没烧好会如实回报"还没好"。 |
| `place_block` | `block_id, x, y, z` | 在绝对坐标放一个方块:寻路到目标**相邻可站位**(搭桥/挖障同 move_to)→**经 FakePlayer `useItemOn` 对参照方块放置**(走真实 `BlockItem.place`,**朝向正确**:楼梯/原木/箱子/门/床/含水)。吸收 Voyager 的**支撑检查**:目标必须有相邻实体方块,**拒绝浮空放置**;目标格须空/可替换;不持有/非方块/无支撑/无可达站位都给指导性失败。用于火把照明、墙体掩体、封洞、按需摆工作台/熔炉/箱子。 |
| `use_item` | `item_id, x?, y?, z?` | **右键用物品**(经 FakePlayer 走真实 vanilla 物品逻辑)。给 x/y/z = **用在该方块上**(先寻路到可达):打火石点黑曜石**点燃下界传送门**、末影之眼放进**末地框架**、骨粉催熟、桶放/舀液体。省略坐标 = **对空使用**:扔末影之眼定位要塞等。无效/够不到/没持有都给指导。**仅限作用于世界的用法**——吃/喝不走这里(那会作用到假人身上,不治疗实体)。 |
| `eat_item` | `item_id` | **吃东西填饱食度**(直接作用于 Animus,不走 FakePlayer)。**过程式**:按食物 `Consumable.consumeSeconds` 持续若干 tick,期间嘴部 `sendParticles` 喷碎屑 + 播 `GENERIC_EAT`;**吃满才结算** `foodData.eat()` 填饱食度(**不再瞬间回血**)+ 套食物效果(金苹果再生/吸收)+ 扣 1、残留容器回背包。中途打断啥也不消耗。回血靠"吃饱着"由饥饿系统慢慢恢复(见下)。`get_self_status` 回报 `food_level`,模型据此决定何时吃。 |

**自我 / 库存感知（读自身）**

| 工具 | 参数 | 作用 |
|---|---|---|
| `get_self_status` | — | 读自身：HP、坐标、维度、主副手物品、当前攻击目标、移动状态。战斗决策前必调。 |
| `get_owner_status` | — | 读主人：名字、在线、HP、饥饿、坐标、距离、手持物。follow/protect 决策前调；离线返回 online:false。 |
| `get_storage` | — | 读自己背包：每个非空槽的 {slot, item_id, count, max_stack}。采集前后核对携带物。 |

**世界感知（读环境）**

| 工具 | 参数 | 作用 |
|---|---|---|
| `scan_nearby_entities` | `radius, type_filter` | 列附近实体（hostile/passive/player/all），按距离排序，最多 20 个。每条带 id、类型、坐标、距离、HP。纯态势感知（威胁/拥挤/血量）；打怪用 `hunt`（它自己按类型扫）。 |
| `scan_blocks` | `block_ids[], radius` | 球形范围批量找指定方块，按距离排序。纯感知/勘察用——真要采集直接用 `mine_block`(它自己会找)。 |
| `inspect_block` | `x, y, z` | 查单个方块：id、硬度、是否有对的工具、预估挖掘 tick、是否在挖掘范围。mine_block 前确认会成功。 |
| `get_world_info` | — | 读世界：维度、game-time、昼夜（战斗/刷怪规划）、天气。 |

**规划 / 元能力**

| 工具 | 参数 | 作用 |
|---|---|---|
| `todowrite` | `todos[]` | 多步任务的待办清单（同一时刻只允许一个 in_progress）。 |
| `load_skill` | `name` | 按名加载一段 SKILL.md 工作流（编排好的任务链 / 详细操作指南），按需注入上下文。 |

## 当前进度

- 已从 MultiLoader-Template 克隆并完成改名：`mod_id=animus`、`mod_name=Animus`、`group=com.dwinovo.animus`、入口类 `AnimusMod`、`rootProject.name=minecraft-animus`。
- 三个 mixin 配置文件已重命名为 `animus.mixins.json` / `animus.fabric.mixins.json` / `animus.neoforge.mixins.json`，内部 `package` 字段指向 `com.dwinovo.animus.mixin`。
- **基岩版渲染管线已搭建**（迁移自 [`minecraft-chiikawa`](https://github.com/dwinovo/minecraft-chiikawa)，作者本人为两个项目的唯一版权人，可自由重新许可）：`common/.../anim/` 下完整的 Bedrock geo/animation/molang 烘焙 + 多控制器状态机 + 不可变快照渲染。
- **`AnimusEntity` 实体已注册**：继承 `PathfinderMob` 实现 `AnimusAnimated`，Fabric + NeoForge 各自走 vanilla 注册路径（无 service 抽象，YAGNI）。
- **双源资源加载**：默认资产走 `assets/animus/`（namespace `animus`），玩家自定义模型走 `<gameDir>/config/animus/models/`（namespace `animus_user`）。
- **默认模型 Hachiware** 已就位（dwinovo 原创美术资产，重新许可为 CC BY-NC 4.0）。
- **owner + 驯服系统**：`AnimusEntity extends TamableAnimal`，食物 tag `animus:tame_foods` 控制驯服食材。蹲下右键打开换肤 GUI；普通右键打开 LLM 对话 GUI（仅 owner）。
- **玩家式饥饿系统**：`AnimusEntity` 持有 vanilla `FoodData`(0-20,NBT 存档 + 同步 `DATA_FOOD_LEVEL`)。`eat_item` 填饱食度;`tickHunger`(自研,因 `FoodData.tick` 绑死 ServerPlayer)在 `customServerAiStep` 跑:饱食度 ≥18 且受伤 → 每 80 tick 慢回 1 HP(消耗饱食度),~600 tick 被动掉一点。**故意不饿死**——空饱食度只是停止回血。`get_self_status` 暴露 `food_level`。这样吃东西是"填饱 + 持续回血"而非瞬间满血。
- **零第三方 LLM 依赖**：用 JDK `java.net.http.HttpClient`（Java 25 内置）+ Gson（MC vanilla 自带）直发 OpenAI 协议。无 OkHttp / OpenAI SDK / kotlin-stdlib / jackson / swagger。**mod jar ~260KB**（早期内嵌 OpenAI SDK 时 50MB，砍了 99.5%）。
- **LlmProvider 抽象** (`common/.../agent/provider/`)：单点 OpenAI ↔ 内部协议适配。`OpenAIProvider` 是默认实现，`DeepSeekProvider` 继承并处理 `reasoning_content` 字段的 round-trip（修 thinking 模式 400 兼容性问题）。Config 字段 `provider: "openai" | "deepseek"` 切换。
- **LLM 调用在客户端**：每个玩家用自己的 API key、自付 token。服务端不调 LLM。设计原因：避免服务器主人为所有玩家承担 token 消耗 + 玩家不需要把 key 上交服务端。
- **LLM 任务执行框架**（端到端跑通，SSE streaming 默认开启）：`common/.../task/`（原子任务生命周期 + GoalSelector 桥接）+ `common/.../agent/`（HTTP transport + provider + LLM 客户端 + ConvoState + turn cap + batch-dedup + stale watchdog）+ `common/.../client/agent/`（per-entity `EntityAgentLoop` + 注册表）+ 右键 owner 对话 GUI（聊天 / 换模型 / 看库存三页签）+ `ExecuteToolPayload`(C→S) / `TaskResultPayload`(S→C) / `AnimusInventoryPayload`(S→C) 网包 + 跨 loader `IAnimusConfig`（Fabric JSON / NeoForge ModConfigSpec）。
- **22 个 ToolCall 已注册**（见下方[工具清单](#toolcall-清单llm-能力面)）：行动 13（含 `hunt` / `shoot` / `collect_items` / `craft` / `equip_item` + 熔炼三件套 `load_furnace` / `check_furnace` / `collect_furnace` + `place_block` + `use_item` + `eat_item`）+ 自我/库存感知 3 + 世界感知 4 + 规划 2。
- **FakePlayer 桥接**（`IFakePlayerBridge` Service，Fabric `FakePlayer.get` / NeoForge `FakePlayerFactory.getMinecraft`）：实体是 Mob 不是 Player，右键用物品/正确朝向放置走不了玩家路径，故借**单例共享假人**(固定 profile、复用不累积)代为执行 `gameMode.useItem(On)`。`use_item` 与 `place_block` 都基于它（[`FakePlayerUse`](common/src/main/java/com/dwinovo/animus/task/tasks/FakePlayerUse.java)：摆假人→用→把消耗/返还对账回实体背包）。**回收保证**：`withFakePlayer` 作用域 + finally 在用前用后都 reset(清背包/停用),common 不可能泄漏假人；任务串行,无并发争用。
- **自研地形改造寻路**（`common/.../pathing/`）：`move_to` 不再用 vanilla `PathNavigation`，改用 Baritone 风格 A* over 移动原语——会用背包里的圆石/泥土（`animus:scaffolds` tag）**搭桥、垫脚、搭柱上升**，挖穿障碍、下挖楼梯，按真实 tick 成本（走/挖/放）规划。**时间片化**：A* 跨 tick 续算（每 tick 限额节点数），多实体同时规划也不卡服务端。moveset：前后左右 traverse / ascend / descend·fall / 对角（仅走现成地面，不斜搭）/ pillar / dig-down；parkour 暂缓。**实时重规划**：`PathExecutor` 的卡死检测按"是否在向当前节点靠近"判定(不是原始逐 tick 位移——位移会被外力推搡/抖动骗过,导致"被推进坑里永久卡死"),且**一旦被推离节点明显变远**(掉坑/被怪/活塞推开)立即 `NEEDS_REPLAN`,`MoveToTaskGoal` 随即从当前位置重新 A*。**清障挖掘的可行性判据**全在 [`NavContext.costOfBreaking`](common/src/main/java/com/dwinovo/animus/pathing/calc/NavContext.java)（`move_to` 与 `mine_block` 寻路共用），返回 `COST_INF` 即不可挖、A\* 绕开：不可破坏（基岩）/ 危险源（熔岩火）/ 会引发液体流动 / **功能方块**（`BlockHelper.shouldAvoidBreaking`：任何 BlockEntity 或床——箱子/熔炉/漏斗等玩家放置物，不拆不 grief）/ **落沙**（`breakReleasesFallingBlock`：正上方是 `FallingBlock`，挖了会塌埋）/ **无效破坏**（方块需正确工具但当前主手不对——拒绝徒手磨石头那种又慢又零掉落的破坏，只挖当前工具能有效处理的；要穿石头/矿必须先 equip 对的镐，与 `mine_block` 同一原则）。**不自动换装**（理由见上方"核心原则"小节）。
- **`/animus debug` 调试层**：开关后所有自己的宠物头顶实时显示当前任务（如 `move_to 25,150,60` / `idle`），走 SynchedEntityData 同步 + vanilla name-tag 渲染。
- **战斗也已切自研寻路**：`hunt`（按类型批量击杀）经共享 `Navigator`（动态目标=移动实体，能搭桥/挖墙/跳过去够目标）+ `MeleeEngine`（到位后的挥砍/冷却/`doHurtTarget`）。**故意不 `setTarget`**,避免常驻 vanilla `MeleeAttackGoal` 抢 MoveControl。原 `attack_target`（精确单体）已删,统一到 `hunt(count=1)`。已无任务用 vanilla `PathNavigation` 做移动。
- 还没有：周期 Sensor/Perception 快照、复合任务链编排、parkour 跨缝、LLM 瞬时网络错误自动重试（下一阶段候选）。

下一步候选：① Perception 层（周期把附近玩家/方块/伤害事件喂给 LLM，目前靠 scan_* 工具按需拉取）；② 维度穿越基建（下界/末地 + 修跨维度 entityId 变化断 client agent loop）；③ 复合任务链（Skill 内部按序编排原子 task）；④ LLM EOF/SSL 等瞬时错误自动重试；⑤ **放置过的功能方块记忆**：`craft` 摆的工作台、`load_furnace` 摆的熔炉现在都留在世界并在结果里回报坐标；接入实体记忆层后让它记住这些位置、优先复用而不是每次新摆（位置已经从 tool 结果透出，记忆层落地即可消费）。

## 技术栈与版本

来源：[gradle.properties](gradle.properties)

| 项 | 值 |
|---|---|
| Minecraft | 26.1.2 |
| Java | 25 |
| Loader | Fabric (loom 1.15.5) + NeoForge (moddev 2.0.141) |
| Fabric API | 0.148.2+26.1.2 |
| NeoForge | 26.1.2.50-beta |
| Mixin | 0.8.7 + MixinExtras 0.5.4 |

注意 **Java 25** —— IDEA Gradle JVM 与 Project SDK 都要切到 25，否则刷新会失败（见 [README.md](README.md)）。

## 仓库结构（MultiLoader）

```
common/      # 共享逻辑，只能用 vanilla MC API（Brain、Entity、Sensor 等都在这里实现）
fabric/      # Fabric 入口 + 平台特定胶水代码
neoforge/    # NeoForge 入口 + 平台特定胶水代码
buildSrc/    # 共享的 multiloader-common / multiloader-loader gradle 约定
```

关键约定（来自 [README.md](README.md) 和模板）：

- **绝大多数代码写在 `common/`**。实体类、Brain 配置、Sensor、Memory、Behavior、ToolCall 注册表、LLM 客户端抽象——都属于 common。
- **加载器特有 API 必须通过 Service 抽象**。模式见 [Services.java](common/src/main/java/com/dwinovo/animus/platform/Services.java) + [IPlatformHelper.java](common/src/main/java/com/dwinovo/animus/platform/services/IPlatformHelper.java)：在 common 定义接口，在 `fabric/` 和 `neoforge/` 各放一个实现，通过 `META-INF/services/<完全限定名>` 注册（当前已注册：`com.dwinovo.animus.platform.services.IPlatformHelper`，Fabric 实现 `FabricPlatformHelper`，NeoForge 实现 `NeoForgePlatformHelper`）。
- **不要** 在 common 里 `import net.fabricmc.*` 或 `net.neoforged.*` —— 编译能过也是错的（运行期另一侧会炸）。
- 实体注册、网络包、事件订阅这类 API 在两个加载器上写法不同，**预期会通过 Service 抽象**：例如 `IEntityRegistrar`、`INetworkChannel` 等。模板暂未提供，需要时新增。

## 命名与配置（已落定）

| 项 | 值 | 来源 |
|---|---|---|
| `rootProject.name` | `minecraft-animus` | [settings.gradle](settings.gradle) |
| `mod_id` | `animus` | [gradle.properties](gradle.properties) |
| `mod_name` | `Animus` | [gradle.properties](gradle.properties) |
| `group` | `com.dwinovo.animus` | [gradle.properties](gradle.properties) |
| `mod_author` | `dwinovo` | [gradle.properties](gradle.properties) |
| 包根 | `com.dwinovo.animus` | `common` / `fabric` / `neoforge` 下 |
| Fabric 入口类 | `com.dwinovo.animus.AnimusMod` | [fabric/src/main/resources/fabric.mod.json](fabric/src/main/resources/fabric.mod.json) |
| NeoForge 入口类 | `com.dwinovo.animus.AnimusMod`（`@Mod(Constants.MOD_ID)`） | [neoforge/src/main/java/com/dwinovo/animus/AnimusMod.java](neoforge/src/main/java/com/dwinovo/animus/AnimusMod.java) |
| Mixin 配置 | `animus.mixins.json` / `animus.fabric.mixins.json` / `animus.neoforge.mixins.json` | 三个 `src/main/resources/` 下 |
| `license` | `PolyForm-Noncommercial-1.0.0 (code) + CC-BY-NC-4.0 (art)` | [gradle.properties](gradle.properties) |

模板的 `processResources` 占位符展开（见 [buildSrc/src/main/groovy/multiloader-common.gradle](buildSrc/src/main/groovy/multiloader-common.gradle)）会把 `${mod_id}` 等注入到 `fabric.mod.json` / `neoforge.mods.toml` / mixin json 里——所以那些文件里的 `${mod_id}.mixins.json` 不要手工展开，保留占位符。

> **任何在 `gradle.properties` 新增的字段**，必须同时加进 `multiloader-common.gradle` 的 `expandProps` map，否则 `processResources` 不会替换。文件顶部有注释强调过这点。

## 构建与运行

```powershell
./gradlew build                  # 全量构建（fabric + neoforge）
./gradlew :fabric:runClient      # 启动 Fabric 客户端
./gradlew :fabric:runServer      # Fabric 专用服务端
./gradlew :neoforge:runClient    # 启动 NeoForge 客户端
./gradlew :neoforge:runServer    # NeoForge 专用服务端
./gradlew :fabric:runDatagen     # Fabric 数据生成 → fabric/src/generated/resources/
./gradlew :neoforge:runData      # NeoForge 数据生成 → neoforge/src/generated/resources/
```

运行目录：`fabric/runs/{client,server,datagen}/`、`neoforge/runs/{client,server,data}/`。

Gradle daemon 在 [gradle.properties](gradle.properties) 里关掉了（`org.gradle.daemon=false`），第一次构建会慢，正常现象。

### 数据生成（i18n / tags / 等）工作流

`generated/` 目录被 `.gitignore` 排除——它是 [`ModLanguageData.java`](common/src/main/java/com/dwinovo/animus/data/ModLanguageData.java)（及未来的 tag / recipe provider）的产物。**source of truth 始终在 Java 代码里**，生成的 JSON 是中间产物。

工作流：
1. 改 `common/.../data/ModLanguageData.java`（加 key / 改翻译）；新 key 用 `ModLanguageData.Keys` 常量引用。
2. 跑 `./gradlew :fabric:runDatagen :neoforge:runData` —— 两侧的 lang JSON 会被重新写入 `*/src/generated/resources/`。
3. 运行 / 构建 / 发布 jar 时，generated 资源自动被 `sourceSets.main.resources` 拣进 jar。
4. **clone 后第一次构建**：要先跑一次上面那两条 datagen 命令，否则 jar 里没 lang 资源（游戏内会显示原始 key 字符串）。

## 渲染管线

基岩版（Bedrock）模型 + 动画 + 贴图渲染管线在 `common/src/main/java/com/dwinovo/animus/anim/` 下，分层如下：

| 子包 | 职责 |
|---|---|
| `format/` | Gson POJO（`BedrockGeoFile`）反序列化 `.geo.json` |
| `compile/` | 烘焙管线（`ModelBaker` / `AnimationBaker` / `BedrockResourceLoader` / `ConfigModelLoader` / `ConfigTextureLoader` / `MolangCompiler`） |
| `baked/` | 不可变烘焙数据（`BakedModel` / `BakedBone` / `BakedCube` / `BakedAnimation` / `BakedBoneChannel`）+ `BakeStamp` |
| `molang/` | Mini-Molang AST + 求值（2 vars + 5 funcs，详见 `MolangContext` 注释） |
| `runtime/` | 纯函数采样（`PoseSampler` / `PoseMixer`）+ per-entity 状态（`Animator`）+ `AnimContext`（占位，未来填 LLM 状态字段） |
| `controller/` | 多控制器状态机（OVERRIDE / ADDITIVE 模式、淡入淡出、`playOnce` 触发） |
| `render/` | EntityRenderer 基类（`AnimusEntityRenderer`）+ 顶点提交（`ModelRenderer`）+ 程序性拦截器（`BoneInterceptor` / `HeadLookInterceptor`）+ 可见性规则（`BoneVisibilityRule`）+ 渲染层 `render/layer/`（`RenderLayer` 接口，主网格之后按注册序运行；`BoneTransformWalker` 把 PoseStack 推到指定骨骼/locator；`HeldItemLayer` 把主手物品挂到手骨 locator——默认模型用 `RightHandLocator`，无该骨则优雅跳过。物品 `ItemStackRenderState` 在 extract 时经 `ItemModelResolver.updateForLiving` 解析、submit 时提交。**摆放 offset/scale 常量需游戏内目测调**） |

**资源加载有两条路径**：

1. **默认资产** → vanilla `ResourceManager`：
   - `assets/animus/models/entity/<id>.json`
   - `assets/animus/animations/<id>.json`
   - `assets/animus/textures/entities/<id>.png`
   - 命名空间 `animus`，Identifier 为 `animus:<id>` / `animus:<id>/<anim_name>`。
2. **玩家自定义模型** → 文件系统：**一个文件夹一个模型**（by-model 布局）：
   ```
   <gameDir>/config/animus/models/
     <id>/
       geometry.json          (必需，Bedrock .geo.json)
       animation.json         (可选，含多条动画)
       render_controller.json (可选)
       manifest.json          (可选，display_name / description / author)
       texture.png            (渲染必需)
   ```
   - 目录名即模型 id，例如 `<id>=my_skin` 注册为 `animus_user:my_skin`，动画为 `animus_user:my_skin/<anim_name>`。
   - 命名空间 `animus_user` 与默认 `animus` 互不覆盖。
   - 贴图通过 `DynamicTexture` + `TextureManager.register` 注册到 `animus_user:textures/entities/<id>.png`。
   - 模型 / 动画 / RC / manifest 走 `ConfigModelLoader.scan()`。
   - `/reload` 或 GUI 刷新按钮（`ConfigModelLoader.rescan()`）触发——后者只重扫 `animus_user`，比全量 reload 轻。

> **为什么 assets 端是 by-type 而 config 端是 by-model**：vanilla `ResourceManager` 按 path prefix 索引（`models/entity/*.json`），所以内置端遵循 vanilla 资源包惯例；玩家面向的目录则按"一个模型一个包"组织，方便拖拽、压缩、上传分享。

实体的模型选择走 `AnimusEntity.DATA_MODEL_KEY` (EntityDataAccessor&lt;String&gt;)，由蹲下右键 GUI 通过 `SetModelPayload` 修改；vanilla SynchedEntityData 自动 S→C 同步。模型切换时 `Animator.resetAll()` 清掉跨模型的 stale `BakedAnimation` 引用，防止 boneIdx 越界。

## 给 AI 代理的关键指引

### 在添加代码前先问自己

1. **这段代码用到了加载器特有 API 吗？**
   - 否 → 写在 `common/`。
   - 是 → 在 common 定义接口（`platform/services/`），在 `fabric/` + `neoforge/` 各加一个实现，并把全限定名写进对应的 `META-INF/services/` 文件。

2. **这是 LLM 决策层、Goal 执行层、还是 Tool 桥接层？** 不要把三层混在一个类里。实际分层：
   - `common/.../task/` — Task 抽象（`TaskRecord` / `TaskResult` / `TaskQueue`）+ `LlmTaskGoal` 基类（Goal 生命周期 ↔ 任务生命周期 1:1 桥接，**服务端**）
   - `common/.../task/tasks/` — 具体原子任务实现（`MoveToTaskRecord` + `MoveToTaskGoal`，**服务端**）
   - `common/.../agent/http/` — JDK `HttpClient` 包装（`HttpLlmTransport` + `LlmHttpException`）
   - `common/.../agent/provider/` — `LlmProvider` 接口 + `OpenAIProvider` / `DeepSeekProvider`（单点 wire-format 适配）
   - `common/.../agent/tool/` — Tool 抽象 + `ToolRegistry`（mod-global，两侧都用：客户端构造 tool 列表给 LLM；服务端用同一 registry 校验 ExecuteToolPayload）
   - `common/.../agent/llm/` — `AnimusLlmClient`（async 单例）+ `ConvoState`（per-entity 对话历史）
   - `common/.../client/agent/` — `ClientAgentLoop`（per-entity 编排循环，**客户端**）+ `ClientAgentLoopRegistry`（int entityId → loop 映射）
   - `common/.../network/payload/` — `ExecuteToolPayload`（C→S，含 schema 校验）+ `TaskResultPayload`（S→C，喂结果给客户端 loop）+ `SetModelPayload`（C→S，换皮 GUI 用）
   - `common/.../entity/` — 实体类本身 + 注册（已落地：`AnimusEntity` + `InitEntity`）

3. **要新建一个原子 Task 时**：保持单一职责。`extends LlmTaskGoal<T>` 只需实现 `onStart` / `onTick` / `buildResult` 三个方法。把可调参数尽量上提到对应的 `AnimusTool.parameterSchema()` 里，而不是写死在 Task 实现里。

4. **要新建一个 Tool 时**：实现 `AnimusTool` 接口。一个 Tool 可以发多种 TaskRecord（命名 → 类型映射通过 Goal 的 `recordClass` 字段做 `instanceof` 分发，无反射）。在 [`CommonClass.registerTools`](common/src/main/java/com/dwinovo/animus/CommonClass.java) 里注册。

### 核心原则：tool 结果即给模型的"游戏说明书"

**每个 tool_call 的返回 result，无论成功还是失败，都必须承担"指导 LLM 如何玩 Minecraft"的职责。** 模型只能从工具反馈里学会玩游戏——一个干巴巴的 `success:false` 等于什么都没教。result 的 message 要讲清**缺什么 / 为什么失败 / 下一步该干嘛**，把它当成模型的操作手册而不是状态码。

具体要求：

- **失败要可执行**：合成缺料 → 明确说"缺 3 个 oak_planks"（不是"无法合成"）；前置物品缺了 → 报具体缺口让 LLM 自己先合前置。`craft` 的 `missing 3x oak_planks` 就是范例。
- **挖掘要先判定"有效挖掘"，按当前主手判定，绝不自动换装备**：开挖前判断宠物**当前主手物品**能否真正 harvest 目标方块（产出掉落），不能 harvest 就**直接失败**并告知**最低工具类型+等级**（如 `iron_ore can't be harvested with bare hands — need at least a stone pickaxe`），绝不徒手挖石头白费时间还零掉落。**刻意不做"自动从背包换最优工具"**——虽然 Voyager 的 `collectBlock` 会自动 `equipForBlock`，但那样会**误导模型对工具等级的认知**（玩家装着木镐问"木镐能挖铁矿吗"，引擎偷偷换成石镐挖成功了，模型就以为木镐能挖铁矿）。必须让模型自己 `equip_item`，工具反馈才如实。判定链：`harvestRequirement`（返回 null=可挖，否则=指导串）/ [`MineBlockTaskGoal`](common/src/main/java/com/dwinovo/animus/task/tasks/MineBlockTaskGoal.java)（`onStart` 全部挖不动则前置 fast-fail，`tickScan` 混合 `block_ids` 时跳过挖不动的类型）。
- **合成缺料提示要对标 Voyager `failedCraftFeedback`**：在该产物的**所有配方里挑"缺得最少"那条**报缺口（[`CraftingEngine.findRecipe`](common/src/main/java/com/dwinovo/animus/task/tasks/CraftingEngine.java) 选 fewest-missing recipe）；且 tag 类材料**按类别命名**而非取首项——`#minecraft:planks` 报 `planks (any type)` 不是 `oak_planks`，无共同后缀的报 `any of: cobblestone, blackstone, …`，否则模型会以为只能用某一种（手里有樱花木板却质疑能不能用就是这个坑）。匹配本身用 `ing.test()` 是认 tag 的，樱花木板**确实能合**。
- **harvest 判定只作用于 LLM 的目标方块**，不要套到寻路清障的挖掘上——清路不在乎掉落，徒手挖开挡路的土/石是该允许的。
- **成功也要带可决策的数据**：如实回报实际产出/采集数量（可能少于请求），让 LLM 决定换地方还是收手，而不是盲目重试。
- 能在 tool **description** 里前置引导的（如"挖矿前先 equip 对的镐、查 get_self_status"），就别只靠失败后才教。

### 任务框架速查

- **端到端流程**（client-side LLM）：
  ```
  玩家右键 → PromptScreen → ClientAgentLoopRegistry.getOrCreate(entityId).submitPrompt
     → AnimusLlmClient.chat（异步 HTTPS via JDK HttpClient，玩家自己的 API key）
     → AssistantTurn（含 tool_calls，DeepSeek 的 reasoning_content 在 extras 里保留）
     → ExecuteToolPayload(C→S) per tool_call
        → 服务端 schema 校验 + owner + 距离 → entity.taskQueue.enqueue(TaskRecord)
        → MoveToTaskGoal.canUse() → start() → tick() → stop() → outbox
     → AnimusEntity.customServerAiStep drain outbox → TaskResultPayload(S→C) per record
     → 客户端 ClientAgentLoop.onToolResult → convo.addToolResult → 下一轮 LLM
  ```
- **任务生命周期**：`queue.enqueue(record)` → `Goal.canUse()` peek 匹配 → `Goal.start()` poll + 标记 RUNNING + 调 `onStart`（并 `entity.pathTally().reset()`）→ `Goal.tick()` 每 tick 检查 deadline + `onTick` → 子类设置终止 state → `Goal.canContinueToUse()` 返回 false → `Goal.stop()` 调 `buildResult` + **`enrichWithPathTally`**（把寻路沿途挖/放的方块统计追加进结果 message + `path_dug`/`path_placed` data）+ 写 outbox。
- **寻路副作用回报**：`PathExecutor` 走路时挖掉的障碍 / 放下的脚手架由 `entity.pathTally()`（[`PathTally`](common/src/main/java/com/dwinovo/animus/pathing/exec/PathTally.java)）按方块类型计数,`LlmTaskGoal.stop` 统一折进**每个**涉及寻路的任务结果——模型由此知道导航不是免费的(如 "reached target (en route: dug 4x dirt; placed 6x cobblestone as scaffold)")。贯彻"tool 结果教模型"原则。
- **跨线程关口唯一一处**：`HttpLlmTransport.post` 的 future 在 JDK HttpClient 的 daemon 线程完成；`ClientAgentLoop.bounceBackToMain` 通过 `Minecraft.getInstance().execute(...)` 投回 client tick 线程。所有 convo / 网包发送只在 client tick 线程发生（single-writer）。
- **超时计时**：用 `level.getGameTime()`（`/tick freeze` 和 `/tick rate` 都正确响应）。`TaskRecord.deadlineGameTime` 在 tool 翻译时算好（now + 默认 timeout）。
- **抢占处理**：不做。所有 LLM Goal 都注册在 priority 0，selector 不会让一个 LLM Goal 抢另一个。
- **死循环防护**：**无工具调用次数硬上限**（强 agent 连挂很多任务是正常的）——唯一的自主停止是循环检测 `MAX_REPEAT_TOOL_BATCH_COUNT = 2`（连续两次完全相同的 tool 批次就停），真跑飞了靠 owner 的 Stop 中断。`turnCount` 仅用于日志编号，新用户指令 / 纯文本回复时重置（一并清掉循环检测签名）。
- **LLM 路由关键类**：`HttpLlmTransport`（POST + Gson）→ `LlmProvider.buildRequestBody` / `parseResponseBody` → `AssistantTurn`（含 `content` + `toolCalls` + `extras` 透传 backend 专属字段）。Provider 选择由 `config.provider` 决定。
- **加新 Provider**（Anthropic native / Gemini 等）：实现 `LlmProvider` 接口；如果是 OpenAI 方言只改 `parseResponseBody` 和 `extractExtras`，参考 `DeepSeekProvider`（30 行）；如果是完全不同协议，从头实现 `buildXxxMessage` + `buildRequestBody` + `parseResponseBody`，参考 `OpenAIProvider`（200 行）。然后在 `AnimusLlmClient.pickProvider` 加 case。

### 调 LLM 时打开 DEBUG 日志

INFO 级别已经包含足够诊断信息（每次 LLM 调用的耗时 / token / finish reason / content 摘要 / 每次 tool dispatch / tool result）。**真出问题时**打开 DEBUG 级别能看到：

- HTTP 层每次请求的 `[lr-N]` id（贯穿整条链路）+ 请求字节数 + 响应字节数 + 耗时
- 每个 SSE chunk 的解析（chunk 计数）
- AgentLoop 每次 `tryStartTurn` 的跳过原因（aborted / awaiting / pending）
- 服务端 ExecuteToolPayload 的每条 ← 接收日志
- 实体 drain outbox 时 dispatch 给 owner 的统计

开 DEBUG 方式：在对应 loader 的 run dir 里编辑 `log4j2.xml`，加一行：
```xml
<Logger name="Animus" level="DEBUG"/>
```

具体位置：
- Fabric dev：`fabric/runs/client/config/log4j2.xml`（或玩家本地 MC `.minecraft/config/`）
- NeoForge dev：`neoforge/runs/client/config/log4j2.xml`
- 生产环境：MC `.minecraft/config/` 下，对应的 `log4j2.xml`

每条 log 都带前缀 `[animus-llm]` / `[animus-http]` / `[animus-agent#N]` / `[animus-net]` / `[animus-entity#N]` / `[animus-config]`，便于 grep。

### 引入外部依赖

**默认拒绝**。当前 mod 零第三方运行时依赖，jar 仅 ~260KB，启动快、distribution 友好。引入新库要严格论证：
- 这个功能能不能用 JDK 自带 / MC vanilla 提供的库做（HttpClient、Gson、Lucene 等）？
- 加这个库会让 jar 多多少？要不要 relocate（避免与其他 mod 冲突）？
- Fabric / NeoForge 两侧 classpath 是否一致？

**OpenAI SDK 的历史教训**：早期为了 type safety 引入了 `com.openai:openai-java` + transitive（kotlin-stdlib / okhttp / okio / jackson / swagger / jsonschema-generator）= 50MB jar。后来发现：
1. 我们用到的 SDK 表面只占 ~20%（一打 typed POJO）
2. SDK 把 schema 锁死成"标准 OpenAI"，DeepSeek 的 `reasoning_content` 等扩展字段全部被吞
3. 体积代价 250 倍

**最终方案**：用 JDK `java.net.http.HttpClient` + Gson 直发 JSON，约 200 行代码，零依赖。任何 OpenAI-compat backend（DeepSeek、Ollama、Together、Groq、Mistral、Moonshot 等）通过 `provider` 适配 30 行就能加，详见 `DeepSeekProvider`。

**真要嵌外部 AI 框架时**（LangChain4j、MCP Java SDK 等），优先考虑把它放到 mod 外的 sidecar HTTP service，mod 只指向 `baseUrl`。模组本体永远保持轻量。

### 不要做的事

- 不要在 common 里 `import net.fabricmc.*` 或 `net.neoforged.*`。
- 不要在 Behavior 里写决策树或 if-else 策略堆，决策属于 LLM。
- 不要 `git push --force`、`reset --hard`、删 `runs/` 之外的生成目录前不确认。
- 不要在没改 `gradle.properties` 之前就大范围重命名 —— 先确定最终的 `mod_id` / `group`。

## 许可证

**Source-available + 非商用**（不是严格意义上的 OSI 开源；对外宣传请用 "source-available" 而不是 "open source"）：

- **代码** → [LICENSE](LICENSE) = PolyForm Noncommercial 1.0.0
- **美术资产**（entity 模型、贴图、声音等）→ [LICENSE-ART](LICENSE-ART) = CC BY-NC 4.0
- **商用必须单独获得授权。**

`gradle.properties` 里的 `license` 字段是个自由字符串，会写到 `fabric.mod.json` 与 `neoforge.mods.toml`，在 mod 菜单里展示。当前值：`PolyForm-Noncommercial-1.0.0 (code) + CC-BY-NC-4.0 (art)`。

> **资产 / 代码归属约定**：
> - **代码**（`*.java` 等）默认归 [LICENSE](LICENSE)（PolyForm Noncommercial 1.0.0）管。
> - **默认美术 / 声音 / 模型文件**归 [LICENSE-ART](LICENSE-ART)（CC BY-NC 4.0）管，放在 `common/src/main/resources/assets/animus/{models/entity,animations,textures/entities}/`。
> - **玩家自定义资产**走运行期路径 `<gameDir>/config/animus/models/<id>/`（by-model 布局，详见上方"渲染管线"章节），由玩家自带 license，**不进 git**。
> - 未来引入第三方资源（CC0 贴图、字体、社区贡献的模型等）单独建一个 `THIRD_PARTY_NOTICES.md` 记录来源与许可证，避免与我们自有资产混淆。

> **区块链签名**：如果未来真要做美术资产的签名，那是「举证当时归属」的证据手段，不是法律授权——法律保护始终来自上述两份许可证。
