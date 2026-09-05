# ESP32-S3 水位检测节点

已在 ESP32-S3 N16R8 + 128×64 I²C OLED + 三引脚模拟水位模块上验证。
实际运行入口是 `micropython/main.py`：GPIO5 采集、滤波、标定、OLED 百分比与进度条。

本目录与 `../AIxNode` 的 ESP-NOW/GPS Arduino 固件并存。它是独立的传感器验证节点，
尚未实现向现有 Mesh 报文或 Android App 发送水位数据；烧录两者中的一个会替换另一个运行环境。
根目录 `platformio.ini` 仍构建原来的 AIxNode。

## 文件

| 路径 | 用途 |
|---|---|
| `micropython/main.py` | 上电自动运行 |
| `micropython/water_monitor.py` | GPIO5、OLED、真实采样、干湿标定与保存 |
| `micropython/signal_processing.py` | 去极值均值、自适应 EMA、标定判别 |
| `micropython/ssd1306.py` | 实测可点亮该 OLED 的官方兼容驱动；实际控制器型号未独立确认 |
| `tools/node.py` | 指定串口部署、读取状态、标定、复位 |
| `tools/ground_*.py` | GPIO4/5 接地对照，执行前必须断开传感器 OUT 并正确接地 |
| `tools/simulate_filters.py` | PC 合成数据比较，不往板子注入模拟水位 |
| `tests/` | 算法、部署范围测试；1cm 实测数据仅作测试证据 |
| `arduino/WaterLevelADC/` | 3.3V 模块的 Arduino 串口参考源码，GPIO5，未编译/上板验证，不含 OLED/Mesh |
| `arduino/WaterLevelADC5V/` | 5V + 两只 10kΩ 分压的 Arduino 参考源码，GPIO5，电压估计倍率为 2，未编译/上板验证 |

## 接线与输入电压

| 连接 | 主板引脚 |
|---|---|
| OLED VCC / GND | 3V3 / GND |
| OLED SDA / SCL | **GPIO9 / GPIO8** |
| 传感器模拟信号 | **GPIO5，ADC1_CH4** |
| 传感器 GND | 主板 GND |

这块测试板的 GPIO4 接地读数异常，换 GPIO5 后获得明确的干湿响应；这不是所有 S3 的通病结论。
测传感器前，必须拆掉 GPIO5→GND 的临时接地测试线。改线时断开所有电源。

传感器电源按具体模块规格选择，不能从电源灯亮推断 3.3V 供电合规。
实验中曾用 5V 供电、OUT 直连，但只验证了当时约 0.8V 的浸水输出，**不代表所有深度都不会超压**。
GPIO5 不耐 5V；未证明 OUT 最大电压安全时，5V 模块应加分压，例如：

```text
传感器 OUT ── 10kΩ ──┬── GPIO5
                      │
                     10kΩ
                      │
                     GND
```

这样 5V 输出约变为 2.5V。`water_monitor.py` 中 `divider_gain=1.0` 记录了原实验直连接法；
使用两只相等电阻后将其改为 `2.0`，并把 OLED 标题中的 `DIRECT` 改为 `DIV 1:2`。
该倍率只用于输出电压估计，不能提供电气保护。改变供电、分压或引脚后，重新执行干燥及浸水标定。
OLED 一直使用 3V3；浸水仅限探测区，顶部元件、针脚及主板不得入水。

## 环境与部署

实测 MicroPython：`ESP32_GENERIC_S3-SPIRAM_OCT` v1.29.0；N16R8 使用 Octal PSRAM 版本。
官方固件：https://micropython.org/download/ESP32_GENERIC_S3/

电脑安装 Python 3 与工具（以下命令从仓库根目录运行）：

```bash
python -m pip install -r firmware/WaterSensorNode/requirements.txt
python -m serial.tools.list_ports -v
```

如果板上已有 MicroPython，只需部署应用；串口以实际枚举为准，实测原生 USB 为 COM4：

```bash
python firmware/WaterSensorNode/tools/node.py --port COM4 deploy
python firmware/WaterSensorNode/tools/node.py --port COM4 status
```

部署只复制四个运行文件并复位，不复制测试夹具或旧标定，不清空整片闪存。
已有设备上的当前 GPIO5 标定文件会保留；新设备默认 `NO CAL`。
如果更改了输入电路，部署后立即重新做 dry/wet 标定，不要使用旧百分比。

