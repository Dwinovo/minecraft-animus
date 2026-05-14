# AGENTS.md

> 面向 AI 编码代理的项目说明。简体中文。

## 项目愿景（Animus）

`minecraft-animus` 的目标是打造一个 **完全由 LLM 驱动的 Minecraft 实体**：

1. 模组只包含 **一个实体**。该实体的行为不通过传统硬编码 Goal/Behavior 决定，而是由外部 LLM 决策。
2. 使用 Minecraft 原生 **Brain 系统**（`net.minecraft.world.entity.ai.Brain`，配合 `Memory`、`Sensor`、`Activity`、`Behavior/BehaviorControl`）构建 **原子化 AI**：每个 Behavior 只做一件最小粒度的事（如「走向某坐标」「攻击某目标」「拾取最近物品」），不内嵌任何策略性判断。
3. 将这些 **原子 Behavior 映射为 ToolCall**：每个原子 AI = 一个工具。LLM 通过工具调用来组合行为，Brain 系统负责执行与状态机层面的安全约束。
4. 结合 **Skill** 与 **MCP**（Model Context Protocol）扩展能力：Skill 提供更高层的封装与可复用「招式」，MCP 用于把外部上下文 / 工具暴露给驱动该实体的 Agent。
5. 感知层（Sensor → Memory）作为 LLM 的 **观测输入**：把世界状态结构化后传给 Agent，Agent 输出 ToolCall，回到 Brain 执行。

设计原则：**Brain 是执行层 + 状态机护栏；LLM 是决策层；ToolCall 是它们之间唯一的接口。** 不要在 Behavior 里写策略，也不要在 LLM prompt 里写底层位移/路径逻辑——两边各司其职。

## 当前进度

- 已从 MultiLoader-Template 克隆并完成改名：`mod_id=animus`、`mod_name=Animus`、`group=com.dwinovo.animus`、入口类 `AnimusMod`、`rootProject.name=minecraft-animus`。
- 三个 mixin 配置文件已重命名为 `animus.mixins.json` / `animus.fabric.mixins.json` / `animus.neoforge.mixins.json`，内部 `package` 字段指向 `com.dwinovo.animus.mixin`。
- **基岩版渲染管线已搭建**（迁移自 [`minecraft-chiikawa`](https://github.com/dwinovo/minecraft-chiikawa)，作者本人为两个项目的唯一版权人，可自由重新许可）：`common/.../anim/` 下完整的 Bedrock geo/animation/molang 烘焙 + 多控制器状态机 + 不可变快照渲染。
- **`AnimusEntity` 实体已注册**：继承 `PathfinderMob` 实现 `AnimusAnimated`，Fabric + NeoForge 各自走 vanilla 注册路径（无 service 抽象，YAGNI）。
- **双源资源加载**：默认资产走 `assets/animus/`（namespace `animus`），玩家自定义模型走 `<gameDir>/config/animus/models/`（namespace `animus_user`）。
- **默认模型 Hachiware** 已就位（dwinovo 原创美术资产，重新许可为 CC BY-NC 4.0）。
- 还没有 Brain、Sensor、Memory、Activity 的实现代码（下一阶段 plan）。
- 还没有引入任何 LLM/HTTP/MCP 客户端依赖。

下一步通常是：① 把 `AnimusEntity` 接到 Brain 系统的空骨架上；② 设计 ToolCall ↔ Behavior 的桥接层；③ 引入 LLM/MCP 客户端依赖（先确认 Fabric / NeoForge 两侧 classpath 不冲突）。

## 技术栈与版本

来源：[gradle.properties](gradle.properties)

| 项 | 值 |
|---|---|
| Minecraft | 26.1.2 |
| Java | 25 |
| Loader | Fabric (loom 1.15.5) + NeoForge (moddev 2.0.141) |
| Fabric API | 0.145.4+26.1.2 |
| NeoForge | 26.1.2.7-beta |
| Mixin | 0.8.5 + MixinExtras 0.5.3 |

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
| `render/` | EntityRenderer 基类（`AnimusEntityRenderer`）+ 顶点提交（`ModelRenderer`）+ 程序性拦截器（`BoneInterceptor` / `HeadLookInterceptor`）+ 可见性规则（`BoneVisibilityRule`） |

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

2. **这是 LLM 决策层、Brain 执行层、还是 ToolCall 桥接层？** 不要把三层混在一个类里。预期的分层骨架（尚未实现）：
   - `common/.../ai/brain/` — Behavior、Sensor、Memory、Activity 定义
   - `common/.../ai/tools/` — ToolCall schema + 执行入口（每个工具调用映射到 1~N 个 Behavior 触发或 Memory 写入）
   - `common/.../ai/agent/` — LLM 客户端、对话循环、Skill/MCP 集成
   - `common/.../entity/` — 实体类本身 + 注册（已落地：`AnimusEntity` + `InitEntity`）

3. **要新建一个原子 Behavior 时**：保持单一职责。能用 `OneShot` / `Trigger` 等内置基类就别从头实现。把可调参数尽量上提到 ToolCall 的入参，而不是写死在 Behavior 里。

### Brain 系统速查（实现时再查 Mojang 映射确认 API 签名）

- `Brain<E>`：实体的大脑，持有 Memory 和 Activity 状态机。
- `MemoryModuleType<T>`：键值化的世界感知结果（如「最近的玩家」「攻击目标」「家坐标」）。**自定义 Memory 需要注册**。
- `SensorType<S extends Sensor<?>>`：周期性把世界扫一遍写入 Memory。**自定义 Sensor 需要注册**。
- `Activity`：行为模式分组（IDLE、WORK、FIGHT…）。Brain 在不同 Activity 间切换。
- `BehaviorControl<E>` / `Behavior<E>`：单个原子行为。
- 注册一般通过 `DeferredRegister` 或加载器特有的事件总线 —— 这部分需要 Service 抽象。

### 引入外部依赖

LLM 客户端、HTTP、MCP SDK 等多半需要直接 `implementation` 到 common（如果是 Java/Kotlin pure jar 且不引入加载器冲突），或者通过 jar-in-jar（fabric loom 的 `include` / neoforge moddev 的 jarJar）打包进去。**先确认依赖在 Fabric / NeoForge 两侧都没有 classpath 冲突再引入**，否则会出现「dev 能跑、正式包炸」。

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
