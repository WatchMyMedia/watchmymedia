#!/usr/bin/env python3
"""Which CI jobs a change runs.

Each job in .github/workflows/ci.yml builds or tests one part of the
repository, and runs only when that part changed. AREAS names what each part
is made from; the workflow's changes job runs this and gives every other job
its answer (`needs.changes.outputs.<area>`). A job runs when:

  - a file its area names changed, other than guidance (GUIDANCE);
  - its own definition in ci.yml changed. A change to another job's does not
    run it, and neither does a comment. A change to what every job shares
    (the triggers, the permissions, the concurrency group) runs them all;
  - a job that needs its output runs (ALSO);
  - the run was started by hand, or there is no commit to compare with.

The scripts job is not here: it takes seconds, its tests read files across
the repository, and it runs for every change.

In the changes job the event comes from the environment:

    EVENT=pull_request python3 -I deploy/ci/changes.py
    EVENT=push BEFORE=<sha> python3 -I deploy/ci/changes.py

Here, it prints what a pull request from this branch would run:

    python3 -I deploy/ci/changes.py            # against origin/main
    python3 -I deploy/ci/changes.py <commit>   # against another commit
"""
import os
import re
import subprocess
import sys

WORKFLOW = '.github/workflows/ci.yml'

# What each area is built or tested from: a name ending in / is a folder and
# everything under it, any other a file. deploy/ci/changes_test.py holds
# every job to one of these, and every name to something that is there.
AREAS = {
    # server and server-macos: every Go test, and what the Makefile's server
    # targets run.
    'server': ['server/', 'deploy/macos/fetch-ffmpeg.sh', 'Makefile'],
    # server-race.
    'race': ['server/', 'deploy/macos/fetch-ffmpeg.sh'],
    # contract: api/ and the web's copy and types against the server's code.
    'contract': ['server/', 'api/', 'web/src/api/', 'web/package.json', 'web/pnpm-lock.yaml', 'Makefile'],
    # web: the web app and the Cast receiver keep their own copies of the
    # icons, so brand/export is not theirs.
    'web': ['web/'],
    'docker': ['deploy/Dockerfile', 'deploy/entrypoint.sh', '.dockerignore', 'deploy/ci/docker-smoke.sh',
               'deploy/ci/prepare-docker.py', 'server/', 'web/'],
    # linux-packages and linux-packages-install, thirteen jobs, so this list
    # is that on purpose: the packaging and its make targets (deploy/linux),
    # the files it packs from elsewhere, and the two parts of the server the
    # wrapper and the service rely on, how it starts and where it looks for
    # its folders, its config and ffmpeg. Not the root Makefile, and not the
    # rest of server/ or web/: the server, web and docker jobs cover what
    # the packages hold of those.
    'linux-packages': ['deploy/linux/', 'deploy/macos/fetch-ffmpeg.sh', 'deploy/macos/FFMPEG-LICENSE.md',
                       'deploy/config.example.yaml', 'LICENSE', 'server/cmd/server/', 'server/internal/config/'],
    # android: its client is generated from api/ when it builds, and the job
    # ends with the baseline profile check.
    'android': ['android/', 'api/', 'deploy/ci/baseline_profile.py'],
    # apple and apple-tv: each app's own folders, the Kit and the project
    # they share, and the scripts their make targets run. Not api/: the apps
    # compile the Swift client committed in the Kit, which apple-api holds to
    # api/. Not apple/Tools, the generator apple-api builds. Not the scripts
    # only a release or a person runs (version.sh, generate.sh,
    # live-tv-fixture.py): version.sh's test is in the scripts job.
    'apple': ['apple/App/', 'apple/UITests/', 'apple/Kit/', 'apple/project.yml', 'apple/scripts/select-xcode.sh',
              'apple/scripts/install-xcodegen.sh', 'apple/scripts/simulator.sh', 'apple/scripts/check-release-binary.sh'],
    'apple-tv': ['apple/TV/', 'apple/TVUITests/', 'apple/TopShelf/', 'apple/Kit/', 'apple/project.yml',
                 'apple/scripts/select-xcode.sh', 'apple/scripts/install-xcodegen.sh', 'apple/scripts/simulator.sh',
                 'apple/scripts/check-release-binary.sh', 'apple/scripts/tv-ui-shard.py'],
    # apple-api: the committed Swift client is what api/ generates.
    'apple-api': ['api/', 'apple/Tools/', 'apple/Kit/Sources/WMMAPI/', 'apple/scripts/openapi-for-swift.py',
                  'apple/scripts/open-response-enums.py'],
    # windows-server, windows-live-tv and windows-app: the app holds the
    # server, the Makefile builds both, and its icons are brand/export's.
    'windows': ['windows/', 'deploy/windows/', 'brand/export/windows/', 'Makefile', 'server/',
                'deploy/macos/fetch-ffmpeg.sh'],
}

