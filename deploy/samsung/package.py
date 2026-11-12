#!/usr/bin/env python3
"""Sign a built prototype app. Credentials stay inside the SDK profile."""
import argparse
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SIGNING_SUFFIXES = {'.p12', '.p8', '.key', '.pem', '.cer', '.pfx', '.jks', '.keystore'}


def validate_app(app):
    if not all((app / name).is_file() for name in ('index.html', 'config.xml')):
        raise RuntimeError('The app is incomplete: index.html and config.xml are required. The core-only bundle cannot be installed.')
    if any(path.is_symlink() or path.suffix.lower() in SIGNING_SUFFIXES for path in app.rglob('*')):
        raise RuntimeError('The built app must not contain symlinks or signing material.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', required=True, help='Existing Samsung TV certificate profile name')
    parser.add_argument('--app', required=True, type=Path, help='Built TV app containing index.html and config.xml (not the core-only bundle)')
    parser.add_argument('--tizen', default=str(Path.home() / 'tizen-studio/tools/ide/bin/tizen'))
    args = parser.parse_args()
    if not args.profile or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-' for c in args.profile):
        parser.error('Use an SDK profile name containing only letters, digits, underscore or hyphen.')
    if not Path(args.tizen).is_file():
        parser.error('Tizen CLI not found. Install the SDK or pass --tizen /path/to/tizen.')
    validate_app(args.app)
    output = ROOT / 'build/samsung'
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='package-', dir=output) as stage:
        staging = Path(stage)
        shutil.copytree(args.app, staging / 'app')
        # Reuse the existing brand export; do not commit a duplicate generated icon.
        shutil.copyfile(ROOT / 'brand/export/android/play-store-icon-512.png', staging / 'app/icon.png')
        (staging / 'signed').mkdir()
        subprocess.run([args.tizen, 'package', '-t', 'wgt', '-s', args.profile,
                        '-o', str(staging / 'signed'), '--', str(staging / 'app')], check=True)
        packages = list((staging / 'signed').glob('*.wgt'))
        if len(packages) != 1:
            raise RuntimeError('The SDK did not produce exactly one signed package.')
        with zipfile.ZipFile(packages[0]) as package:
            names = set(package.namelist())
            if not {'author-signature.xml', 'signature1.xml', 'config.xml', 'index.html'} <= names:
                raise RuntimeError('Package is missing its signatures or entry files; refusing to label it signed.')
            if any(Path(name).suffix.lower() in SIGNING_SUFFIXES for name in names):
                raise RuntimeError('Signing material must never be included in the app.')
        final = output / 'watchmymedia-qn90d-prototype.wgt'
        shutil.copyfile(packages[0], final)
        print(f'Signed prototype: {final}')
        print('TV install acceptance still requires the matching Samsung distributor certificate/DUID.')


if __name__ == '__main__':
    try:
        main()
    except (subprocess.CalledProcessError, RuntimeError) as error:
        print(f'Package failed: {error}', file=sys.stderr)
        sys.exit(1)
