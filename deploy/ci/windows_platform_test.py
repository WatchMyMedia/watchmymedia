"""Keep the hosted Server exception out of ordinary desktop artifacts."""
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


class WindowsPlatformBuildTest(unittest.TestCase):
    def test_ci_builds_refuse_missing_host_signals_and_release_versions(self):
        hosted = dict(GITHUB_ACTIONS='true', RUNNER_ENVIRONMENT='github-hosted', RUNNER_OS='Windows', VERSION='development')
        cases = [dict(hosted, **{key: ''}) for key in ('GITHUB_ACTIONS', 'RUNNER_ENVIRONMENT', 'RUNNER_OS')]
        cases += [dict(hosted, VERSION='v1.2.3'), dict(hosted, VERSION='v1.2.3-rc.1')]
        for script in ('build.sh', 'installer.sh'):
            for values in cases:
                with self.subTest(script=script, values=values):
                    result = subprocess.run(['bash', str(ROOT / 'windows' / script), '--ci-server-host'],
                                            env=dict(os.environ, **values), capture_output=True, text=True, timeout=10)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn('require development on disposable hosted Windows CI', result.stderr)

    def test_generated_tray_metadata_distinguishes_ci_builds(self):
        with tempfile.TemporaryDirectory() as tmp:
            descriptions = []
            for ci in ('false', 'true'):
                out = pathlib.Path(tmp) / ci
                subprocess.run([sys.executable, '-I', str(ROOT / 'windows/resources.py'), str(out), 'development', ci], check=True)
                tray = json.loads((out / 'tray.json').read_text())
                descriptions.append(tray['RT_VERSION']['#1']['0000']['info']['0409']['FileDescription'])
                self.assertEqual(tray['RT_MANIFEST']['#1']['0409']['minimum-os'], 'win10')
                server = json.loads((out / 'server.json').read_text())
                self.assertNotIn('minimum-os', server['RT_MANIFEST']['#1']['0409'])
            self.assertEqual(descriptions, ['WatchMyMedia', 'WatchMyMedia (CI host validation only)'])


if __name__ == '__main__':
    unittest.main()
