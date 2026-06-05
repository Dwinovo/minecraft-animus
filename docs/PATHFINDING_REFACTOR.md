# 寻路系统釜底抽薪重构方案

> 综合 mineflayer-pathfinder / Voyager、Baritone、MineColonies + 原版实体寻路四方调研，对照我们现有 `common/.../pathing` 包的病灶清单制定。目标：把"靠不断加检测打补丁"的现状，换成业内成熟系统共有的两个底层原语，从而**整类删除补丁**。

---

## 0. 一句话诊断：我们缺的不是补丁，是两个原语

我们现在所有"杂七杂八的卡死"——被推进坑、窄井反弹、立柱空跳、stale start、多跳做不了——**根因只有两条，且业内每个成熟系统都用同一套手段解决**：

1. **执行器没有"重定位 (re-localization)"。** 实体一旦被外力移动，我们没有任何机制去问"我现在是不是站在计划里的另一个节点上了？"，而是**直接把整条路扔掉重算**，靠 `STUCK_TICKS=40` / `DEVIATION_MARGIN_SQR` / `bestDistSqToNode` 一堆计时器去猜"什么时候该放弃"。Baritone 的 `PathExecutor.onTick` 每 tick 拿实体脚下位置去比对**每个 Movement 的 `validPositions()`**：往后扫(被推回)、往前扫(被推前/跳过头)，命中就**就地重新对齐、不重算**；只有真正持续偏离才在容差预算耗尽后重算。**被推进坑 → 脚下不属于任何计划节点 → `ticksAway` 累加 → 从真实位置重算**。这一条机制吃掉我们至少 5 个补丁。

2. **规划与执行是"冻结—算—走—冻结"的串行，没有"边走边算 + 把路重锚到实体当前脚下"。** 我们每次重算都 `setWantedPosition(当前位置, 0.0)` 把实体**冻住**，多 tick 时间切片搜索期间实体若被推动，算出的路 `start` 就过期了 → 触发 `ROOT GUARD` 又重算。mineflayer 用 `pathFromPlayer` 每次把路**重锚到玩家实际脚下**，Baritone 用 `current`+`next` 双执行器**后台预算下一段并 splice 拼接**，两者**都从不冻结**。

> 把这两个原语补上，下面《§7 删除清单》里的全部补丁都失去存在理由。这才是"根本原因"。

---

## 1. 北极星定位：我们更像 MineColonies，不是 Baritone

关键区别(来自实体寻路调研)：**Baritone 是玩家 bot，直接按键盘 (`MOVE_FORWARD/JUMP/SPRINT`)；我们是 Mob，骑在 `MoveControl` 上。** 原版与 MineColonies 的实体寻路每 tick 的交接都只有一句：

```
挑当前路点 → moveControl.setWantedPosition(x, groundY, z, speed) → MoveControl.tick() 自己转向/给推力/跳
```

所以**架构主干抄 MineColonies**(实体 + 改地形 + 自定义 MoveControl)，**执行器的健壮性抄 Baritone**(`validPositions` 重定位 + 边走边算 + splice),**多跳抄 Baritone 的 `MovementParkour`**(原子边 + 动量执行),**世界变更失效与进度看门狗抄 mineflayer**。

**由此确立的中心设计决策**(直接解决"MoveControl 和我们打架"这个立柱反弹根因)：

- **行走类移动 (TRAVERSE/ASCEND/DESCEND/DIAGONAL)**：交给 MoveControl 转向，`setWantedPosition(dest)`，我们**不插手**。
- **垂直/动量类移动 (PILLAR/PARKOUR/DIG_DOWN)**：**绝不把 wantedY 设到实体上方**(那会让原版 MoveControl 自动起跳，和我们的显式跳互相抵消——正是立柱空跳 40t 的根因)；改为"水平方向按**实体当前 Y** 居中保持 + `JumpControl` 显式跳"的**输入驱动微控制器**。
- 每个 Movement 子类自带一个标志：`steeredByMoveControl()` vs `inputDriven()`，执行器据此选驱动方式。
- 备选：若步上(step-up)在某些方块上不可靠，像 MineColonies 一样换上自定义 `MoveControl` 子类(它正是为"踏入空碰撞箱方块"才替换的)。

---

## 2. 新分层架构

