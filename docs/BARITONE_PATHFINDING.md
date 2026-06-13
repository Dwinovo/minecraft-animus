# Baritone 寻路对照基准(重写 SSOT)

本文件是我们寻路重写的**标准答案**:逐字扒自 Baritone(`cabaletta/baritone` master)的精确常量、参数、cost 公式、A* 逻辑、执行器与分段规划。重写时逐项对齐;卡死 debug 时拿 Baritone 行为对照。

> 单位:**所有 cost 以 tick 计**(20 tick/s)。基础移动 cost = `20 / 速度(m/s)`。
> Baritone 源码是 1.12/1.19 映射,数值/逻辑与版本无关;我们用 26.x 映射照搬逻辑。

---

## 1. ActionCosts(cost 常量,精确值)

| 常量 | 表达式 | 数值 | 推导 |
|---|---|---|---|
| `WALK_ONE_BLOCK_COST` | `20 / 4.317` | **4.633** | 走速 4.317 m/s |
| `WALK_ONE_IN_WATER_COST` | `20 / 2.2` | **9.091** | 水中 2.2 m/s |
| `WALK_ONE_OVER_SOUL_SAND_COST` | `WALK × 2` | **9.266** | 灵魂沙半速 |
| `LADDER_UP_ONE_COST` | `20 / 2.35` | **8.511** | 爬梯 2.35 |
| `LADDER_DOWN_ONE_COST` | `20 / 3.0` | **6.667** | 下梯 3.0 |
| `SNEAK_ONE_BLOCK_COST` | `20 / 1.3` | **15.385** | 潜行 1.3 |
| `SPRINT_ONE_BLOCK_COST` | `20 / 5.612` | **3.564** | 疾跑 5.612 |
| `SPRINT_MULTIPLIER` | `SPRINT / WALK` | **0.769** | 疾跑折扣 |
| `WALK_OFF_BLOCK_COST` | `WALK × 0.8` | **3.706** | 走下边缘 |
| `CENTER_AFTER_FALL_COST` | `WALK − WALK_OFF` | **0.927** | 落地后回中心 |
| `FALL_1_25_BLOCKS_COST` | `distanceToTicks(1.25)` | (实测,见下) | 跳跃顶点高 |
| `FALL_0_25_BLOCKS_COST` | `distanceToTicks(0.25)` | (实测) | |
| `JUMP_ONE_BLOCK_COST` | `FALL_1_25 − FALL_0_25` | **≈3.16** | 净跳 1 格 |
| `FALL_N_BLOCKS_COST[i]` | `distanceToTicks(i)`,i=0..256 | 表 | 见下公式 |
| `COST_INF` | — | **1_000_000** | 不可通行哨兵 |

**下落物理(必须照抄)**:
```java
static double velocity(int ticks) { return (Math.pow(0.98, ticks) - 1) * -3.92; } // 重力3.92 阻力0.98
static double distanceToTicks(double distance) {
    if (distance == 0) return 0;
    double tmp = distance; int t = 0;
    while (true) { double fall = velocity(t);
        if (tmp <= fall) return t + tmp / fall;
        tmp -= fall; t++; }
}
// FALL_N_BLOCKS_COST[i] = distanceToTicks(i)
```
代表值(jshell 实测):`[1]≈5.615 [2]≈7.788 [3]≈9.469`,单调递增。**无硬编码覆盖**,全部来自公式。(调研 agent 早先给的 2.546/1.896 是未跑代码的估算,错误;落 1 格 ≈5 tick 才符合物理 `0.5·g·t²≈1`。)

---

## 2. Settings(寻路相关参数,默认值)

