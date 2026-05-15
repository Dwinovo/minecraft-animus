# AGENTS.md

> 面向 AI 编码代理的项目说明。简体中文。

## 项目愿景（Animus）

`minecraft-animus` 的目标是打造一个 **完全由 LLM 驱动的 Minecraft 实体**：

1. 模组只包含 **一个实体**。该实体的行为不通过传统硬编码 Goal/Behavior 决定，而是由外部 LLM 决策。
2. 使用 vanilla **`Goal` + `GoalSelector`** 系统作为执行层：每个原子任务（如 `move_to`）= 一个 `Goal` 子类，channel 概念直接复用 `Goal.Flag {MOVE, LOOK, JUMP, TARGET}` —— selector 自然提供"同 flag 互斥、跨 flag 并行"。LLM 任务统一注册在 priority 0，自动消除抢占。
3. 将原子任务 **映射为 ToolCall**：每个工具 = LLM 视角的 schema + Task 翻译器。Tool 和 Task 严格分离 —— Tool 描述 LLM 看到什么，Task 描述世界里发生什么。一对一不是硬约束（一个 Tool 可以发多个 Task，反之亦然）。
4. 结合 **Skill** 与 **MCP**（Model Context Protocol）扩展能力：Skill = 编排好的任务链（对 LLM 暴露为单个 tool，内部按序执行原子 task，失败时回报具体到步），MCP 用于把外部上下文 / 工具喂给 Agent。
5. 感知层（周期 sensor → perception 快照）作为 LLM 的 **观测输入**：先用一个 100 行的 `Perception` POJO（Phase-2 实现），不引入 vanilla Brain 的 Memory/MemoryModuleType 注册机制（成本过高）。

设计原则：**Goal 是执行层；LLM 是决策层；ToolCall 是它们之间唯一的接口。** 不要在 Goal 里写策略，也不要在 LLM prompt 里写底层位移/路径逻辑——两边各司其职。

**为什么不用 Brain**：Brain 的 Activity/Schedule/Memory 模型是为"被动 AI"（村民日程、Warden 状态机）设计的，命令式调度需要伪造 memory 状态。Goal.Flag 已经天然就是我们设计的 channel mutex。详见 [`common/.../task/LlmTaskGoal.java`](common/src/main/java/com/dwinovo/animus/task/LlmTaskGoal.java) 类注释。

## 当前进度