# Guidance for people and agents: no build reads it, wherever it is. The
# scripts job checks the paths and make targets it names.
GUIDANCE = ('AGENTS.md', 'CLAUDE.md', 'README.md')

# An area that runs brings these with it: windows-app installs the web
# job's build.
ALSO = {'windows': ['web']}

JOB = re.compile(r'(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:\n|\Z)')
GATE = re.compile(r"needs\.changes\.outputs\.([\w-]+) == 'true'")


def matches(path, name):
    return path.startswith(name) if name.endswith('/') else path == name


def changed_areas(files):
    """For each area, the changed files it is built from."""
    built = [path for path in files if path.rsplit('/', 1)[-1] not in GUIDANCE]
    return {area: [path for path in built if any(matches(path, name) for name in names)]
            for area, names in AREAS.items()}


def code(text):
    """A piece of the workflow as what runs: no comment lines, no blank ones."""
    return [line.rstrip() for line in text.splitlines() if line.strip() and not line.lstrip().startswith('#')]


def parts(workflow):
    """What every job shares (the text above jobs:), and each job's own."""
    shared, _, jobs = workflow.partition('\njobs:\n')
    return code(shared), {name: code(body) for name, body in JOB.findall(jobs)}


def edited_areas(before, after):
    """The areas whose jobs differ between two versions of the workflow."""
    shared_before, jobs_before = parts(before)
    shared_after, jobs_after = parts(after)
    if shared_before != shared_after:
        return set(AREAS)
    areas = set()
    for name, body in jobs_after.items():
        if jobs_before.get(name) != body:
            areas.update(GATE.findall('\n'.join(body)))
    return areas & set(AREAS)


def plan(files, workflow_before, workflow_after):
    """For each area, why it runs: empty when it doesn't."""
    why = changed_areas(files)
    if WORKFLOW in files:
        if workflow_before is None:
            return everything('there is no earlier ci.yml to compare with')
        for area in sorted(edited_areas(workflow_before, workflow_after)):
            why[area].append('its job in ci.yml')
    for area, others in ALSO.items():
        for other in others:
            if why[area] and not why[other]:
                why[other].append(f'{area} needs it')
    return why


def everything(reason):
    return {area: [reason] for area in AREAS}


def git(*args):
    return subprocess.run(['git', *args], check=True, capture_output=True, text=True).stdout


def have(commit):
    return subprocess.run(['git', 'cat-file', '-e', commit + '^{commit}'], capture_output=True).returncode == 0


def base_of(event, before):
    """The commit a run's changes are measured from, or None without one."""
    if event == 'pull_request':
        # The commit checked out merges the pull request into its base
        # branch, and its first parent is that branch (fetch-depth: 2).
        parents = git('rev-list', '--parents', '-n', '1', 'HEAD').split()[1:]
        return parents[0] if len(parents) == 2 else None
    if event == 'push' and before.strip('0'):
        if not have(before):
            subprocess.run(['git', 'fetch', '--quiet', '--no-tags', '--depth=1', 'origin', before], capture_output=True)
        return before if have(before) else None
    return None


def plan_from(base):
    files = [path for path in git('diff', '--name-only', '--no-renames', '-z', base, 'HEAD').split('\0') if path]
    shown = subprocess.run(['git', 'show', f'{base}:{WORKFLOW}'], capture_output=True, text=True)
    with open(WORKFLOW) as workflow:
        return plan(files, shown.stdout if shown.returncode == 0 else None, workflow.read())


def reason(started):
    return started[0] + (f' and {len(started) - 1} more' if len(started) > 1 else '')


def main(argv):
    event = os.environ.get('EVENT', '')
    if event == 'workflow_dispatch':
        why = everything('a manual run')
    elif event:
        base = base_of(event, os.environ.get('BEFORE', ''))
        why = plan_from(base) if base else everything('there is no earlier commit to compare with')
    else:
        why = plan_from(git('merge-base', argv[1] if len(argv) > 1 else 'origin/main', 'HEAD').strip())
    width = max(map(len, why))
    for area, started in why.items():
        print(f'{area:<{width}}  ' + (f'runs: {reason(started)}' if started else 'skipped'))
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            for area, started in why.items():
                output.write(f"{area}={'true' if started else 'false'}\n")
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
            summary.write('| Area | Runs for |\n|---|---|\n')
            for area, started in why.items():
                summary.write(f"| `{area}` | {reason(started) if started else 'skipped'} |\n")


if __name__ == '__main__':
    main(sys.argv)