| 参数 | 默认 | 含义 |
|---|---|---|
| `costHeuristic` | **3.563** | A* 启发式系数(≈SPRINT,>1 故不可采纳→快但次优) |
| `movementTimeoutTicks` | **100** | 单 move 超出估算 cost 这么多 tick 就取消 |
| `primaryTimeoutMS` | **500** | 找到首条可用路后再搜这么久就停 |
| `failureTimeoutMS` | **2000** | 没找到路时的硬上限 |
| `planAheadPrimaryTimeoutMS` | 4000 | 边走边规划下一段的 primary |
| `planAheadFailureTimeoutMS` | 5000 | 同上 failure |
| `planningTickLookahead` | **150** | 当前段剩余 <150 tick(7.5s)就开搜下一段 |
| `pathCutoffFactor` | **0.9** | 每条路裁掉尾部 10%(避免提交陈旧尾巴) |
| `pathCutoffMinimumLength` | **30** | 短于此的路不裁 |
| `costVerificationLookahead` | **5** | 向前重验这么多 move 的 cost |
| `minimumImprovementRepropagation` | true | 跳过 <0.01 tick 的改进重传播 |
| `maxFallHeightNoWater` | **3** | 无水时最大下落 |
| `maxFallHeightBucket` | **20** | 有水桶时最大下落 |
| `blockBreakAdditionalPenalty` | **2** | 每次破坏的平局加价(偏好不挖) |
| `blockPlacementPenalty` | **20** | 每次放置加价(省方块,高=不爱放) |
| `jumpPenalty` | **2** | 每次按跳(跳/上升/塔/跑酷)加价(模拟饥饿) |
| `walkOnWaterOnePenalty` | **3** | 每格水上行走加价(冰霜行者,掉饥饿快) |
| `backtrackCostFavoringCoefficient` | **0.5** | 偏好已探索回溯节点的乘子(1.0=关) |
| `maxCostIncrease` | **10** | 当前 move live cost 升这么多就重算 |
| `allowBreak` / `allowPlace` / `allowSprint` | true | |
| `allowParkour` | **false** | 跑酷(不稳/易冲过) |
| `allowParkourPlace` / `allowParkourAscend` | false / true | |
| `allowDiagonalDescend` / `allowDiagonalAscend` | **false** / false | |
| `sprintAscends` | true | 几何允许时上升疾跑跳 |
| `allowDownward` | true | 挖脚下方块下降 |
| `assumeWalkOnWater` / `assumeStep` | false / false | |
| `considerPotionEffects` | true | 急迫/挖掘疲劳计入破坏 cost |
| `avoidance` | false | 怪/刷怪笼规避总开关(有性能开销) |
| `mobAvoidanceCoefficient` / `Radius` | 1.5 / 8 | |
| `mobSpawnerAvoidanceCoefficient` / `Radius` | 2.0 / 16 | |

---

## 3. Cost 计算逻辑(CalculationContext + MovementHelper)

**放置 cost**(任意垫脚块,扔货块):
```java
costOfPlacingAt(x,y,z): 无 throwaway → COST_INF;受保护 → COST_INF;越界 → COST_INF;否则 = blockPlacementPenalty(20)
placeBucketCost() = blockPlacementPenalty(20)
hasThrowaway = allowPlace && inventory.hasGenericThrowaway()  // allowPlace 折在这
```

**破坏耗时**(核心公式,`getMiningDurationTicks`):
```
ticks = (1 / strVsBlock) + blockBreakAdditionalPenalty(2)   // ×mult(正常=1,否则已返回INF)
        + 递归上方 BlockFalling(沙/砾)的耗时(includeFalling)
不可通行才计费,液体→COST_INF,破坏禁用/受保护/avoidBreaking→COST_INF
```
其中:
```
strVsBlock = destroySpeed                                   // 工具对方块基础速度
           (+ eff² + 1  若 efficiency>0 且 speed>1)         // 效率附魔
           / hardness
           / (30 若可正确收获或无需工具,否则 100)            // 错工具 ×3.33 慢
           × potion 因子(急迫/挖掘疲劳)
hardness<0(基岩)→ strVsBlock=-1 → COST_INF
```
> **关键差异**:Baritone **不含**"水下 ÷5""离地 ÷5"惩罚 —— 它按最佳情况算(假设在地面、出水)。若要 vanilla 精确耗时,这两个 ÷5 要自己加。**对齐 Baritone = 不加**。

**通行/危险判定**(MovementHelper):
- `avoidWalkingInto`:液体/岩浆块/仙人掌/火/末地门/蜘蛛网 —— 绝不走入。
- `avoidBreaking`(返回 true=禁破坏→COST_INF):冰、虫蚀块、相邻有液体(上方或四向,防破坏放水/岩浆)、`blocksToDisallowBreaking`、越界。
- `canPlaceAgainst`:目标面须实心方块/玻璃且在边界内。

---

## 4. Movement 速查(8 种,cost 公式 + 执行要点)

> 通用模式:`cost = 距离基础 × 惩罚 + 破坏 + 放置 + jumpPenalty`。
> 疾跑折扣(×0.769)**仅**在「纯走、无需破坏、非水、非潜行」时套用。
> 灵魂沙/岩浆/水对**边走 move**按**半惩罚** `(SPECIAL − WALK)/2` 每接触地块;岩浆强制潜行。

