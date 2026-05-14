# Molang 表达式参考

Animus 实现了 Bedrock Molang 的一个**实用子集**——足够写骨骼可见性与动画关键帧表达式，没有完整 Bedrock Molang 的 `loop` / `return` / `break_function` 等控制流。

## 用在哪

- `render_controllers.json` 的 `part_visibility`（详见 [render-controllers.md](render-controllers.md)）
- `animation.json` 的 `rotation` / `position` / `scale` 关键帧值字符串

## 数据类型

只有 `double`。**字符串字面量**在编译期 hash 成 32-bit 整数（FNV-1a），存储为 double。这意味着字符串只能用 `==` / `!=` 比较，不能做算术。

## 字面量

| 形式 | 例子 |
|---|---|
| 数字 | `3.14`、`-2`、`.5`、`0` |
| 字符串（单引号） | `'play_music'` |
| 字符串（双引号） | `"play_music"` |

## 运算符（优先级从低到高）

| 优先级 | 运算符 | 备注 |
|---|---|---|
| 最低 | `cond ? a : b` | 三元（短路） |
| | `\|\|` | 逻辑或（短路） |
| | `&&` | 逻辑与（短路） |
| | `==` `!=` `<` `<=` `>` `>=` | 比较，返回 `1.0` / `0.0` |
| | `+` `-` | 加减 |
| | `*` `/` `%` | 乘除模 |
| | `-x` | 一元负 |
| 最高 | `!x` | 逻辑非 |

**truthy 规则**：`0.0` 是假，其他都是真（跟 Bedrock 一致）。

## Namespace

| 全名 | 别名 | 用途 | 谁写入 |
|---|---|---|---|
| `query.*` | `q.` | mod 提供的运行时状态 | mod 代码 |
| `variable.*` | `v.` | 作者自定义（per-frame 临时变量） | 编译时按需懒分配 slot；当前 phase 没有写入路径 |
| `entity.*` | `e.` | 保留给未来 Animus 特有状态 | 当前为空 |

**写新表达式时优先用 `query.*`**——跟 Bedrock 生态对齐。

## query 词汇表（当前实现）

### 渲染相关

| query | 单位/类型 | 来源 |
|---|---|---|
| `query.anim_time` | 秒 | PoseSampler 每条动画通道采样前注入 |
| `query.ground_speed` | 标量 | `walkAnimation.speed(partialTick)` |

### 实体状态

| query | 单位/类型 | 来源 |
|---|---|---|
| `query.task` | string hash | `AnimusEntity.getCurrentTask()` 的 FNV-1a hash |
| `query.is_on_ground` | `0.0` / `1.0` | `LivingEntity.onGround()` |
| `query.is_in_water` | `0.0` / `1.0` | `LivingEntity.isInWater()` |
| `query.health` | float | `LivingEntity.getHealth()` |
| `query.max_health` | float | `LivingEntity.getMaxHealth()` |
| `query.scale` | float | `LivingEntity.getScale()` |
| `query.body_y_rotation` | 度 | 身体 Y 旋转 |
| `query.head_y_rotation` | 度 | 头部 Y 旋转 |

> 缺什么 query？打开 [`MolangQueries.java`](../common/src/main/java/com/dwinovo/animus/anim/molang/MolangQueries.java) 加一行 `registerQuery("...")`，再到 [`EntityMolangInputProvider`](../common/src/main/java/com/dwinovo/animus/anim/render/EntityMolangInputProvider.java) 加一行 `fill`，编译完玩家就能用了。

## math.* 函数

| 函数 | 元数 | 备注 |
|---|---|---|
| `math.sin(deg)` `math.cos(deg)` | 1 | **参数是度数**，不是弧度（Bedrock 约定） |
| `math.sqrt(x)` `math.abs(x)` `math.exp(x)` | 1 | |
| `math.floor(x)` `math.ceil(x)` `math.round(x)` | 1 | |
| `math.min(a,b)` `math.max(a,b)` `math.mod(a,b)` | 2 | |
| `math.lerp(a, b, t)` | 3 | `a + (b - a) * t` |
| `math.clamp(v, lo, hi)` | 3 | |

函数名**大小写不敏感**——`Math.sin` 和 `math.sin` 等价。

**未实现**（Bedrock 有但 Animus 没有）：`math.random` / `math.random_integer` / `math.die_roll` / `math.atan / atan2 / asin / acos / hermite_blend / lerprotate / to_deg / to_rad / pi`。要哪个改 [`MolangFn.java`](../common/src/main/java/com/dwinovo/animus/anim/molang/MolangFn.java) 加。

## 常见写法

```jsonc
// 飞行时显示翅膀
{ "wing_*": "!query.is_on_ground" }

// 在水里才有鱼尾
{ "tail_water": "query.is_in_water" }

// 血量低于 30% 显示警告
{ "low_hp_overlay": "query.health < query.max_health * 0.3" }

// 任务比较
{ "guitar": "query.task == 'play_music'" }

// 头转动时显示朝向标
{ "facing_marker": "query.head_y_rotation > 90 || query.head_y_rotation < -90" }

// 三元 + 数学
"rotation": [ "math.sin(query.anim_time * 90) * 5", 0, 0 ]
```

## 软失败行为

Molang 失败永远**不抛异常**。出错路径：

| 情况 | 结果 |
|---|---|
| 未知 query / variable / function | 编译时 `warn`，运行时返回 `0` |
| 除以 0 / 模 0 | 返回 `0` |
| 字符串字面量未闭合 / 语法错 | 整个表达式降级为 `Const(0)`，warn |
| 函数 arity 错（参数个数不对） | 表达式降级为 `Const(0)`，warn |

这样一个坏表达式不会让模型/动画整体崩溃——只有用到它的那一项失效（通常是骨骼隐藏）。

## 不支持的语法

- `return expr;` / `expr1; expr2;` 语句链
- `variable.x = ...` 赋值（Bedrock 是有的）
- `loop(...)` 循环
- `temp.*` / `context.*` namespace
- 链式属性访问（`query.player.health` 不行；要么注册成单个 query 名，要么用 `variable.*` 中转）
