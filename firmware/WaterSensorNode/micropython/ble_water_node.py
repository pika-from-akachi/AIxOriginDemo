"""GPIO5 water sensor -> optional OLED + non-connectable BLE broadcasts."""

import bluetooth
import json
from machine import ADC, PWM, Pin, SPI
import time

from ble_protocol import (
    FLAG_ADC_SATURATED,
    FLAG_BUZZER_ACTIVE,
    FLAG_CALIBRATED,
    FLAG_DEPTH_CURVE,
    FLAG_INPUT_DIRECT,
    FLAG_OLED_PRESENT,
    FLAG_WATER_DETECTED,
    advertising_packet,
    encode_payload,
)
from signal_processing import (
    AdaptiveFilter,
    calibration_check,
    depth_curve_check,
    level_percent,
    level_percent_curve,
    select_buzzer_mode,
)


NODE_ID = 1
ADC_GPIO = 5
OLED_SCK_GPIO = 12
OLED_MOSI_GPIO = 11
OLED_CS_GPIO = 10
OLED_DC_GPIO = 7
OLED_RES_GPIO = 8
OLED_RETRY_MS = 5_000
BUZZER_GPIO = 6
HIGH_WATER_TONE_HZ = 1_600
HIGH_BEEP_ON_MS = 90
HIGH_BEEP_OFF_MS = 60
# Original short alert tune: (frequency Hz, duration ms); zero frequency is a rest.
NOTICE_MELODY = (
    (659, 160), (784, 160), (988, 240), (0, 80),
    (880, 160), (784, 160), (659, 240), (587, 160),
    (659, 160), (784, 260), (0, 120), (523, 160),
    (659, 160), (880, 220), (784, 300), (0, 180),
)
REFERENCE_DEPTH_MM = 30  # The wet calibration point is exactly 3 cm immersion.
ADVERTISING_INTERVAL_US = 250_000
UPDATE_INTERVAL_MS = 1_000
CALIBRATION_FILE = "water_calibration_gpio5_3cm.json"
CURVE_FILE = "water_depth_curve_gpio5_3cm.json"
INPUT_IS_DIRECT = True

LEVEL_WORDS = ("SAFE", "WARN", "DANGER")
HAZARD_NAME = "RAIN"

# 3x5 点阵数字，用于大字号存活率（对齐 AIxNode 的 kDigitRows）
DIGIT_ROWS = (
    (7, 5, 5, 5, 7),
    (2, 6, 2, 2, 7),
    (7, 1, 7, 4, 7),
    (7, 1, 7, 1, 7),
    (5, 5, 7, 1, 1),
    (7, 4, 7, 1, 7),
    (7, 4, 7, 5, 7),
    (7, 1, 2, 2, 2),
    (7, 5, 7, 5, 7),
    (7, 5, 7, 1, 7),
)


def water_level(percent):
    if percent is None:
        return 0
    if percent > 60:
        return 2
    if percent >= 20:
        return 1
    return 0


def draw_digit_big(display, x, y, scale, digit):
    for row in range(5):
        bits = DIGIT_ROWS[digit][row]
        for col in range(3):
            if bits & (1 << (2 - col)):
                display.fill_rect(x + col * scale, y + row * scale, scale, scale, 1)


