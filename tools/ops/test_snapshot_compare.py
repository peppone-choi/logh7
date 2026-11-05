"""Checks for snapshot_compare.py. Author: Peppone Choi."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import snapshot_compare  # noqa: E402

BASE = ("WorldSnapshot(revision=10, session=SessionSnapshot(generation=1, gameSeconds=500, strategyTicks=3), "
        "navigators={a=Navigator(characterId=1, grid=2018, mcp=900)}, planetStocks=PlanetStocks(nationalTreasuries={2=7}))")


class SnapshotCompareTest(unittest.TestCase):
    def test_clock_fields_are_masked_but_state_is_not(self):
        later = BASE.replace("revision=10", "revision=12").replace("gameSeconds=500", "gameSeconds=800")
        self.assertEqual([], snapshot_compare.compare(BASE, later))
        moved = BASE.replace("grid=2018", "grid=2017")
        self.assertTrue(snapshot_compare.compare(BASE, moved))
        richer = BASE.replace("{2=7}", "{2=8}")
        self.assertTrue(snapshot_compare.compare(BASE, richer))

    def test_a_snapshot_without_world_state_is_never_equal(self):
        empty = "WorldSnapshot(revision=1, navigators={}, planetStocks=null)"
        self.assertTrue(snapshot_compare.compare(empty, empty))
        for junk in ("Unauthorized", "", "<html>error</html>"):
            self.assertTrue(snapshot_compare.compare(junk, junk), junk)

    def test_stores_compare_mail_siblings_but_not_locks_or_owner_markers(self):
        import tempfile
        with tempfile.TemporaryDirectory() as temp:
            roots = [Path(temp) / name / "characters" for name in ("a", "b")]
            for root in roots:
                root.mkdir(parents=True)
                (root / "characters.properties.mail.session-1").write_text("letter")
                (root / "characters.properties.mail.session-1.lock").write_text("")
            (roots[1] / "characters.properties.navigation.world-owner").write_text("other path")
            self.assertEqual(snapshot_compare.stores(roots[0].parent), snapshot_compare.stores(roots[1].parent))
            (roots[1] / "characters.properties.mail.session-1").write_text("lost")
            self.assertNotEqual(snapshot_compare.stores(roots[0].parent), snapshot_compare.stores(roots[1].parent))


if __name__ == "__main__":
    unittest.main()
