"""Keep a two-client GUI group exclusive from other GUI tests. Author: Peppone Choi."""
import subprocess
import sys
import threading
import unittest
from unittest.mock import patch
from qa_gui_group import GuiTestGroup, NAME, assert_no_gui_group


@unittest.skipUnless(sys.platform == 'win32', 'Windows named mutex required')
class GuiTestGroupTest(unittest.TestCase):
    def test_distinct_copies_and_cleanup_before_unlock(self):
        with GuiTestGroup() as group:
            first, second = group.claim('first-client'), group.claim('second-client')
            with self.assertRaises(RuntimeError): group.claim('third-client')
            with self.assertRaises(RuntimeError): group.__exit__()
            first.close(); second.close()
        assert_no_gui_group()

    def test_three_copy_group_admits_exactly_three(self):
        with self.assertRaises(ValueError): GuiTestGroup(copies=4)
        with GuiTestGroup(copies=3) as group:
            leases = [group.claim(name) for name in ('first-client', 'second-client', 'third-client')]
            with self.assertRaises(RuntimeError): group.claim('fourth-client')
            for lease in leases: lease.close()

    def test_same_copy_cannot_be_reused_after_member_exits(self):
        with GuiTestGroup() as group:
            first = group.claim('first-client'); first.close()
            with self.assertRaises(RuntimeError): group.claim('FIRST-CLIENT')

    def test_reconnect_reclaims_only_released_copy_and_retains_two_copy_limit(self):
        with GuiTestGroup(reusable=True) as group:
            first, second = group.claim('first-client'), group.claim('second-client')
            with self.assertRaises(RuntimeError): group.claim('FIRST-CLIENT')
            first.close()
            resumed = group.claim('FIRST-CLIENT')
            with self.assertRaises(RuntimeError): group.claim('third-client')
            resumed.close(); second.close()

    def test_other_process_cannot_acquire_gui_mutex(self):
        with GuiTestGroup():
            code = ('import win32event;h=win32event.CreateMutex(None,False,' + repr(NAME) + ');'
                    'v=win32event.WaitForSingleObject(h,0);h.Close();raise SystemExit(0 if v==258 else 1)')
            result = subprocess.run([sys.executable, '-B', '-c', code], timeout=10,
                                    creationflags=subprocess.CREATE_NO_WINDOW)
            self.assertEqual(0, result.returncode)

    def test_inactive_or_foreign_process_group_cannot_launch(self):
        group = GuiTestGroup()
        with self.assertRaises(RuntimeError): group.claim('first-client')
        with group:
            with patch('qa_gui_group.os.getpid', return_value=group._pid + 1):
                with self.assertRaises(RuntimeError): group.claim('first-client')

    def test_standalone_and_recursive_groups_cannot_bypass_limit(self):
        with GuiTestGroup():
            with self.assertRaises(RuntimeError): assert_no_gui_group()
            with self.assertRaises(RuntimeError): GuiTestGroup().__enter__()
        assert_no_gui_group()

    def test_failure_stops_group_and_prevents_late_member_launch(self):
        with GuiTestGroup() as group:
            first = group.claim('first-client')
            group.request_stop()
            self.assertTrue(group.stopping())
            with self.assertRaises(RuntimeError): group.claim('second-client')
            first.close()

    def test_worker_cannot_release_coordinator_lock(self):
        errors = []
        with GuiTestGroup() as group:
            def worker():
                try: group.__exit__()
                except RuntimeError as error: errors.append(str(error))
            task = threading.Thread(target=worker); task.start(); task.join(5)
            self.assertFalse(task.is_alive()); self.assertEqual(1, len(errors))


if __name__ == '__main__': unittest.main()
