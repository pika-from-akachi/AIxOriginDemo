/* ============================================================================
 *  AIxNode.ino  —— 自然灾害应急自组网「检测节点」固件 (ESP32-S3 / Arduino)
 * ============================================================================
 *  黑客松 Demo: 泥石流 / 山洪 应急自组网边缘检测节点
 *
 *  对应 PRD 的四个核心功能模块:
 *    模块1  本地输入与状态模拟      —— 板载按键(短按切换 安全/预警/危险, 长按广播)
 *                                   + 串口命令注入
 *    模块2  边缘决策引擎            —— 危险指数 Risk Index (0-100) 与预估存活率
 *    模块3  ESP-NOW 离网自组网通信  —— 不依赖路由器, 低延迟广播 (含中继转发)
 *    模块4  OLED 视觉交互          —— U8g2 驱动 128x64 OLED, 极简像素风 UI,
 *                                   高危自动反白闪烁 + 逃生指引
 *
 *  三种角色(同一份代码, 编译期切换, 见下方 NODE_ROLE):
 *    ROLE_A_SENTINEL=1  哨兵/触发端(终端复合)    本 Demo 的默认角色
 *    ROLE_B_RELAY   =2  指挥/中继节点 (收到后转发, 扩展覆盖范围)
 *    ROLE_C_TERMINAL=3  随身逃生终端 (主要接收并显示)
 *
 *  烧录方式:
 *    A) Arduino IDE:  直接把本文件夹整个拷入你的 Arduino 目录并打开 AIxNode.ino
 *       1) 开发板选 "ESP32S3 Dev Module" (或你手上的具体 S3 板型)
 *       2) 工具 -> 库管理, 安装 "U8g2" by olikraus
 *       3) 按需修改下方 NODE_ROLE / OLED_* 配置 -> 编译上传
 *    B) PlatformIO:   见仓库根目录 platformio.ini
 *
 *  详细接线见  docs/wiring.md
 * ==========================================================================*/

// ============================================================================
//  0. 用户配置区 (改这里即可适配你的板子)
// ============================================================================

// ---- 0.1 节点角色 ---------------------------------------------------------
#define ROLE_A_SENTINEL  1   // 哨兵/触发端(终端复合)
#define ROLE_B_RELAY     2   // 指挥/中继节点
#define ROLE_C_TERMINAL  3   // 随身逃生终端
#ifndef NODE_ROLE
  #define NODE_ROLE  ROLE_A_SENTINEL   // <-- Arduino IDE 用户改这里切换角色
#endif

// ---- 0.2 节点 ID (广播包里的身份标识, 每块板子保持唯一) --------------------
#ifndef NODE_ID
  #if   NODE_ROLE == ROLE_B_RELAY
    #define NODE_ID "Node_B"
  #elif NODE_ROLE == ROLE_C_TERMINAL
    #define NODE_ID "Node_C"
  #else
    #define NODE_ID "Node_A"
  #endif
#endif

// ---- 0.3 OLED 屏幕 ----------------------------------------------------------
// 屏幕像素控制器型号: 常见 1.54"/2.42" 128x64 OLED
//   SSD1306 (很多 1.54"), SH1106 / SSD1309 (部分 2.42")
//   显示异常(花屏/无显示)时优先切换 OLED_MODEL 或检查 SPI 接线与引脚
#define OLED_MODEL_SSD1306  1
#define OLED_MODEL_SH1106   2
#define OLED_MODEL_SSD1309  3
#ifndef OLED_MODEL
  #define OLED_MODEL  OLED_MODEL_SSD1306
#endif

// SPI 引脚 (ESP32-S3-DevKitC-1 默认 SPI: SCK=12 / MOSI=11 / MISO=13 / SS=10)
#ifndef OLED_SCK
  #define OLED_SCK   12
#endif
#ifndef OLED_MOSI
  #define OLED_MOSI  11
#endif
#ifndef OLED_MISO
  #define OLED_MISO  13
#endif
#ifndef OLED_CS
  #define OLED_CS    10
#endif
#ifndef OLED_DC
  #define OLED_DC    6
#endif
#ifndef OLED_RES
  #define OLED_RES   7
#endif

// ---- 0.4 按键 / 告警 LED(可选外接) ------------------------------------------
// 按键接在 GPIO0 (开发板 BOOT 键, 低电平有效)。注意: 上电瞬间不要按住它, 否则会进入下载模式
#define BTN_PIN   0
// 可选外接告警 LED / 有源蜂鸣器引脚 (高电平触发闪烁); 不用则保持 -1
#define ALARM_PIN (-1)

// ---- 0.5 ESP-NOW 通信参数 ---------------------------------------------------
#define ESPNOW_CHANNEL     1     // 所有节点必须同信道(默认1); 现场不要连任何 WiFi
#define HEARTBEAT_MS       1000  // 周期心跳广播, 刷新邻居在线状态
#define REMOTE_TIMEOUT_MS  4000  // 超过此时长未收到某邻居 -> 判定离线
#define MAX_HOPS           2     // 中继最大跳数 (限制广播风暴)

// 按键长短按阈值
#define BTN_LONG_MS  800

// ============================================================================
//  1. 头文件与库
// ============================================================================
#include <Arduino.h>
#include <string.h>
#include <stdio.h>
#include <math.h>
#include <SPI.h>
#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>
#include <U8g2lib.h>

// ============================================================================
//  2. OLED 对象 (按 OLED_MODEL 选择 U8g2 构造函数, 均为 128x64 4线SPI)
// ============================================================================
#if   OLED_MODEL == OLED_MODEL_SH1106
  U8G2_SH1106_128X64_NONAME_F_4W_HW_SPI u8g2(U8G2_R0, OLED_CS, OLED_DC, OLED_RES);