### Traverse(平移 1 格)
- **走分支**(目标地面已可走):base=WALK;水→waterWalkSpeed;灵魂沙/岩浆半惩罚;若头脚都不需挖且非水非潜行 → `×SPRINT_MULTIPLIER`。否则 `WC + hardness1 + hardness2`(梯/藤起步 hardness×5)。
- **架桥分支**(需放地板):侧面放 `WC + placeCost + hardness`;只能潜行后退放则 `WC×(SNEAK/WALK) + placeCost + hardness`;梯/藤上架桥、水上走架桥 → COST_INF。
- **执行**:`moveTowards(dest)` + 清头脚障碍(边走边挖 `pitchToBreak=26`,dist<0.83);低于 dest → JUMP;架桥时面向 `src.below()` 右键放、太近 MOVE_BACK;SUCCESS = feet==dest。

### Ascend(上 1 + 过 1)
- cost:目标块不可走→算 placeCost(找 5 邻面,无→INF);上方落沙→INF;梯/藤脚下→INF;`jumpingFromBottomSlab && !toSlab`→INF。
- walk = `max(JUMP_ONE_BLOCK_COST, WALK_ONE_BLOCK_COST)` + `jumpPenalty`(slab/灵魂沙/岩浆特例);+ placeCost + 三处破坏耗时。
- **执行**:`feet.y < src.y` → UNREACHABLE;需放则放垫脚(SNEAK+CLICK_RIGHT,>10 tick 没放成 MOVE_BACK);**跳时机**:已在 slab 不跳;`assumeStep` 或已上去则不跳;`|侧向速度|>0.1` 等到对齐;`headBonkClear` → JUMP。SUCCESS=feet==dest。

### Descend(下 1 + 过 1,兼 Fall 入口)
- cost:三处破坏(y-1/y/y+1);若 y-2 不可走 → 交给 `dynamicFallCost`;`walk = WALK_OFF_BLOCK_COST(灵魂沙×倍) + max(FALL_N_BLOCKS_COST[1], CENTER_AFTER_FALL_COST)`。
- `dynamicFallCost`:`tentative = WALK_OFF + FALL_N_BLOCKS_COST[h] + 前向破坏 + 已累`;落水/落岩浆/梯藤(+LADDER_DOWN)/实地分支;干地 `h ≤ maxFallHeightNoWater+1`;有水桶 `h ≤ maxFallHeightBucket+1`(+placeBucketCost,返回 true=要放桶)。
- **执行**:无显式 JUMP(重力下降);`safeMode` 防冲过(瞄 0.17/0.83 混点);前 20 tick 瞄 `fakeDest`(延伸一格保动量)再瞄 dest。SUCCESS = 在 dest 且 `y差<0.5`。

### Diagonal(斜角,×SQRT_2)
- cost:头顶须通行;分 ascend(需 `allowDiagonalAscend`)/平/descend(需 `allowDiagonalDescend`);base=WALK + 灵魂沙/岩浆半惩罚(两地块)+ 水×SQRT_2;两斜切角块若都需挖 → INF;能挖一个则 `×(SQRT_2−0.001)`(贴角不能疾跑),否则 `×SPRINT_MULTIPLIER`;`res.cost = multiplier × SQRT_2`;ascend `+JUMP_ONE_BLOCK_COST`,descend `+max(FALL[1],CENTER_AFTER_FALL)`。
- **执行**:`moveTowards(dest)`;`sprint()`(非液体且 4 破坏位都通行)→ SPRINT;升 + 撞墙 → JUMP。SUCCESS=feet==dest。

### Parkour(冲刺跳 2–4 格)
- `costFromJumpDistance`:`2→WALK×2  3→WALK×3  4→SPRINT×4`;`+jumpPenalty`。ascend 落点:`i×SPRINT + jumpPenalty`。
- `maxJump`:岩浆/灵魂沙→2;`canSprint`→4;否则 3。从近到远扫 `i=2..maxJump`,头脚通行;ascend 需 `allowParkourAscend && canSprint`;parkour-place 需 `allowParkourPlace`。
- **执行**:`feet.y<src.y`→UNREACHABLE;`dist≥4||ascend`→SPRINT;离开起步块后 **dist==3 助跑**:`distFromStart<0.7` 先不跳,够了 JUMP;落后则取消 sprint 走回 src 重试。
- **默认 `allowParkour=false`**(不稳)。

