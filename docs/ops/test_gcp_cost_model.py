"""Regression test for docs/ops/gcp_cost_model.py. Run: python3 -m unittest docs/ops/test_gcp_cost_model.py"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gcp_cost_model as m  # noqa: E402


class CostModelTest(unittest.TestCase):
    def test_running_total(self):
        items, total = m.running_vm_month()
        self.assertAlmostEqual(items["e2-standard-2"], 62.755, places=2)
        self.assertAlmostEqual(items["pd-balanced 70 GiB"], 9.10, places=2)
        self.assertAlmostEqual(items["IPv4 in use"], 3.65, places=2)
        self.assertAlmostEqual(total, 98.98, places=1)

    def test_stopped_still_bills(self):
        _, total = m.stopped_vm_month()
        self.assertGreater(total, 0)
        self.assertAlmostEqual(total, 17.23, places=1)

    def test_cloud_sql(self):
        c, s = m.cloud_sql_month()
        self.assertAlmostEqual(c, 131.55, places=1)
        ch, _ = m.cloud_sql_month(ha=True)
        self.assertAlmostEqual(ch, 2 * c, places=1)

    def test_legacy_example_reproduced(self):
        self.assertAlmostEqual(m.legacy_cloud_sql_example(), 101.178, places=2)


if __name__ == "__main__":
    unittest.main()
