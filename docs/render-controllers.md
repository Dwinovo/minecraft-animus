# render_controllers.json 使用指南

`render_controllers.json` 是 Animus 控制**骨骼可见性**的声明式资源文件——遵循 Bedrock 1.8.0 标准格式。模型作者写这个文件，无需碰 Java 代码就能让"骨骼 X 只在条件 Y 满足时显示"。

> 想了解条件表达式怎么写？看 [molang.md](molang.md)。

## 文件路径（双源加载）

两个源都会被扫，加载结果挂在不同 namespace 下：

| 源 | 路径 | namespace |
|---|---|---|
| **Mod 默认** | `assets/animus/render_controllers/<id>.json` | `animus` |
| **玩家自定义** | `<gameDir>/config/animus/models/render_controllers/<id>.json` | `animus_user` |

文件名去掉 `.json` 后缀就是这个 controller 的查找 key——例如 `hachiware.json` → `animus:hachiware`。

## 文件结构（完整 Bedrock schema）

```json
{
  "format_version": "1.8.0",
  "render_controllers": {
    "controller.render.<id>": {
      "geometry": "Geometry.default",
      "materials": [{ "*": "Material.default" }],
      "textures": ["Texture.default"],
      "part_visibility": [
        { "*": true },
        { "guitar": "query.task == 'play_music'" }
      ]
    }
  }
}
```

注意点：

- **只消费 `part_visibility`**。`geometry` / `materials` / `textures` / `color` / `overlay_color` / `on_fire_color` / `is_hurt_color` 字段都被解析但**忽略**——这是为了能直接吃 Bedrock add-on 的现成资源包。
- **一个文件多个 controller**：Animus 只用 `render_controllers` map 里的**第一个** controller。其他被忽略。
- 顶层 `controller.render.<id>` 命名是 Bedrock 惯例，但 Animus 实际查找用的是**文件名**，所以这个 key 取什么名都行。

## part_visibility 语义

`part_visibility` 是个数组，每个元素是 `{ "<bone-glob>": <expression> }` 形式。一个元素里**可以有多个键值对**：

```json
{ "wings_left": "!query.is_on_ground", "wings_right": "!query.is_on_ground" }
```

### 求值顺序

- 数组**从前往后**遍历每个元素
- 元素内的每个 (bone, expression) 求值
- **后面的覆盖前面的**——Bedrock last-write-wins 语义

### 默认行为

- 没规则的 bone = **可见**
- 一个 bone 在多条规则匹配时，最后一条规则赢

### expression 类型

| 形式 | 含义 |
|---|---|
| `true` / `false` | 硬编码可见性 |
| 数字（如 `1` / `0`） | 非零 = 可见 |
| molang 字符串 | 表达式求值，非零 = 可见 |

### 隐藏的传递

bone 被隐藏时它的**所有子 bone 也跟着隐藏**——`hidden` 在骨架树上向下传递。

## bone glob 语法

| 形式 | 含义 |
|---|---|
| `*` | 匹配所有 bone |
| `prefix*` | 前缀匹配（如 `wing_*` 匹配 `wing_left`、`wing_right`） |
| `*suffix` | 后缀匹配（如 `*_locator`） |
| `exact_name` | 精确匹配 |

**不支持**多通配符或中间通配符（`a*b`、`a*b*c`）——加载时 warn 跳过该规则。

> Bedrock 标准本身对 glob 也没明确定义；上面是 Animus 的实现范围。

## 完整例子（建议模板）

```json
{
  "$schema": "https://raw.githubusercontent.com/Blockception/Minecraft-bedrock-json-schemas/main/source/general/renderController/main.json",
  "format_version": "1.8.0",
  "render_controllers": {
    "controller.render.my_pet": {
      "part_visibility": [
        { "*": true },
        { "wings": "!query.is_on_ground" },
        { "tail_water": "query.is_in_water" },
        { "low_hp_marker": "query.health < query.max_health * 0.3" },
        { "guitar": "query.task == 'play_music'" }
      ]
    }
  }
}
```

第一行 `$schema` 字段让 VSCode 自动从 Blockception 的官方 Bedrock JSON schema 库拉补全。Gson 会忽略这个字段，不影响运行。

## 编辑器推荐

Blockbench **不支持** GUI 编辑 render_controllers（功能请求挂了三年没人做）。推荐：

- **VSCode** + 装 `BlockceptionLtd.blockceptionvscodeminecraftbedrockdevelopmentextension` 扩展，自动 part_visibility + Molang 补全
- **bridge.** ([editor.bridge-core.app](https://editor.bridge-core.app/))——浏览器内 Bedrock add-on IDE，零安装

## 加载与热重载

### 加载触发

| 时机 | 触发的扫描 |
|---|---|
| 客户端启动 / `/reload` / F3+T | `assets/` 源 + `config/` 源都重扫 |

### 成功日志

切回 gradle 终端找：

```
[animus-anim] loaded 1 render controllers (1 from assets, 0 from config) (stamp N)
```

### 失败日志

```
[animus-anim] failed to load render_controller animus:foo: <异常细节>
```

通常原因：
- JSON 语法错（漏逗号 / 引号不闭合）
- top-level 不是 `format_version + render_controllers` 形态
- 文件不是 UTF-8

### 编译期 warn

```
[animus-anim] unknown molang variable 'query.is_sneaking'; using 0
[animus-anim] unsupported bone glob 'a*b' in part_visibility — skipping
[animus-anim] failed to parse molang 'query.task ==': ...
```

这些都不会让模型崩溃——只有相关的那条规则失效。

### 热重载注意事项

- 改 `assets/animus/...` 下的 JSON 后**直接 F3+T 不生效**——因为 dev 模式下游戏读 build cache。需要先 `./gradlew :common:processResources` 同步，再 F3+T。
- 改 `<gameDir>/config/animus/models/...` 下的 JSON **F3+T 立即生效**——文件系统直接读，没有 build cache 中间层。
- 重启游戏永远生效。

## 与 Java `BoneVisibilityRule` 的关系

Animus 还支持在 Java 里用 lambda 注册可见性规则（`AnimusEntityRenderer.addBoneVisibilityRule`）。两条路径**叠加**：

1. Java lambda 先评估（OR 语义：同一 bone 多 lambda 任一为 true 即显示）
2. molang RC 后评估，**覆盖** Java lambda 的结果

如果一个 bone 同时被 Java rule 和 molang rule 管，**molang 赢**。当前 Animus 本身不注册任何 Java rule，所以实际使用中只有 molang 在起作用。