### Pillar(竖直上 1,塔/梯)
- cost:水柱特例 `LADDER_UP_ONE_COST`;非梯 placeCost(脚下空 +0.1);`ladder → LADDER_UP + hardness×5`;`else → JUMP_ONE_BLOCK_COST + placeCost + jumpPenalty + hardness`。
- **执行**:`feet.y<src.y`→UNREACHABLE;**块塔核心**:全程 SNEAK(不踩空);偏 >0.17 则 MOVE_FORWARD 回中;`flatMotion<0.05 && y<dest` 才 JUMP;蹲 + 看脚下 + `y>dest+0.1`(跳顶)才 CLICK_RIGHT 放脚下块。

### Fall(多格下落,可选水桶)
- cost:全权交 `MovementDescend.cost`;`result.y != dest.y` → COST_INF(是 descend 不是 fall)。
- **执行**:水桶 clutch(临近 dest 且空中,选水桶瞄正下右键);偏心 MOVE_FORWARD(快落 `|Δy|>0.4` 加 SNEAK 防冲);落水后回收水桶。SUCCESS=feet==dest 且 `y差<0.094`。需水桶时热栏须有水桶且非下界。

### Downward(竖直下 1,挖脚下/下梯)
- cost:`!allowDownward`→INF;y-2 不可落→INF;下方梯/藤 → `LADDER_DOWN_ONE_COST`;else `FALL_N_BLOCKS_COST[1] + getMiningDurationTicks(脚下)`。
- **执行**:前 10 tick 居中等待;`moveTowards(脚下块)`(挖+瞄);重力下落。

---

## 5. A* 搜索(AStarPathFinder + AbstractNodeCostSearch)

```java
COEFFICIENTS = {1.5, 2, 2.5, 3, 4, 5, 10};   // 7 个
MIN_DIST_PATH = 5;        // 可用 partial 的最短进度(²=25)
MIN_IMPROVEMENT = 0.01;   // 重传播 epsilon
timeCheckInterval = 64;   // 每 64 节点查一次超时
```

- **开集**:`BinaryHeapOpenSet` 二叉最小堆,键 `combinedCost = cost + estimatedCostToGoal`(f 值)。`removeLowest/update/insert`。
- **节点**:`map.get(longHash(x,y,z))` 惰性建,构造时缓存 `estimatedCostToGoal = goal.heuristic`。
- **启发式**(GoalXZ,**不可采纳故快**):
```java
x=|xDiff|, z=|zDiff|;
if (x<z){straight=z-x; diagonal=x;} else {straight=x-z; diagonal=z;}
diagonal *= SQRT_2;
heuristic = (diagonal + straight) * costHeuristic(3.563);   // GoalBlock 再加 y 方向(降:FALL[2]/2·每格;升:JUMP·每格)
```
- **松弛**:`tentative = cur.cost + actionCost`;`neighbor.cost − tentative > minImprovement` 才更新;favoring 时 `actionCost ×= favoring.calculate(hash)`。
- **COEFFICIENTS 逃局部最小(单次搜索,7 候选)**:每次更新对每 i 算 `h_i = estimatedCostToGoal + cost / COEFFICIENTS[i]`,维护 `bestSoFar[i]`。大系数≈忽略已花 cost、纯贪近目标;1.5≈近最优。未达目标时 `bestSoFar()` 从小到大取**首个**进度 >25(²)的候选返回 partial。
- **超时**:`failing`(无可用 partial)时搜到 `failureTimeout(2000)`;一旦有可用 partial(`!failing`)则 `primaryTimeout(500)` 停。命中目标立即返回。
- **结果**:回溯 `node.previous` 建 Path → `postProcess` → `cutoffAtLoadedChunks` → `staticCutoff` → `SUCCESS_TO_GOAL`/`SUCCESS_SEGMENT`(partial,触发重规划)/`FAILURE`。

---

## 6. PathExecutor(执行器参数)

```java
MAX_MAX_DIST_FROM_PATH = 3;   // 偏离 >3 立即 cancel
MAX_DIST_FROM_PATH     = 2;   // 偏离 >2 开始累 ticksAway
MAX_TICKS_AWAY         = 200; // 漂移 >200 tick(~10s)放弃
```
- **推进/重定位**:每 tick 查 feet ∈ 当前 move 的 `validPositions`。否则:① 向后扫 `0..pos`(滞后/传送),命中则回退 + 重置中间;② 向前扫 `pos+3 .. len-2`(**故意跳过 +1/+2**,move 自报完成),命中跳到 `i-1`。move 返回 SUCCESS → `pathPosition++`。
- **看门狗**:偏离 >2 → `ticksAway++`,>200 → cancel;偏离 >3 → 立即 cancel。
- **cost 重验**:`costVerificationLookahead(5)` 向前重算,`≥COST_INF` 或 `Δ > maxCostIncrease(10)` → cancel。
- **单 move 超时**:`ticksOnCurrent > 估算cost + movementTimeoutTicks(100)` → cancel。UNREACHABLE/FAILED 也 cancel。
- **疾跑决策**(`shouldSprintNextTick`):`!canSprint`(allowSprint 或饥饿)→ 否;move 自请求 → 是;`Traverse→sprintableAscend`(需 sprintAscends)→ 是并冲过;`canSprintFromDescendInto`(同向 descend / traverse-down / 斜角)链式 → 是。

