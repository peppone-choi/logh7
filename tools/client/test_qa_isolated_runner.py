import argparse
import ast
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location('runner', Path(__file__).with_name('qa_isolated_runner.py'))
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class IsolationTests(unittest.TestCase):
    def test_fixture_processes_disable_site_initialization(self):
        directory = Path(__file__).parent
        for filename in ('qa_isolated_runner.py', 'qa_silent_fixture.py'):
            tree = ast.parse((directory / filename).read_text())
            commands = [node for node in ast.walk(tree) if isinstance(node, ast.List)
                        and any(isinstance(item, ast.Constant) and item.value == '-I'
                                for item in node.elts)]
            self.assertEqual(len(commands), 1, filename)
            options = [item.value for item in commands[0].elts if isinstance(item, ast.Constant)]
            self.assertIn('-S', options, filename)

    def test_runtime_inventory_includes_pth(self):
        tree = ast.parse(Path(__file__).with_name('qa_isolated_runner.py').read_text())
        suffixes = [node.comparators[0] for node in ast.walk(tree)
                    if isinstance(node, ast.Compare) and isinstance(node.ops[0], ast.In)
                    and isinstance(node.comparators[0], ast.Tuple)
                    and any(isinstance(item, ast.Constant) and item.value == '.pyd'
                            for item in node.comparators[0].elts)]
        self.assertEqual(len(suffixes), 1)
        self.assertIn('.pth', ast.literal_eval(suffixes[0]))

    def test_effective_ui_gate(self):
        record = dict(pid=1, in_named_job=True, effective_ui_flags=255,
                      atom_isolated=True, desktop_creation_denied=True, create_desktop_err=5)
        self.assertTrue(runner.ui_enforced([record], {1: 'created'}, 'desktop-denial'))
        for change in (dict(effective_ui_flags=0), dict(atom_isolated=False),
                       dict(create_desktop_err=1), dict(in_named_job=False)):
            self.assertFalse(runner.ui_enforced([dict(record, **change)], {1: 'created'}, 'desktop-denial'))
        self.assertFalse(runner.ui_enforced([], {1: 'created'}, 'success'))
        self.assertFalse(runner.ui_enforced([record], {1: 'created', 2: 'missing'}, 'success'))

    def test_manifest_base_path_mismatch(self):
        repo = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as temporary:
            manifest = Path(temporary) / 'manifest.json'
            data = runner.inventory(repo)
            data['fixture_executable'] = 'unreviewed-launcher.exe'
            manifest.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, 'UNREVIEWED_RUNTIME'):
                runner.validate(repo, manifest, Path(__file__).with_name('qa_silent_fixture.py'))

    def test_structural_gate(self):
        state = dict(runner_session=0, active_console_session=1, sessions=[(0, 4), (1, 0)], winsta='Service-private', flags=0)
        self.assertTrue(runner.isolated(state))
        for patch in [dict(active_console_session=0), dict(sessions=[(0, 0)]), dict(sessions=[(0, 1)]), dict(winsta='WinSta0'), dict(flags=1)]:
            with self.subTest(patch=patch):
                self.assertFalse(runner.isolated(dict(state, **patch)))

    def test_mutex_literal(self):
        self.assertEqual(runner.MUTEX, 'Local' + chr(92) + 'OpenClaw_GUI_TEST_SHARED_PEPPONE_CHOI_PC')

    def test_manifest_exact_and_fixture_path(self):
        repo = Path(__file__).resolve().parents[2]
        fixture = Path(__file__).with_name('qa_silent_fixture.py')
        with tempfile.TemporaryDirectory() as temporary:
            manifest = Path(temporary) / 'manifest.json'
            manifest.write_text(json.dumps(runner.inventory(repo)))
            runner.validate(repo, manifest, fixture)
            with self.assertRaises(ValueError):
                runner.validate(repo, manifest, Path(__file__))
            manifest.write_text('{}')
            with self.assertRaisesRegex(ValueError, 'UNREVIEWED_RUNTIME'):
                runner.validate(repo, manifest, fixture)

    def test_manifest_failure_never_imports_windows(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = argparse.Namespace(report=Path(temporary)/'report.json', repo=Path(temporary), manifest=Path(temporary)/'missing', fixture=Path(temporary)/'fixture', product_logh=False)
            with mock.patch.object(runner, 'validate', side_effect=ValueError('UNREVIEWED_RUNTIME')):
                result = runner.run(args)
            self.assertEqual(result['status'], 'FAIL')
            self.assertEqual(result['owned_processes'], [])
            self.assertEqual(json.loads(args.report.read_text())['error'], 'UNREVIEWED_RUNTIME')
            with self.assertRaises(FileExistsError):
                runner.run(args)

    def test_product_routes_only_to_prestart_runtime(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = argparse.Namespace(report=Path(temporary)/'report.json', repo=Path(temporary), manifest=Path(temporary)/'missing', fixture=Path(temporary)/'fixture', product_logh=True)
            with mock.patch('qa_prestart_runner.run', return_value={'status': 'WAIT_TEST'}) as product:
                result = runner.run(args)
            product.assert_called_once_with(args)
            self.assertEqual(result['status'], 'WAIT_TEST')


if __name__ == '__main__':
    unittest.main()
