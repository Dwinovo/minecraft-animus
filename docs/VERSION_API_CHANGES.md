# Minecraft 版本间 API 变动记录（移植手册）

Numen 采用**分支即版本**模型：每个受支持的 MC 版本一条分支（`1.21.1`、`1.21.4`、…、`26.1.2`），
Fabric + NeoForge 同源，**api 与 core 各自同名分支**。向上移植（把低版本分支搬到高版本）时，绝大多数
改动是**机械的映射/签名替换**——本文件逐版本记录这些 MC/loader API 变动，作为移植配方。

> 规则：每完成一档移植（`A → B`），把这一档碰到的**每一个** API 变动追加到对应小节。
> 宁可啰嗦：一条记录省下的是下一个人（或下一个 MC 版本）重新踩坑的时间。

约定：❗=编译期破坏性变更；📦=构建/依赖。代码示例用 `旧 → 新`。

---

## 版本阶梯

`1.20.1 → 1.20.2 → 1.20.4 → 1.20.6 → 1.21.1 → 1.21.4 → 1.21.5 → 1.21.8 → 1.21.10 → 1.21.11 → 26.1.2`

新架构（numen-api 拆分 + 调度器 + raw `NumenTool` + skill 体系）基线在 **`1.21.1`**，正逐档向上移植。
**已移植：1.21.1 → 1.21.4 → 1.21.5 → 1.21.8 → 1.21.10 → 1.21.11 ✓**

## 每档的流程

1. **api 先**：从下一档低版本分支开新分支 → 改构建旋钮 → 编译修 → `publish` 本地 maven **并 push numen-maven**。
2. **core 后**：开/重置同名分支为低版本内容 → 改构建旋钮 + api 依赖坐标指向目标 MC → 编译修 → 出包验证（内嵌 api）。
3. 边修边把变动追加到本文件。

> CI 在干净环境从远程 maven 取 api，所以 api 制品**必须 push 到 numen-maven**，否则 CI 编不过。
> 同坐标重发后 core 端若编不到新符号，删 `.gradle/loom-cache/remapped_mods/.../com/dwinovo/numen` 再编（Loom remap 缓存；新坐标无此问题）。下载 MC 用 BMCLAPI 镜像。

## 每档都要改的构建旋钮 📦

`gradle.properties`（core 与 api 各一份；core 还要改 `fabric/build.gradle`、`neoforge/build.gradle` 里的 `numen-api-*-<mc>` 坐标）：

| 键 | 1.21.1 | 1.21.4 |
|---|---|---|
| `minecraft_version` | 1.21.1 | 1.21.4 |
| `minecraft_version_range` | `[1.21.1, 1.21.2)` | `[1.21.4, 1.21.5)` |
| `neo_form_version` | 1.21.1-20240808.144430 | 1.21.4-20241203.161809 |
| `fabric_version` | 0.116.7+1.21.1 | 0.117.0+1.21.4 |
| `neoforge_version` | 21.1.233 | 21.4.123 |

---

## 1.21.1 → 1.21.4 ✓（已验证，双 loader 编译 + 出包通过）

### 通用（common，api 与 core 都有）

**注册表按 id 取值** ❗ — 方法整体改名：
```java
BuiltInRegistries.ITEM.get(rl)        → BuiltInRegistries.ITEM.getValue(rl)
BuiltInRegistries.BLOCK.get(rl)       → BuiltInRegistries.BLOCK.getValue(rl)
BuiltInRegistries.ENTITY_TYPE.get(rl) → BuiltInRegistries.ENTITY_TYPE.getValue(rl)
// 1.21.1 .get(ResourceLocation) 返回 T；1.21.4 返回 Optional<Reference<T>>，要 .getValue() 拿 T
```

**registryAccess 查注册表** ❗：
```java
registryAccess().registryOrThrow(Registries.STRUCTURE) → registryAccess().lookupOrThrow(Registries.STRUCTURE)
```

