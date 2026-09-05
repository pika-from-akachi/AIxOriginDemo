"""Deploy or calibrate the water node using an explicitly selected serial port."""
import argparse
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def command_args(port, action, state=None):
    args = [sys.executable, "-m", "mpremote", "connect", port]
    if action == "deploy":
        # Dependencies first, boot entry point last. Calibration is never copied.
        for name in (
            "ssd1306.py", "signal_processing.py", "water_monitor.py",
            "ble_protocol.py", "ble_water_node.py", "main.py",
        ):
            args += ["fs", "cp", str(ROOT / "micropython" / name), ":" + name, "+"]
        args += ["reset"]
    elif action == "calibrate":
        args += ["exec", "from water_monitor import capture; capture(%r)" % state]
    elif action == "calibrate-point":
        args += ["exec", "from water_monitor import capture_depth; capture_depth(%d)" % state]
    elif action == "status":
        args += ["exec", "from water_monitor import WaterMonitor; m=WaterMonitor(); "
                 "m.update(); print(m.adc); print(m.last)"]
    else:
        args += ["reset"]
    return args


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", required=True, help="Explicit port, e.g. COM4 or /dev/ttyACM0")
    sub = parser.add_subparsers(dest="action", required=True)
    sub.add_parser("deploy", help="Copy application files; does not erase or flash firmware")
    sub.add_parser("status", help="Read one live frame, then restart the application")
    sub.add_parser("reset")
    cal = sub.add_parser("calibrate", help="User must already hold the requested physical state")
    cal.add_argument("state", choices=("dry", "wet"))
    point = sub.add_parser(
        "calibrate-point",
        help="Capture one point for nonlinear depth correction",
    )
    point.add_argument("depth_mm", type=int, choices=(0, 5, 10, 15, 20, 25, 30))
    args = parser.parse_args()
    if args.action == "calibrate":
        print("Capturing %s for about 10 seconds; keep the physical state fixed." % args.state,
              flush=True)
    if args.action == "calibrate-point":
        print("Capturing depth %d mm for about 10 seconds; keep it fixed."
              % args.depth_mm, flush=True)
    value = getattr(args, "state", getattr(args, "depth_mm", None))
    result = subprocess.run(command_args(args.port, args.action, value))
    if args.action in ("calibrate", "calibrate-point", "status"):
        # A raw-REPL operation pauses main.py. Resume the live display even on errors.
        reset = subprocess.run(command_args(args.port, "reset"))
        if result.returncode == 0 and reset.returncode != 0:
            return reset.returncode
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