#elif OLED_MODEL == OLED_MODEL_SSD1309
  U8G2_SSD1309_128X64_NONAME2_F_4W_HW_SPI u8g2(U8G2_R0, OLED_CS, OLED_DC, OLED_RES);
#else
  U8G2_SSD1306_128X64_NONAME_F_4W_HW_SPI u8g2(U8G2_R0, OLED_CS, OLED_DC, OLED_RES);
#endif

// ============================================================================
//  3. 数据 / 协议定义
// ============================================================================
#define PKT_MAGIC  0xA5      // 帧头魔数, 防止收到非本协议数据
#define PKT_VER    1         // 协议版本

// 灾害类型枚举
#define HAZ_NONE     0       // 无 / 正常
#define HAZ_QUAKE    1       // 震动 / 地震
#define HAZ_RAIN     2       // 强降雨 / 山洪
#define HAZ_MUD      3       // 泥石流

#define MAX_REMOTES  4       // 同时跟踪的邻居数量上限

/* 空中数据包(ESP-NOW 载荷)。用定长结构体而非 JSON:
 *  - 体积小、封包/解包快, 满足 <50ms 级联同步
 *  - __attribute__((packed)) 保证结构体无填充, 收发两端字节对齐一致
 */
typedef struct __attribute__((packed)) {
  uint8_t  magic;            // 魔数 PKT_MAGIC
  uint8_t  ver;              // 协议版本
  char     node_id[12];      // 发送方节点 ID, 如 "Node_A"
  uint8_t  role;             // 发送方角色 (ROLE_*)
  uint8_t  hazard;           // 灾害类型 HAZ_*
  uint8_t  level;            // 风险等级 0/1/2
  uint8_t  risk;             // 危险指数 0-100
  uint8_t  survival;         // 预估存活率 %
  uint16_t seq;              // 包序号 (发送方每次发包自增, 用于去重)
  uint8_t  hops;             // 已转发跳数
  uint32_t ts_ms;            // 事件时间戳(ms, 使用开机毫秒; 无 RTC 所以非墙上时钟)
  uint8_t  crc;              // CRC8 校验(对整个包其余字节)
} AlertPacket;
// 注意: 包内容改动后 sizeof 变化, 接收端按 sizeof 校验长度, 无需额外字段

// 本机"当前评估结果"的本地状态
typedef struct {
  uint8_t hazard;
  uint8_t level;
  uint8_t risk;
  uint8_t survival;
} NodeState;

// 邻居(远端节点)最近一次状态
typedef struct {
  char     node_id[12];
  bool     valid;
  uint32_t last_seen_ms;     // 最近一次收到其报文的时间
  uint16_t seq;              // 最近一次 seq (用于丢弃重复帧)
  uint8_t  level;
  uint8_t  risk;
  uint8_t  survival;
  uint8_t  hazard;
} RemoteNode;

// ============================================================================
//  4. 全局变量
// ============================================================================
static const uint8_t BROADCAST_MAC[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};

char       nodeId[12]  = NODE_ID;
NodeState  localState  = {HAZ_NONE, 0, 0, 95};
RemoteNode remotes[MAX_REMOTES];

uint16_t  txSeq          = 0;          // 发送自增序号
uint32_t  lastHeartbeatMs= 0;          // 上次心跳广播时间
uint32_t  lastSendMs     = 0;          // 上次真正发送时间(限流用)
bool      stateChanged   = false;      // 本地状态变更, 需要立即广播
bool      espNowOk       = false;      // ESP-NOW 是否初始化成功

// 接收(中断回调 -> 主循环)的单缓冲交接
volatile AlertPacket g_rx;
volatile bool        g_rx_ready = false;

// 中继转发挂起队列
bool      relayPending = false;
AlertPacket relayPkt;
uint32_t  relayAtMs    = 0;

// UI
uint32_t  lastUiMs        = 0;
uint32_t  lastBlinkToggle = 0;
bool      blinkOn         = false;

// 按键状态机
bool      btnWasDown   = false;
bool      btnLongFired = false;
uint32_t  btnDownAt    = 0;

// 串口行缓冲
char      cmdLine[64];
uint8_t   cmdIdx = 0;

// ============================================================================
//  5. 小工具: CRC8 与按键
// ============================================================================

// 简单 CRC8 (多项式 0x07), 校验包完整性与帧同步
static uint8_t crc8(const uint8_t *data, size_t len) {
  uint8_t crc = 0;
  for (size_t i = 0; i < len; i++) {
    crc ^= data[i];
    for (uint8_t b = 0; b < 8; b++) {
      crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x07) : (uint8_t)(crc << 1);
    }
  }
  return crc;
}

char roleChar() {
  switch (NODE_ROLE) {
    case ROLE_B_RELAY:    return 'B';
    case ROLE_C_TERMINAL: return 'C';
    default:              return 'A';
  }
}

// 邻居在线数量(最近 REMOTE_TIMEOUT_MS 内有报文)
int aliveNeighbors() {
  uint32_t now = millis();
  int n = 0;
  for (int i = 0; i < MAX_REMOTES; i++) {
    if (remotes[i].valid && (int32_t)(now - remotes[i].last_seen_ms) < REMOTE_TIMEOUT_MS) n++;
  }
  return n;
}

int findRemote(const char *id) {
  for (int i = 0; i < MAX_REMOTES; i++) {
    if (remotes[i].valid && strcmp(remotes[i].node_id, id) == 0) return i;
  }
  return -1;
}

