# Minecraft 版本间 API 变动记录（移植手册）

Numen 采用**分支即版本**模型：每个受支持的 MC 版本一条分支（`1.21.1`、`1.21.4`、…、`26.1.2`），
Fabric + NeoForge 同源。向上移植（把低版本分支的代码搬到高版本）时，绝大多数改动是
**机械的映射/签名替换**——本文件逐版本记录这些 MC/loader API 变动，作为移植配方。

> 规则：每完成一档移植（`A → B`），把这一档碰到的**每一个** API 变动追加到对应小节。
> 宁可啰嗦：一条记录省下的是下一个人（或下一个 MC 版本）重新踩坑的时间。

约定：
- ❗ = 编译期会直接报错的破坏性变更；🔁 = 行为/语义变化需留意；📦 = 构建/依赖（gradle.properties 等）。
- 代码示例用 `旧 → 新`。

---

## 版本阶梯

`1.20.1 → 1.20.2 → 1.20.4 → 1.20.6 → 1.21.1 → 1.21.4 → 1.21.5 → 1.21.8 → 1.21.10 → 1.21.11 → 26.1.2`

新架构（numen-api 拆分 + 调度器 + raw `NumenTool` + skill 体系）当前基线在 **`1.21.1`**，正逐档向上移植。

---

## 每档都要改的构建旋钮 📦

`gradle.properties`（core 与 api 各一份）：

| 键 | 含义 |
|---|---|
| `minecraft_version` | 目标 MC，如 `1.21.4` |
| `minecraft_version_range` | 如 `[1.21.4, 1.21.5)` |
| `neo_form_version` | NeoForm 数据版本（见 projects.neoforged.net/neoforged/neoform） |
| `fabric_version` | Fabric API，如 `0.117.0+1.21.4` |
| `neoforge_version` | 如 `21.4.123` |
| `fabric_loader_version` | 一般跨小版本不变 |

loader 依赖 build.gradle 里的 `numen-api-*-<mc>` 坐标也要同步成目标 MC 版本（api 须先发对应版本到 maven）。

> 下载 MC 用国内镜像（BMCLAPI），否则容易卡死/断流。

---

## 1.21.1 → 1.21.4

来源：老架构 `v0.0.2-1.21.1-beta` ↔ `v0.0.2-1.21.4-beta` 的纯 MC delta（约 30 个 java 文件）。
新架构里文件路径/包名已变（`tulpa`→`numen`、工具类移入 `core/tools`），但**API 替换内容一致**。

### 注册表查询 ❗
按 `ResourceLocation` 取值的方法整体重命名：
```java
BuiltInRegistries.ITEM.get(id)         → BuiltInRegistries.ITEM.getValue(id)
BuiltInRegistries.ENTITY_TYPE.get(id)  → BuiltInRegistries.ENTITY_TYPE.getValue(id)
// 同理 BLOCK、MOB_EFFECT 等所有 BuiltInRegistries.* 的 get(ResourceLocation)
```
波及（新架构对应类）：`CollectItems`、`DropItems`、`EatItem`、`Equip`、`Hunt`、
`InteractAt`、`InteractEntity`、`ScanBlocks`、`PlaceBlock`、`MineBlock` 等所有按 id 取 Item/EntityType 的工具与 task。

### registryAccess 查注册表 ❗
```java
registryAccess().registryOrThrow(Registries.STRUCTURE)  → registryAccess().lookupOrThrow(Registries.STRUCTURE)
```
波及：`GetSelfStatusTool`（结构感知）、`LocateBiome*`、任何 `registryOrThrow`。

### 配方系统 ❗（改动最大）
1. RecipeManager 入口换名：
   ```java
   level.getRecipeManager().getRecipes()  → level.recipeAccess().getRecipes()
   ```
2. 通用配料获取走 `PlacementInfo`（1.21.1 没有此类）：
   ```java
   // 判空：
   cr.getIngredients().isEmpty() || allMatch(Ingredient::isEmpty)
     → PlacementInfo info = cr.placementInfo();
       info.isImpossibleToPlace() || info.ingredients().isEmpty()
   // 遍历配料：
   recipe.getIngredients()  → recipe.placementInfo().ingredients()
   ```
3. 单输入配方（熔炼/切石）直接 `.input()`：
   ```java
   sc.getIngredients().get(0)        → sc.input()                 // StonecutterRecipe
   cookingRecipe.getIngredients().get(0) → cookingRecipe.input()  // AbstractCookingRecipe
   ```