```
┌─ Goal 层  MoveToTaskGoal / MineBlockTaskGoal
│   • 边走边算：current + next 双执行器，后台算下一段、splice 拼接，从不冻结
│   • 事件驱动重算：{偏离预算耗尽, 目标移动, 路径附近方块变更, 动作失败}
│   • 结果重锚：每次搜索结果把路 start 锚到实体真实脚下
│
├─ 执行层  PathExecutor（重写核心）
│   • 每 tick 先「重定位」：脚下 ∈ 当前move.validPositions()？否则后扫/前扫重对齐
│   • 再「驱动」：steered → setWantedPosition；inputDriven → JumpControl 微控
│   • 动作时序：到位 → break/place → 确认世界匹配 → 才 advance（绝不走进未清理方块）
│   • 实时代价校验：当前move代价变 INF（世界变了）→ 取消重算
│   • 兜底超时：ticksOnCurrent > 本move代价估计 + buffer（按move缩放，非平摊40t）
│
├─ 图/移动层  Movement + Moves + MovementHelper(=强化版 BlockHelper)
│   • 每个 Movement 暴露 validPositions()  ← 重定位的使能原语
│   • 新增 MovementParkour：2~4 格原子跳跃边（多跳）
│   • 动态落点用 MutableMoveResult（DESCEND/FALL/PARKOUR 不再枚举常量）
│   • 破坏/放置全部经 MovementHelper 在快照上判定 + 记忆化
│
├─ 搜索层  AStarSearch（升级）
│   • 二叉堆 + decrease-key（heapPosition 缓存在节点里）← 替换 PriorityQueue
│   • 八方向(octile)启发 × 不可采纳系数(≈3.5) + 多系数 best 跟踪 → 提速 + 部分路兜底
│   • fastutil Long2ObjectOpenHashMap，按打包 long 坐标键（去 BlockPos 装箱）
│   • 双超时（primary 一旦有可用路就停 / failure 硬上限）
│
└─ 快照层  NavSnapshot（新增，可选/性能档）
    • 规划开始时缓存 start→goal 包围盒内方块 + 记忆化 passability
    • 让时间切片搜索内部一致；为后台线程化留口（当前仍可单线程直读）
```

---

## 3. 逐层设计细节

### 3.1 搜索层 `AStarSearch`（病灶：`PriorityQueue` 重复节点 + 可采纳启发太慢，9 秒规划空档）

| 现状 | 改为 | 出处 |
|---|---|---|
| `PriorityQueue<Node>`，靠 `via!=null && tentativeG>=g` 跳过、重复 add | **自研二叉堆 `BinaryHeapOpenSet`**，节点内缓存 `heapPosition`，relax 时 O(log n) `decrease-key`，无重复入堆 | Baritone `BinaryHeapOpenSet` —— 全系统第一性能/正确性决策 |
| `HashMap<BlockPos,Node>` | `Long2ObjectOpenHashMap`，键=打包 long(x12/y8/z12 或完整 long) | Baritone / MineColonies |
| 欧氏 × WALK（可采纳=最优但慢） | **octile**：`(min(dx,dz)*√2 + |dx-dz|)*WALK + 上行项`，再 `× costHeuristic≈3.5`（**故意不可采纳**换 5~50× 节点削减） | Baritone `GoalXZ.calculate` + `COEFFICIENTS` |
| 单一 best 兜底 | **多系数 best**：对 `{1.5,2,2.5,3,4,5,10}` 各记一份 best，超时返回"仍有 ≥5 格实际进展的最深"部分路 | Baritone `bestSoFar[]` / `MIN_DIST_PATH=5` |
| 每 tick 固定 node 预算 | 保留 node 切片；加**双超时**：一旦有可用路就早停，否则磨到硬上限；时间检查每 1024 节点一次 | Baritone `primaryTimeoutMS/failureTimeoutMS` + `numNodes & 0x3FF` |

> 这一层主要修**性能**(9 秒空档)和**重复节点正确性**，与健壮性正交，可独立上线。

### 3.2 移动层：补 `validPositions()` 与 `MovementParkour`

**(a) `Movement.validPositions()` —— 重定位的使能原语（纯新增，零行为变更）**

每个 Movement 声明"执行途中实体脚可以合法占据哪些格"：