**高度访问器** ❗ — `LevelHeightAccessor` 方法改名（实现类的 `@Override` 方法名也要跟着改）：
```java
level.getMinBuildHeight() → level.getMinY()
level.getMaxBuildHeight() → level.getMaxY()
// getHeight() 不变
```

**Entity.teleportTo** ❗ — 末尾新增 boolean 参数：
```java
e.teleportTo(level, x, y, z, Set.of(), yRot, xRot) → e.teleportTo(level, x, y, z, Set.of(), yRot, xRot, false)
```

**spawnAtLocation** ❗ — 新增首个 `ServerLevel` 参数：
```java
player.spawnAtLocation(stack) → player.spawnAtLocation(serverLevel, stack)
```

**物品使用动画枚举改名** ❗：
```java
import net.minecraft.world.item.UseAnim;   → import net.minecraft.world.item.ItemUseAnimation;
UseAnim.CROSSBOW                            → ItemUseAnimation.CROSSBOW
```

**配方系统大改** ❗（`QueryExtraTools` / 老 `LookupRecipeTool`）：
```java
level.getRecipeManager().getRecipes()       → level.recipeAccess().getRecipes()
// 通用配料：1.21.4 走 PlacementInfo（新增 import net.minecraft.world.item.crafting.PlacementInfo）
cr.getIngredients().isEmpty() || allMatch(Ingredient::isEmpty)
                                            → PlacementInfo info = cr.placementInfo();
                                              info.isImpossibleToPlace() || info.ingredients().isEmpty()
recipe.getIngredients()                     → recipe.placementInfo().ingredients()  // 无空位，去掉 isEmpty 判断
// 单输入配方（切石/熔炼）：
sc.getIngredients().get(0)                  → sc.input()
cookingRecipe.getIngredients().get(0)       → cookingRecipe.input()
// shaped 网格：类型变了，空位由 Ingredient.EMPTY 变 Optional.empty()
NonNullList<Ingredient> = shaped.getIngredients()  → List<Optional<Ingredient>> = shaped.getIngredients()
cells.get(i).isEmpty() ? "." : describe(cells.get(i))
                                            → cells.get(i).map(X::describe).orElse(".")
// 配料里的物品：
Arrays.stream(ing.getItems()).map(s -> ...s.getItem()...)   // ItemStack[]
                                            → ing.items().map(h -> ...h.value()...)  // Stream<Holder<Item>>
cookingRecipe.getCookingTime()              → cookingRecipe.cookingTime()
```

### 客户端 / UI（api）

**GuiGraphics.blitSprite** ❗ — 新增首参（RenderType 函数）：
```java
g.blitSprite(sprite, x, y, w, h) → g.blitSprite(net.minecraft.client.renderer.RenderType::guiTextured, sprite, x, y, w, h)
```
波及 6 个文件 22 处：`NumenScreen`、`Dropdown`、`ProviderDropdown`、`FlatEditBox`、`SimpleButton`、`NumenToasts`。

### NeoForge loader

**客户端资源重载事件** ❗（`NumenNeoForgeClient`）：
```java
import ...client.event.RegisterClientReloadListenersEvent; → import ...client.event.AddClientReloadListenersEvent;
event.registerReloadListener(listener)
   → event.addListener(ResourceLocation.fromNamespaceAndPath(MOD_ID, "skill_loader"), listener)
```

**数据生成** ❗（`DataGenerators` / `ModBlockTagsProvider`）：
```java
gatherData(GatherDataEvent event)        → gatherData(GatherDataEvent.Client event)
// 标签 provider 不再要 ExistingFileHelper：
new ModBlockTagsProvider(out, lookup, event.getExistingFileHelper())  → new ModBlockTagsProvider(out, lookup)
super(output, lookup, MOD_ID, existingFileHelper)                     → super(output, lookup, MOD_ID)
```
（`ModItemTagsProvider` 不吃 EFH，无需改。）

---

## 1.21.4 → 1.21.5 ✓（已验证，双 loader 编译 + 出包通过）

构建旋钮：MC `1.21.5` / range `[1.21.5, 1.21.6)` / NeoForm `1.21.5-20250325.162830` /
Fabric `0.119.6+1.21.5` / NeoForge `21.5.97`。

