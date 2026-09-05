# AIxOriginDemo · 自然灾害应急自组网（ESP32 Mesh + Android App）

两天黑客松 Demo，实现一套**离网应急避险闭环**。核心链路：

> 灾害输入 → 边缘决策引擎(危险指数/存活率) → **无路由器** ESP-NOW 广播 → OLED / App 实时逃生指引

## 技术栈

![ESP32-S3](https://img.shields.io/badge/-ESP32--S3-E7352C?logo=espressif&logoColor=white)
![PlatformIO](https://img.shields.io/badge/-PlatformIO-F5822A?logo=platformio&logoColor=white)
![ESP-NOW](https://img.shields.io/badge/-ESP--NOW-1B7AC2)
![Android](https://img.shields.io/badge/-Android-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/-Kotlin-7F52FF?logo=kotlin&logoColor=white)
![AMap](https://img.shields.io/badge/-AMap-00B96B)

| 端 | 技术栈 |
|---|---|
| **固件** [`firmware/AIxNode`](firmware/AIxNode) | ESP32-S3 · ESP-NOW mesh · U8g2 OLED(SPI) · 外接 GPS(UART NMEA) |
| **水位检测节点** [`firmware/WaterSensorNode`](firmware/WaterSensorNode/README.md) | MicroPython · GPIO5 ADC1 · SPI OLED（SSD1309）· 自适应滤波与干湿标定 |
| **App** [`android/`](android/README.md) | Kotlin / JDK 17 · 高德 3D 地图 + 定位 · BLE(Nordic UART) / WiFi UDP · 自研 A* 导航 |
| **文档** [`docs/`](docs) | 接线 · 演示脚本 · 验收对照 |

固件侧即 PRD 中的**“第一个检测节点”**（哨兵 / 终端复合为主，也支持中继、随身终端两种角色）。

## 目录结构

```
AIxOriginDemo/
├── android/
│   └── README.md           # Android App(高德地图+mesh桥接+逃生导航) 说明
├── firmware/
│   ├── WaterSensorNode/    # 已实测水位节点、部署工具和测试（独立 MicroPython 固件）
│   └── AIxNode/
│       └── AIxNode.ino      # 完整固件(单文件, 含全部模块, 详细中文注释)
├── docs/
│   ├── wiring.md            # 接线与 OLED 型号说明
│   └── demo-script.md       # 演示脚本与验收标准对应
└── platformio.ini           # PlatformIO 工程配置
```

## 功能总览（固件侧）

| 模块 | 实现 | 触发方式 |
|---|---|---|
| ① 本地输入/状态模拟 | Boot 按键 + 串口命令注入灾害信号 | 短按切 安全→预警→危险；长按立即广播 |
| ② 边缘决策引擎 | 危险指数 0–100 + 存活率分级(L0=95% / L1=70% / L2=25%) | 每次状态变化自动重算 |
| ③ ESP-NOW 自组网 | 定长结构体广播(含 CRC8/序号去重/中继转发)，不依赖路由器 | 状态变更立即发 + 1s 心跳 |
| ④ OLED 交互 | U8g2 128x64：状态栏 + 大字等级 + 存活率 + 逃生导航(方向箭头/下一跳/剩余距离/路线存活率) | 高危整屏反白闪烁 + 可选告警灯 |
| ⑤ 穿戴端定位 | 外接 GPS 模块(串口 NMEA)解析真实坐标，随 ESP-NOW 报文广播 | 定位后自动上报 |

> Android App 的功能说明见 [`android/README.md`](android/README.md)。

## 实测水位检测节点

新增 [WaterSensorNode 使用指南](firmware/WaterSensorNode/README.md)：ESP32-S3 N16R8，
水位 OUT 使用 GPIO5，OLED 使用 SPI（SCK12/MOSI11/CS10/DC7/RES8）。包括已上板运行的 MicroPython 程序、
标定工具、接地对照、滤波仿真及 Arduino 串口参考源码。

这是一套独立的传感器验证固件，尚未接入 AIxNode 的 ESP-NOW/Android 数据链路；
原有 `pio run` 构建入口保持不变。已完成干燥与 **1cm** 浸水验证，计划的 **3cm** 标定尚待实测。

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

### Android App（手机端）
1. 环境：**JDK 17** + **Android SDK**（或直接用 Android Studio 自带）。
2. 在 `android/gradle.properties` 填入高德 Key：`amapKey=你的Key`（到[高德开放平台](https://lbs.amap.com)申请，绑定包名 `com.aix.origin.app` 与调试/发布 SHA1）。
3. 构建：
   ```bash
   cd android
   ./gradlew assembleDebug   # 产物 app/build/outputs/apk/debug/app-debug.apk
   ```
   或用 Android Studio 打开 `android/` 直接 Run。
4. 详见 [`android/README.md`](android/README.md)。

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
| `GPS_ENABLE` | `1` | 1=启用 GPS(穿戴端) / 0=不接 |
| `GPS_RX_PIN` / `GPS_TX_PIN` | `18` / `17` | GPS 模块 UART 引脚（RX 接模块 TX） |
| `GPS_BAUD` | `9600` | GPS 串口波特率 |

## 串口命令（115200）

```
next                       切下一个场景 安全→预警→危险
lvl <0|1|2>                直接设等级
hazard <none|quake|rain|mud> [intensity] [level]
int <0-100>                设强度并重算危险指数/存活率
send                       立即广播当前状态
info                       打印本机与邻居状态(含 GPS 坐标)
help                       命令帮助
```

## 屏幕界面说明

```
┌──────────────────────────────┐
│ A Node_A S8     M2 RX L2     │  ← 顶部: 角色+ID+卫星数 | 邻居数+是否RX远端+等级
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
- Android App：见 [android/README.md](android/README.md)