- TRAVERSE：`{src, dest}`
- ASCEND：`{src, dest, src.above}`（含起跳腾空格）
- DESCEND/FALL：`{src} ∪ 落点列从 src 到 dest 的整列`（Y 合法地发散，用平面 XZ 判偏离——mineflayer/Baritone 对 fall 的特判）
- DIAGONAL：`{src, dest, 两个正交角格}`
- PILLAR：`{src, dest}`（同一 XZ 列，Y 递增）

没有这个集合，执行器无法判断"我被推后/推前到了哪个节点"，重定位无从谈起。**先做这一步，它解锁后面一切。**

**(b) `MovementParkour`（新增）—— 直接解决"多跳问题依旧存在"**

- **表示**：2~4 格间隙跳跃是**单条原子边**，`dest` 直接落在远端落点，绝不拆成逐格。
- **规划期代价**(静态 `calculateCost`)：先确认确实站在间隙边缘(相邻格可通过且其下无落脚)，再 `for d in 2..maxJump(冲刺4/不冲刺3)` 扫：检查落点列头/脚净空 + 间隙上方整列是 `fullyPassable` 清空气；落点分三类(平跳/上跳/跳落)，代价分别 `costFromJumpDistance(d)`(2→2×WALK,3→3×WALK,4→4×SPRINT)。无天然落点时可"parkour-place"在远端放一块再跳上。
- **执行期动量**(输入驱动微控制器，非 MoveControl)：`d≥4` 或上跳时**给冲刺等效推力**；锁朝向到落点中心建立动量；**按距离定时起跳**(远跳先建动量再跳，d=3 反而要等位移<0.7 再跳否则跳过头)；**腾空阶段标记不可取消**(`isSafeToCancel` 仅在准备相位为真——已弹道不可中途变卦)。
- 对我们这种骑 MoveControl 的实体：动量推力用 `setDeltaMovement` 沿朝向施加 + `JumpControl.jump()`，是少数需要短暂直接给速度的移动；其 `validPositions={src,dest}`，腾空中途偏离不算 stuck(不可取消相位)。

**(c) `MovementHelper`（把现有 `BlockHelper` 升级为唯一真相源）**

我们已有 `BlockHelper`(很好)。强化为：所有 `canWalkOn/canWalkThrough/fullyPassable/avoidBreaking/avoidWalkingInto/getMiningDurationTicks` 都走它、都读快照、都**记忆化**。补两点：
- `getMiningDurationTicks(includeFalling=true)`：**把上方下落方块级联的挖掘代价也算进去**(我们现在只是 `breakReleasesFallingBlock` 拒绝，应改为"既能拒绝、也能计价绕行")。
- 三态 `canWalkOn`(YES/NO/MAYBE)，水/地毯/雪等 MAYBE 再做位置级判定。

### 3.3 执行层 `PathExecutor`：围绕"先重定位，再驱动"重写（最大的一块）

`onTick` 新顺序(对照 Baritone `PathExecutor.onTick`)：

```
1) 重定位
   feet = entity.blockPosition()
   if feet ∈ current.validPositions(): 命中，继续
   else:
       后扫 i∈[0,idx)：feet ∈ moves[i].validPositions() → 被推回 → idx=i, 重置中间态, 不重算
       前扫 i∈(idx, idx+LOOKAHEAD]：feet ∈ moves[i].validPositions() → 推前/跳过头 → idx=i, 不重算
       都不命中 → 偏离：ticksAway++；
           dist(feet, 路径折线) > HARD_CAP → 立即 NEEDS_REPLAN
           ticksAway > AWAY_BUDGET(~200) → NEEDS_REPLAN
2) 实时代价校验：current 及前瞻 N 步代价在当前世界重算，若由有限变 INF（落脚块没了/计划清空块复生）→ NEEDS_REPLAN
3) 驱动
   steeredByMoveControl(current)：setWantedPosition(dest 中心, speed)，看向 dest
   inputDriven(current)：水平按「当前 Y」居中保持 + JumpControl 在正确 tick 显式跳
4) 动作时序（改地形的 move）
   到位(脚邻接) → 执行 break/place（遵守不可破坏白名单/falling 守卫）→ 确认世界已匹配 → advance()
   —— 期间被击退只会触发重定位，不会破坏计划
5) 兜底超时：ticksOnCurrent > current 代价估计 + buffer → NEEDS_REPLAN（按 move 缩放，极少触发）
6) advance() 时一次性消费多个已完成微移动（递归 onTick）
```

