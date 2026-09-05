# AIxOrigin 应急避险 · Android App

面向自然灾害（滑坡/落石/塌方/低洼积水）的**单兵应急救援 + 实时导航 App**。
接收 ESP32-S3 ESP-NOW 应急 mesh（由网关经 BLE/WiFi 转发）的灾情多边形，
叠加高德地图渲染红色/黄色危险区，计算**存活率与险情级别**，一旦进入 L2 危险区即
**全屏闪烁告警 + 振动**，并用 **A\* 动态寻路**给出绕开高危区的逃生路线与语音引导。

> 与固件关系：`firmware/AIxNode` 为 mesh 节点端；本 App 作为“人”的入口，
> 手机兼具网关桥、个人终端双重身份，形成“节点发现灾情 → 手机实时避险”的闭环。

## 功能对照（PRD）

| PRD 要求 | 实现 |
|---|---|
| 高德地图（卫星/标准） | `MapController`，`setSatellite()` 切换；默认标准 |
| 红色/黄色半透明灾情多边形 | `MapController.updateHazards()`（L2 红 / L1 黄） |
| 其它节点/避难所标记 | 节点(蓝)来自 mesh 心跳；避难所(绿)由长按地图设定 |
| 高精度定位 GPS+北斗，离线弱网适配 | 高德 `High_Accuracy` 定位 + 缓存回退（弱网用 last-known） |
| 存活率/险情引擎 + 入区告警 | `engine/RiskEngine`（基线对齐固件 95/70/25），L2 → 全屏闪烁+振动+语音 |
| 动态避让塌方/低洼逃生路线 | `engine/EvacRouter`（A* 米制栅格；红区加权不硬封锁，区内也能逃出） |
| 动画箭头导航 + 语音 | 路线 + 沿路动态方向箭头（随定位刷新）+ 中文 TTS |
| BLE/WiFi 桥接 mesh（双向） | `comm/BleMeshClient`(NUS UART) + `comm/WifiUdpBridge`(UDP)，上行上报 GPS/SOS |
| UI：地图≥80%、顶栏、底部简报/逃生指令/SOS | `activity_main.xml` + `MainActivity` |
| 权限清单 | 见 `AndroidManifest.xml`（定位/蓝牙/网络/振动） |
| 60fps 流畅 | 地图为原生 SurfaceView；寻路与解析均在后台协程，不阻塞主线程 |

## 工程结构

```
android/
├─ settings.gradle / build.gradle / gradle.properties
└─ app/src/main/java/com/aix/origin/app/
   ├─ AixApp.kt                应用入口（日志开关）
   ├─ MainActivity.kt          主界面编排：定位→风险→路线→告警/SOS/演示灾情
   ├─ model/Models.kt          领域模型（灾情区/节点/避难所/逃生路线/险情级别）
   ├─ engine/                  纯 Kotlin 逻辑（无 Android 依赖，可单测）
   │  ├─ Geo.kt                WGS84 几何：距离/方位/点在多边形/局部投影
   │  ├─ RiskEngine.kt         存活率与险情级别（L0/L1/L2）
   │  └─ EvacRouter.kt         A* 米制栅格逃生寻路（权重绕行）
   ├─ comm/                    mesh 桥接
   │  ├─ GatewayParser.kt      网关 JSON/竖线帧编解码（下行灾情/心跳，上行 GPS/SOS）
   │  ├─ BleMeshClient.kt      BLE Nordic UART 客户端（扫描/连接/订阅/上行/重连）
   │  └─ WifiUdpBridge.kt      WiFi UDP 监听与广播
   ├─ location/LocationEngine.kt  高德定位封装
   └─ map/MapController.kt     高德地图渲染控制器
```

设计上把 `engine/` 与 `model/` 做成**纯 Kotlin**（只依赖 JDK），
几何与寻路可在 JVM 上直接单测；高德 `LatLng` 只在 `map` 层与引擎 `GeoPoint` 互转。

## 编译运行

1. 环境：Android Studio（Koala 2024.1.1+）、JDK 17（`app/build.gradle` 已配置 Java 17 toolchain）。
2. 打开 `android/` 目录，等待 Gradle 同步（Gradle 8.9 wrapper 已内置）。
3. **填入高德 Key**：在 `android/gradle.properties` 增加
   ```
   amapKey=你的高德地图Key
   ```
   （未填时编译可通过，但地图显示“鉴权失败”，需到[高德开放平台](https://lbs.amap.com)申请 Key，
   并开启「Android 地图 SDK」与「定位 SDK」；包名 `com.aix.origin.app` 要加入 Key 的包名白名单。）
4. 连接 Android 8.0+(minSdk 26) 真机，运行 `app`。
   - 地图与定位建议真机调试；首次授予定位、蓝牙权限。
   - 想立刻看效果：进图后点「模拟灾情 Lv2」，会在你东侧生成一个红色塌方区，
     触发告警并自动规划撤离路线。

## 与固件 mesh 组网联调

- **BLE 通道**：App 扫描名称为 `AIx*` 的网关节点，连接 Nordic UART（NUS）服务；
  网关把 ESP-NOW 汇聚帧逐条经 TX 特征转发，App 订阅解析为灾情/心跳。
- **WiFi 通道**：网关作为 STA 接入同一路由器后，向 UDP `50123` 广播灾情；
  App 监听同端口，并周期回传手机 GPS / 一键 SOS（`255.255.255.255` 广播）。
- **上行协议**（手机→网关）见 `GatewayCodec`：`{"type":"gps"|"sos", ...}`。

## 待办 / 已知边界

- 高德地图瓦片与 AMap 鉴权需联网；离线时地图底图不显示，但定位缓存与告警逻辑仍可用。
- `BleMeshClient` 以单连接为设计（手机直连一台网关）；多网关接力为后续增强。
- 逃生路线基于 20m 栅格 A*，作为现场“指导性”导航；接高德驾车/步行 SDK 可替换为路网级导航。
