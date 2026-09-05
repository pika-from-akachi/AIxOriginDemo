import importlib.util
import json
from pathlib import Path
import random
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "micropython"))
from signal_processing import AdaptiveFilter, block_stats, calibration_check, level_percent


class SignalTests(unittest.TestCase):
    def test_isolated_impulses_do_not_become_a_water_step(self):
        f = AdaptiveFilter()
        f.update([2800] * 31)
        filtered, raw, _ = f.update([0, 65535] + [2800] * 29)
        self.assertEqual((filtered, raw), (2800, 2800))

    def test_sustained_rise_and_fall_are_not_filtered_away(self):
        for start, end in ((2800, 18000), (18000, 2800)):
            f = AdaptiveFilter()
            f.update([start] * 31)
            for _ in range(7):
                value, _, _ = f.update([end] * 31)
            self.assertLess(abs(value - end), abs(end - start) * 0.1)

    def test_same_voltage_cannot_be_calibrated(self):
        cal = calibration_check([2800] * 40, [2800] * 40)
        self.assertFalse(cal["valid"])
        self.assertIsNone(level_percent(2800, cal))

    def test_overlapping_noise_cannot_be_calibrated(self):
        rng = random.Random(10)
        dry = [round(rng.gauss(2800, 180)) for _ in range(80)]
        wet = [round(rng.gauss(2850, 180)) for _ in range(80)]
        self.assertFalse(calibration_check(dry, wet)["valid"])

    def test_measured_one_cm_capture_has_clear_response(self):
        data = json.loads((ROOT / "tests/fixtures/gpio5_dry_wet_1cm.json").read_text())
        self.assertEqual(data["metadata"]["wet_depth_cm"], 1)
        self.assertFalse(data["metadata"]["planned_depth_calibrated"])
        cal = calibration_check(data["dry"], data["wet"])
        self.assertTrue(cal["valid"])
        self.assertEqual(cal["dry"], 0)
        self.assertAlmostEqual(cal["wet"], 15659.25)
        self.assertEqual(level_percent(cal["wet"], cal), 100)

    def test_reverse_sensor_response_and_clipping(self):
        cal = calibration_check([18000] * 40, [2800] * 40)
        self.assertTrue(cal["valid"])
        self.assertEqual(level_percent(18000, cal), 0)
        self.assertEqual(level_percent(2800, cal), 100)
        self.assertEqual(level_percent(10400, cal), 50)
        self.assertEqual(level_percent(0, cal), 100)
        self.assertEqual(level_percent(65535, cal), 0)


class DeploymentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("water_node_tool", ROOT / "tools/node.py")
        cls.tool = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.tool)

    def test_deployment_never_copies_fixture_or_calibration(self):
        args = self.tool.command_args("COM99", "deploy")
        remote_files = [a for a in args if a.startswith(":")]
        self.assertEqual(remote_files, [":ssd1306.py", ":signal_processing.py",
                                        ":water_monitor.py", ":main.py"])
        self.assertEqual(args[-1], "reset")
        self.assertIn("COM99", args)

    def test_calibration_is_explicit_not_assumed_at_boot(self):
        args = self.tool.command_args("COM99", "calibrate", "wet")
        self.assertIn("capture('wet')", args[-1])
        self.assertNotIn("erase-flash", args)


if __name__ == "__main__":
    unittest.main()