**这一步直接删除**：`STUCK_TICKS`、`bestDistSqToNode`、`moveStartDistSqToNode`、`DEVIATION_MARGIN_SQR`、ASCEND 腾空补落脚块、pillar `bestPillarY`/`pillarBaseY` 重捕获/"免 onGround 到顶"等全部补丁(详见 §7)。被推进坑：脚下不属于任何 `validPositions` → `ticksAway` 涨 → 从真实位置重算；被推到另一节点：后扫/前扫静默对齐。

### 3.4 Goal 层 `MoveToTaskGoal`：边走边算 + 重锚（病灶：冻结规划 + ROOT GUARD）

| 现状 | 改为 | 出处 |
|---|---|---|
| 规划时 `setWantedPosition(当前,0.0)` **冻结实体** | **从不冻结**：继续走 current/部分路，同时算下一段 | mineflayer/Baritone 都不冻结 |
| 串行 算→走→算 | **`current`+`next` 双执行器**：current 快走完(或 partial)时，从 current 的 `end`(可预测的未来位置)启动后台搜索，完成即 `splice` 拼到 current 尾，玩家不停步 | Baritone `PathingBehavior.findPathInNewThread` + `trySplice` |
| `ROOT GUARD`：搜索期间实体离开 start 就整条作废重算 | **重锚**：结果回来时把路 start 对齐实体真实脚下；预测式 next 的 start=预测 end(天然有效)；位移式重算 start=真实脚下。执行层的重定位本就容忍小偏移，ROOT GUARD 失去意义 → 删除 | mineflayer `pathFromPlayer` / Baritone `expectedSegmentStart` 校验 |
| `blockUpdate` 无感知 | **定向失效**：仅当变更方块"靠近当前路径"才作废(`isPositionNearPath`)，避免全量重算 | mineflayer `blockUpdate` 监听 |
| `MAX_REPLANS=30` 作主闸 | 主闸回归基类 deadline；重算次数仅作 runaway 兜底 | —— |

---

## 4. 分阶段实施（每阶段独立编译、独立可测，非大爆炸）

| 阶段 | 内容 | 解决 | 风险 |
|---|---|---|---|
| **P0** | `Movement.validPositions()` + 各 Kind 实现（纯新增） | 解锁重定位 | 极低（无行为变更） |
| **P1** | 重写 `PathExecutor.onTick` 围绕重定位；删 stuck 补丁群 | **被推进坑/窄井反弹/立柱空跳** | 中（核心执行器） |
| **P2** | A* 二叉堆+decrease-key + octile×不可采纳 + fastutil + 双超时 | **9 秒规划空档 + 重复节点** | 中（搜索正确性需回归） |
| **P3** | `MoveToTaskGoal` 边走边算(current+next+splice)+重锚；删冻结/ROOT GUARD | **stale start / 每次重算的顿挫** | 中高（并发/拼接边界） |
| **P4** | `MovementParkour` 原子跳跃边 + 动量执行器 | **多跳问题** | 中（动量调参） |
| **P5** | `NavSnapshot` 快照+记忆化（性能档，可选） | 进一步提速 / 线程化预备 | 低（纯加速） |

> 建议落地顺序就是 P0→P1 优先(健壮性，肉眼可见地修掉你一直撞的卡死)，再 P2(性能)，再 P3(顺滑)，P4 单独立项(多跳)，P5 视 P2 后性能再定。

---

## 5. 对照"业内最棒"的取舍总账

**抄 Baritone(必抄)**：① 二叉堆 decrease-key；② Movement=预算代价+break/place 清单(我们已有，保持)；③ "什么是move"与"怎么执行move"分离；④ `validPositions` 重定位执行器；⑤ 不可采纳启发提速 + 多系数 best；⑥ 边走边算 + splice；⑦ 异步结果对齐真实脚下；⑧ parkour 原子边 + 不可取消腾空相位。

**抄 MineColonies(实体专属，必抄)**：⑨ 每 tick 一路点交给 MoveControl 的交接；⑩ 节点贴地(getGroundHeight)，与 MoveControl 对"实体在哪"达成一致；⑪ break/place 建模为带大代价乘子的图边、ladder 作"仅在可放置处存在的竖直边"；⑫ 到位→动作→确认→才 advance 的动作时序；⑬ "卡死=路径索引无进展"而非"位置没动"；⑭ 破坏性脱困是**最后**的分级兜底，不是常规步(我们的 A* 既已把 break/place 当正规边，常规根本不需要破坏式脱困)。

