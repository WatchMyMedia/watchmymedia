#!/usr/bin/env python3
"""Package the built LG TV app as an .ipk with LG's ares-package.

The .ipk is unsigned: LG signs what Seller Lounge accepts, and Developer
Mode installs it as it is. This checks the build (bundle.py), lets
ares-package check the app (--check), packages it into build/lg/ and has
ares-package read the package back (--info). The CLI is LG's
@webos-tools/cli: on PATH, or named with --ares.

    python3 -I deploy/lg/package.py --app web/dist-webos
"""
import argparse
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SIGNING_SUFFIXES = {'.p12', '.p8', '.key', '.pem', '.cer', '.pfx', '.jks', '.keystore'}


def bundle():
    spec = importlib.util.spec_from_file_location('lg_bundle', Path(__file__).resolve().parent / 'bundle.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validate_app(app):
    if not (app / 'appinfo.json').is_file():
        raise RuntimeError(f'{app} has no appinfo.json: run pnpm build:webos in web/ first.')
    if any(path.is_symlink() or path.suffix.lower() in SIGNING_SUFFIXES for path in app.rglob('*')):
        raise RuntimeError('The built app must not contain symlinks or signing material.')
    found = bundle().problems(app)
    if found:
        raise RuntimeError('; '.join(found))


def package_name(app):
    """What ares-package names the package: <id>_<version>_all.ipk."""
    info = json.loads((app / 'appinfo.json').read_text(encoding='utf-8'))
    return f"{info['id']}_{info['version']}_all.ipk"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', required=True, type=Path, help='The built LG app (web/dist-webos)')
    parser.add_argument('--ares', default=shutil.which('ares-package') or 'ares-package', help="LG's ares-package")
    parser.add_argument('--out', type=Path, default=ROOT / 'build/lg', help='Where the .ipk goes')
    args = parser.parse_args()
    if not shutil.which(args.ares) and not Path(args.ares).is_file():
        parser.error('ares-package not found: npm install -g @webos-tools/cli, or pass --ares.')
    app = args.app.resolve()
    validate_app(app)
    args.out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='package-', dir=args.out) as stage:
        staging = Path(stage)
        shutil.copytree(app, staging / 'app')
        subprocess.run([args.ares, '--check', str(staging / 'app')], check=True)
        subprocess.run([args.ares, '-o', str(staging / 'ipk'), str(staging / 'app')], check=True)
        packages = list((staging / 'ipk').glob('*.ipk'))
        if len(packages) != 1 or packages[0].name != package_name(app):
            raise RuntimeError(f'ares-package did not produce {package_name(app)}.')
        subprocess.run([args.ares, '--info', str(packages[0])], check=True)
        final = args.out / packages[0].name
        shutil.copyfile(packages[0], final)
        print(f'Packaged: {final}')
        print('Developer Mode installs it with ares-install; the store takes it through Seller Lounge.')


if __name__ == '__main__':
    try:
        main()
    except (subprocess.CalledProcessError, RuntimeError, OSError, ValueError) as error:
        print(f'Package failed: {error}', file=sys.stderr)
        sys.exit(1)
