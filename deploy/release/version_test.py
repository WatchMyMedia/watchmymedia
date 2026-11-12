"""Tests for apple/scripts/version.sh, which every release job and tag.sh read:
python3 -I -m unittest discover -s deploy/release -p '*_test.py'"""

import pathlib
import subprocess
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


def version(tag):
    result = subprocess.run(["bash", str(ROOT / "apple/scripts/version.sh"), tag],
                            capture_output=True, text=True)
    if result.returncode != 0:
        raise ValueError(result.stderr.strip())
    marketing, build = result.stdout.split()
    return marketing.removeprefix("MARKETING_VERSION="), int(build.removeprefix("CURRENT_PROJECT_VERSION="))


class VersionTest(unittest.TestCase):
    def test_app_store_version_stays_on_1x(self):
        # The App Store had 1.5.10 when v1.0.0 was cut, and takes only higher versions.
        # v1.1 and v1.2 shipped no App Store build, so v1.3.0 follows v1.0.0's 1.6.0.
        cases = {
            "v0.5.2": "1.5.2",
            "v0.5.9": "1.5.9",
            "v1.0.0-rc.1": "1.6.0",
            "v1.0.0": "1.6.0",
            "v1.0.1": "1.6.1",
            "v1.1.0": "1.6.0",
            "v1.2.3-rc.1": "1.6.3",
            "v1.3.0": "1.7.0",
            "v1.3.1": "1.7.1",
            "v1.4.0-rc.1": "1.8.0",
            "v1.4.0": "1.8.0",
        }
        for tag, want in cases.items():
            with self.subTest(tag=tag):
                self.assertEqual(version(tag)[0], want)

    def test_app_store_version_never_goes_down(self):
        # The tags as released (no v1.0.x patch was cut), then later ones.
        tags = ["v0.5.9", "v1.0.0", "v1.1.0", "v1.2.0", "v1.2.1", "v1.3.0",
                "v1.3.2", "v1.4.0", "v1.10.0", "v1.99.999"]
        numbers = [tuple(int(x) for x in version(t)[0].split(".")) for t in tags]
        self.assertEqual(numbers, sorted(numbers))

    def test_v1_sorts_after_v0(self):
        self.assertGreater(version("v1.0.0-alpha.1")[1], version("v0.99.999")[1])

    def test_refuses_what_it_cannot_number(self):
        for bad in ("v2.0.0", "v1.2", "dev", "v1.0.0-rc.30", "v210.0.0"):
            with self.subTest(tag=bad), self.assertRaises(ValueError):
                version(bad)


if __name__ == "__main__":
    unittest.main()