**抄 mineflayer(必抄)**：⑮ `pathFromPlayer` 重锚；⑯ 定向 `blockUpdate` 失效；⑰ 进度看门狗(lastNodeTime/ticksAway)作位移兜底；⑱ break 安全谓词(不挖下落块下方、不挖临液体、落点拒岩浆火、水可视作无限落差)。

**抄 Voyager(LLM 屏蔽)**：⑲ 单个不可达目标"跳过而非整任务失败"(`ignoreNoPath`)；⑳ 失败转成自然语言喂回模型("附近没有 X，请先探索")+ 失败计数到阈值才抛异常——正合我们"toolcall result 指导模型"的原则。

**明确不抄**：Baritone 的"直接按键盘"(我们是 Mob，骑 MoveControl)；Altoclef 的 `UnstuckChain` 外挂看门狗(那是 task 选择层 livelock 的补丁，正是我们要在规划层设计掉的反面教材)；原版双计时器 `doStuckDetection`(只会 `stop()` 盲重算，我们要离开的现状)。

---

## 6. 实体专属的三个"坑"提醒（MoveColonies 血泪）

1. **不要和 MoveControl 抢**：行走移动只喂 `setWantedPosition(dest, speed)`，让它转向；改地形靠"喂哪个路点 + 何时做动作"，不要自己 `setDeltaMovement`(parkour 动量是唯一例外)。
2. **节点必须贴地**：每个节点 Y = MoveControl 会真正停留的地面高度(含步上/绕角的中间空气节点)，否则计划和 MoveControl 对"实体在哪"永远打架。
3. **MoveControl 不够用就换**：MineColonies 专门换 `MovementHandler` 才能踏入空碰撞箱方块。若我们步上/精确放置不稳，预留"自定义 MoveControl 子类"这张牌。

---

## 7. 删除清单（重构后这些补丁全部失去存在理由）

| 文件:行 | 补丁 | 被什么取代 |
|---|---|---|
| PathExecutor `STUCK_TICKS=40` / `PROGRESS_EPS` | 无进展计时器 | §3.3 重定位 + `ticksAway` 预算 |
| PathExecutor `bestDistSqToNode` / `moveStartDistSqToNode` | 位移噪声过滤 | `validPositions` 命中判定 |
| PathExecutor `DEVIATION_MARGIN_SQR=4.0` | 被推离检测 | 后扫/前扫重定位 |
| PathExecutor ASCEND 腾空补落脚块(255–276) | 计划/执行错配 | 正确 ASCEND 把 step 块作 `toPlace` + 动作时序 |
| PathExecutor pillar `bestPillarY`/`pillarBaseY` 重捕获/免onGround到顶 | 立柱空跳/反弹 | 输入驱动立柱微控 + `validPositions` |
| PathExecutor 散落的"unmineable/not placeable"逐相位重算 | 局部世界变更 | §3.3 step2 统一实时代价校验 |
| MoveToTaskGoal `setWantedPosition(当前,0.0)` 冻结 | 规划期漂移 | §3.4 边走边算 |
| MoveToTaskGoal `ROOT GUARD`(188) start 校验 | stale start | §3.4 重锚 + 执行层容忍小偏移 |
| MoveToTaskGoal `MAX_REPLANS=30` 作主闸 | runaway | 基类 deadline 主闸 |

---

## 8. 开放问题（实施前需拍板）

1. **后台线程 vs 单线程切片**：Baritone/MineColonies 用独立线程(需快照 P5)；mineflayer 单线程切片直读世界。我们当前单线程切片。**建议**：先保持单线程切片(配 P2 提速通常够)，P5 快照为线程化留口，非必须。
2. **parkour 动量实现**：Mob 没有玩家的冲刺物理，需用 `setDeltaMovement` 近似 + 调参。**建议**：P4 单独立项，先在平地 2~3 格验证手感再放开 4 格。
3. **自定义 MoveControl**：是否一步到位换 MineColonies 式 `MovementHandler`？**建议**：P1 先用现有 MoveControl + "当前 Y 居中"策略验证；若步上仍不稳再上。

---

*本文档为方案评审稿。落地从 P0+P1 起步即可肉眼验证"被推进坑不再卡死"。*