int allocRemote(const char *id) {
  // 优先空槽
  for (int i = 0; i < MAX_REMOTES; i++) {
    if (!remotes[i].valid) {
      memset(&remotes[i], 0, sizeof(RemoteNode));
      strncpy(remotes[i].node_id, id, sizeof(remotes[i].node_id) - 1);
      remotes[i].node_id[sizeof(remotes[i].node_id) - 1] = 0;
      remotes[i].valid = true;
      return i;
    }
  }
  // 槽满则覆盖最久未更新的
  int victim = 0;
  uint32_t oldest = 0xFFFFFFFF;
  for (int i = 0; i < MAX_REMOTES; i++) {
    if (remotes[i].last_seen_ms < oldest) { oldest = remotes[i].last_seen_ms; victim = i; }
  }
  memset(&remotes[victim], 0, sizeof(RemoteNode));
  strncpy(remotes[victim].node_id, id, sizeof(remotes[victim].node_id) - 1);
  remotes[victim].node_id[sizeof(remotes[victim].node_id) - 1] = 0;
  remotes[victim].valid = true;
  return victim;
}

// ============================================================================
//  6. 模块2: 边缘决策引擎 (危险指数 / 存活率 / 等级换算)
// ============================================================================
// 设计说明:
//  - 危险指数 risk 由"灾害类型 + 强度 intensity(0-100)"决定;
//  - 等级分级与 PRD 一致: 0-30 安全(L0), 31-70 预警(L1), 71-100 危险(L2);
//  - 存活率按 PRD 离散档: L0=95%, L1=70%, L2=25%(<40%)。
//  现场接入真实传感器后, 只要把传感器读数换算成 intensity 调用 applyScenario 即可,
//  引擎逻辑无需改动 —— 这就是"边缘决策"所在。

int calcRisk(uint8_t level, uint8_t intensity) {
  int v = intensity;
  if (level == 0) return (v * 30) / 100;          // 0..30
  if (level == 1) return 31 + (v * 39) / 100;     // 31..70
  return 71 + (v * 29) / 100;                     // 71..100
}

uint8_t calcSurvival(uint8_t level) {
  if (level == 0) return 95;
  if (level == 1) return 70;
  return 25;                                      // <40%, 符合 PRD
}

void applyScenario(uint8_t hazard, uint8_t level, uint8_t intensity) {
  if (level > 2) level = 2;
  localState.hazard   = hazard;
  localState.level    = level;
  localState.risk     = (uint8_t)calcRisk(level, intensity);
  localState.survival = calcSurvival(level);
  stateChanged = true;                            // 让主循环尽快广播出去
}

// 演示用预设场景: 安全 -> 预警(强降雨) -> 危险(泥石流)
static const uint8_t kScenHazard[3]   = {HAZ_NONE, HAZ_RAIN, HAZ_MUD};
static const uint8_t kScenLevel[3]    = {0, 1, 2};
static const uint8_t kScenIntensity[3]= {0, 55, 92};
uint8_t scenIdx = 0;

void cycleScenario() {                            // 短按: 安全->预警->危险->安全
  scenIdx = (scenIdx + 1) % 3;
  applyScenario(kScenHazard[scenIdx], kScenLevel[scenIdx], kScenIntensity[scenIdx]);
  Serial.printf("[SIM] scenario=%u hazard=%u level=%u risk=%u survival=%u%%\r\n",
                scenIdx, localState.hazard, localState.level, localState.risk, localState.survival);
}

uint8_t defaultIntensity(uint8_t level) {
  return level == 0 ? 0 : (level == 1 ? 55 : 92);
}

// ============================================================================
//  7. 模块3: ESP-NOW 通信
// ============================================================================

// 构造本机状态包 (seq 自增, crc 补齐)
void buildPacket(AlertPacket &p) {
  memset(&p, 0, sizeof(AlertPacket));
  p.magic  = PKT_MAGIC;
  p.ver    = PKT_VER;
  strncpy(p.node_id, nodeId, sizeof(p.node_id) - 1);
  p.role     = NODE_ROLE;
  p.hazard   = localState.hazard;
  p.level    = localState.level;
  p.risk     = localState.risk;
  p.survival = localState.survival;
  p.seq      = txSeq++;
  p.hops     = 0;
  p.ts_ms    = (uint32_t)millis();
  p.crc      = crc8((const uint8_t *)&p, sizeof(p) - 1);
}

// 发送(广播到 FF:FF:FF:FF:FF:FF)
void sendPacket(const AlertPacket &p) {
  if (!espNowOk) return;
  esp_now_send(BROADCAST_MAC, (const uint8_t *)&p, sizeof(AlertPacket));
  lastSendMs = millis();
}

void sendLocalState() {
  AlertPacket p;
  buildPacket(p);
  sendPacket(p);
  //Serial.printf("[TX] level=%u risk=%u surv=%u\r\n", p.level, p.risk, p.survival);
}

// 接收回调: 运行在 WiFi 任务里, 只做快速校验与拷贝, 绝不做耗时的绘制/打印
void IRAM_ATTR onEspNowRecv(const uint8_t *mac_addr, const uint8_t *data, int data_len) {
  if (data_len != (int)sizeof(AlertPacket)) return;
  AlertPacket tmp;
  memcpy(&tmp, data, sizeof(AlertPacket));
  if (tmp.magic != PKT_MAGIC) return;
  if (tmp.ver != PKT_VER) return;
  if (crc8((const uint8_t *)&tmp, sizeof(tmp) - 1) != tmp.crc) return;
  memcpy((void *)&g_rx, &tmp, sizeof(AlertPacket));
  g_rx_ready = true;
}

