import argparse
import json
import tempfile
import unittest
from pathlib import Path
from adaptive_caf import Driver, loopback_url, corrupt_fragment


class AdaptiveCAFTests(unittest.TestCase):
    def test_refuses_remote_or_credentialed_origins(self):
        for url in ('https://127.0.0.1:1234', 'http://example.com:1234',
                    'http://127.0.0.1:1234/api', 'http://user@127.0.0.1:1234',
                    'http://127.0.0.1:1234?token=private'):
            with self.subTest(url=url), self.assertRaises(argparse.ArgumentTypeError):
                loopback_url(url)
        self.assertEqual(loopback_url('http://127.0.0.1:1234/'), 'http://127.0.0.1:1234')

    def test_corrupts_encoded_samples_without_removing_fragment_timing(self):
        moof = b'\x00\x00\x00\x08moof'
        mdat = b'\x00\x00\x00\x0cmdatABCD'
        self.assertEqual(corrupt_fragment(moof + mdat), moof + mdat[:8] + b'\xff' * 4)
        for invalid in (b'not a segment', moof, mdat[:-1]):
            with self.assertRaises(ValueError):
                corrupt_fragment(invalid)

    def test_evidence_excludes_credentials_and_corruption_targets_only_offered_family(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'evidence.json'
            driver = Driver(None, '', '', 2, output)
            with self.assertRaises(ValueError):
                driver.control('corrupt', {})
            driver.observe('POST', '/api/files/4/playback', 200, json.dumps({
                'url': '/media?access_token=must-not-appear', 'playId': 'fictional',
                'adaptive': {'renditions': [{'sessionId': 'low'}, {'sessionId': 'high'}]},
            }).encode())
            driver.control('corrupt', {})
            self.assertEqual(driver.corrupt, {'low', 'high'})
            driver.control('sample', {'at': 1, 'height': 720, 'token': 'must-not-appear'})
            self.assertNotIn('must-not-appear', output.read_text())
            self.assertTrue(json.loads(output.read_text())['loads'][0]['adaptive'])
            driver.reset()
            self.assertEqual(driver.corrupt, set())


if __name__ == '__main__':
    unittest.main()
