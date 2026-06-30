# Crafting Tracker

Minecraft NeoForge 1.21.1 模组，高亮正在合成的 Pattern Provider，并显示当前输出物品图标。

## 构建

```bash
./gradlew build
./gradlew runClient
```

## 依赖

- NeoForge 21.1.234 (gradle property: `neo_version`)
- Parchment 映射 1.21.1
- Java 21
- AE2 (`appliedenergistics2-neoforge:19.0.4-alpha`) — compile+runtime
- AdvancedAE (`AdvancedAE-1.6.11-1.21.1.jar`) — compileOnly via `run/mods/`
- ExtendedAE (`ExtendedAE-1.21-2.2.32-neoforge.jar`) — compileOnly via `run/mods/`
- Applied Mekanistics 1.6.3 — compileOnly via `run/mods/`
- Mekanism 10.7.19.85 — compileOnly via `run/mods/`
- EMI — compile+runtime
- EAEP (`extendedae_plus-1.21.1-1.5.4.jar`) — implementation via `libs/`

## 架构

### 服务端 (`server/CraftTracker.java`)
- 扫描 Pattern Provider 方块（支持 `PatternProviderLogicHost`、`AdvPatternProviderLogicHost`、`TileAssemblerMatrixPattern`）
- Phase 3 tick 发送 `S2CCraftHighlightData` 给已启用高亮的玩家
- 包含输出物品检测、CPU 任务匹配、冷却/超时机制

### 网络 (`network/payloads/S2CCraftHighlightData.java`)
- `HighlightEntry(BlockPos pos, int statusOrdinal, @Nullable ResourceLocation itemId, int outputType)`

### 客户端渲染 (`client/render/CraftHighlightRenderer.java`)
- `RenderLevelStageEvent.AFTER_PARTICLES` 阶段渲染
- box fill（外发光+填充）+ box outline（边框）+ sprite billboard（物品图标）
- `getDisplaySprite()` 按优先级查找物品贴图：
  1. `{namespace}:part/{path}` — AE2 贴片方块
  2. `{namespace}:part/{path}_base` — ExtendedAE 存储总线
  3. `model.getParticleIcon()` — 兜底
- sprite 渲染使用自定义 RenderType（无深度测试，始终可见）

## 已知限制

- `ex_pattern_access_part` 等终端类物品的贴图名称与物品注册名不一致，仍回退到 `getParticleIcon()`