4. shaped 配方网格类型变了（gap 由 `Ingredient.EMPTY` 变 `Optional.empty()`）：
   ```java
   NonNullList<Ingredient> cells = shaped.getIngredients();
   cells.get(i).isEmpty() ? "." : describe(cells.get(i))
     → List<Optional<Ingredient>> cells = shaped.getIngredients();   // row-major
       cells.get(i).map(LookupRecipeTool::describe).orElse(".")
   ```
   需 `import net.minecraft.world.item.crafting.PlacementInfo;`
波及：`LookupRecipeTool`（主要）。

### Client / UI 渲染（待移植时逐条补全）
老分支这一档还改了下列客户端文件，多为渲染签名（GuiGraphics / 文本测量 / 颜色）调整，
移植到新架构的 `client/` 时按编译错误对照补：
`NumenScreen`(老 `TulpaScreen`)、`Dropdown`、`ProviderDropdown`、`FlatEditBox`、
`SimpleButton`、`PathVizRenderer`、`NumenToasts`。
<!-- TODO: 实际移植时把每个渲染 API 变动写到这里 -->

### 其它
`BlockDigger`、`NavSnapshot`、`CachedNavView`、`ScanBlocksJob`、`EquipCompanionTask`、
`ShootCompanionTask`、`EatCompanionTask`、`InteractAtTaskRecord` 有零星签名微调（各 1–4 行）。
<!-- TODO: 移植时确认并记录 -->

---

## 1.21.4 → 1.21.5
<!-- 来源：v0.0.2-1.21.4-beta ↔ v0.0.2-1.21.5-beta（约 16 文件）。移植时填写。 -->
_待移植时填写_

## 1.21.5 → 1.21.8
<!-- 约 24 文件 -->
_待移植时填写_

## 1.21.8 → 1.21.10
_待移植时填写_

## 1.21.10 → 1.21.11
_待移植时填写_

## 1.21.11 → 26.1.2
_（升级链的完整配方在各上行分支的本文件里；本分支是从 1.21.1 向下分出的）_

---

# 向下移植（↓ 低于 1.21.1）

新架构基线在 1.21.1;往下是把 1.21.1 的新 API **改回**旧 API。参考物同样是老架构 tag diff,
方向取 `git diff v0.0.2-1.21.1-beta v0.0.2-1.20.x-beta` 的 `+` 侧(即 1.20.x 的写法)。

## 1.21.1 → 1.20.6 ✓（已验证,双 loader 编译 + 出包通过）

构建旋钮:MC `1.20.6` / range `[1.20.6, 1.21)` / NeoForm `1.20.6-20240627.102356` /
Fabric `0.100.8+1.20.6` / **Fabric loader `0.16.10`** / NeoForge `20.6.139`。Java 仍 21;fabric/build.gradle 仍 remap loom(不动)。

### 反向 MC delta
```java
// ResourceLocation 工厂 → 公开构造器(1.20.6 构造器是 public;工厂是 1.21+)。大量文件(payload/screen/entity)：
ResourceLocation.fromNamespaceAndPath(ns, path) → new ResourceLocation(ns, path)
//   注意全限定写法要修正为 new net.minecraft.resources.ResourceLocation(...)（别写成 net.minecraft.resources.new …）
// 配方 assemble → getResultItem（1.20.6 无 CraftingInput/SingleRecipeInput/SmithingRecipeInput）：
cr.assemble(CraftingInput.EMPTY, ra) → cr.getResultItem(ra)
cook/sc.assemble(new SingleRecipeInput(ItemStack.EMPTY), ra) → .getResultItem(ra)
sm.assemble(new SmithingRecipeInput(EMPTY,EMPTY,EMPTY), ra) → sm.getResultItem(ra)
//   删掉那三个 crafting.*Input import。
// VertexConsumer 旧链式（PathVizRenderer）：
vc.addVertex(pose,…).setColor(c).setNormal(pose,…)
  → vc.vertex(pose.pose(),…).color(c).normal(pose,…).endVertex()
// FakeConnection：删 disconnect(DisconnectionDetails) 重写（1.21+ 才有）+ 其 import；保留 disconnect(Component)。
```

> ⚠ **NeoForge publish 需在线**:1.20.6 的 NeoForm runtime 依赖 `log4j:2.11.+`(动态版本),
> `--offline` 解析不了 → publish 用**在线**(非 MC 下载,只拉 maven 制品)。

## 1.20.6 → 1.20.4 ✓（分水岭：NeoForge → **Forge** + Java 21 → **17**；双 loader 编译 + 出包通过）

**这不是机械档,是加载器 + 工具链切换。** `1.20.4` 及以下用 **Forge**(不是 NeoForge)、**Java 17**。
构建旋钮:MC `1.20.4` / range `[1.20.4, 1.21)` / Fabric `0.92.1+1.20.4` / **loader `0.15.3`** /
`forge_version=49.0.19`(去掉 neoforge/neoform);`java_version=17`。

