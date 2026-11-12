import importlib.util
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch


def load(name):
    spec = importlib.util.spec_from_file_location(f'samsung_{name}', Path(__file__).with_name(f'{name}.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


fixture = load('fixture')
package = load('package')


class PrototypeGuards(unittest.TestCase):
    def test_fixture_refuses_an_existing_unmarked_directory(self):
        with tempfile.TemporaryDirectory() as root:
            data = Path(root)
            with patch.multiple(fixture, DATA=data, MARKER=data / '.marker'):
                with self.assertRaisesRegex(SystemExit, 'without the prototype marker'):
                    fixture.render()
            self.assertEqual(list(data.iterdir()), [])

    def test_seed_refuses_non_fixture_media_before_writing(self):
        with tempfile.TemporaryDirectory() as root:
            data = Path(root)
            marker = data / '.marker'
            marker.touch()
            with sqlite3.connect(data / 'watchmymedia.db') as db:
                db.execute('CREATE TABLE media_files (id INTEGER, library_id INTEGER, path TEXT)')
                db.executemany('INSERT INTO media_files VALUES(?,?,?)', [(i, 1, f'/not-fixture/{i}.mp4') for i in range(3)])
            with patch.multiple(fixture, DATA=data, MEDIA=data / 'media', MARKER=marker):
                with self.assertRaisesRegex(SystemExit, 'exactly the three prototype samples'):
                    fixture.seed()
            with sqlite3.connect(data / 'watchmymedia.db') as db:
                self.assertEqual(db.execute('SELECT count(*) FROM media_files').fetchone()[0], 3)

    def test_package_rejects_core_only_output_and_signing_files(self):
        with tempfile.TemporaryDirectory() as root:
            app = Path(root)
            (app / 'tizen-core.js').write_text('export {}')
            with self.assertRaisesRegex(RuntimeError, 'core-only bundle'):
                package.validate_app(app)
            (app / 'index.html').write_text('<!doctype html>')
            (app / 'config.xml').write_text('<widget/>')
            package.validate_app(app)
            # Empty fixture name only; no certificate contents are read or created.
            (app / 'forbidden.p12').touch()
            with self.assertRaisesRegex(RuntimeError, 'signing material'):
                package.validate_app(app)

    def test_package_rejects_symlinks_outside_the_built_app(self):
        with tempfile.TemporaryDirectory() as root:
            app = Path(root)
            (app / 'index.html').write_text('<!doctype html>')
            (app / 'config.xml').write_text('<widget/>')
            (app / 'external').symlink_to('/nonexistent-fixture-path')
            with self.assertRaisesRegex(RuntimeError, 'symlinks'):
                package.validate_app(app)