### 通用（common）

**世界明暗判断** ❗（`GetWorldInfoTool`）：
```java
level.isDay()   → level.isBrightOutside()
level.isNight() → level.isDarkOutside()
```

**Inventory 选中槽** ❗ — 字段 `selected` 私有化，改读写方法（`BlockDigger`、`Equip/Hunt/Mine/Shoot` task）：
```java
inv.selected        → inv.getSelectedSlot()
inv.selected = slot → inv.setSelectedSlot(slot)
```

**SavedData → SavedDataType** ❗ — 存档数据走 codec 化的 `SavedDataType`（`CompanionRegistry`，在 api）：
```java
// 删 save()/load() 重写 + SavedData.Factory，改成：
import net.minecraft.world.level.saveddata.SavedDataType;
private static final SavedDataType<T> TYPE = new SavedDataType<>(
        "numen_companions", T::new, CODEC, DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);
getDataStorage().computeIfAbsent(FACTORY, "name") → computeIfAbsent(TYPE)
// 仍 extends SavedData；不再需要 HolderLookup/CompoundTag/NbtOps import
```

**CompoundTag codec 化 NBT** ❗（`NumenPlayer`）：
```java
output.putUUID(KEY, uuid)                        → output.store(KEY, UUIDUtil.CODEC, uuid)
if (input.hasUUID(KEY)) x = input.getUUID(KEY)   → input.read(KEY, UUIDUtil.CODEC).ifPresent(v -> x = v)
```

### NeoForge loader
**事件总线合并** — 1.21.5 把 mod-bus 与 game-bus 合并。旧的「构造器分别向 modBus / NeoForge.EVENT_BUS
注册」**仍可编译可用**，只是 `@EventBusSubscriber(bus=…)` 的 `bus` 属性标记为 deprecated-for-removal
（仅警告）。为最小改动本档未改写为 `@EventBusSubscriber`，留待将来必要时再做。

### 未触及（新架构无需改，记录备查）
老分支这一档还改过：`SmithingRecipe.baseIngredient()`（1.21.4 Optional → 1.21.5 直接 Ingredient）、
9 字段 payload 改回 `StreamCodec.composite`（1.21.4 上限 8）、`GetOwnerStatusTool` 的 `EntityReference`。
新架构当前实现未用到这些点，故本档未改；后续相关代码若改动碰到，再按此补。

## 1.21.5 → 1.21.8 ✓（已验证，双 loader 编译 + 出包通过）

**跨过 1.21.6/1.21.7**，含 1.21.6 的渲染 + IO 大改。构建旋钮：MC `1.21.8` / range `[1.21.8, 1.21.9)` /
NeoForm `1.21.8-20250717.133445` / Fabric `0.136.1+1.21.8` / NeoForge `21.8.47`。

### 客户端渲染（api）
**blitSprite 首参：函数 → 常量** ❗ — 1.21.6 渲染管线化：
```java
g.blitSprite(RenderType::guiTextured, sprite, x,y,w,h)
   → g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x,y,w,h)   // net.minecraft.client.renderer.RenderPipelines
```
（全限定写法：`net.minecraft.client.renderer.RenderType::guiTextured` → `...RenderPipelines.GUI_TEXTURED`，22 处。）

**其它渲染**：
```java
camera.getPosition()                    → camera.position()                 // PathVizRenderer
g.renderTooltip(font, st, mx, my)       → g.setTooltipForNextFrame(font, st, mx, my)   // NumenScreen
```