// 收到包后的主循环处理: 更新邻居表 -> 判断是否需要中继
void handleReceived(const AlertPacket &p) {
  if (strcmp(p.node_id, nodeId) == 0) return;     // 忽略"自己"的包(正常情况下不会收到)

  int idx = findRemote(p.node_id);
  if (idx < 0) {
    idx = allocRemote(p.node_id);
  } else {
    // 同序号 = 同一条广播被(直接/间接)收到两次, 丢弃重复, 只刷新在线时间
    if (remotes[idx].seq == p.seq) {
      remotes[idx].last_seen_ms = millis();
      return;
    }
  }

  RemoteNode &r = remotes[idx];
  r.last_seen_ms = millis();
  r.seq          = p.seq;
  r.level        = p.level;
  r.risk         = p.risk;
  r.survival     = p.survival;
  r.hazard       = p.hazard;

  // 中继节点: 把高危/普通状态再广播一次 (hops+1, 去重保证不风暴)
#if NODE_ROLE == ROLE_B_RELAY
  if (p.hops < MAX_HOPS && !relayPending) {
    relayPkt = p;
    relayPkt.hops++;
    relayPkt.crc = crc8((const uint8_t *)&relayPkt, sizeof(relayPkt) - 1);
    relayPending = true;
    relayAtMs = millis() + 20;                    // 微随机退避, 错开同发
  }
#endif
}

void initEspNow() {
  WiFi.mode(WIFI_STA);                            // 关键: 用 STA 模式但不连任何路由器
  WiFi.setSleep(false);                           // 关闭省电, 保证低延迟收发
  delay(50);
  esp_wifi_set_channel(ESPNOW_CHANNEL, WIFI_SECOND_CHAN_NONE);  // 所有节点固定信道1

  if (esp_now_init() != ESP_OK) {
    Serial.println("[ESP-NOW] init FAILED");
    espNowOk = false;
    return;
  }
  espNowOk = true;
  esp_now_register_recv_cb(onEspNowRecv);

  // 广播即发送给 ff:ff:ff:ff:ff:ff; 需把它作为一个 peer 加入
  esp_now_peer_info_t peer;
  memset(&peer, 0, sizeof(peer));
  memcpy(peer.peer_addr, BROADCAST_MAC, 6);
  peer.channel = ESPNOW_CHANNEL;                  // ifidx 默认 0 = STA
  peer.encrypt = false;
  esp_err_t e = esp_now_add_peer(&peer);
  if (e != ESP_OK && e != ESP_ERR_ESPNOW_EXIST) {
    Serial.printf("[ESP-NOW] add broadcast peer err 0x%x\r\n", e);
  }
  Serial.println("[ESP-NOW] ready (broadcast @ ch1)");
}

// ============================================================================
//  8. 模块1: 本地输入 —— 按键(短按/长按) 与 串口命令
// ============================================================================

// 短按: 循环切换 安全->预警->危险 (自动广播, 满足"按键触发50ms级联同步")
void onShortPress() {
  Serial.println("[BTN] short press");
  cycleScenario();
}

// 长按: 立即把当前本地状态广播一次
void onLongPress() {
  Serial.println("[BTN] long press -> broadcast");
  sendLocalState();
}

void updateButton() {
  uint32_t now = millis();
  bool down = (digitalRead(BTN_PIN) == LOW);      // 低电平有效
  if (down) {
    if (!btnWasDown) {
      btnWasDown = true;
      btnDownAt = now;
      btnLongFired = false;
    } else if (!btnLongFired && (now - btnDownAt >= BTN_LONG_MS)) {
      btnLongFired = true;
      onLongPress();
    }
  } else {
    if (btnWasDown) {
      if (!btnLongFired) onShortPress();          // 释放时若未触发长按则视为短按
      btnWasDown = false;
      btnLongFired = false;
    }
  }
}

void printHelp() {
  Serial.println("== AIxNode commands ==");
  Serial.println("  next           切换到下一个演示场景(安全/预警/危险)");
  Serial.println("  lvl <0|1|2>    直接设置风险等级");
  Serial.println("  hazard <none|quake|rain|mud> [intensity 0-100] [level 0-2]");
  Serial.println("  int <0-100>    设置当前强度(重算危险指数/存活率)");
  Serial.println("  send           立即广播当前状态");
  Serial.println("  info           打印本机与邻居状态");
  Serial.println("  help           帮助");
}

void printInfo() {
  Serial.printf("[INFO] id=%s role=%c  local hazard=%u level=%u risk=%u survival=%u%%\r\n",
                nodeId, roleChar(), localState.hazard, localState.level,
                localState.risk, localState.survival);
  for (int i = 0; i < MAX_REMOTES; i++) {
    if (!remotes[i].valid) continue;
    uint32_t ago = millis() - remotes[i].last_seen_ms;
    Serial.printf("[INFO]   peer %-8s lv=%u risk=%u surv=%u%% age=%ums %s\r\n",
                  remotes[i].node_id, remotes[i].level, remotes[i].risk,
                  remotes[i].survival, (unsigned)ago,
                  ((int32_t)ago < REMOTE_TIMEOUT_MS) ? "ONLINE" : "OFFLINE");
  }
}

uint8_t parseHazard(const String &tok) {
  if (tok == "none") return HAZ_NONE;
  if (tok == "quake" || tok == "earthquake") return HAZ_QUAKE;
  if (tok == "rain" || tok == "rainstorm" || tok == "flood") return HAZ_RAIN;
  if (tok == "mud" || tok == "mudslide") return HAZ_MUD;
  return 0xFF;                                     // 无法识别
}

