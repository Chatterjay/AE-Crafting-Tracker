# AE Crafting Tracker（AE 合成追踪器）

[English](README.md)

AE Crafting Tracker 是一个用于 Minecraft NeoForge 1.21.1 的 AE2 合成可视化模组。它会在世界中高亮样板供应器，显示精简的合成状态牌和输出预览，并提供“网络定位器”用于按过滤物品查找 AE 网络中的相关方块。

## 功能

### 供应器高亮

- 根据方块实际可见形状绘制外轮廓，避免粗描边在内部产生多层线条。
- 悬浮状态牌和输出图标分离显示，减少重叠。
- 按状态显示制作中、变慢、阻塞三类颜色。
- 支持 AE2 物品、流体，以及已支持的化学品输出追踪。
- 针对机器合成流程增加恢复判断：供应器自身空闲但相邻机器正在工作时，不会继续被旧计时判定为阻塞。
- 支持从 AE 合成状态界面或网络定位器流程临时启用运行时高亮。

### 网络定位器

- 可合成的定位工具，用于扫描已绑定的 AE 网络。
- 提供 9 个幽灵过滤槽位。
- 支持从 EMI 拖入虚拟过滤物品。
- 从 EMI 拖拽物品悬停到可用过滤槽时会显示高亮反馈。
- 定位器追踪独立于普通供应器高亮配置运行。
- 丢弃、解绑或切换网络时会立即清除旧高亮。

### EMI 与 EmiLink

- 内置 Network Locator 的 EMI 兼容。
- 兼容 EmiLink 的快速填充行为，可以用配置的快速填充按键把 EMI 中的物品填入定位器幽灵槽位。

### 配置

配置已拆分为更清晰的分组：

- `status`：变慢和阻塞阈值。
- `scan`：扫描半径和完整扫描间隔。
- `appearance.colors`：十六进制 RGB 颜色，例如 `#55FF55`。
- `appearance.opacity`：状态牌和轮廓透明度。
- `diagnostics`：可选的追踪诊断日志。

供应器高亮不再通过配置默认启用。需要临时查看时，使用运行时按钮或定位器相关流程开启。

## 状态颜色

- 制作中：供应器或相关合成请求仍在推进。
- 变慢：请求持续时间超过配置的变慢阈值。
- 阻塞：请求超过阻塞阈值、供应器被锁定，或检测到明确的发配/输出问题。

对于机器合成，供应器可能已经把物品发给机器，但自身仍显示为空闲。追踪器会检查相邻机器常见的状态属性，例如 `active`、`lit`、`working`、`running`、`crafting`、`processing`，避免机器恢复运行后仍被旧计时标记为阻塞。

## 合成配方

```
PEP
ENE
PEP
```

- `P`：AE2 运算处理器
- `E`：末影之眼
- `N`：AE2 网络工具

## 指令

- `/crafttracker toggle`
- `/crafttracker on`
- `/crafttracker off`
- `/crafttracker status`

## 依赖

- Minecraft 1.21.1
- NeoForge 21.1+
- Applied Energistics 2
- EMI 可选，但推荐与定位器过滤槽一起使用。

## 构建

```bash
./gradlew build
```

Windows：

```bat
gradlew.bat build
```

需要 Java 21。

## 诊断日志

在配置中启用 `diagnostics.debugTracking` 后，会向日志写入详细的供应器状态转换。常用阶段包括：

- `quick.create_tentative`
- `refresh.tentative_promote`
- `refresh.idle_cpu_busy`
- `refresh.stuck_clear_adjacent_active`
- `refresh.recover_busy`
- `entry.remove_missed`

诊断默认关闭，主要用于测试和反馈问题。

## 许可证

GNU LGPL 3.0

### 资源说明

网络定位器物品贴图基于 AE2 的 `network_tool` 贴图制作，版权归 Applied Energistics 2 所有。
