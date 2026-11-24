"""The check that a built LG TV app loads on the TV and packages for LG's store."""
import importlib.util
import json
import pathlib
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib

SCRIPT = pathlib.Path(__file__).with_name('bundle.py')
spec = importlib.util.spec_from_file_location('lg_bundle', SCRIPT)
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)
ROOT = pathlib.Path(__file__).resolve().parents[2]

PAGE = ('<!doctype html>\n<html lang="en">\n<head>\n  <script defer src="./assets/index-a1.js"></script>\n'
        '  <link rel="stylesheet" href="./assets/style-b2.css">\n</head>\n<body><div id="root"></div></body>\n</html>\n')
APPINFO = {'id': 'media.watchmy.client', 'version': '2.2.0', 'type': 'web', 'main': 'index.html', 'title': 'WatchMyMedia',
           'icon': 'icon.png', 'largeIcon': 'largeIcon.png', 'disableBackHistoryAPI': True, 'handlesRelaunch': True}


def png(size):
    """A blank PNG of size×size."""
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    rows = b''.join(b'\x00' + b'\x00\x00\x00' * size for _ in range(size))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b''))


class AppTest(unittest.TestCase):
    def app(self, appinfo=APPINFO, icons=(80, 130)):
        folder = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(lambda: subprocess.run(['rm', '-rf', str(folder)], check=True))
        (folder / 'assets').mkdir()
        (folder / 'index.html').write_text(PAGE)
        (folder / 'assets/index-a1.js').write_text('(function(){})()')
        (folder / 'assets/style-b2.css').write_text('a{color:red}')
        if appinfo is not None:
            (folder / 'appinfo.json').write_text(appinfo if isinstance(appinfo, str) else json.dumps(appinfo))
        (folder / 'icon.png').write_bytes(png(icons[0]))
        (folder / 'largeIcon.png').write_bytes(png(icons[1]))
        return folder

    def test_a_complete_build_passes(self):
        self.assertEqual([], bundle.problems(self.app()))

    def test_samsungs_checks_still_apply(self):
        app = self.app()
        (app / 'assets/index-a1.js').write_text('a.toSorted()')
        self.assertEqual(['assets/index-a1.js uses .toSorted, from Chrome 110'], bundle.problems(app))

    def test_appinfo_must_be_there_and_be_json(self):
        self.assertIn('appinfo.json is missing', bundle.problems(self.app(appinfo=None)))
        self.assertEqual(['appinfo.json is not JSON'], bundle.problems(self.app(appinfo='{"id":')))

    def test_the_id_never_changes(self):
        found = bundle.problems(self.app({**APPINFO, 'id': 'com.example.other'}))
        self.assertEqual(1, len(found))
        self.assertIn('never changes', found[0])

    def test_what_lg_requires(self):
        for change, words in (({'version': '2.2'}, 'version'), ({'version': '02.2.0'}, 'version'), ({'type': 'native'}, 'type'),
                              ({'title': ' '}, 'title'), ({'main': 'start.html'}, 'main'), ({'largeIcon': None}, 'largeIcon'),
                              ({'disableBackHistoryAPI': False}, 'disableBackHistoryAPI'), ({'handlesRelaunch': None}, 'handlesRelaunch')):
            with self.subTest(change=change):
                found = bundle.problems(self.app({**APPINFO, **change}))
                self.assertEqual(1, len(found), found)
                self.assertIn(words, found[0])

    def test_icons_are_lgs_sizes(self):
        self.assertEqual(['icon.png must be a 80×80 PNG, as LG asks for icon'], bundle.problems(self.app(icons=(512, 130))))
        self.assertEqual(['largeIcon.png must be a 130×130 PNG, as LG asks for largeIcon'], bundle.problems(self.app(icons=(80, 80))))

    def test_the_command_says_what_is_wrong(self):
        app = self.app({**APPINFO, 'handlesRelaunch': False})
        run = subprocess.run([sys.executable, '-I', str(SCRIPT), str(app)], capture_output=True, text=True)
        self.assertEqual(1, run.returncode)
        self.assertIn('handlesRelaunch', run.stderr)
        ok = subprocess.run([sys.executable, '-I', str(SCRIPT), str(self.app())], capture_output=True, text=True)
        self.assertEqual(0, ok.returncode, ok.stderr)


class SourceTest(unittest.TestCase):
    def test_the_apps_own_appinfo_passes(self):
        # Its version is held to the app's by the app's own test.
        info = json.loads((ROOT / 'web/tv/platform/lg/public/appinfo.json').read_text())
        expected = {key: value for key, value in APPINFO.items() if key != 'version'}
        self.assertEqual(expected, {key: info.get(key) for key in expected})

    def test_the_exported_icons_are_lgs_sizes(self):
        self.assertEqual((80, 80), bundle.png_size(ROOT / 'brand/export/webos/icon-80.png'))
        self.assertEqual((130, 130), bundle.png_size(ROOT / 'brand/export/webos/icon-130.png'))
        self.assertEqual((400, 400), bundle.png_size(ROOT / 'brand/export/webos/icon-400.png'))


if __name__ == '__main__':
    unittest.main()