void cmdHazard(String arg) {
  arg.trim();
  int sp = arg.indexOf(' ');
  String hTok = (sp < 0) ? arg : arg.substring(0, sp);
  hTok.toLowerCase();
  uint8_t haz = parseHazard(hTok);
  if (haz == 0xFF) { Serial.println("[CMD] hazard type? none|quake|rain|mud"); return; }

  int inten = (haz == HAZ_NONE) ? 0 : 92;          // 默认按危险级给强度
  int lv    = (haz == HAZ_NONE) ? 0 : 2;
  if (sp >= 0) {
    String a = arg.substring(sp + 1); a.trim();
    int sp2 = a.indexOf(' ');
    String iTok = (sp2 < 0) ? a : a.substring(0, sp2); iTok.trim();
    if (iTok.length() > 0) inten = iTok.toInt();
    if (sp2 >= 0) {
      String lTok = a.substring(sp2 + 1); lTok.trim();
      if (lTok.length() > 0) lv = lTok.toInt();
    }
  }
  applyScenario(haz, (uint8_t)constrain(lv, 0, 2), (uint8_t)constrain(inten, 0, 100));
  Serial.printf("[CMD] hazard=%u level=%u risk=%u survival=%u%%\r\n",
                localState.hazard, localState.level, localState.risk, localState.survival);
}

void handleCmd(const String &line) {
  String s = line; s.trim();
  if (s.length() == 0) return;
  int sp = s.indexOf(' ');
  String cmd = (sp < 0) ? s : s.substring(0, sp);
  cmd.toLowerCase();
  String arg = (sp < 0) ? "" : s.substring(sp + 1);

  if      (cmd == "help")    printHelp();
  else if (cmd == "next")    cycleScenario();
  else if (cmd == "lvl") {
    arg.trim(); int lv = arg.toInt();
    if (arg.length() == 0 || lv < 0 || lv > 2) { Serial.println("[CMD] lvl 0|1|2"); return; }
    applyScenario(localState.hazard, (uint8_t)lv, defaultIntensity((uint8_t)lv));
    Serial.printf("[CMD] level=%u risk=%u survival=%u%%\r\n",
                  localState.level, localState.risk, localState.survival);
  }
  else if (cmd == "hazard") cmdHazard(arg);
  else if (cmd == "int") {
    arg.trim(); int iv = arg.toInt();
    applyScenario(localState.hazard, localState.level, (uint8_t)constrain(iv, 0, 100));
    Serial.printf("[CMD] intensity set -> risk=%u survival=%u%%\r\n",
                  localState.risk, localState.survival);
  }
  else if (cmd == "send")  { sendLocalState(); Serial.println("[CMD] sent"); }
  else if (cmd == "info")  printInfo();
  else { Serial.println("[CMD] unknown, type 'help'"); }
}

void readSerial() {
  while (Serial.available()) {
    char ch = (char)Serial.read();
    if (ch == '\n' || ch == '\r') {
      if (cmdIdx > 0) {
        cmdLine[cmdIdx] = 0;
        handleCmd(String(cmdLine));
        cmdIdx = 0;
      }
    } else if (cmdIdx < (int)sizeof(cmdLine) - 1) {
      cmdLine[cmdIdx++] = ch;
    }
  }
}

// ============================================================================
//  8.5 逃生导航: 路网(经纬度+海拔) + 危险加权最短路径
// ============================================================================
//  演示用假想河谷路网, 每个节点含经纬度+海拔+类型, 后续可替换为真实地理数据。
//  思路: 把"危险"作为路段代价, 用 Dijkstra 求到避难所"累计危险最小"的路径,
//  等价于"存活率最高"的逃生路线。山洪/泥石流场景下海拔越低越危险 -> 自动绕开低洼。
//  注: 图规模小用 Dijkstra 即可; 换真实大规模路网时可加 A* 启发式(直线距离)。

#define NET_NUM_NODES    7
#define NET_NUM_EDGES    11
#define NET_START        0      // 出发点: 谷口(人员所在)
#define NET_GOAL         5      // 避难所
#define NET_HAZARD       2      // 灾害源: 监测点
#define LOW_ALT_M        20     // 低于此海拔 = 低洼, 山洪/泥石流时危险
#define HAZARD_RADIUS_M  120    // 灾害源影响半径(米)

#define NT_JUNCTION  0
#define NT_DETECTION 1
#define NT_SHELTER   2

typedef struct {
  const char *name;             // ASCII 名称(5x7 像素字库只支持英文)
  float lat, lon;               // 经纬度 (WGS84, 度)
  int16_t alt;                  // 海拔 (米)
  uint8_t type;                 // NT_*
} NetNode;

typedef struct {
  uint8_t a, b;                 // 无向边两端节点 id
} NetEdge;

// 假想河谷: 下游低洼(1 河滩 / 2 监测点) + 上游高地避难所(5)
static const NetNode NET_NODES[NET_NUM_NODES] = {
  /*0*/ {"VALLEY",  30.00000f, 103.00000f, 25, NT_JUNCTION},
  /*1*/ {"RIVER",   30.00018f, 103.00062f,  3, NT_JUNCTION},
  /*2*/ {"SENSOR",  30.00004f, 103.00057f,  5, NT_DETECTION},
  /*3*/ {"SLOPE",   30.00126f, 102.99969f, 40, NT_JUNCTION},
  /*4*/ {"RIDGE",   30.00216f, 102.99917f, 65, NT_JUNCTION},
  /*5*/ {"SHELTER", 30.00287f, 103.00062f, 95, NT_SHELTER},
  /*6*/ {"NORTH",   30.00054f, 103.00207f, 45, NT_JUNCTION},
};

static const NetEdge NET_EDGES[NET_NUM_EDGES] = {
  {0,1}, {1,5}, {0,3}, {3,4}, {4,5}, {1,2}, {2,3}, {0,6}, {6,5}, {1,6}, {3,6}
};

