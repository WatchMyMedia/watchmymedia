#!/usr/bin/env python3
"""Checks that the Android app's committed baseline and startup profiles are current.

The profiles in android/app/src/release/generated/baselineProfiles/ name the
classes and methods compiled ahead of time at install, made by the journeys in
android/baselineprofile and committed (android/README.md, "Baseline profile").
They go stale quietly: a renamed or removed screen's rules match nothing, and
the screen that replaced it runs interpreted. This fails when many of the
profiles' rules for the app's own code (media.watchmy.client) name classes
that are no longer in the sources, the sign that the profiles need generating
again. On 2026-11-13 the profiles of 2026-11-01 had 7% such rules, most for
the two show screens the show page replaced.

  python3 -I deploy/ci/baseline_profile.py [android-dir]

A class counts as present when a Kotlin or Java source declares it, or when it
is a Kotlin file's facade (Foo.kt's FooKt); inner classes, lambdas and
synthetic classes count as their top-level class. CI's android job runs it.
"""

import os
import re
import sys

PACKAGE = "media/watchmy/client/"

# Fails when more than this share of the profiles' rules for the app's own
# classes name classes that are gone, and at least MIN_STALE_RULES of them: a
# renamed file or two between regenerations is expected, a screen's worth is not.
MAX_STALE_SHARE = 0.05
MIN_STALE_RULES = 50

PROFILES = ("baseline-prof.txt", "startup-prof.txt")
PROFILE_DIR = os.path.join("app", "src", "release", "generated", "baselineProfiles")

RULE = re.compile(r"^[HSP]*L(" + re.escape(PACKAGE) + r"[^;]+);")
PACKAGE_LINE = re.compile(r"^\s*package\s+([\w.]+)", re.MULTILINE)
JVM_NAME = re.compile(r'^\s*@file:JvmName\(\s*"([^"]+)"\s*\)', re.MULTILINE)
DECLARATION = re.compile(
    r"^[ \t]*(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:(?:public|internal|private|protected|abstract|open|sealed|data|enum|annotation|inline|value|inner|expect|actual|final|static|fun)\s+)*"
    r"(?:class|interface|object|record|@interface)\s+([A-Za-z_]\w*)",
    re.MULTILINE,
)
# Made by the build, not written in a source.
GENERATED = {"R", "BuildConfig"}


def top_level(name):
    """media/watchmy/client/x/FooKt$Bar$1 -> media/watchmy/client/x/FooKt."""
    package, _, simple = name.rpartition("/")
    # Compose's singletons for Foo.kt's lambdas: ComposableSingletons$FooKt.
    if simple.startswith("ComposableSingletons$"):
        simple = simple[len("ComposableSingletons$"):]
    return package + "/" + simple.split("$")[0]


def profile_rules(text):
    """How many of a profile's rules name each top-level app class."""
    found = {}
    for line in text.splitlines():
        match = RULE.match(line)
        if match:
            name = top_level(match.group(1))
            found[name] = found.get(name, 0) + 1
    return found


def source_classes(text, file_name):
    """The top-level classes one Kotlin or Java source gives, as internal names."""
    match = PACKAGE_LINE.search(text)
    package = match.group(1).replace(".", "/") + "/" if match else ""
    names = set(DECLARATION.findall(text))
    stem, ext = os.path.splitext(file_name)
    if ext == ".kt":
        jvm_name = JVM_NAME.search(text)
        names.add(jvm_name.group(1) if jvm_name else stem + "Kt")
    elif ext == ".java":
        names.add(stem)
    return {package + n for n in names}


def all_source_classes(android_dir):
    found = set()
    for root, dirs, files in os.walk(android_dir):
        dirs[:] = [d for d in dirs if d not in ("build", ".gradle", ".idea", ".kotlin")]
        for f in files:
            if f.endswith((".kt", ".java")):
                with open(os.path.join(root, f), encoding="utf-8") as src:
                    found |= source_classes(src.read(), f)
    return found


def missing(named, present):
    """The classes named that no source gives, leaving out the build's own."""
    return sorted(c for c in named if c not in present and c.rpartition("/")[2] not in GENERATED)


def stale(stale_rules, rules):
    return stale_rules >= MIN_STALE_RULES and stale_rules > MAX_STALE_SHARE * rules


def main(argv):
    android_dir = argv[1] if len(argv) > 1 else "android"
    rules = {}
    for name in PROFILES:
        with open(os.path.join(android_dir, PROFILE_DIR, name), encoding="utf-8") as f:
            for c, n in profile_rules(f.read()).items():
                rules[c] = rules.get(c, 0) + n
    gone = missing(rules, all_source_classes(android_dir))
    total = sum(rules.values())
    stale_rules = sum(rules[c] for c in gone)
    print(
        f"{stale_rules} of the profiles' {total} rules for the app's code ({stale_rules / max(total, 1):.1%}) name "
        f"{len(gone)} of its {len(rules)} classes that are not in the sources."
    )
    for c in sorted(gone, key=lambda c: -rules[c])[:40]:
        print(f"  {rules[c]:5d}  " + c.replace("/", "."))
    if len(gone) > 40:
        print(f"  … and {len(gone) - 40} more")
    if stale(stale_rules, total):
        print(
            "The profiles are stale: generate them again (android/README.md, \"Baseline profile\") and commit the result.",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