def draw_number_big(display, x, y, scale, value):
    value = max(0, min(99, int(value)))
    xcur = x
    if value >= 10:
        draw_digit_big(display, xcur, y, scale, value // 10)
        xcur += 3 * scale + scale // 2
    draw_digit_big(display, xcur, y, scale, value % 10)
    return xcur + 3 * scale


class BleWaterNode:
    def __init__(self):
        Pin(ADC_GPIO, Pin.IN, pull=None)
        self.adc = ADC(Pin(ADC_GPIO), atten=ADC.ATTN_11DB)
        self.filter = AdaptiveFilter()
        self.calibration = self._load_calibration()
        self.depth_curve = self._load_depth_curve()
        self.buzzer = PWM(Pin(BUZZER_GPIO), freq=HIGH_WATER_TONE_HZ, duty_u16=0)
        self.buzzer_mode = "off"
        self._beep_on = False
        self._melody_index = 0
        self._next_note_ms = time.ticks_ms()
        self.sequence = 0
        self.ble = bluetooth.BLE()
        self.ble.active(True)
        self._last_oled_probe_ms = time.ticks_ms()
        self.oled = self._open_oled()
        self.last = None

    def _load_calibration(self):
        try:
            with open(CALIBRATION_FILE) as file:
                data = json.load(file)
            if data.get("gpio") != ADC_GPIO or data.get("atten") != "11DB":
                return None
            checked = calibration_check(data["dry"], data["wet"])
            return checked if checked["valid"] else None
        except (OSError, ValueError, KeyError, TypeError):
            return None

    def _load_depth_curve(self):
        try:
            with open(CURVE_FILE) as file:
                data = json.load(file)
            if data.get("gpio") != ADC_GPIO or data.get("atten") != "11DB":
                return None
            checked = depth_curve_check(data["records"])
            return checked if checked["valid"] else None
        except (OSError, ValueError, KeyError, TypeError):
            return None

    def _open_oled(self):
        try:
            from ssd1306 import SSD1309_SPI
            spi = SPI(
                1,
                baudrate=10_000_000,
                polarity=0,
                phase=0,
                sck=Pin(OLED_SCK_GPIO),
                mosi=Pin(OLED_MOSI_GPIO),
            )
            display = SSD1309_SPI(
                128, 64, spi,
                dc=Pin(OLED_DC_GPIO),
                res=Pin(OLED_RES_GPIO),
                cs=Pin(OLED_CS_GPIO),
            )
            display.contrast(80)
            display.fill(0)
            display.text("OLED ONLINE", 16, 24)
            display.show()
            print(
                "OLED_ONLINE controller=SSD1309 spi=SPI1 sck=GPIO%d mosi=GPIO%d"
                % (OLED_SCK_GPIO, OLED_MOSI_GPIO)
            )
            return display
        except Exception as exc:
            print("OLED_DISABLED", repr(exc))
            return None

    def _retry_oled(self):
        if self.oled is not None:
            return
        now = time.ticks_ms()
        if time.ticks_diff(now, self._last_oled_probe_ms) >= OLED_RETRY_MS:
            self._last_oled_probe_ms = now
            self.oled = self._open_oled()

    def sample(self):
        samples = []
        millivolts = []
        for _ in range(31):
            samples.append(self.adc.read_u16())
            millivolts.append(self.adc.read_uv() // 1000)
            time.sleep_ms(3)
        filtered, raw, noise = self.filter.update(samples)
        mv_sorted = sorted(millivolts)
        mv = round(sum(mv_sorted[7:-7]) / len(mv_sorted[7:-7]))
        saturated = raw >= 65_000
        if self.depth_curve:
            percent = level_percent_curve(filtered, self.depth_curve)
        else:
            percent = level_percent(filtered, self.calibration) if self.calibration else None
        if saturated:
            percent = None
        flags = 0
        if self.depth_curve or self.calibration:
            flags |= FLAG_CALIBRATED
        if saturated:
            flags |= FLAG_ADC_SATURATED
        if self.oled:
            flags |= FLAG_OLED_PRESENT
        if percent is not None and percent > 5:
            flags |= FLAG_WATER_DETECTED
        if INPUT_IS_DIRECT:
            flags |= FLAG_INPUT_DIRECT
        if self.depth_curve:
            flags |= FLAG_DEPTH_CURVE
        return {
            "flags": flags,
            "raw": raw,
            "filtered": filtered,
            "mv": mv,
            "percent": percent,
            "noise": noise,
        }

    def _update_buzzer(self, reading):
        new_mode = select_buzzer_mode(reading["percent"], self.buzzer_mode)
        if new_mode != self.buzzer_mode:
            self.buzzer_mode = new_mode
            if new_mode == "off":
                self.buzzer.duty_u16(0)
            elif new_mode == "high":
                self._beep_on = False
                self._next_note_ms = time.ticks_ms()
            else:
                self._melody_index = 0
                self._next_note_ms = time.ticks_ms()
        self._service_buzzer()
        reading["buzzer"] = self.buzzer_mode != "off"
        reading["buzzer_mode"] = self.buzzer_mode
        if reading["buzzer"]:
            reading["flags"] |= FLAG_BUZZER_ACTIVE

    def _service_buzzer(self):
        now = time.ticks_ms()
        if self.buzzer_mode == "high":
            if time.ticks_diff(now, self._next_note_ms) < 0:
                return
            if self._beep_on:
                self.buzzer.duty_u16(0)
                self._next_note_ms = time.ticks_add(now, HIGH_BEEP_OFF_MS)
            else:
                self.buzzer.freq(HIGH_WATER_TONE_HZ)
                self.buzzer.duty_u16(32_768)
                self._next_note_ms = time.ticks_add(now, HIGH_BEEP_ON_MS)
            self._beep_on = not self._beep_on
            return
        if self.buzzer_mode != "melody":
            return
        if time.ticks_diff(now, self._next_note_ms) < 0:
            return
        frequency, duration = NOTICE_MELODY[self._melody_index]
        self._melody_index = (self._melody_index + 1) % len(NOTICE_MELODY)
        if frequency:
            self.buzzer.freq(frequency)
            self.buzzer.duty_u16(32_768)
        else:
            self.buzzer.duty_u16(0)
        self._next_note_ms = time.ticks_add(now, duration)

    def publish(self):
        self._retry_oled()
        reading = self.sample()
        self._update_buzzer(reading)
        payload = encode_payload(
            reading["flags"], NODE_ID, self.sequence, reading["filtered"],
            reading["mv"], reading["percent"], REFERENCE_DEPTH_MM, reading["noise"]
        )
        packet = advertising_packet(payload)
        self.ble.gap_advertise(
            ADVERTISING_INTERVAL_US, adv_data=packet, connectable=False
        )
        reading["sequence"] = self.sequence
        reading["packet"] = packet
        self.sequence = (self.sequence + 1) & 0xFFFF
        self.last = reading
        self._draw(reading)
        print(
            "BLE_WATER seq=%d raw=%d mv=%d level=%s alarm=%s noise=%d flags=0x%02x adv=%s"
            % (
                reading["sequence"], reading["filtered"], reading["mv"],
                "NA" if reading["percent"] is None else "%d%%" % reading["percent"],
                reading["buzzer_mode"].upper(),
                reading["noise"], reading["flags"], packet.hex(),
            )
        )
        return reading

    def _draw(self, reading):
        if self.oled is None:
            return
        display = self.oled
        percent = reading["percent"]
        level = water_level(percent)
        risk = percent if percent is not None else 0
        survival = 100 - risk if percent is not None else 0

        display.fill(0)

        # 顶部状态栏：节点 ID + 水位等级
        display.text("W N%02d" % NODE_ID, 0, 0)
        lvl_text = "LV%d" % level
        display.text(lvl_text, 128 - len(lvl_text) * 8, 0)
        display.hline(0, 9, 128, 1)

        # 左：等级大字（横向偏移 1px 模拟加粗）
        word = LEVEL_WORDS[level]
        display.text(word, 0, 12)
        display.text(word, 1, 12)

        # 右：存活率标签 + 自绘大数字（靠右，避开左侧数值）
        display.text("SURVIVAL", 64, 12)
        ex = draw_number_big(display, 72, 20, 3, survival)
        display.text("%", ex + 2, 27)

        # 左：灾害类型 + 危险指数数值（同行）+ 动画条
        display.text(HAZARD_NAME, 0, 24)
        display.text("%d" % risk, 32, 24)
        display.rect(0, 34, 40, 4, 1)
        display.fill_rect(1, 35, max(0, min(38, round(38 * risk / 100))), 2, 1)

        # 底部：降雨量进度条
        display.text("RAIN", 0, 42)
        pct_text = "%d%%" % risk
        display.text(pct_text, 128 - len(pct_text) * 8, 42)
        display.rect(0, 52, 128, 11, 1)
        display.fill_rect(1, 53, max(0, min(126, round(126 * risk / 100))), 9, 1)

        display.show()

    def run(self):
        print("AIx Water BLE broadcaster started; GPIO5; OLED optional")
        while True:
            start = time.ticks_ms()
            try:
                self.publish()
            except Exception as exc:
                print("BLE_WATER_ERROR", repr(exc))
            deadline = time.ticks_add(start, UPDATE_INTERVAL_MS)
            while time.ticks_diff(deadline, time.ticks_ms()) > 0:
                self._service_buzzer()
                time.sleep_ms(20)


def run():
    BleWaterNode().run()