#define NAV_PI    3.14159265358979f
#define DEG2RAD   (NAV_PI / 180.0f)

// 两点间大圆距离(米), Haversine 公式
float haversineM(float lat1, float lon1, float lat2, float lon2) {
  float dLat = (lat2 - lat1) * DEG2RAD;
  float dLon = (lon2 - lon1) * DEG2RAD;
  float s1 = sin(dLat * 0.5f);
  float s2 = sin(dLon * 0.5f);
  float a = s1 * s1 + cos(lat1 * DEG2RAD) * cos(lat2 * DEG2RAD) * s2 * s2;
  if (a > 1.0f) a = 1.0f;
  return 6371000.0f * 2.0f * asin(sqrt(a));
}

// 方位角(0-360, 正北=0 顺时针)
int bearingTo(int fromNode, int toNode) {
  float lat1 = NET_NODES[fromNode].lat * DEG2RAD;
  float lat2 = NET_NODES[toNode].lat * DEG2RAD;
  float dLon = (NET_NODES[toNode].lon - NET_NODES[fromNode].lon) * DEG2RAD;
  float y = sin(dLon) * cos(lat2);
  float x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon);
  float brng = atan2(y, x) * 180.0f / NAV_PI;
  if (brng < 0) brng += 360.0f;
  return (int)brng;
}

// 方位角 -> 8 方位名
const char *dirName(int brng) {
  static const char *dirs[8] = {"N","NE","E","SE","S","SW","W","NW"};
  int idx = ((brng + 22) % 360) / 45;
  if (idx < 0) idx += 8;
  return dirs[idx];
}

// 单节点"危险系数" (>=1, 越大越危险): 由本地灾害等级 + 海拔 + 距灾害源距离决定
float nodeDanger(int n) {
  float d = 1.0f;
  if (localState.level == 0) return d;              // 安全: 只按距离
  uint8_t lv = localState.level;
  if (NET_NODES[n].alt < LOW_ALT_M) d += 2.0f * lv; // 低洼被淹
  float dh = haversineM(NET_NODES[n].lat, NET_NODES[n].lon,
                        NET_NODES[NET_HAZARD].lat, NET_NODES[NET_HAZARD].lon);
  if (dh < HAZARD_RADIUS_M) d += 3.0f * lv * (1.0f - dh / HAZARD_RADIUS_M); // 靠近源头
  return d;
}

// 边代价 = 距离 × 两端平均危险系数
float edgeCost(int a, int b) {
  float d = haversineM(NET_NODES[a].lat, NET_NODES[a].lon,
                       NET_NODES[b].lat, NET_NODES[b].lon);
  return d * (nodeDanger(a) + nodeDanger(b)) * 0.5f;
}

// Dijkstra 求 start->goal 最小累计代价路径; 返回 0 成功 / -1 不可达
int findRoute(int start, int goal, int *path, int *pathLen) {
  float dist[NET_NUM_NODES];
  int   prev[NET_NUM_NODES];
  bool  visited[NET_NUM_NODES];
  for (int i = 0; i < NET_NUM_NODES; i++) {
    dist[i] = 1e9f; prev[i] = -1; visited[i] = false;
  }
  dist[start] = 0.0f;

  for (int it = 0; it < NET_NUM_NODES; it++) {
    int u = -1; float best = 1e9f;
    for (int i = 0; i < NET_NUM_NODES; i++)
      if (!visited[i] && dist[i] < best) { best = dist[i]; u = i; }
    if (u < 0) break;
    if (u == goal) break;
    visited[u] = true;
    for (int e = 0; e < NET_NUM_EDGES; e++) {
      int a = NET_EDGES[e].a, b = NET_EDGES[e].b;
      int v = (a == u) ? b : ((b == u) ? a : -1);
      if (v < 0) continue;
      float w = edgeCost(a, b);
      if (dist[u] + w < dist[v]) { dist[v] = dist[u] + w; prev[v] = u; }
    }
  }

  if (goal != start && prev[goal] < 0) return -1;
  int rev[NET_NUM_NODES], n = 0, cur = goal;
  while (cur >= 0) { rev[n++] = cur; cur = prev[cur]; }
  for (int i = 0; i < n; i++) path[i] = rev[n - 1 - i];
  *pathLen = n;
  return 0;
}

// 路线存活率估计: 100 / 路径上最大危险系数 (排除出发点, 因已在原地无法避开)
uint8_t routeSurvival(const int *path, int len) {
  float maxD = 1.0f;
  for (int i = 1; i < len; i++) {
    float d = nodeDanger(path[i]);
    if (d > maxD) maxD = d;
  }
  int surv = (int)(100.0f / maxD + 0.5f);
  if (surv < 5) surv = 5;
  if (surv > 99) surv = 99;
  return (uint8_t)surv;
}

// ============================================================================
//  9. 模块4: OLED 界面 (128x64, U8g2 全缓冲)
// ============================================================================

const char *levelWord(uint8_t lv) {
  switch (lv) {
    case 1:  return "WARN";
    case 2:  return "DANGER";
    default: return "SAFE";
  }
}

const char *hazardName(uint8_t h) {
  switch (h) {
    case HAZ_QUAKE: return "QUAKE";
    case HAZ_RAIN:  return "RAIN";
    case HAZ_MUD:   return "MUDSLIDE";
    default:        return "";
  }
}

