"""Scan and decode AIx Water BLE advertisements with Bleak."""

import argparse
import asyncio
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "micropython"))

from ble_protocol import decode_payload


UUID_SUFFIX = "fff0-0000-1000-8000-00805f9b34fb"


async def scan(seconds):
    from bleak import BleakScanner

    found = await BleakScanner.discover(timeout=seconds, return_adv=True)
    matches = 0
    for device, advertisement in found.values():
        for uuid, payload in advertisement.service_data.items():
            if uuid.lower().endswith(UUID_SUFFIX):
                try:
                    decoded = decode_payload(bytes(payload))
                except ValueError as exc:
                    print("INVALID", device.address, exc)
                    continue
                matches += 1
                print(device.address, advertisement.rssi, decoded)
    if matches == 0:
        print("No valid AIx Water broadcast found")
        return 1
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seconds", type=float, default=8)
    args = parser.parse_args()
    return asyncio.run(scan(args.seconds))


if __name__ == "__main__":
    raise SystemExit(main())
