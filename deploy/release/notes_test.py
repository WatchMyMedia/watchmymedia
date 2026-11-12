"""Tests for notes.sh, which tag.sh, release.yml and publish read:
python3 -I -m unittest discover -s deploy/release -p '*_test.py'"""

import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
NOTES = ROOT / "deploy/release/notes.sh"


def changelog(older_sections):
    """A changelog whose 1.2.3 section comes first, followed by enough older
    text to outgrow a pipe's buffer."""
    body = ["# Changelog", "", "## 1.2.3", "", "An intro that wraps", "  onto a second line.", "",
            "- One item", "- Another item that", "  wraps.", ""]
    for n in range(older_sections):
        body += [f"## 0.{n}.0", "", "x" * 400, "", "- An older item", ""]
    return "\n".join(body) + "\n"


class NotesTest(unittest.TestCase):
    def test_prints_the_section_and_joins_wrapped_lines(self):
        with tempfile.NamedTemporaryFile("w", suffix=".md") as f:
            f.write(changelog(3))
            f.flush()
            out = subprocess.run(["sh", str(NOTES), "v1.2.3-rc.1", f.name],
                                 capture_output=True, text=True, check=True).stdout
        self.assertEqual("An intro that wraps onto a second line.\n\n- One item\n- Another item that wraps.\n", out)

    def test_a_large_changelog_can_be_piped_in(self):
        # tag.sh pipes git show into notes.sh under pipefail. Before, notes.sh
        # stopped reading at the next section, so a changelog bigger than the
        # pipe's buffer (64 KB) made the writer fail with SIGPIPE.
        big = changelog(1000)
        self.assertGreater(len(big), 256 * 1024)
        # Written to a file and piped with cat, as an environment variable
        # this size is over Linux's 128 KB limit for one.
        with tempfile.NamedTemporaryFile("w", suffix=".md") as f:
            f.write(big)
            f.flush()
            result = subprocess.run(["bash", "-o", "pipefail", "-c", 'cat "$BIG" | sh "$NOTES" v1.2.3 -'],
                                    env={"BIG": f.name, "NOTES": str(NOTES), "PATH": "/usr/bin:/bin"},
                                    capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(result.stdout.startswith("An intro that wraps"))

    def test_refuses_a_missing_section(self):
        with tempfile.NamedTemporaryFile("w", suffix=".md") as f:
            f.write(changelog(1))
            f.flush()
            result = subprocess.run(["sh", str(NOTES), "v9.9.9", f.name], capture_output=True, text=True)
        self.assertEqual(1, result.returncode)
        self.assertIn('no "## 9.9.9" section', result.stderr)


if __name__ == "__main__":
    unittest.main()
