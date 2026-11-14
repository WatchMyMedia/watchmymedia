#!/usr/bin/env python3
"""Print the -only-testing arguments for one share of the Apple TV UI tests.

CI runs the remote UI tests (apple/TVUITests) in two jobs at once
(make apple-tv-test-sim-ci TV_UI_SHARD=1 or 2). Each job takes whole test
classes: a class's tests share its setup, and xcodebuild names a class in
-only-testing. The classes are shared out by how many tests each has,
largest first, each to the share with fewest tests so far (ties go to the
lower share), so a new class or test lands somewhere without anyone keeping
a list. Every class is in exactly one share.

    python3 -I apple/scripts/tv-ui-shard.py 1 2 [apple/TVUITests]
"""

import pathlib
import re
import sys

TARGET = 'WatchMyMediaTVUITests'
CLASS = re.compile(r'^\s*(?:final\s+)?class\s+(\w+)\s*:\s*XCTestCase\b', re.M)
TEST = re.compile(r'\bfunc\s+test\w*\s*\(')


def classes(folder):
    """(class name, test count) for every XCTestCase class in the folder."""
    found = []
    for path in sorted(pathlib.Path(folder).glob('*.swift')):
        text = path.read_text()
        heads = list(CLASS.finditer(text))
        for i, head in enumerate(heads):
            end = heads[i + 1].start() if i + 1 < len(heads) else len(text)
            found.append((head.group(1), len(TEST.findall(text, head.end(), end))))
    return found


def shares(found, count):
    """The classes in `count` shares, balanced by test count."""
    if count < 1:
        raise ValueError('at least one share')
    buckets = [[] for _ in range(count)]
    totals = [0] * count
    for name, tests in sorted(found, key=lambda c: (-c[1], c[0])):
        i = min(range(count), key=lambda b: (totals[b], b))
        buckets[i].append(name)
        totals[i] += tests
    return [sorted(b) for b in buckets]


def main(argv):
    if len(argv) not in (3, 4):
        sys.exit(__doc__)
    share, count = int(argv[1]), int(argv[2])
    if not 1 <= share <= count:
        sys.exit(f'share {share} is not between 1 and {count}')
    folder = argv[3] if len(argv) == 4 else 'apple/TVUITests'
    found = classes(folder)
    if not found:
        sys.exit(f'no XCTestCase classes in {folder}')
    for name in shares(found, count)[share - 1]:
        print(f'-only-testing:{TARGET}/{name}')


if __name__ == '__main__':
    main(sys.argv)
