# 寻路异步化 — 后台线程 A\* + 常驻世界缓存(Baritone 式)落地计划

> 目标:把 A\* 搜索从"主线程时间片(`step(1500节点/tick)`,~7tick 一次)"搬到**后台线程**,
> 让寻路不再占服务端 tick,为"一个服务器上跑很多伙伴"的规模铺路。
> 约束:① 不破坏已对齐的 Baritone 代价/路径输出(**同输入同输出,逐字节**);
> ② 服务端安全(绝不在 worker 线程上撞 chunk 卸载竞争 / 撕裂 `PalettedContainer` 读 / CME);
> ③ 设计对着 Baritone 真实源码,不凭记忆。

本文是**落地计划**,不含实现。批准后按阶段实现,每阶段编译 + 中立 review + 守确定性对拍。

---

## 1. 为什么现在就绪(已有的接缝)

当年寻路就是按"可线程化"设计的(`NavSnapshot` 注释 & `PATHFINDING_REFACTOR.md` P5 "为后台线程化留口")。现状:

- `AStarSearch` **自包含**:所有代价读取只走 `NavContext`(世界视图 `NavSnapshot` + 冻结库存),`Moves.generate` 也只读 `ctx.view`。
- 搜索期间**唯二**碰 live 世界的点:
  1. `NavSnapshot.getBlockState → level.getBlockState`(懒读穿透 live `Level`);
  2. `NavContext.toolCache / hasScaffold → inventory.getItem`(懒读 live 库存)。
- 执行器 `PlayerPathExecutor`(破坏/放置/移动 = **写**世界)在主线程,不动。

把上面两点在主线程**冻结**成不可变快照,搜索就是纯函数 → 可整体丢线程。

---

## 2. Baritone 真实模型(已读源码,`../baritone`)

| 关注点 | Baritone 类 / 机制 | 对我们的启示 |
|---|---|---|
| 线程安全世界读 | `BlockStateInterface`(`copyLoadedChunks` → `IClientChunkProvider.createThreadSafeCopy()`:**拷贝 chunk 映射表,共享 chunk 对象**)。主线程构造(断言 `isSameThread`),worker 线程 `get0()`。 | 需要一个"线程安全的已加载世界视图",主线程建,worker 读。 |
| 已加载 vs 未加载 | `useTheRealWorld`:已加载 chunk → 直接读真实 `LevelChunkSection` 调色板(完整 BlockState,带 `prev` chunk 局部性缓存);未加载 → 回退**打包的 `CachedRegion`**。 | 两层:加载层(精确)+ 缓存层(近似)。 |
| 缓存打包 | `ChunkPacker.pack`:每格 **2bit** → `PathingBlockType{AIR,WATER,AVOID,SOLID}` + 每列顶层 `BlockState[256]` + 追踪特殊方块。`pathingTypeToBlock`:SOLID→STONE、AVOID→LAVA、WATER→WATER。 | **2bit 丢方块身份** → 未加载地形上代价是近似(石头硬度),拿不到真实硬度/工具/流体。这是关键取舍点(见 §4)。 |
| 线程驱动 | `PathingBehavior.findPathInNewThread`:提交到共享 `Baritone.getExecutor()`;`pathfinder.calculate(primaryTimeout,failureTimeout)` 在 worker **整段跑完**(非 tick 预算);结果在 `pathPlanLock` 下接进 `current`/`next`。 | 共享线程池;搜索一次跑到底;结果加锁回接。 |
| 取消 | `AbstractNodeCostSearch`:`volatile cancelRequested`,搜索循环里检查即退;`volatile isFinished`。`inProgress` 为 `volatile`。 | 取消 = volatile 标志 + 循环检查。 |
| 防陈旧(关键) | **孤儿丢弃**:回接时校验 `path.start == expectedSegmentStart`,不符就丢("discarding orphan path segment")→ 等下次重算。 | 异步结果落地前必须校验起点 = 派发时的脚下/段尾,否则丢弃重算。 |
| 上下文门禁 | `CalculationContext.safeForThreadedUse` 布尔,`findPathInNewThread` 拒绝非线程安全上下文。 | `NavContext` 加"为搜索冻结"标志,执行用的 live 上下文禁止进线程。 |

---

## 3. 我们与 Baritone 的两点环境差异(必须适配)

1. **客户端 → 服务端**:Baritone 是客户端,`ClientChunkCache.createThreadSafeCopy()` 拷的是客户端 chunk 存储。我们是服务端假人,chunk 源是 `ServerChunkCache`,没有同样可整体拷贝的存储。需要服务端等价物(见 §5 `SectionCache` 的填充)。
2. **撕裂读后果更重**:客户端 worker 读到正在被结构性改写的 `PalettedContainer`(`set` 会换底层 bits 数组/调色板),最坏一次坏读 → 重算,客户端能忍。服务端上方块变更更频繁(其他玩家/红石/怪物/伙伴自己),且 worker 线程上抛异常无人接。**所以我们比 Baritone 更保守:worker 只读我们自己的不可变拷贝,绝不读 live chunk。**