### 构建机制（整套换掉）❗
- **Gradle wrapper 9.2 → 8.7**(9.2 与 loom 1.14 都要 Java 21,和 1.20.4 的 Java 17 冲突)。
- `common`:NeoForge moddev → **Sponge VanillaGradle**(`org.spongepowered.gradle.vanilla`);mixin 0.8.5 / mixinextras 0.3.5。
- Fabric:`fabric-loom-remap` → 普通 **`fabric-loom` 1.6-SNAPSHOT** + `officialMojangMappings()`。
- `forge/` 子项目:**ForgeGradle 6** (`net.minecraftforge.gradle [6.0,6.2)`) + Sponge mixin + `reobfJar`。
- `settings.gradle` 加 Forge + Sponge 仓库、`include('forge')`;buildSrc expandProps neoforge→forge(`META-INF/mods.toml`)。api 那边额外保留 `apiJar`/`numen-api-*` 发布。
- 删 `neoforge/` 子项目。**三个 build.gradle 的 api 坐标都要 → 1.20.4。**

### Forge 平台层（api）❗
NeoForge 的 4 个平台实现要重写成 Forge 版(在 api):
- `ForgePlatformHelper`(`ModList`/`FMLPaths`/`FMLEnvironment`)、`ForgeNumenConfig`(`ForgeConfigSpec`)、
  `ForgeBlockCapabilityReader`(`ForgeCapabilities` item/fluid/energy,null + 6 面)、
  `ForgeNetworkChannel`(单 `SimpleChannel`,所有 payload 走一个 `(id,bytes)` 信封,靠 `payload.id()` 路由)。
- Forge `@Mod` 入口 + client + datagen + `META-INF/mods.toml` + 4 个 `META-INF/services` provider 文件 + `*.forge.mixins.json`。

### 网络层降级（api common）❗**最大的坑**
1.20.4 **没有** `StreamCodec`/`RegistryFriendlyByteBuf`/`CustomPacketPayload.Type`/`ByteBufCodecs`。
所有 payload(12 个)+ `INetworkChannel` + `NumenNetwork` + 两端通道重写成 1.20.4 形状:
`ID` 常量 + `id()` + `write(FriendlyByteBuf)` + 静态 `read(FriendlyByteBuf)` 当 decoder;`ItemStack` 用 `writeItem/readItem`。
> ⚠ 反向 delta 会漏掉这个(它是 1.20.5 引入的,不在 1.20.6↔1.20.4 老 tag diff 里,因为老 tag 本身是 1.20.4 老写法)。

### Java 17 / 1.20.4 语言 + MC 降级
```
sealed switch(类型模式) → if/else instanceof（ConvoLog、NumenLlmClient、NumenScreen）
Math.clamp(v,lo,hi) → Math.max(lo, Math.min(hi, v))；Mth.clamp 保留
VertexConsumer.setColor/setNormal(pose) → color/normal(pose.normal(), …).endVertex()（Matrix3f 重载）
CommonListenerCookie.createInitial(profile, false) → createInitial(profile)
PlayerList.load 返回 nullable CompoundTag（不是 Optional）；SavedData save/load 去掉 HolderLookup.Provider 参
new ResourceLocation(ns,path)（公开构造器）；recipe.getResultItem(registryAccess())
chunk.status.ChunkStatus → chunk.ChunkStatus；player.blockInteractionRange() → 4.5D
HuntCompanionTask getAttributeModifiers(slot) Multimap；food：getFoodProperties()/isEdible()
ItemStack.isSameItemSameComponents → isSameItemSameTags；FakeConnection 只有 disconnect(Component)
```

### ⚠ Forge JiJ + 产物文件名
core 内嵌 api 用 **ForgeGradle jarJar**:`jarJar.enable()` + `jarJar(group,name,version){ transitive=false; jarJar.ranged(it,'[0.0.0,)') }`。
`transitive=false` 必须(否则把整个 Forge/MC 依赖图打进去,94MB)。
**FG6 的 jarJar 产物带 `-all` classifier** → 可分发的 Forge 单文件是 `numen-forge-1.20.4-0.0.3-all.jar`(内嵌 api),
plain `numen-forge-1.20.4-0.0.3.jar` 是不含 api 的 reobf jar。**发布/CI 要发 `-all.jar`。**

## 1.20.4 → 1.20.2 / 1.20.2 → 1.20.1
_待移植时填写（同为 Forge/Java17，预计更小）_
