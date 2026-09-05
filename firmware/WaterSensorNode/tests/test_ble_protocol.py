import sys
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "micropython"))

from ble_protocol import (
    FLAG_CALIBRATED,
    FLAG_INPUT_DIRECT,
    advertising_packet,
    decode_payload,
    encode_payload,
    extract_from_advertising,
)


class BleProtocolTests(unittest.TestCase):
    def test_round_trip_and_exact_legacy_packet_size(self):
        flags = FLAG_CALIBRATED | FLAG_INPUT_DIRECT
        payload = encode_payload(flags, 1, 0x1234, 15659, 806, 100, 10, 1191)
        packet = advertising_packet(payload)
        self.assertEqual(len(payload), 16)
        self.assertEqual(len(packet), 31)
        self.assertEqual(decode_payload(payload), extract_from_advertising(packet))
        decoded = decode_payload(payload)
        self.assertEqual(decoded["sequence"], 0x1234)
        self.assertEqual(decoded["raw16"], 15659)
        self.assertEqual(decoded["reference_depth_mm"], 10)

    def test_invalid_percent_uses_ff(self):
        decoded = decode_payload(encode_payload(0, 2, 1, 0, 0, None, 10, 0))
        self.assertIsNone(decoded["percent"])

    def test_crc_detects_corruption(self):
        payload = bytearray(encode_payload(0, 1, 1, 1, 1, None, 10, 1))
        payload[8] ^= 1
        with self.assertRaisesRegex(ValueError, "CRC"):
            decode_payload(payload)


if __name__ == "__main__":
    unittest.main()
