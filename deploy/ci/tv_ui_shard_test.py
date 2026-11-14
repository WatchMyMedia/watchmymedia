"""The shares of the Apple TV UI tests that CI's two apple-tv jobs run."""

import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'apple/scripts/tv-ui-shard.py'
spec = importlib.util.spec_from_file_location('tv_ui_shard', SCRIPT)
shard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(shard)


class TVUIShardTest(unittest.TestCase):
    def test_every_real_class_is_in_exactly_one_share(self):
        found = shard.classes(ROOT / 'apple/TVUITests')
        self.assertGreater(len(found), 10)
        names = [name for name, _ in found]
        one, two = shard.shares(found, 2)
        self.assertEqual(sorted(names), sorted(one + two))
        self.assertFalse(set(one) & set(two))
        counts = dict(found)
        a, b = sum(counts[n] for n in one), sum(counts[n] for n in two)
        self.assertLessEqual(abs(a - b), max(counts.values()), (a, b))

    def test_classes_and_tests_are_counted_per_class(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = pathlib.Path(tmp)
            (folder / 'A.swift').write_text(
                'import XCTest\nfinal class ATests: XCTestCase {\n'
                '    @MainActor func testOne() {}\n    func testTwo() throws {}\n    private func helper() {}\n}\n'
                'class BTests: XCTestCase {\n    func testThree() async {}\n}\n')
            (folder / 'Support.swift').write_text('struct Remote { func testLikeHelper() {} }\n')
            self.assertEqual([('ATests', 2), ('BTests', 1)], shard.classes(folder))

    def test_shares_balance_by_tests_and_are_stable(self):
        found = [('Big', 9), ('Mid', 5), ('Small', 4), ('Tiny', 1), ('None', 0)]
        self.assertEqual([['Big', 'Tiny'], ['Mid', 'None', 'Small']], shard.shares(found, 2))
        self.assertEqual(shard.shares(list(reversed(found)), 2), shard.shares(found, 2))
        self.assertEqual([['Big', 'Mid', 'None', 'Small', 'Tiny']], shard.shares(found, 1))

    def test_the_command_prints_only_testing_arguments(self):
        out = subprocess.run([sys.executable, '-I', str(SCRIPT), '1', '2', str(ROOT / 'apple/TVUITests')],
                             check=True, capture_output=True, text=True).stdout.split()
        self.assertTrue(out)
        self.assertTrue(all(arg.startswith('-only-testing:WatchMyMediaTVUITests/') for arg in out))
        for bad in (['0', '2'], ['3', '2']):
            with self.subTest(bad=bad):
                failed = subprocess.run([sys.executable, '-I', str(SCRIPT)] + bad, capture_output=True)
                self.assertNotEqual(0, failed.returncode)


if __name__ == '__main__':
    unittest.main()