// 简易 3x5 点阵数字, 用于大字号存活率 (自绘, 不依赖大字体资源)
static const uint8_t kDigitRows[10][5] = {
  {7,5,5,5,7},   // 0
  {2,6,2,2,7},   // 1
  {7,1,7,4,7},   // 2
  {7,1,7,1,7},   // 3
  {5,5,7,1,1},   // 4
  {7,4,7,1,7},   // 5
  {7,4,7,5,7},   // 6
  {7,1,2,2,2},   // 7
  {7,5,7,5,7},   // 8
  {7,5,7,1,7}    // 9
};

void drawDigitBig(int x, int y, int scale, uint8_t d) {
  for (int r = 0; r < 5; r++) {
    uint8_t row = kDigitRows[d][r];
    for (int c = 0; c < 3; c++) {
      if (row & (1 << (2 - c))) u8g2.drawBox(x + c * scale, y + r * scale, scale, scale);
    }
  }
}

// 画 0-99, 返回结束 x (方便后面接 "%")
int drawNumberBig(int x, int y, int scale, uint8_t val) {
  int xcur = x;
  int tens = val / 10, ones = val % 10;
  if (val >= 10) {
    drawDigitBig(xcur, y, scale, (uint8_t)tens);
    xcur += 3 * scale + scale / 2;
  }
  drawDigitBig(xcur, y, scale, (uint8_t)ones);
  xcur += 3 * scale;
  return xcur;
}

// 指向指定方位角的完整箭头(箭杆 + 箭头), 带脉冲位移
void drawArrowDir(int cx, int cy, int len, int bearingDeg, uint8_t color) {
  float rad = bearingDeg * DEG2RAD;
  float dx = (float)sin(rad);                 // 北=0 -> (0,-1) 即向上
  float dy = -(float)cos(rad);
  float px = -dy, py = dx;                    // 法向(垂直方向)
  int ox = (px > 0.5f) - (px < -0.5f);        // 法向 1px 整数近似
  int oy = (py > 0.5f) - (py < -0.5f);
  int pulse = (int)(sin(millis() * 0.008f) + 1.0f);   // 0..2 沿方向前后脉冲

  int L = len + pulse;                        // 中心到尖端长度
  int tipX = cx + (int)(dx * L);
  int tipY = cy + (int)(dy * L);
  int hbX  = cx + (int)(dx * (L - 5));        // 箭头根部
  int hbY  = cy + (int)(dy * (L - 5));
  int tailX = cx - (int)(dx * L);             // 箭尾
  int tailY = cy - (int)(dy * L);

  u8g2.setDrawColor(color);
  // 箭杆(三线加粗)
  u8g2.drawLine(tailX, tailY, hbX, hbY);
  u8g2.drawLine(tailX + ox, tailY + oy, hbX + ox, hbY + oy);
  u8g2.drawLine(tailX - ox, tailY - oy, hbX - ox, hbY - oy);
  // 箭头(三角头 + 底边闭合)
  u8g2.drawTriangle(tipX, tipY, hbX + ox * 4, hbY + oy * 4, hbX - ox * 4, hbY - oy * 4);
  u8g2.drawLine(hbX + ox * 4, hbY + oy * 4, hbX - ox * 4, hbY - oy * 4);
}

// 危险指数动画条: 外框 + 呼吸脉冲填充
void drawRiskBar(int x, int y, int w, int h, uint8_t risk, uint8_t color) {
  u8g2.setDrawColor(color);
  u8g2.drawFrame(x, y, w, h);                        // 外框
  int inner = w - 2;
  int pulse = (int)(sin(millis() * 0.012f) * 1.0f);  // -1..1 呼吸
  int fill = inner * risk / 100 + pulse;
  if (fill < 0) fill = 0;
  if (fill > inner) fill = inner;
  u8g2.drawBox(x + 1, y + 1, fill, h - 2);           // 填充
}

// 计算"当前应显示的最严重状态"(本地 vs 邻居取最危险)
void computeEffective(NodeState &eff, bool &isRemote) {
  eff = localState;
  isRemote = false;
  uint32_t now = millis();
  for (int i = 0; i < MAX_REMOTES; i++) {
    if (!remotes[i].valid) continue;
    if ((int32_t)(now - remotes[i].last_seen_ms) >= REMOTE_TIMEOUT_MS) continue; // 已离线
    if (remotes[i].level > eff.level ||
        (remotes[i].level == eff.level && remotes[i].risk > eff.risk)) {
      eff.level    = remotes[i].level;
      eff.risk     = remotes[i].risk;
      eff.survival = remotes[i].survival;
      eff.hazard   = remotes[i].hazard;
      isRemote = true;
    }
  }
}

