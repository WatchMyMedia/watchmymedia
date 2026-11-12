#!/usr/bin/env python3
"""Checks that the AGENTS.md and CLAUDE.md files still match the repository.

Every `make <target>` they name must be a Makefile target, and every path in
backticks or in a Markdown link must exist (or be gitignored, for build
outputs and secrets the files mention on purpose). A path is looked up from
the folder of the file that names it, then from the repository root. Run it
with `make agents-check` after moving files or renaming targets.
"""

import glob
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

# Things in backticks that look like paths but are not files here: branch
# names, URLs and URL paths, other machines' folders, and ./commands.
NOT_PATHS = ("taimoorq/", "WatchMyMedia/", "origin/", "refs/", "http://", "https://", "~/", "/", "./")
PATHLIKE = re.compile(r"^[\w.\-/*{},]+$")

# The public repository is a copy without the plans, the mockups, the site,
# the brand sources, the release and downloads scripts and the runner and
# signing notes
# (deploy/oss/private-paths.txt). The guidance still names them, for the
# maintainers who have them; there, a missing one of these is not a mistake.
PUBLIC_COPY = not os.path.exists(os.path.join(ROOT, "PLAN.md"))
UNPUBLISHED = re.compile(
    r"(^|/)(PLAN(-[A-Z0-9-]+)?\.md|(AGENTS|README)\.private\.md|SIGNING\.md|Makefile\.private)$"
    r"|(^|/)(design/mockups|site|deploy/(release|downloads|site|oss)|release|downloads|brand/(src|tools))(/|$)"
    r"|(^|/)ci/(mac-studio\.md|site_test\.py)$"
    r"|(^|/)\.github/workflows/downloads\.yml$"
)
UNPUBLISHED_TARGETS = {"release-tag", "download-stats", "site-dev", "site-check", "brand", "brand-sources"}


def guidance_files():
    out = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "--", "*AGENTS.md", "*AGENTS.private.md", "*CLAUDE.md"],
        cwd=ROOT, capture_output=True, text=True, check=True,
    ).stdout.split()
    return sorted(os.path.join(ROOT, f) for f in out)


def make_targets():
    targets = set(UNPUBLISHED_TARGETS) if PUBLIC_COPY else set()
    for name in ("Makefile", "Makefile.private"):
        path = os.path.join(ROOT, name)
        if os.path.exists(path):
            with open(path) as f:
                targets |= {m.group(1) for m in re.finditer(r"^([A-Za-z0-9_-]+):", f.read(), re.M)}
    return targets


def expand(token):
    m = re.search(r"\{([^{}]*)\}", token)
    if not m:
        return [token]
    return [p for alt in m.group(1).split(",") for p in expand(token[: m.start()] + alt + token[m.end():])]


def ignored(path):
    rel = os.path.relpath(path, ROOT)
    if rel.startswith(".."):
        return False
    # normpath removes the slash that identifies an absent ignored directory.
    # A documented glob also needs a concrete candidate for check-ignore.
    probes = (rel, rel + "/", rel.replace("*", "__agent_docs_probe__"))
    return any(subprocess.run(["git", "check-ignore", "-q", "--no-index", p], cwd=ROOT).returncode == 0 for p in probes)


def exists(token, base):
    if PUBLIC_COPY and UNPUBLISHED.search(os.path.normpath(token)):
        return True
    for start in (base, ROOT):
        for p in expand(token):
            full = os.path.normpath(os.path.join(start, p))
            if ("*" in p and glob.glob(full)) or os.path.exists(full) or ignored(full):
                return True
    return False


def looks_like_path(token):
    """A token with a folder in it. Bare names (`client.ts`, `play/`) are
    left alone: they are named relative to a row or sentence, not a folder."""
    if token.startswith(NOT_PATHS) or not PATHLIKE.match(token):
        return False
    return "/" in token.rstrip("/")


def main():
    targets = make_targets()
    problems = []
    for path in guidance_files():
        base = os.path.dirname(path)
        rel = os.path.relpath(path, ROOT)
        with open(path) as f:
            text = f.read()
        for n, line in enumerate(text.splitlines(), 1):
            for code in re.findall(r"`([^`]+)`", line):
                for t in re.findall(r"\bmake ([a-z][a-z0-9-]*)", code):
                    if t not in targets:
                        problems.append(f"{rel}:{n}: no Makefile target {t!r}")
            for link in re.findall(r"\]\(([^)#\s]+)(?:#[^)]*)?\)", line):
                if not link.startswith(("http://", "https://", "mailto:")) and not exists(link, base):
                    problems.append(f"{rel}:{n}: link to missing {link}")
            for code in re.findall(r"`([^`]+)`", line):
                for token in code.split():
                    token = token.rstrip(".,;:)")
                    if looks_like_path(token) and not exists(token, base):
                        problems.append(f"{rel}:{n}: missing path {token}")
    for p in problems:
        print(p)
    if problems:
        sys.exit(1)
    print(f"checked {len(guidance_files())} AGENTS.md and CLAUDE.md files")


if __name__ == "__main__":
    main()