---

## 4. 关键取舍:缓存里存什么

Baritone 缓存层是 **2bit**,因为它的代价模型(`MovementHelper`)能用近似 path-type 凑合,且它要把整个探索过的世界持久化到磁盘。

**我们的代价模型更富**:`NavContext` 读真实 `getDestroySpeed`/`requiresCorrectToolForDrops`/`FluidState`/`FallingBlock`。2bit 把"橡木原木/铁矿/沙子"全压成 STONE → 代价失真、`canHarvest` 失真 → **路径输出变样**,直接违反约束①。

> **决策(已定 — 用户 #1):缓存单元 = 压缩的 `CompactSection`,既不是 Baritone 2bit,也不是整份 `PalettedContainer.copy()`。**
>
> `CompactSection` = 我们自己的精简调色板编码,**只保留方块状态,扔掉一切寻路用不上的东西**:
> - **保留**:该 section 内去重后的 `BlockState` 调色板(`BlockState` 是注册期冻结的**驻留单例**,存引用即可,8B/个,典型 section <16 个) + 位打包索引(`ceil(log2(palette))` bit × 4096)。
> - **扔掉(不必要的信息)**:生物群系、方块光/天空光、vanilla `PalettedContainer` 的锁/监听/扩容机制。
> - **跳过**:全空气 section 整个不存(读到 → `AIR`,抄 `ChunkPacker` 跳 null storage)。
>
> 为什么不丢方块身份(不学 Baritone 2bit):我们代价模型要真实 `getDestroySpeed`/`requiresCorrectToolForDrops`/`FluidState`/`FallingBlock`,且**工具感知挖掘代价依赖每次搜索的库存**(`stack.getDestroySpeed(state)`),无法在打包期预计算 → 必须留 `BlockState`。`BlockState` 不可变 + 驻留 ⇒ worker 读引用**零竞争**;邻居依赖的碰撞箱(栅栏/墙)off-thread 从缓存里读邻居纯计算,安全。
>
> 为什么不直接 `PalettedContainer.copy()`:它带着 biome/light 无关数据 + 锁机制;自编码更省、更可控,且能跳空气 section。这是对 Baritone 的**主动偏离**(富代价 + 服务端安全),记进对齐备忘 [[pathfinding-baritone-alignment-state]]。

### 分层(避免一次吃下整个 Baritone)

- **Tier 1(本计划核心):内存版 `SectionCache`(`CompactSection`)** —— 主线程 hook 刷新,每维度共享,按距离淘汰。**覆盖伙伴 99% 场景**(伙伴在 owner 附近 = chunk 已加载)。无磁盘。
- **Tier 2(已定推迟 — 用户 #2 "按你的来"):2bit + 磁盘持久化 region + 特殊方块扫描** —— 服务于"在已探索但当前未加载地形里寻路 / 跨会话"。伙伴近距离导航用不上。**不做**,需要时再起。

---

## 5. 组件设计

```
主线程(server tick)                         worker 线程(PathPlannerPool)
──────────────────                          ──────────────────────────
chunk 加载 / 方块变更 hook ─┐
                           ├─► SectionCache(每维度,拷贝的 section,LRU)◄── NavSnapshot.get(只读拷贝)
PlayerNav.tick:            │                                                    │
  goalSupplier.get()       │                                                    ▼
  冻结 NavContext ─────────┘                                          AStarSearch.step(maxNodes) 跑到底
   (SectionCache 视图 + 库存副本)                                              │
  pool.submit(search) ─────────────────────────────────────────────────────► CompletableFuture<Path>
  每 tick 轮询 future:未完→RUNNING                                            │
  完成 → 孤儿校验(start==派发脚下?)否→丢弃重算 ◄───────────────────────────┘
         是 → staticCutoff → PlayerPathExecutor(live 上下文,主线程执行)
```

### 5.1 `SectionCache` + `CompactSection`(新,`pathing/cache/`)
- 每 `ServerLevel` 一份(`Long2ObjectOpenHashMap` 按 packed section key:`x,y,z` section 坐标)。
- `CompactSection`(见 §4)= `BlockState[] palette`(去重驻留引用) + 位打包 `long[] indices`(`ceil(log2(palette.len))` bit/格) + section Y;**构造后不可变**,worker 安全读。全空气 section 不存。
  - `get(lx,ly,lz)` → `palette[index.read(...)]`;构造:遍历该 section 的 `PalettedContainer`,建调色板 + 填索引,丢 biome/light。
- **填充/刷新(主线程)**:
  - chunk 加载 → 该 chunk 每个非空 section 编码进缓存(两 loader 各自 hook:NeoForge `ChunkEvent.Load` / Fabric `ServerChunkEvents.CHUNK_LOAD`)。
  - 方块变更 → 标记该 section dirty;轻量"重编码队列"在 tick 末批量刷新(去抖,避免一格变更重编整段)。陈旧窗口由 §6 的 replan + 执行期重算 + 孤儿丢弃兜底。
  - chunk 卸载 → section 留在缓存(**常驻**),靠下面的距离淘汰,不随卸载立即删。
- **淘汰(参考 Baritone — 用户 #3)**:抄 `CachedWorld.prune`:周期性删掉**距任一伙伴脚下 >1024 方块**的 section(Baritone `dist > 1024`,`pruneRegionsFromRAM` 开关同款),按距离而非硬 MB 上限(可选再加一个 MB 安全阀)。
- **worker 读未命中(参考 Baritone — 用户 #5)**:从未编码过的格子 → 返回 `AIR`(抄 `BlockStateInterface.get0` 未命中返 AIR)。注:未知空气自然在已加载边界止步——脚下若也是 miss→AIR→`canWalkOn` 假→该 move 被否,搜索不会走进虚空。

### 5.2 `NavSnapshot` 一分为二(都 `implements BlockGetter`,`BlockHelper` 不变)
- `CachedNavView`(搜索用,worker):读 `SectionCache` 拷贝。**不碰 live `Level`。**
- `LiveNavView`(执行用,主线程):现状的懒读穿透 live `Level`(执行器即将物理交互真实方块,要最新值)。

### 5.3 `NavContext` 双构造 + 线程安全门禁
- `NavContext.forSearch(level, SectionCache, 库存副本)`:`view=CachedNavView`,`safeForThreadedUse=true`,库存是 `SimpleContainer` 副本(`toolCache`/`hasScaffold` 离线安全)。
- `NavContext.forExecution(level, live 库存)`:`view=LiveNavView`,`safeForThreadedUse=false`(现状行为,执行器/重算用)。
- `AStarSearch`/`PathPlannerPool` 断言传入上下文 `safeForThreadedUse`(抄 Baritone 的门禁)。

### 5.4 `PathPlannerPool`(新,全局单例)
- 共享 `ExecutorService`,**默认参考 Baritone(用户 #4)**:抄 `Baritone.java` 的 `new ThreadPoolExecutor(4, Integer.MAX_VALUE, 60L, SECONDS, new SynchronousQueue<>())`(核心 4、按需增长、60s 空闲回收、守护线程、命名 `animus-path-N`)。100 个伙伴共用,不是一伙伴一线程。
- **可配置(用户 #4)**:核心数 / 最大数 走 mod 配置项;默认即上面 Baritone 值。规模化时可把 max 设成有界(文档提示:每个 `PlayerNav` 最多 1 个在飞搜索,replan 非持续,实际并发远低于伙伴数)。
- `submit(AStarSearch) → CompletableFuture<Path>`:worker 调 `search.step(maxNodes)`(一次跑到底,可加 worker 端 wall-clock 超时近似 Baritone 的 primary/failureTimeout)。
- 关停 hook(服务器停 / mod 卸载)优雅 shutdown。

### 5.5 `PlayerNav` 改 future 驱动(对齐 Baritone `findPathInNewThread`)
- `startFreshSearch()`:主线程建 `forSearch` 上下文 + 提交 → 存 `CompletableFuture<Path> pending` + 派发时的 `expectedStart`(脚下)。
- `advanceFreshSearch()`:`pending.isDone()` 否 → RUNNING;是 → 取 `Path`:
  - **孤儿校验**:`path.start.equals(expectedStart)`?否(派发后人走开了)→ 丢弃 + `restartFresh`(从当前脚下重派);是 → `staticCutoff` → `PlayerPathExecutor(... forExecution ...)`。(我们已有 `validPositions` 执行期重定位,二者配合。)
- `maybePrecompute()/advancePrecompute()`:`nextSearch` 同样改 future;`expectedStart = current.pathEnd()`(可预测的段尾),孤儿校验同理。
- **取消**:`stop()`/`restartFresh`/目标移动丢弃时,给在飞的 search 置 `cancelRequested`(`AStarSearch` 循环里检查)并丢弃其 future 结果(epoch/generation 令牌:每个 PlayerNav 自增 epoch,回调比对,过期即弃)。
- 每个 `PlayerNav` 单生产者单消费者 → 不需要 Baritone 的全局 `pathPlanLock`/`pathCalcLock`;`volatile`/`CompletableFuture` + epoch 足够。

---

## 6. 确定性 & 安全(怎么守约束①②)

- **同输入同输出**:冻结快照 + `AStarSearch` 稳定 tie-break(二叉堆 + `MIN_IMPROVEMENT` + 7 系数 best)⇒ 给定同一份 section 快照,异步路径和同步**逐字节相同**。异步只改变"何时算完",不改"算出什么"。
  - **对拍测试**(P-E):同一 `SectionCache` 快照,`forSearch` 上下文,跑"同步 step 到底" vs "线程池跑",断言 `Path` 完全相等。
- **零竞争**:worker 只读 `SectionCache` 的不可变拷贝;库存是副本;绝不碰 live `Level`/`Inventory`。
- **陈旧**(异步比时间片多出几 ms~几十 ms 的规划延迟):三重兜底——
  1. **孤儿丢弃**:起点不符即弃(Baritone 同款);
  2. **执行期重定位** `validPositions`(已有):路径起点贴回真实脚下;
  3. **执行期重算**:`PlayerPathExecutor` 用 `forExecution` live 上下文逐 move 重核代价,偏差即 `NEEDS_REPLAN`(已有)。
  - 这套兜底现状(时间片预算 + planAhead)就在用,异步只是把延迟窗口略放大,机制不变。
- **维度切换**:伙伴跟随 owner 过传送门时,丢弃在飞 search + 切到目标维度的 `SectionCache`(关联记忆 [[getowner-is-level-scoped]] / [[client-side-brain-billing]])。

---

## 7. 阶段(每阶段:编译 + `:common:test` + 中立 review + 对拍)

| 阶段 | 内容 | 风险 | 验收 |
|---|---|---|---|
| **P-A** 接缝拆分 | `NavSnapshot`→`CachedNavView`/`LiveNavView`;`NavContext` 双构造 + `safeForThreadedUse`;`PlayerNav` 搜索用 forSearch、执行用 forExecution。**仍全程主线程、仍时间片。** | 低(纯重构) | 路径输出对现状逐字节不变(回归对拍)。 |
| **P-B** SectionCache | `SectionCache` + 两 loader 的 chunk 加载/方块变更/卸载 hook + LRU。`CachedNavView` 读它(仍主线程跑搜索)。 | 中(刷新正确性/内存) | 缓存读 vs live 读,代价/路径一致;内存封顶可观测。 |
| **P-C** 上线程池 | `PathPlannerPool`;`PlayerNav.advanceFreshSearch` 改 future + 孤儿校验 + 取消 epoch + 库存副本。 | 高(并发) | 不再占 tick;并发压力下无崩;路径仍与同步对拍相等。 |
| **P-D** 预算段异步 | `nextSearch` 也走 future;viz/handoff 不卡顿。 | 中 | 段边界仍无停顿(path-while-moving 保持)。 |
| **P-E** 测试 & 压测 | sync↔async 对拍单测 + N 伙伴并发寻路压测 + 确定性回归。 | 低 | 绿;无竞争告警;tick 时间随伙伴数近似平。 |

> 可随时停在 P-B(已得"缓存化的接缝",仍单线程但更稳)。P-C 才真正上线程,是收益与风险的分水岭。

---

## 8. 受影响文件(预估)

- 改:`pathing/calc/NavSnapshot.java`(拆)、`NavContext.java`(双构造 + 门禁)、`pathing/exec/PlayerNav.java`(future 驱动)、`PlayerPathExecutor.java`(确认用 forExecution)、`AStar.java`/`AStarSearch.java`(`cancelRequested` volatile + 门禁断言;`step` 可一次跑到底)。
- 新:`pathing/cache/SectionCache.java`、`CopiedSection.java`、`pathing/cache/CachedNavView.java`、`LiveNavView.java`、`pathing/calc/PathPlannerPool.java`;两 loader 各一个 chunk/block 事件桥。
- 测试:`AsyncDeterminismTest`(对拍)、`SectionCacheTest`(刷新/淘汰)。

---

## 9. 决策(已定 2026-06-16)

1. **缓存单元** → 压缩的 `CompactSection`(去重驻留 `BlockState` 调色板 + 位打包索引,丢 biome/light/锁,跳空气 section)。既非 2bit 也非整份 copy。见 §4。
2. **Tier 2(2bit + 磁盘 + 特殊方块扫描)** → **推迟**,需要时再起。见 §4。
3. **缓存淘汰** → 参考 Baritone:删距任一伙伴 >1024 方块的 section(`CachedWorld.prune` 同款),可选加 MB 安全阀。见 §5.1。
4. **线程池** → 可配置,默认参考 Baritone(`ThreadPoolExecutor(4, MAX, 60s, SynchronousQueue)`,守护)。见 §5.4。
5. **未命中语义** → 参考 Baritone:返回 `AIR`(`BlockStateInterface.get0` 同款)。见 §5.1。

→ 五项已定,可进入实现。建议从 **P-A(纯重构,最低风险)** 起步。