### 存档 / IO 大改（api，1.21.6 ValueInput/ValueOutput 重构）❗
```java
// Entity 存读档签名换类型且变 protected（NumenPlayer）：
public void addAdditionalSaveData(CompoundTag output)  → protected void addAdditionalSaveData(ValueOutput output)
public void readAdditionalSaveData(CompoundTag input)  → protected void readAdditionalSaveData(ValueInput input)
// 方法体不变：ValueOutput.store(key,CODEC,v) / ValueInput.read(key,CODEC) 与 CompoundTag 同名可用。
// import net.minecraft.world.level.storage.ValueInput / ValueOutput

// PlayerList.load 加 ProblemReporter（CompanionFactory）：
getPlayerList().load(player)  → getPlayerList().load(player, ProblemReporter.DISCARDING)  // 仍 .ifPresent(player::load)

// Connection.send 的 listener 类型（FakeConnection）：
send(Packet<?>, PacketSendListener, boolean)  → send(Packet<?>, ChannelFutureListener, boolean)
// import io.netty.channel.ChannelFutureListener（去 net.minecraft.network.PacketSendListener）
```

### NeoForge loader（api）
```java
// @EventBusSubscriber 的 bus 属性 1.21.8 删除（总线已合并）：
@EventBusSubscriber(modid = MOD_ID, bus = Bus.MOD)  → @EventBusSubscriber(modid = MOD_ID)
// RenderLevelStageEvent 用 per-stage 子类，去掉 Stage 枚举判断：
onRenderLevel(RenderLevelStageEvent e){ if(e.getStage()!=AFTER_TRANSLUCENT_BLOCKS) return; … }
   → onRenderLevel(RenderLevelStageEvent.AfterTranslucentBlocks e){ … }
// 客户端发包用 ClientPacketDistributor（NeoForgeNetworkChannel）：
PacketDistributor.sendToServer(payload)  → net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(payload)
```

### 数据生成（core）
```java
// Fabric：getOrCreateTagBuilder 改名 valueLookupBuilder（Fabric{Block,Item}TagsProvider）：
var b = getOrCreateTagBuilder(key)  → var b = valueLookupBuilder(key)
// NeoForge：改用 NeoForge 自带 ItemTagsProvider（3 参构造），弃 vanilla + 空 block-tag lookup：
extends net.minecraft.data.tags.ItemTagsProvider
  super(output, lookup, completedFuture(TagLookup.empty()))
   → extends net.neoforged.neoforge.common.data.ItemTagsProvider
     super(output, lookup, Constants.MOD_ID)
// NeoForge DataGenerators 同样去掉 @EventBusSubscriber 的 bus 属性。
```

## 1.21.8 → 1.21.10 ✓（已验证，双 loader 编译 + 出包通过；跳过 1.21.9）

含 1.21.9 的**输入 API 重构 + NeoForge Transfer 重写**。构建旋钮：MC `1.21.10` / range `[1.21.10, 1.21.11)` /
NeoForm `1.21.10-20251010.172816` / Fabric `0.138.4+1.21.10` / NeoForge `21.10.64`。几乎全在 api。

### 客户端输入 ❗（api：NumenScreen、SettingsScreen）
```java
keyPressed(int keyCode, int scanCode, int modifiers)  → keyPressed(KeyEvent event)          // event.key()
mouseClicked(double x, double y, int button)          → mouseClicked(MouseButtonEvent event, boolean dbl)
                                                                              // event.x()/y()/button()
super.keyPressed(k,s,m) → super.keyPressed(event); super.mouseClicked(x,y,b) → super.mouseClicked(event, dbl)
// 内部大量 mouseX/mouseY/button 引用：方法开头取局部 double mouseX=event.x(),… 保持方法体不变即可。
// import net.minecraft.client.input.KeyEvent / MouseButtonEvent
```

### 其它客户端（api）
```java
// EditBox 格式化器：setFormatter(BiFunction) → addFormatter(EditBox.TextFormatter)（FlatEditBox、NumenScreen）
//   FlatEditBox 的 fmt 字段类型 BiFunction → EditBox.TextFormatter，取值 fmt.apply(..) → fmt.format(..)
// KeyMapping 分类：字符串 "key.categories.misc" → 枚举 KeyMapping.Category.MISC（NumenKeys）
// PlayerSkin 包移动：net.minecraft.client.resources.PlayerSkin → net.minecraft.world.entity.player.PlayerSkin
// Fabric 世界渲染事件包：...rendering.v1.WorldRenderEvents → ...rendering.v1.world.WorldRenderEvents
//   回调 ctx.matrixStack() → ctx.matrices()（NumenFabricClient）
```

