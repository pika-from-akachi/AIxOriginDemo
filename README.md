# AIxOriginDemo · 自然灾害应急自组网检测节点

两天黑客松 Demo。基于 **ESP32-S3 + ESP-NOW + OLED(128x64)** 实现单台“检测/终端复合节点”：
模拟灾害输入 → 边缘决策引擎(危险指数/存活率) → **无路由器** ESP-NOW 广播 →
OLED 实时刷新逃生指引。多台节点可组成离网自组网，高危状态自动广播并在全网点亮紧急界面。

本项目即 PRD 中的**“第一个检测节点”**（哨兵/终端复合节点为主，也支持中继、随身终端两种角色）。

## 目录结构

```
AIxOriginDemo/
├── firmware/
│   └── AIxNode/
│       └── AIxNode.ino      # 完整固件(单文件, 含全部模块, 详细中文注释)
├── docs/
│   ├── wiring.md            # 接线与 OLED 型号说明
│   └── demo-script.md       # 演示脚本与验收标准对应
└── platformio.ini           # PlatformIO 工程配置
```

## 功能总览（对应 PRD 四大模块）

| 模块 | 实现 | 触发方式 |
|---|---|---|
| ① 本地输入/状态模拟 | Boot 按键 + 串口命令注入灾害信号 | 短按切 安全→预警→危险；长按立即广播 |
| ② 边缘决策引擎 | 危险指数 0–100 + 存活率分级(L0=95% / L1=70% / L2=25%) | 每次状态变化自动重算 |
| ③ ESP-NOW 自组网 | 定长结构体广播(含 CRC8/序号去重/中继转发)，不依赖路由器 | 状态变更立即发 + 1s 心跳 |
| ④ OLED 交互 | U8g2 128x64：状态栏 + 大字等级 + 存活率 + 逃生导航(方向箭头/下一跳/剩余距离/路线存活率) | 高危整屏反白闪烁 + 可选告警灯 |

## 三种角色（同一份固件，编译期切换）

| 值 | 角色 | 说明 | 默认节点 ID |
|---|---|---|---|
| `1` | **哨兵/触发端(终端复合)** ← 本次主交付 | 本地模拟灾害、广播、接收、显示 | `Node_A` |
| `2` | 指挥/中继节点 | 转发收到的报文(hops+1)，扩展覆盖 | `Node_B` |
| `3` | 随身逃生终端 | 主要接收广播、刷新逃生界面 | `Node_C` |

Arduino IDE 用户直接改 `AIxNode.ino` 顶部 `NODE_ROLE`；PlatformIO 用户见下文。

## 快速开始

### Arduino IDE（推荐给现场改板）
1. 安装 **esp32 by Espressif** 开发板包（Arduino IDE 2.x：开发板管理器搜 `esp32`）。
2. 工具 → 库管理 → 安装 **U8g2 by olikraus**。
3. 把 `firmware/AIxNode` 整个文件夹拷到本地，双击打开 `AIxNode.ino`。
4. 选择板型 **ESP32S3 Dev Module**（或手头实际板型），按需改 `NODE_ROLE`/OLED 配置。
5. 上传。每块板子一个唯一 `NODE_ID`（默认已按角色区分）。

### PlatformIO
用 VS Code + PlatformIO 打开本目录：

```bash
pio run                     # 编译 (默认 Node_A 角色)
pio run -t upload           # 烧录
pio device monitor -b 115200
```

三块板子不同角色：把 `platformio.ini` 里注释的 `nodeB` / `nodeC` env 启用，
或用 `pio run -e <env> -t upload` 分别烧录。

## 常用配置（`AIxNode.ino` 顶部）

| 宏 | 默认 | 说明 |
|---|---|---|
| `NODE_ROLE` | `1` | 角色 1/2/3 |
| `NODE_ID` | 按角色 | 节点名（每块板唯一） |
| `OLED_MODEL` | `1` | 1=SSD1306  2=SH1106  3=SSD1309（platformio.ini 已设为 `3`） |
| `OLED_SCK` / `OLED_MOSI` / `OLED_MISO` | `12` / `11` / `13` | ESP32-S3 DevKitC 默认 SPI 总线引脚 |
| `OLED_CS` / `OLED_DC` / `OLED_RES` | `10` / `6` / `7` | SPI 片选 / 数据命令 / 复位 |
| `BTN_PIN` | `0` | 板载 Boot 键(低有效) |
| `ALARM_PIN` | `-1` | 可选外接告警 LED/蜂鸣器引脚 |
| `ESPNOW_CHANNEL` | `1` | 所有节点必须相同 |

## 串口命令（115200）

```
next                       切下一个场景 安全→预警→危险
lvl <0|1|2>                直接设等级
hazard <none|quake|rain|mud> [intensity] [level]
int <0-100>                设强度并重算危险指数/存活率
send                       立即广播当前状态
info                       打印本机与邻居状态
help                       命令帮助
```

## 屏幕界面说明

```
┌──────────────────────────────┐
│ A Node_A        M2 RX L2     │  ← 顶部: 角色+ID | 邻居数+是否RX远端+等级
├──────────────────────────────┤
│ DANGER            SURVIVAL   │  ← 左: 大字危险等级;  右: 存活率(自绘大数字)
│ MUDSLIDE ▓▓▓ 97     25 %     │     左: 灾害类型 + 危险指数动画条 + 数值
│                              │
│  ↗ > SHELTER N              │  ← 底部: 逃生导航(脉冲箭头 + 下一跳 + 方向)
│    312m SURV 78%            │      剩余距离 + 路线存活率
└──────────────────────────────┘
```

- **安全(绿)**：`SAFE`，白底黑字，底部显示逃生导航（下一跳+方向+剩余距离+路线存活率，无箭头）。
- **预警(黄)**：`WARN`，静态界面，脉冲箭头指向逃生方向。
- **危险(红)**：`DANGER`，整屏反白 350ms 闪烁（可外接告警灯同步闪烁），箭头同步脉冲。

> OLED 为单色，PRD 中的绿/黄/红以**亮度/反白/闪烁节奏**区分，黑白屏下保证“无乱码”。

## 为什么用结构体而不是 JSON？

ESP-NOW 单帧建议 ≤250 字节；结构体 `packed` 后约 30 字节，封包/解包快、无线开销小，
更易满足验收“**50ms 内状态同步**”。如后续需要便于外部解析，可在保留结构体的同时另加
一条 JSON 串行调试输出。

## 文档

- 接线/引脚：见 [docs/wiring.md](docs/wiring.md)
- 2 板 / 3 板演示脚本与验收对照：见 [docs/demo-script.md](docs/demo-script.md)

> ⚠️ 本项目仓库创建后尚未包含任何提交（git 远端为空）。代码按“可直接拷入仓库根目录”的
> 布局放置。首次在仓库提交后即可正常使用 issue 自动 checkout / push 流程。
