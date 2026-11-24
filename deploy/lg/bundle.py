#!/usr/bin/env python3
"""Check that the built LG TV app loads on the TV and packages for LG's store.

The LG app is the same packaged web app as Samsung's, for the Chromium 108
of webOS 24, so it gets Samsung's checks (../samsung/bundle.py: one classic
script and one stylesheet, nothing newer than Chrome 108, nothing file://
refuses). Then LG's own package files (pnpm check:webos in web/, and CI's lg
job), failing when:

  - appinfo.json is missing, isn't JSON, or lacks what LG requires: the id
    (media.watchmy.client, which never changes), a version of three numbers,
    type web, the title, and main, icon and largeIcon naming files that are
    there;
  - Back doesn't come to the app as a key (disableBackHistoryAPI), or the
    app doesn't handle being opened again (handlesRelaunch);
  - icon.png isn't an 80 px PNG or largeIcon.png a 130 px one.

    python3 -I deploy/lg/bundle.py [web/dist-webos]
"""
import importlib.util
import json
import pathlib
import re
import struct
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP_ID = 'media.watchmy.client'
ICONS = {'icon': 80, 'largeIcon': 130}


def _samsung():
    spec = importlib.util.spec_from_file_location('samsung_bundle', ROOT / 'deploy/samsung/bundle.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def png_size(path):
    """A PNG's width and height from its header, or None when it isn't a PNG."""
    head = path.read_bytes()[:24]
    if len(head) < 24 or head[:8] != b'\x89PNG\r\n\x1a\n' or head[12:16] != b'IHDR':
        return None
    return struct.unpack('>II', head[16:24])


def appinfo_problems(app):
    path = app / 'appinfo.json'
    if not path.is_file():
        return ['appinfo.json is missing']
    try:
        info = json.loads(path.read_text(encoding='utf-8'))
    except ValueError:
        return ['appinfo.json is not JSON']
    if not isinstance(info, dict):
        return ['appinfo.json is not an object']
    found = []
    if info.get('id') != APP_ID:
        found.append(f'appinfo.json id is {info.get("id")!r}, not {APP_ID}: the id never changes after the first upload')
    if not re.fullmatch(r'(0|[1-9]\d{0,8})(\.(0|[1-9]\d{0,8})){2}', str(info.get('version', ''))):
        found.append('appinfo.json needs a version of three numbers without leading zeros, like 2.2.0')
    if info.get('type') != 'web':
        found.append('appinfo.json type must be web')
    if not str(info.get('title', '')).strip():
        found.append('appinfo.json needs a title')
    for key in ('main', 'icon', 'largeIcon'):
        name = info.get(key)
        if not isinstance(name, str) or not name or not (app / name).is_file():
            found.append(f'appinfo.json {key} must name a file in the package')
    if info.get('disableBackHistoryAPI') is not True:
        found.append('appinfo.json needs disableBackHistoryAPI: the app takes Back (461) as a key')
    if info.get('handlesRelaunch') is not True:
        found.append('appinfo.json needs handlesRelaunch: the app comes forward itself when opened again')
    for key, size in ICONS.items():
        name = info.get(key)
        if isinstance(name, str) and (app / name).is_file() and png_size(app / name) != (size, size):
            found.append(f'{name} must be a {size}×{size} PNG, as LG asks for {key}')
    return found


def problems(app):
    """What keeps the app in this folder from loading on an LG TV or packaging for LG's store."""
    return _samsung().problems(app, package=('appinfo.json', 'icon.png', 'largeIcon.png')) + appinfo_problems(app)


def main(argv):
    app = pathlib.Path(argv[1]) if len(argv) > 1 else ROOT / 'web/dist-webos'
    if not app.is_dir():
        sys.exit(f'{app} is not there: run pnpm build:webos in web/ first.')
    found = problems(app)
    for problem in found:
        print(f'{app}: {problem}', file=sys.stderr)
    if found:
        sys.exit(f'{len(found)} problem{"s" if len(found) > 1 else ""}: the LG app runs in Chromium 108 from file://, packaged by appinfo.json.')
    files = sorted(str(path.relative_to(app)) for path in app.rglob('*') if path.is_file())
    print(f'{app} loads in Chromium 108 from file:// and packages for webOS: {", ".join(files)}')


if __name__ == '__main__':
    main(sys.argv)
