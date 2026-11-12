#!/usr/bin/env python3
"""Write resource inputs for pinned go-winres; outputs stay under build/."""
import json
import os
import pathlib
import re
import sys

root = pathlib.Path(__file__).resolve().parent.parent
out = pathlib.Path(sys.argv[1]).resolve()
version = sys.argv[2]
ci_host = len(sys.argv) > 3 and sys.argv[3] == 'true'
m = re.fullmatch(r'v?(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.(\d+))?', version)
numbers = [0, 0, 0, 0]
if m:
    numbers = [int(m[1]), int(m[2]), int(m[3]), {'alpha': 0, 'beta': 30, 'rc': 60, None: 99}[m[4]] + int(m[5] or 0)]
if any(n > 65535 for n in numbers):
    raise SystemExit('Version exceeds Windows version resource limits')
fixed = '.'.join(map(str, numbers))
for name, description in [('tray', 'WatchMyMedia'), ('server', 'WatchMyMedia server')]:
    icons = {'#1': {'0000': os.path.relpath(root / 'brand/export/windows/watchmymedia.ico', out)}}
    if name == 'tray':
        if ci_host:
            description += ' (CI host validation only)'
        icons.update({'#2': {'0000': os.path.relpath(root / 'brand/export/windows/tray-light.ico', out)}, '#3': {'0000': os.path.relpath(root / 'brand/export/windows/tray-dark.ico', out)}})
    manifest = {'execution-level': 'as invoker', 'dpi-awareness': 'per monitor v2', 'long-path-aware': True, 'use-common-controls-v6': True}
    if name == 'tray':
        # Win11 shares the Win10 supportedOS GUID. The native policy check
        # enforces build 26200 and client/native-x64, rather than this label.
        manifest['minimum-os'] = 'win10'
    value = {
        'RT_GROUP_ICON': icons,
        'RT_MANIFEST': {'#1': {'0409': manifest}},
        'RT_VERSION': {'#1': {'0000': {'fixed': {'file_version': fixed, 'product_version': fixed}, 'info': {'0409': {'CompanyName': 'Eldwick Software LLC', 'FileDescription': description, 'FileVersion': version, 'ProductName': 'WatchMyMedia', 'ProductVersion': version, 'OriginalFilename': 'WatchMyMedia.exe' if name == 'tray' else 'watchmymedia-server.exe'}}}}},
    }
    out.mkdir(parents=True, exist_ok=True)
    (out / (name + '.json')).write_text(json.dumps(value, indent=2) + '\n')
