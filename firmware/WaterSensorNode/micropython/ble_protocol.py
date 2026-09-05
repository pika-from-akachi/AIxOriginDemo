"""AIx Water BLE advertising protocol v1."""

import struct


SERVICE_UUID16 = 0xFFF0
MAGIC = b"AW"
VERSION = 1
DEVICE_NAME = b"AIxWtr"

FLAG_CALIBRATED = 0x01
FLAG_ADC_SATURATED = 0x02
FLAG_OLED_PRESENT = 0x04
FLAG_WATER_DETECTED = 0x08
FLAG_INPUT_DIRECT = 0x10
FLAG_DEPTH_CURVE = 0x20
FLAG_BUZZER_ACTIVE = 0x40


def crc8(data):
    """CRC-8/ATM: polynomial 0x07, init 0x00, no reflection/xorout."""
    crc = 0
    for value in data:
        crc ^= value
        for _ in range(8):
            crc = ((crc << 1) ^ 0x07) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


def encode_payload(flags, node_id, sequence, raw16, millivolts, percent,
                   reference_depth_mm, noise):
    percent_byte = 0xFF if percent is None else max(0, min(100, int(percent)))
    body = struct.pack(
        "<2sBBBHHHBBH",
        MAGIC,
        VERSION,
        flags & 0xFF,
        node_id & 0xFF,
        sequence & 0xFFFF,
        max(0, min(0xFFFF, int(raw16))),
        max(0, min(0xFFFF, int(millivolts))),
        percent_byte,
        max(0, min(0xFF, int(reference_depth_mm))),
        max(0, min(0xFFFF, int(noise))),
    )
    return body + bytes((crc8(body),))


def decode_payload(payload):
    if len(payload) != 16:
        raise ValueError("water payload must be 16 bytes")
    if crc8(payload[:-1]) != payload[-1]:
        raise ValueError("water payload CRC mismatch")
    magic, version, flags, node_id, sequence, raw16, mv, percent, depth, noise = \
        struct.unpack("<2sBBBHHHBBH", payload[:-1])
    if magic != MAGIC:
        raise ValueError("water payload magic mismatch")
    return {
        "version": version,
        "flags": flags,
        "node_id": node_id,
        "sequence": sequence,
        "raw16": raw16,
        "millivolts": mv,
        "percent": None if percent == 0xFF else percent,
        "reference_depth_mm": depth,
        "noise": noise,
    }


def advertising_packet(payload):
    def field(ad_type, value):
        return bytes((len(value) + 1, ad_type)) + value

    packet = b"".join((
        field(0x01, b"\x06"),                    # BLE flags
        field(0x09, DEVICE_NAME),                 # Complete local name
        field(0x16, struct.pack("<H", SERVICE_UUID16) + payload),
    ))
    if len(packet) > 31:
        raise ValueError("legacy advertising packet exceeds 31 bytes")
    return packet


def extract_from_advertising(packet):
    """Decode this protocol from a complete legacy advertising byte string."""
    offset = 0
    while offset < len(packet):
        length = packet[offset]
        if length == 0:
            break
        end = offset + length + 1
        if end > len(packet):
            raise ValueError("truncated advertising field")
        ad_type = packet[offset + 1]
        value = packet[offset + 2:end]
        if ad_type == 0x16 and len(value) == 18 and struct.unpack("<H", value[:2])[0] == SERVICE_UUID16:
            return decode_payload(value[2:])
        offset = end
    raise ValueError("AIx Water service data not found")