首次安装 MicroPython 会替换旧固件：先用 `esptool read-flash 0 ALL backup.bin` 备份（明确指定目标串口），
再按官方页面执行擦除和地址 0 烧录；本仓库不自动执行擦除，也不保存个人设备备份。
原生 USB 的端口号可能在安装后变化，请重新枚举。

## 标定：当前 1cm 已验证，目标 3cm 待完成

2026-09-05 的实测：

| 状态 | 40 组块统计值范围 | 去极值代表值（0～65535） |
|---|---:|---:|
| 干燥 | 0～85 | 0 |
| 浸水 **1cm** | 14927～17028 | 15659.25 |

该组数据通过干湿差异检查，重启后显示正常。它只能证明干湿响应明显，不是精确水深校准。
用户后来指定最大测量深度为 **3cm**，但尚未采集 3cm 数据。**不要把 1cm 的标定当作 3cm。**
测试数据位于 `tests/fixtures/gpio5_dry_wet_1cm.json`，部署工具不会复制它。

1. 探测区取出、擦干、固定，等待稳定，然后执行：

   ```bash
   python firmware/WaterSensorNode/tools/node.py --port COM4 calibrate dry
   ```

2. 浸入目标深度 **3cm**（不碰到顶部元件），固定并等待稳定，再执行：

   ```bash
   python firmware/WaterSensorNode/tools/node.py --port COM4 calibrate wet
   ```

每次采集约 10 秒。`dry` 开始新一轮标定，清除旧的有效标定；`wet` 必须在同一接法的新 dry 之后采集。
工具执行后会复位，恢复实时显示。数据保存在设备的 `water_capture_5v_direct_gpio5.json` /
`water_calibration_5v_direct_gpio5.json`；文件名来自原实验，换分压也必须重新采集。

状态含义：

- `NO CAL`：尚未完成标定。
- `WEAK SIGNAL`：干湿差异不足，不显示百分比。
- `ADC SATURATED`：采样块估计接近满量程，不显示百分比。
- `REL LEVEL`：0% 为干燥，100% 为该次 wet 参考点；百分比限制在 0～100。

电极式传感器可能非线性且受水质、残留水膜和腐蚀影响。要显示厘米数，需补采已知深度的多点数据；
不能简单把百分比乘以 3 就宣称精确厘米数。程序尚未实现多点深度映射。
饱和检查是诊断提示，并不检测或阻止危险输入电压。

## 信号处理与已知限制

每帧读取 31 组原始值及校准电压，去掉两端约四分之一的值，再用自适应 EMA：
静态 alpha=0.08；连续三帧出现同方向大变化时 alpha=0.5。典型显示更新周期约 250ms，日志约每秒一行。
64 次均值/eFuse 校准及可选实测 LUT 的 Arduino 参考实现单独放在 `arduino/`，不能与 MicroPython 的采样策略混淆。

标定至少需要 `max(512, 3*(dry噪声 + wet噪声))` 的跨度（16 位刻度），噪声采用 P90-P10。
这是实验性的区分规则，不是厂商给出的测量精度；滤波不能修复断线、供电不足或模块失效。
当前 OLED 的电压与原始 ADC 值分别采样、平滑，不保证每一帧数值严格一一对应。

Arduino 的 12 位原始值为 0～4095，MicroPython `read_u16()` 为 0～65535。
两者不可直接照搬阈值，也不能仅按 ADC 位数换算 Arduino UNO 的 5V 阈值。

## 本地验证

无需连接硬件：

```bash
python -m unittest discover -s firmware/WaterSensorNode/tests -v
python firmware/WaterSensorNode/tools/simulate_filters.py
```

合成噪声场景中，原滤波/自适应滤波的上升响应稳定误差为 19.9/13.5，
达到阶跃 90% 的时间为 2.75/1.25 秒。该结果仅描述固定随机种子的算法比较。

接地诊断脚本会暂停主程序，执行后要复位恢复；例如完成 GPIO4/5 接地准备后：

```bash
python -m mpremote connect COM4 run firmware/WaterSensorNode/tools/ground_dual_test.py
python firmware/WaterSensorNode/tools/node.py --port COM4 reset
```

Arduino 参考代码使用开发板包自带的校准库，无需另装 `esp_adc_cal`：核心 2.x 使用旧接口，
3.x 使用 `adc_cali` 曲线拟合。`USE_LUT` 默认关闭，数组是明确标记的占位值；启用前必须替换为万用表实测点。
该参考程序只输出串口，不能替代本目录已经上板验证的 OLED 程序。