// 画一帧。invert=true 时整屏反色(用于危险状态闪烁告警)
void drawUi(const NodeState &eff, bool isRemote, bool invert) {
  uint8_t c = invert ? 0 : 1;                       // 前景色

  u8g2.clearBuffer();                               // 每帧先清空, 避免残影/重影
  u8g2.setDrawColor(1);
  if (invert) u8g2.drawBox(0, 0, 128, 64);          // 反白: 先整屏填白, 再画黑字
  u8g2.setDrawColor(c);

  // ---- 顶部状态栏: 节点ID + 角色 | 邻居数 + 等级 ----
  char topL[24], topR[24];
  snprintf(topL, sizeof(topL), "%c %s", roleChar(), nodeId);
  snprintf(topR, sizeof(topR), "M%d%s L%d", aliveNeighbors(),
           isRemote ? " RX" : "", (int)eff.level);
  u8g2.setFont(u8g2_font_6x10_tf);
  u8g2.drawStr(2, 9, topL);
  int w = u8g2.getStrWidth(topR);
  u8g2.drawStr(116 - w, 9, topR);
  // 心跳指示: 每次广播后短暂点亮(与 1s 心跳同步)
  if ((int32_t)(millis() - lastSendMs) < 250) u8g2.drawBox(120, 3, 3, 3);
  u8g2.drawHLine(0, 12, 128);

  // ---- 左: 危险等级大字 + 灾害类型/危险指数 ----
  u8g2.setFont(u8g2_font_10x20_tf);
  u8g2.drawStr(2, 30, levelWord(eff.level));
  u8g2.setFont(u8g2_font_5x7_tf);
  const char *hn = hazardName(eff.hazard);
  u8g2.drawStr(2, 39, hn[0] ? hn : "RISK");          // 灾害类型
  drawRiskBar(2, 42, 40, 4, eff.risk, c);            // 危险指数动画条
  char riskTxt[8];
  snprintf(riskTxt, sizeof(riskTxt), "%d", (int)eff.risk);
  u8g2.drawStr(45, 45, riskTxt);

  // ---- 右: 预估存活率 (自绘大数字) ----
  u8g2.setFont(u8g2_font_5x7_tf);
  u8g2.drawStr(64, 20, "SURVIVAL");
  int ex = drawNumberBig(64, 24, 4, eff.survival);
  u8g2.drawStr(ex + 2, 39, "%");

  // ---- 底栏: 逃生导航 (下一跳 + 方向 + 剩余距离 + 路线存活率) ----
  int navPath[NET_NUM_NODES], navLen = 0;
  findRoute(NET_START, NET_GOAL, navPath, &navLen);
  u8g2.setFont(u8g2_font_5x7_tf);
  if (navLen >= 2) {
    int next = navPath[1];
    int brng = bearingTo(NET_START, next);
    float remain = 0;
    for (int i = 0; i < navLen - 1; i++)
      remain += haversineM(NET_NODES[navPath[i]].lat, NET_NODES[navPath[i]].lon,
                           NET_NODES[navPath[i+1]].lat, NET_NODES[navPath[i+1]].lon);
    char nav1[24], nav2[24];
    snprintf(nav1, sizeof(nav1), "> %s %s", NET_NODES[next].name, dirName(brng));
    snprintf(nav2, sizeof(nav2), "%dm SURV %u%%", (int)remain, routeSurvival(navPath, navLen));
    if (eff.level > 0) drawArrowDir(11, 52, 7, brng, c);   // 指向逃生方向的脉冲箭头(加粗, 尺寸适中)
    u8g2.drawStr(22, 54, nav1);
    u8g2.drawStr(22, 60, nav2);
  } else {
    u8g2.drawStr(22, 54, "NO ROUTE");
  }
}

void renderUi() {
  uint32_t now = millis();

  NodeState eff;
  bool isRemote = false;
  computeEffective(eff, isRemote);

  bool danger = (eff.level >= 2);                   // 收到或本地高危 -> 闪屏告警
  if (danger) {
    if ((int32_t)(now - lastBlinkToggle) >= 350) {
      lastBlinkToggle = now;
      blinkOn = !blinkOn;
    }
  } else {
    blinkOn = false;
  }

  // 节流重绘 (约 12-20 fps, 避免无谓刷新)
  if ((int32_t)(now - lastUiMs) >= 80) {
    lastUiMs = now;
    drawUi(eff, isRemote, danger && blinkOn);
    u8g2.sendBuffer();
  }

  // 可选外接告警灯 / 蜂鸣器: 危险时随闪烁节奏输出
#if ALARM_PIN >= 0
  digitalWrite(ALARM_PIN, (danger && blinkOn) ? HIGH : LOW);
#endif
}

// ============================================================================
//  10. setup / loop
// ============================================================================

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println();
  Serial.printf("AIxNode boot  id=%s role=%c\r\n", nodeId, roleChar());

  // --- OLED 初始化 (4线SPI: 先初始化 SPI 总线再 begin) ---
  SPI.begin(OLED_SCK, OLED_MISO, OLED_MOSI);        // CS/DC/RES 由 u8g2 构造器管理
  if (!u8g2.begin()) {
    Serial.println("[OLED] init FAILED (check wiring/addr/model)");
  } else {
    u8g2.setFontMode(1);                            // 透明字体模式
    u8g2.clearBuffer();
    u8g2.sendBuffer();
    Serial.println("[OLED] ready");
  }

  pinMode(BTN_PIN, INPUT_PULLUP);
#if ALARM_PIN >= 0
  pinMode(ALARM_PIN, OUTPUT);
  digitalWrite(ALARM_PIN, LOW);
#endif

  applyScenario(HAZ_NONE, 0, 0);                    // 默认安全态
  stateChanged = false;                             // ESP-NOW 起来前不急着发

  initEspNow();

  lastHeartbeatMs = millis();
  lastUiMs        = millis();
}

void loop() {
  uint32_t now = millis();

  readSerial();
  updateButton();

  // 取出接收回调缓存的数据
  if (g_rx_ready) {
    g_rx_ready = false;
    AlertPacket p;
    memcpy(&p, (void *)&g_rx, sizeof(AlertPacket));
    handleReceived(p);
  }

  // 中继转发(延迟 20ms 执行)
  if (relayPending && (int32_t)(now - relayAtMs) >= 0) {
    relayPending = false;
    sendPacket(relayPkt);
  }

  // 发送策略: 状态变更立即发(限流 250ms) + 周期心跳刷新在线状态
  bool wantSend = false;
  if (stateChanged && (int32_t)(now - lastSendMs) >= 250) {
    stateChanged = false;
    wantSend = true;
  }
  if ((int32_t)(now - lastHeartbeatMs) >= HEARTBEAT_MS) {
    lastHeartbeatMs = now;
    wantSend = true;
  }
  if (wantSend) sendLocalState();

  renderUi();
}