### 存档 load（api：CompanionFactory）❗
```java
getPlayerList().load(player, ProblemReporter.DISCARDING).ifPresent(player::load)
  → getPlayerList().loadPlayerData(player.nameAndId())
        .map(tag -> TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), tag))
        .ifPresent(player::load)
// import net.minecraft.world.level.storage.TagValueInput
```

### NeoForge 平台（api）❗
```java
// Transfer API 重写（NeoForgeBlockCapabilityReader 整体重写）：
IItemHandler/IFluidHandler + Capabilities.ItemHandler/FluidHandler.BLOCK
   → ResourceHandler<ItemResource>/<FluidResource> + Capabilities.Item/Fluid.BLOCK
     （size()/getResource(i)/getAmountAsLong(i)/getCapacityAsLong(i,res)）
IEnergyStorage + Capabilities.EnergyStorage.BLOCK → EnergyHandler + Capabilities.Energy.BLOCK
   canReceive()/canExtract() 没了 → 在回滚的 Transaction 里模拟 insert/extract 探测方向
// FMLLoader.isProduction() → FMLLoader.getCurrent().isProduction()（NeoForgePlatformHelper）
```

### NeoForge 核心入口（core：NumenCoreNeoForge）❗
```java
// FMLEnvironment.dist（静态字段没了）→ FMLLoader.getCurrent().getDist()
// IModFile.findResource/getSecureJar 一直在变 → 改用 loader 无关的 classloader 资源解析：
Path.of(NumenCoreNeoForge.class.getResource("/skills").toURI())   // 防御式 try/catch
```

## 1.21.10 → 1.21.11 ✓（已验证，双 loader 编译 + 出包通过）

**Mojang 大改名**，量大但纯机械。构建旋钮：MC `1.21.11` / range `[1.21.11, 1.22)` /
NeoForm `1.21.11-20251209.172050` / Fabric `0.139.5+1.21.11` / NeoForge `21.11.42`。

### 全局改名 ❗（api 24 文件 + core 15 文件）
```java
net.minecraft.resources.ResourceLocation → net.minecraft.resources.Identifier   // 类改名
ResourceLocation（类型/静态调用一切） → Identifier                                // 全局字符串替换即可
ResourceKey.location()  → ResourceKey.identifier()
   // 波及 dimension().location()、ref.key().location()、unwrapKey().map(k->k.location()) 等
```
> 注意:`SkillInfo.location()`（api 里我们自己的 record 访问器，返回 Path）**不是** ResourceKey，别误改。
> 盲替 `ResourceLocation`→`Identifier` 前确认源码没有 `ResourceLocationException`（本项目源码没有；只在 build/ 的 jar 里）。

### 渲染 / Button（api）❗
```java
net.minecraft.client.renderer.RenderType → net.minecraft.client.renderer.rendertype.RenderTypes  // 移包+复数
RenderType.lines() → RenderTypes.lines()（PathVizRenderer）
AbstractButton 抽象方法 renderWidget(...) → renderContents(...)（SimpleButton）
// 顺手删掉 1.21.8 换 RenderPipelines 后残留的 RenderType 无用 import（FlatEditBox、SimpleButton），
//   否则 1.21.11 里 RenderType 那个位置没了会直接报错。
```

### ⚠ 构建坑（core）：common/build.gradle 的 api 坐标
之前每档只改了 `fabric/build.gradle`、`neoforge/build.gradle` 的 `numen-api-*-<mc>` 坐标，
**漏了 `common/build.gradle` 里的 `compileOnly numen-api-common-<mc>`**——它一直停在 `1.21.1`。
1.21.4~1.21.10 因 MC 兼容侥幸没暴露（运行期用的是 JiJ 里正确版本的 api），到 1.21.11
`ResourceLocation→Identifier` 才炸（core 编到旧 api 的 `PathVizPayload`）。
**每档三个 build.gradle 的 api 坐标都要一起改。**

## 1.21.11 → 26.1.2
_待移植时填写_