---

## 7. 分段规划(PathingBehavior)

双槽:`current`(执行中) + `next`(预规划)。
- **触发**:`ticksRemainingInSegment(false) < planningTickLookahead(150)` 且无在算、无 next、当前段终点非目标 → 后台从 `current 终点` 开搜。(排除当前 move,免得一个超长 move 压住规划。)
- **回调入槽**:仅当 `next.src == current.dest` 才 `next = 结果`。
- **拼接**:① 当前完成 → 若 next 含当前位置则 `current=next`;② 早拼(`snipsnap`):move 刚结束且可安全取消 → `current=next`;③ 中途拼(`splicePath`,默认 true):`current.trySplice(next)` 把 next 接到 current 尾。
- **尾部裁剪**(`staticCutoff`,算路时):
```java
if (length < pathCutoffMinimumLength(30)) 整段保留;
if (终点是目标) 不裁;
newLength = (length − 30) × pathCutoffFactor(0.9) + 30 − 1;  // 留前 30 + 余下 90%
```

---

## 8. 我们当前实现 vs Baritone — 对齐 checklist

| 区域 | 我们现状 | Baritone | 待对齐 |
|---|---|---|---|
| ActionCosts | 简化值 | §1 精确值 | ☐ 改成 `20/speed` 精确值 + fall 表用 `velocity` 公式生成 |
| Settings | 硬编码散落 | §2 参数表 | ☐ 建 `PathSettings` 常量类,默认=Baritone |
| 破坏 cost | `costOfBreaking` 简化 | `1/strVsBlock + 2` | ☐ 对齐公式(含 eff²+1、/30 vs /100、potion;**不加**水下/离地 ÷5) |
| 放置 cost | 简化 | flat `20` | ☐ = blockPlacementPenalty |
| Movement cost | walk/jump/swim/fall/break/place | §4 每种精确公式 | ☐ 逐个对齐(sprint 折扣条件、半惩罚、×SQRT_2、jumpPenalty) |
| A* 堆 | `BinaryHeapOpenSet`(已同名) | 同 | ✅ 结构已对齐 |
| 启发式 | `NavGoal` 简化 | `(diag×SQRT_2+straight)×3.563` | ☐ 对齐 GoalXZ 公式 + costHeuristic |
| 局部最小逃逸 | 无 | 7-COEFFICIENTS bestSoFar | ☐ 加 backoff 候选数组 + partial 返回 |
| 搜索超时 | 节点预算 | 500/2000 ms | ☐ 改时间预算 + `failing` 切换 |
| 执行器偏离 | `AWAY_BUDGET=60` | `MAX_TICKS_AWAY=200` + 2/3 距离 | ☐ 对齐阈值 + 距离双档 |
| 执行器超时 | `cost×4+60` | `估算+100` | ☐ 改 `cost + movementTimeoutTicks` |
| 重定位 | 前后扫 8 | 后 0..pos / 前 pos+3..len-2 | ☐ 对齐扫描窗口(前向跳 +1/+2) |
| cost 重验 | 无 | lookahead 5 / maxCostIncrease 10 | ☐ 加 |
| 分段 planAhead | 到头重算 | 150 tick 前台后台规划 + splice | ☐ 加双槽 + planningTickLookahead + cutoff 0.9/30 |
| per-move 执行 | 统一 stepToward | §4 每种 updateState(跳时机/助跑/塔放) | ☐ 拆出每种 move 的执行状态机 |

**建议重写顺序**:① ActionCosts + PathSettings(纯数值,零风险) → ② 破坏/放置 cost + Movement cost 公式(规划质量) → ③ A* 启发式 + COEFFICIENTS backoff(找路鲁棒) → ④ 执行器阈值 + cost 重验(执行鲁棒) → ⑤ per-move updateState(手感) → ⑥ 分段 planAhead(长途流畅)。

> 逐字源码该 agent 曾 clone 到 `%TEMP%\baritone-src`(可能被清);需要时重新 `git clone https://github.com/cabaletta/baritone`。