- 已从 MultiLoader-Template 克隆并完成改名：`mod_id=animus`、`mod_name=Animus`、`group=com.dwinovo.animus`、入口类 `AnimusMod`、`rootProject.name=minecraft-animus`。
- 三个 mixin 配置文件已重命名为 `animus.mixins.json` / `animus.fabric.mixins.json` / `animus.neoforge.mixins.json`，内部 `package` 字段指向 `com.dwinovo.animus.mixin`。
- **基岩版渲染管线已搭建**（迁移自 [`minecraft-chiikawa`](https://github.com/dwinovo/minecraft-chiikawa)，作者本人为两个项目的唯一版权人，可自由重新许可）：`common/.../anim/` 下完整的 Bedrock geo/animation/molang 烘焙 + 多控制器状态机 + 不可变快照渲染。
- **`AnimusEntity` 实体已注册**：继承 `PathfinderMob` 实现 `AnimusAnimated`，Fabric + NeoForge 各自走 vanilla 注册路径（无 service 抽象，YAGNI）。
- **双源资源加载**：默认资产走 `assets/animus/`（namespace `animus`），玩家自定义模型走 `<gameDir>/config/animus/models/`（namespace `animus_user`）。
- **默认模型 Hachiware** 已就位（dwinovo 原创美术资产，重新许可为 CC BY-NC 4.0）。
- **owner + 驯服系统**：`AnimusEntity extends TamableAnimal`，食物 tag `animus:tame_foods` 控制驯服食材。蹲下右键打开换肤 GUI；普通右键打开 LLM 对话 GUI（仅 owner）。
- **零第三方 LLM 依赖**：用 JDK `java.net.http.HttpClient`（Java 25 内置）+ Gson（MC vanilla 自带）直发 OpenAI 协议。无 OkHttp / OpenAI SDK / kotlin-stdlib / jackson / swagger。**mod jar ~260KB**（早期内嵌 OpenAI SDK 时 50MB，砍了 99.5%）。
- **LlmProvider 抽象** (`common/.../agent/provider/`)：单点 OpenAI ↔ 内部协议适配。`OpenAIProvider` 是默认实现，`DeepSeekProvider` 继承并处理 `reasoning_content` 字段的 round-trip（修 thinking 模式 400 兼容性问题）。Config 字段 `provider: "openai" | "deepseek"` 切换。
- **LLM 调用在客户端**：每个玩家用自己的 API key、自付 token。服务端不调 LLM。设计原因：避免服务器主人为所有玩家承担 token 消耗 + 玩家不需要把 key 上交服务端。
- **LLM 任务执行框架**（MVP 端到端跑通）：`common/.../task/`（原子任务生命周期 + GoalSelector 桥接）+ `common/.../agent/`（HTTP transport + provider + LLM 客户端 + ConvoState + 16-turn cap + batch-dedup）+ `common/.../client/agent/`（per-entity `ClientAgentLoop` + `ClientAgentLoopRegistry`）+ 右键 owner Prompt GUI + `ExecuteToolPayload`(C→S) / `TaskResultPayload`(S→C) 双向网包 + 跨 loader `IAnimusConfig`（Fabric JSON / NeoForge ModConfigSpec）。第一个原子工具 `move_to(x,y,z,speed)` 已注册。
- 还没有 Sensor / Perception / 复合任务链 / streaming / 客户端任务状态可视化（下一阶段）。

下一步通常是：① 玩家配置 API key 进 `config/animus.json` 或 `config/animus-common.toml`，启动游戏右键实体试 `move_to`；② 加更多原子工具（`look_at` / `say` / `attack`）；③ 加 Perception 层把附近玩家/方块/伤害事件喂给 LLM；④ 设计复合任务（任务链）编排 + 透明结果回报。

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
- **任务生命周期**：`queue.enqueue(record)` → `Goal.canUse()` peek 匹配 → `Goal.start()` poll + 标记 RUNNING + 调 `onStart` → `Goal.tick()` 每 tick 检查 deadline + `onTick` → 子类设置终止 state → `Goal.canContinueToUse()` 返回 false → `Goal.stop()` 调 `buildResult` + 写 outbox。
- **跨线程关口唯一一处**：`HttpLlmTransport.post` 的 future 在 JDK HttpClient 的 daemon 线程完成；`ClientAgentLoop.bounceBackToMain` 通过 `Minecraft.getInstance().execute(...)` 投回 client tick 线程。所有 convo / 网包发送只在 client tick 线程发生（single-writer）。
- **超时计时**：用 `level.getGameTime()`（`/tick freeze` 和 `/tick rate` 都正确响应）。`TaskRecord.deadlineGameTime` 在 tool 翻译时算好（now + 默认 timeout）。
- **抢占处理**：不做。所有 LLM Goal 都注册在 priority 0，selector 不会让一个 LLM Goal 抢另一个。
- **死循环防护**：`ConvoState.MAX_TOOL_TURN_COUNT = 16`（硬上限）+ `MAX_REPEAT_TOOL_BATCH_COUNT = 2`（连续两次相同 tool 批次就停）。LLM 返回纯文本则重置计数。
- **LLM 路由关键类**：`HttpLlmTransport`（POST + Gson）→ `LlmProvider.buildRequestBody` / `parseResponseBody` → `AssistantTurn`（含 `content` + `toolCalls` + `extras` 透传 backend 专属字段）。Provider 选择由 `config.provider` 决定。
- **加新 Provider**（Anthropic native / Gemini 等）：实现 `LlmProvider` 接口；如果是 OpenAI 方言只改 `parseResponseBody` 和 `extractExtras`，参考 `DeepSeekProvider`（30 行）；如果是完全不同协议，从头实现 `buildXxxMessage` + `buildRequestBody` + `parseResponseBody`，参考 `OpenAIProvider`（200 行）。然后在 `AnimusLlmClient.pickProvider` 加 case。

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
