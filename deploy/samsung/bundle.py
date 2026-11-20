#!/usr/bin/env python3
"""Check that the built Samsung TV app loads on the TV.

The app is a packaged Tizen web app: one classic script and one stylesheet,
loaded from file:// by the TV's Chromium 108. pnpm build:tizen lowers our
syntax to Chrome 108, but nothing adds what the browser lacks, so a method
newer than that, in our code or a library's, fails only on the TV. This
reads the build (pnpm check:tizen in web/, and CI's samsung job) and fails
when:

  - a file the package needs is missing: index.html, config.xml, icon.png,
    one script and one stylesheet;
  - index.html loads a module script or asks for crossorigin, either of
    which file:// can refuse;
  - the script uses import.meta, which a classic script can't hold, or a
    method newer than Chrome 108 (SCRIPT);
  - the stylesheet uses a colour function newer than Chrome 108 (STYLE), or
    nests a rule in another (Chrome 112).

It reads text, not a parse: a library that checks for a method before it
calls it fails here too, and is for a person to look at.

    python3 -I deploy/samsung/bundle.py [web/dist-tizen]
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]

# What the script may not use, and the Chrome version that added it.
SCRIPT = [
    (r'\.(?:toSorted|toReversed|toSpliced)\b', 110),
    (r'\.(?:isWellFormed|toWellFormed)\b', 111),
    (r'\b(?:Object|Map)\.groupBy\b', 117),
    (r'\bPromise\.withResolvers\b', 119),
    (r'\bArray\.fromAsync\b', 121),
    # Set methods: their names are common words, so only calls count.
    (r'\.(?:union|intersection|difference|symmetricDifference|isSubsetOf|isSupersetOf|isDisjointFrom)\(', 122),
]

# What the stylesheet may not use, and the Chrome version that added it.
# Not lab(): the build writes oklch() as a hex colour and then lab(), and
# Chrome 108 keeps the hex one.
STYLE = [
    (r'(?<![\w-])color-mix\(', 111),
    (r'(?<![\w-])(?:oklch|oklab)\(', 111),
    (r'(?<![\w-])light-dark\(', 123),
]

# At-rules whose blocks hold rules. Any other block holds declarations, and
# a block inside one of those is a nested rule.
GROUPS = {'media', 'supports', 'layer', 'container', 'scope', 'starting-style', 'document', 'keyframes',
          '-webkit-keyframes'}


def page_problems(html):
    found = []
    if re.search(r'<script\b[^>]*\stype\s*=\s*["\']?module\b', html, re.I):
        found.append('loads a module script: build it as one classic deferred script')
    if re.search(r'<[^>]*\scrossorigin(?=[\s=/>])', html, re.I):
        found.append('asks for crossorigin, which file:// can refuse')
    return found


def script_problems(script):
    found = [f'uses {match.group()}, from Chrome {version}'
             for pattern, version in SCRIPT for match in re.finditer(pattern, script)]
    if re.search(r'\bimport\.meta\b', script):
        found.append('uses import.meta, which a classic script cannot hold')
    return sorted(set(found))


def without_comments_and_strings(css):
    return re.sub(r'/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
                  lambda match: '' if match.group().startswith('/*') else '""', css, flags=re.S)


def nested_rules(css):
    """The preludes of rules written inside another rule."""
    found, blocks, start = [], [], 0
    for at, char in enumerate(css):
        if char == '{':
            prelude = css[start:at].strip()
            name = re.match(r'@([\w-]+)', prelude)
            if blocks and blocks[-1] == 'rule':
                found.append(prelude)
            blocks.append('group' if name and name.group(1).lower() in GROUPS
                          else 'rule' if not name and (not blocks or blocks[-1] == 'group') else 'other')
        elif char == '}' and blocks:
            blocks.pop()
        if char in '{};':
            start = at + 1
    return found


def style_problems(style):
    css = without_comments_and_strings(style)
    found = [f'uses {match.group()}, from Chrome {version}'
             for pattern, version in STYLE for match in re.finditer(pattern, css, re.I)]
    found += [f'nests {prelude}{{ in another rule, from Chrome 112' for prelude in nested_rules(css)]
    return sorted(set(found))


def problems(app):
    """What keeps the app in this folder from loading on the TV."""
    found = [f'{name} is missing' for name in ('index.html', 'config.xml', 'icon.png') if not (app / name).is_file()]
    scripts, styles = sorted(app.rglob('*.js')), sorted(app.rglob('*.css'))
    for kind, files in (('script', scripts), ('stylesheet', styles)):
        if len(files) != 1:
            names = ', '.join(str(path.relative_to(app)) for path in files)
            found.append(f'should hold one {kind} and holds {len(files)}' + (f': {names}' if files else ''))
    if (app / 'index.html').is_file():
        found += [f'index.html {problem}' for problem in page_problems((app / 'index.html').read_text(encoding='utf-8'))]
    for files, check in ((scripts, script_problems), (styles, style_problems)):
        for path in files:
            found += [f'{path.relative_to(app)} {problem}' for problem in check(path.read_text(encoding='utf-8'))]
    return found


def main(argv):
    app = pathlib.Path(argv[1]) if len(argv) > 1 else ROOT / 'web/dist-tizen'
    if not app.is_dir():
        sys.exit(f'{app} is not there: run pnpm build:tizen in web/ first.')
    found = problems(app)
    for problem in found:
        print(f'{app}: {problem}', file=sys.stderr)
    if found:
        sys.exit(f'{len(found)} problem{"s" if len(found) > 1 else ""}: the TV runs Chromium 108 from file://.')
    files = sorted(str(path.relative_to(app)) for path in app.rglob('*') if path.is_file())
    print(f'{app} loads in Chromium 108 from file://: {", ".join(files)}')


if __name__ == '__main__':
    main(sys.argv)
