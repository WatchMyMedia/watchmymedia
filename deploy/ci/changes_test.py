"""Each CI job runs for what it builds or tests, and for nothing else."""
import importlib.util
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'deploy/ci/changes.py'
spec = importlib.util.spec_from_file_location('changes', SCRIPT)
changes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(changes)

# The areas each job waits for. scripts runs for every change, and
# windows-signing only when asked by hand.
GATES = {'server': ['server'], 'server-macos': ['server'], 'server-race': ['race'], 'contract': ['contract'],
         'web': ['web'], 'samsung': ['samsung'], 'docker': ['docker'], 'android': ['android'],
         'apple': ['apple', 'apple-api'], 'apple-tv': ['apple-tv'], 'windows-server': ['windows'],
         'windows-live-tv': ['windows'], 'windows-app': ['windows'], 'linux-packages': ['linux-packages'],
         'linux-packages-install': ['linux-packages']}
UNGATED = {'changes', 'scripts', 'windows-signing'}


def workflow():
    return (ROOT / changes.WORKFLOW).read_text()


def jobs():
    return dict(changes.JOB.findall(workflow().partition('\njobs:\n')[2]))


def running(why):
    return {area for area, started in why.items() if started}


class RulesTest(unittest.TestCase):
    def test_a_change_runs_the_jobs_built_from_it_and_no_others(self):
        server = {'server', 'race', 'contract', 'docker', 'windows', 'web'}
        for files, expected in (
                (['server/internal/api/activity.go'], server),
                # How the server starts is also what the Linux packages rely on.
                (['server/cmd/server/main.go'], server | {'linux-packages'}),
                (['web/src/App.tsx'], {'web', 'docker'}),
                (['web/receiver/main.ts', 'web/vite.receiver.config.ts'], {'web', 'docker'}),
                # The TV app's own files are its jobs' alone, and
                # what it shares with the web app runs both.
                (['web/tv/app/App.tsx'], {'samsung'}),
                (['web/vite.tv.config.ts', 'web/vite.tizen-core.config.ts', 'deploy/samsung/bundle.py'], {'samsung'}),
                (['brand/export/android/play-store-icon-512.png'], {'samsung'}),
                (['web/src/lib/play/rules.ts'], {'web', 'docker', 'samsung'}),
                (['web/src/api/schema.d.ts'], {'web', 'docker', 'contract', 'samsung'}),
                (['web/pnpm-lock.yaml'], {'web', 'docker', 'contract', 'samsung'}),
                (['web/tv/app/App.tsx', 'web/src/App.tsx'], {'web', 'docker', 'samsung'}),
                (['android/tv/src/main/kotlin/Home.kt'], {'android'}),
                (['apple/App/Screens/Home.swift', 'apple/UITests/HomeTests.swift'], {'apple'}),
                (['apple/TV/Screens/Home.swift', 'apple/TVUITests/HomeTests.swift'], {'apple-tv'}),
                (['apple/TopShelf/Provider.swift'], {'apple-tv'}),
                (['apple/Kit/Sources/WMMUI/Artwork.swift'], {'apple', 'apple-tv'}),
                (['apple/project.yml'], {'apple', 'apple-tv'}),
                # The API alone: the apps compile the committed client, so
                # only the check that it is current runs for them.
                (['api/openapi.json', 'api/openapi-3.0.json'], {'contract', 'android', 'apple-api'}),
                (['api/openapi.json', 'apple/Kit/Sources/WMMAPI/Generated/Types.swift'],
                 {'contract', 'android', 'apple-api', 'apple', 'apple-tv'}),
                (['apple/Tools/Package.resolved'], {'apple-api'}),
                # A release's version numbers: nothing CI builds reads them.
                (['CHANGELOG.md', 'apple/scripts/version.sh', 'deploy/release/tag.sh'], set()),
                (['apple/scripts/tv-ui-shard.py'], {'apple-tv'}),
                (['apple/scripts/simulator.sh'], {'apple', 'apple-tv'}),
                (['deploy/linux/nfpm.yaml'], {'linux-packages'}),
                (['deploy/ci/baseline_profile.py'], {'android'}),
                (['deploy/ci/docker-smoke.sh'], {'docker'}),
                (['brand/export/windows/tray-light.ico'], {'windows', 'web'}),
                # The web app keeps its own copies of the icons.
                (['brand/export/web/icon-512.png', 'brand/export/logo/logo.svg'], set()),
                (['windows/tray/main.go'], {'windows', 'web'}),
                (['Makefile'], {'server', 'contract', 'windows', 'web'}),
                (['.github/workflows/release.yml', 'deploy/ci/security_test.py'], set()),
                ([], set())):
            with self.subTest(files=files):
                self.assertEqual(expected, running(changes.plan(files, None, '')))

    def test_guidance_starts_no_build_wherever_it_is(self):
        for path in ('AGENTS.md', 'README.md', 'CHANGELOG.md', 'server/AGENTS.md', 'server/CLAUDE.md', 'web/tv/README.md',
                     'android/README.md', 'android/core/playback/README.md', 'windows/README.md', 'deploy/linux/README.md',
                     'apple/Kit/README.md'):
            with self.subTest(path=path):
                self.assertEqual(set(), running(changes.plan([path], None, '')))
        # A Markdown file a build packs or a test reads is not guidance.
        self.assertEqual({'linux-packages'}, running(changes.plan(['deploy/macos/FFMPEG-LICENSE.md'], None, '')))
        self.assertIn('server', running(changes.plan(['server/internal/playback/NOTES.md'], None, '')))

    def test_a_job_that_uses_anothers_build_runs_it(self):
        why = changes.plan(['windows/tray/main.go'], None, '')
        self.assertEqual(['windows needs it'], why['web'])
        # Named once, by what changed, when it runs for its own files too.
        self.assertEqual(['web/src/App.tsx'], changes.plan(['windows/tray/main.go', 'web/src/App.tsx'], None, '')['web'])

    def test_a_pull_requests_merge_does_not_build_the_apple_apps_again(self):
        server = {'server', 'race', 'contract', 'docker', 'windows', 'web'}
        for files, expected in (
                (['apple/App/Screens/Home.swift', 'apple/UITests/HomeTests.swift'], set()),
                (['apple/Kit/Sources/WMMUI/Artwork.swift', 'apple/TV/Screens/Home.swift'], set()),
                (['apple/scripts/simulator.sh'], set()),
                # What their package caches are keyed on: main builds, and
                # saves the caches pull requests read.
                (['apple/Kit/Package.resolved', 'apple/Kit/Sources/WMMUI/Artwork.swift'], {'apple', 'apple-tv'}),
                (['apple/project.yml'], {'apple', 'apple-tv'}),
                # The client check is not the apps, and every other job runs
                # as it does for any push: main saves their caches too.
                (['api/openapi.json', 'apple/Kit/Sources/WMMAPI/Generated/Types.swift'], {'contract', 'android', 'apple-api'}),
                (['server/internal/api/activity.go', 'android/tv/Home.kt', 'apple/App/Home.swift'], server | {'android'})):
            with self.subTest(files=files):
                self.assertEqual(expected, running(changes.plan(files, None, '', merged=True)))
                self.assertLessEqual(expected, running(changes.plan(files, None, '')))
        # Named by the pin alone, not by the code that came with it.
        why = changes.plan(['apple/App/Home.swift', 'apple/Kit/Package.resolved'], None, '', merged=True)
        self.assertEqual(['apple/Kit/Package.resolved'], why['apple'])
        # The pins are what the jobs' package caches are keyed on, and each
        # is a file both jobs are built from.
        ci = jobs()
        self.assertEqual(('apple', 'apple-tv'), changes.ONCE)
        for job in changes.ONCE:
            keys = re.findall(r"key: swift-packages-\$\{\{ runner\.os \}\}-\$\{\{ hashFiles\(([^)]*)\) \}\}-\$\{\{ github\.job \}\}",
                              ci[job])
            self.assertEqual([list(changes.PINS)], [re.findall(r"'([^']+)'", key) for key in keys], job)
            for pin in changes.PINS:
                self.assertTrue(any(changes.matches(pin, name) for name in changes.AREAS[job]), pin)

    def test_a_name_is_a_folder_or_a_file_that_is_there(self):
        self.assertTrue(changes.matches('server/internal/api/activity.go', 'server/'))
        self.assertFalse(changes.matches('server-notes/x.go', 'server/'))
        self.assertFalse(changes.matches('deploy/server/x.go', 'server/'))
        self.assertTrue(changes.matches('Makefile', 'Makefile'))
        self.assertFalse(changes.matches('deploy/linux/Makefile', 'Makefile'))
        self.assertFalse(changes.matches('Makefile.old', 'Makefile'))
        for area, names in [*changes.AREAS.items(), *changes.ELSEWHERE.items()]:
            for name in names:
                with self.subTest(area=area, name=name):
                    # Written as git lists paths, with no patterns, and
                    # there: a folder that moved away would start nothing.
                    self.assertNotRegex(name, r'^(\./|/)|[*?\[]|//')
                    found = ROOT / name
                    self.assertTrue(found.is_dir() if name.endswith('/') else found.is_file(), 'is not there')

    def test_what_an_area_leaves_to_another_job_starts_that_job(self):
        # The TV app's files are in web/, and only the TV jobs build them:
        # none falls between the two.
        for area, names in changes.ELSEWHERE.items():
            for name in names:
                path = name + 'App.tsx' if name.endswith('/') else name
                with self.subTest(area=area, path=path):
                    self.assertTrue(any(changes.matches(path, listed) for listed in changes.AREAS[area]))
                    started = running(changes.plan([path], None, ''))
                    self.assertNotIn(area, started)
                    self.assertTrue(started)

    def test_every_apple_script_starts_a_job_or_is_named_as_one_ci_does_not_run(self):
        # A script the apps' make targets run starts their jobs; a new one
        # has to be put on one side or the other.
        elsewhere = {'version.sh': 'a release numbers its builds with it; deploy/release/version_test.py',
                     'generate.sh': 'make apple-gen VERSION=…, for a release',
                     'live-tv-fixture.py': 'started by hand for the opt-in Live TV tests',
                     'open_response_enums_test.py': 'the scripts job runs it'}
        apple = [name for area in ('apple', 'apple-tv', 'apple-api') for name in changes.AREAS[area]]
        scripts = sorted(path.name for path in (ROOT / 'apple/scripts').iterdir() if path.is_file())
        self.assertLessEqual(set(elsewhere), set(scripts))
        for name in scripts:
            with self.subTest(script=name):
                starts = any(changes.matches(f'apple/scripts/{name}', listed) for listed in apple)
                self.assertNotEqual(starts, name in elsewhere)
        # What the jobs and their make targets call is on the starting side.
        makefile = (ROOT / 'Makefile').read_text()
        ci = jobs()
        for job in ('apple', 'apple-tv'):
            targets = re.findall(r'make (apple[\w-]+)', ci[job])
            self.assertTrue(targets)
            called = set(re.findall(r'apple/scripts/[\w.-]+', ci[job]))
            for target in targets + ['apple-gen']:
                recipe = re.search(rf'(?m)^{target}:.*\n((?:\t.*\n)+)', makefile).group(1)
                called.update(re.findall(r'apple/scripts/[\w.-]+', recipe))
            listed = [name for area in GATES[job] for name in changes.AREAS[area]]
            # generate.sh is apple-gen's other branch, taken with VERSION=.
            for path in sorted(called - {'apple/scripts/generate.sh'}):
                with self.subTest(job=job, script=path):
                    self.assertTrue(any(changes.matches(path, name) for name in listed))


class WorkflowEditsTest(unittest.TestCase):
    BEFORE = ('name: CI\n\non:\n  pull_request:\n\n# Why.\npermissions:\n  contents: read\n\njobs:\n'
              '  changes:\n    runs-on: ubuntu-latest\n    steps:\n      - run: python3 -I deploy/ci/changes.py\n\n'
              '  # The server.\n  server:\n    needs: changes\n    if: needs.changes.outputs.server == \'true\'\n'
              '    steps:\n      - run: go test ./...\n\n'
              '  apple:\n    needs: changes\n'
              '    if: needs.changes.outputs.apple == \'true\' || needs.changes.outputs.apple-api == \'true\'\n'
              '    steps:\n      # Builds.\n      - run: make apple-test-sim\n\n'
              '  scripts:\n    steps:\n      - run: python3 -I -m unittest\n')

    def edited(self, old, new):
        self.assertIn(old, self.BEFORE)
        return changes.edited_areas(self.BEFORE, self.BEFORE.replace(old, new))

    def test_a_change_to_a_job_runs_that_job_alone(self):
        self.assertEqual({'server'}, self.edited('go test ./...', 'go test -race ./...'))
        self.assertEqual({'apple', 'apple-api'}, self.edited('make apple-test-sim', 'make apple-test-sim SIM=x'))

    def test_comments_and_blank_lines_run_nothing(self):
        self.assertEqual(set(), self.edited('  # The server.\n', '  # The server, on Linux.\n\n'))
        self.assertEqual(set(), self.edited('      # Builds.\n', ''))
        self.assertEqual(set(), self.edited('# Why.\n', '# Why not.\n'))

    def test_jobs_that_wait_for_no_area_run_nothing_else(self):
        self.assertEqual(set(), self.edited('python3 -I deploy/ci/changes.py', 'python3 -I deploy/ci/changes.py -v'))
        self.assertEqual(set(), self.edited('python3 -I -m unittest', 'python3 -I -m unittest -v'))

    def test_what_every_job_shares_runs_them_all(self):
        self.assertEqual(set(changes.AREAS), self.edited('  contents: read\n', '  contents: read\n  actions: read\n'))
        self.assertEqual(set(changes.AREAS), self.edited('  pull_request:\n', '  pull_request:\n  push:\n'))

    def test_a_new_job_runs_and_a_removed_one_runs_nothing(self):
        new = "  android:\n    needs: changes\n    if: needs.changes.outputs.android == 'true'\n    steps:\n      - run: ./gradlew\n\n"
        self.assertEqual({'android'}, self.edited('  scripts:\n', new + '  scripts:\n'))
        without = self.BEFORE[:self.BEFORE.index('  # The server.')] + self.BEFORE[self.BEFORE.index('  apple:'):]
        self.assertEqual(set(), changes.edited_areas(self.BEFORE, without))

    def test_each_job_here_runs_for_a_change_to_itself(self):
        # The real workflow: a line added to any one job starts that job's
        # areas and no others.
        text = workflow()
        shared, _, body = text.partition('\njobs:\n')
        for match in changes.JOB.finditer(body):
            name = match.group(1)
            with self.subTest(job=name):
                after = shared + '\njobs:\n' + body[:match.end(1)] + ':\n    timeout-minutes: 99' + body[match.end(1) + 1:]
                self.assertEqual(text.count('\n') + 1, after.count('\n'))
                self.assertEqual(set(GATES.get(name, [])), changes.edited_areas(text, after))
                self.assertEqual(set(GATES.get(name, [])) | ({'web'} if name.startswith('windows-') and name in GATES else set()),
                                 running(changes.plan([changes.WORKFLOW], text, after)))
                # On main too: a job's cache-saving steps run nowhere else.
                self.assertEqual(running(changes.plan([changes.WORKFLOW, 'apple/App/Home.swift'], text, after)) - {'apple'}
                                 | set(GATES.get(name, [])),
                                 running(changes.plan([changes.WORKFLOW, 'apple/App/Home.swift'], text, after, merged=True)))
        self.assertEqual(set(changes.AREAS), running(changes.plan([changes.WORKFLOW], None, text)))
        self.assertEqual(set(changes.AREAS), running(changes.plan([changes.WORKFLOW], None, text, merged=True)))

    def test_every_job_waits_for_its_area(self):
        ci = jobs()
        self.assertEqual(set(GATES) | UNGATED, set(ci))
        for name, areas in GATES.items():
            with self.subTest(job=name):
                self.assertRegex(ci[name], r'(?m)^    needs: (changes|\[changes[^\]]*\])$')
                condition = re.search(r'(?m)^    if: (.+)$', ci[name]).group(1)
                self.assertEqual(areas, changes.GATE.findall(condition))
        # Every area starts a job, and the changes job hands each one on.
        self.assertEqual(set(changes.AREAS), {area for areas in GATES.values() for area in areas})
        outputs = re.findall(r'(?m)^      ([\w-]+): \$\{\{ steps\.areas\.outputs\.([\w-]+) \}\}$', ci['changes'])
        self.assertEqual(sorted(changes.AREAS), sorted(area for area, _ in outputs))
        for area, output in outputs:
            self.assertEqual(area, output)
        # The scripts job waits for nothing and runs for every change.
        self.assertNotRegex(ci['scripts'], r'(?m)^    (needs|if):')

    def test_the_changes_job_reads_the_change_from_git_alone(self):
        job = jobs()['changes']
        self.assertIn('        with:\n          fetch-depth: 2\n', job)
        self.assertIn('        id: areas\n        env:\n          EVENT: ${{ github.event_name }}\n'
                      '          BEFORE: ${{ github.event.before }}\n        run: python3 -I deploy/ci/changes.py\n', job)
        # No action and no token decide what runs, and nothing a pull
        # request's author writes (a title, a branch name) reaches the shell.
        self.assertEqual(1, job.count('uses: '))
        for word in ('permissions:', 'pull-requests', 'github.head_ref', 'github.event.pull_request', 'paths-filter'):
            self.assertNotIn(word, workflow() if word == 'paths-filter' else job)


class RunTest(unittest.TestCase):
    """The script against a repository, cloned as a job checks it out."""

    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.root = pathlib.Path(folder.name)
        self.env = {'PATH': os.environ['PATH'], 'HOME': str(self.root), 'GIT_CONFIG_GLOBAL': os.devnull,
                    'GIT_CONFIG_NOSYSTEM': '1', 'GIT_AUTHOR_NAME': 'Test', 'GIT_AUTHOR_EMAIL': 'test@example.com',
                    'GIT_COMMITTER_NAME': 'Test', 'GIT_COMMITTER_EMAIL': 'test@example.com'}
        self.origin = self.root / 'origin'
        self.origin.mkdir()
        self.git('init', '--quiet', '--initial-branch=main')
        # As GitHub serves a commit that a branch reaches.
        self.git('config', 'uploadpack.allowReachableSHA1InWant', 'true')
        self.write({changes.WORKFLOW: workflow(), 'server/main.go': 'package main\n', 'android/app.kt': '\n'})
        self.first = self.commit('First')

    def git(self, *args, cwd=None):
        return subprocess.run(['git', *args], cwd=cwd or self.origin, env=self.env, check=True,
                              capture_output=True, text=True).stdout.strip()

    def write(self, files):
        for path, text in files.items():
            (self.origin / path).parent.mkdir(parents=True, exist_ok=True)
            (self.origin / path).write_text(text)

    def commit(self, message):
        self.git('add', '--all')
        self.git('commit', '--quiet', '--message', message)
        return self.git('rev-parse', 'HEAD')

    def checkout(self, ref):
        """A clone two commits deep at ref, as actions/checkout makes with fetch-depth: 2."""
        clone = self.root / f'clone-{len(list(self.root.iterdir()))}'
        clone.mkdir()
        self.git('init', '--quiet', cwd=clone)
        self.git('remote', 'add', 'origin', self.origin.as_uri(), cwd=clone)
        self.git('fetch', '--quiet', '--no-tags', '--depth=2', 'origin', ref, cwd=clone)
        self.git('checkout', '--quiet', '--detach', 'FETCH_HEAD', cwd=clone)
        return clone

    def run_script(self, clone, **env):
        output = self.root / 'output'
        output.write_text('')
        result = subprocess.run([sys.executable, '-I', str(SCRIPT)], cwd=clone, capture_output=True, text=True,
                                env=dict(self.env, GITHUB_OUTPUT=str(output), **env))
        self.assertEqual(0, result.returncode, result.stderr)
        answers = dict(line.split('=') for line in output.read_text().splitlines())
        self.assertEqual(set(changes.AREAS), set(answers))
        self.assertLessEqual(set(answers.values()), {'true', 'false'})
        return {area for area, answer in answers.items() if answer == 'true'}, result.stdout

    def pull_request(self, files, message='Merge pull request #7 from WatchMyMedia/topic'):
        """A branch with a change, merged into main as GitHub merges one; main moves on first."""
        self.git('checkout', '--quiet', '-b', 'topic')
        self.write(files)
        self.commit('Change')
        self.git('checkout', '--quiet', 'main')
        self.write({'web/src/App.tsx': '\n'})
        self.commit('Something else lands on main')
        self.git('merge', '--quiet', '--no-ff', '--message', message, 'topic')
        return self.git('rev-parse', 'HEAD')

    def test_a_pull_request_runs_for_its_own_changes_not_mains(self):
        merge = self.pull_request({'android/app.kt': 'class App\n', 'android/README.md': 'Notes\n'})
        ran, printed = self.run_script(self.checkout(merge), EVENT='pull_request')
        self.assertEqual({'android'}, ran)
        self.assertRegex(printed, r'(?m)^android +runs: android/app\.kt$')
        self.assertRegex(printed, r'(?m)^web +skipped$')

    def test_a_pull_request_that_changes_one_job_runs_that_job(self):
        text = workflow()
        self.assertIn('    runs-on: macos-26\n', text)
        merge = self.pull_request({changes.WORKFLOW: text.replace('    runs-on: macos-26\n', '    runs-on: macos-27\n')})
        ran, printed = self.run_script(self.checkout(merge), EVENT='pull_request')
        self.assertEqual({'server'}, ran)
        self.assertRegex(printed, r'(?m)^server +runs: its job in ci\.yml$')

    def test_a_push_runs_for_everything_since_the_commit_before_it(self):
        self.write({'server/main.go': 'package main\n\nfunc main() {}\n'})
        self.commit('Second')
        self.write({'CHANGELOG.md': 'Notes\n'})
        self.commit('Third')
        self.write({'deploy/linux/nfpm.yaml': 'name: watchmymedia\n'})
        third = self.commit('Fourth')
        # Three commits pushed at once: the first is further back than the
        # clone goes, and the script fetches it.
        clone = self.checkout(third)
        self.assertNotEqual(0, subprocess.run(['git', 'cat-file', '-e', self.first], cwd=clone, env=self.env,
                                              capture_output=True).returncode)
        ran, _ = self.run_script(clone, EVENT='push', BEFORE=self.first)
        self.assertEqual({'server', 'race', 'contract', 'docker', 'windows', 'web', 'linux-packages'}, ran)

    def test_a_merge_on_main_leaves_the_apple_apps_to_its_pull_request(self):
        merge = self.pull_request({'apple/App/Home.swift': '\n', 'apple/TV/Home.swift': '\n', 'android/app.kt': 'class App\n'})
        clone = self.checkout(merge)
        before = self.git('rev-parse', merge + '^1')
        # The pull request's run builds them, whatever its commit looks like.
        self.assertEqual({'apple', 'apple-tv', 'android'}, self.run_script(clone, EVENT='pull_request')[0])
        self.assertEqual({'apple', 'apple-tv', 'android'}, self.run_script(clone, EVENT='pull_request', BEFORE=before)[0])
        # The push that merges it builds the rest again, whose caches main saves.
        ran, printed = self.run_script(clone, EVENT='push', BEFORE=before)
        self.assertEqual({'android'}, ran)
        self.assertRegex(printed, r"(?m)^apple +skipped: the pull request's run built it$")
        self.assertRegex(printed, r"(?m)^apple-tv +skipped: the pull request's run built it$")
        self.assertRegex(printed, r'(?m)^server +skipped$')

    def test_a_merge_on_main_builds_the_apple_apps_for_new_package_pins(self):
        merge = self.pull_request({'apple/App/Home.swift': '\n', 'apple/Kit/Package.resolved': '{}\n'})
        ran, printed = self.run_script(self.checkout(merge), EVENT='push', BEFORE=self.git('rev-parse', merge + '^1'))
        self.assertEqual({'apple', 'apple-tv'}, ran)
        self.assertRegex(printed, r'(?m)^apple +runs: apple/Kit/Package\.resolved$')

    def test_a_push_no_pull_request_ran_builds_the_apple_apps(self):
        # Straight to main: nothing has built it.
        self.write({'apple/App/Home.swift': '\n'})
        direct = self.commit('Change')
        self.assertEqual({'apple'}, self.run_script(self.checkout(direct), EVENT='push', BEFORE=self.first)[0])
        # A merge made by hand, not by GitHub for a pull request.
        self.git('reset', '--quiet', '--hard', self.first)
        by_hand = self.pull_request({'apple/App/Home.swift': '\n'}, message="Merge branch 'topic'")
        self.assertEqual({'apple'}, self.run_script(self.checkout(by_hand), EVENT='push',
                                                    BEFORE=self.git('rev-parse', by_hand + '^1'))[0])
        # A pull request's merge pushed with a commit before it that no run saw.
        self.git('checkout', '--quiet', '-B', 'main', self.first)
        self.git('branch', '--quiet', '-D', 'topic')
        merge = self.pull_request({'apple/App/Home.swift': '\n'})
        self.assertEqual({'apple', 'web', 'docker'}, self.run_script(self.checkout(merge), EVENT='push', BEFORE=self.first)[0])

    def test_everything_runs_by_hand_and_without_a_commit_to_compare_with(self):
        clone = self.checkout(self.first)
        everything = set(changes.AREAS)
        for env in ({'EVENT': 'workflow_dispatch'}, {'EVENT': 'push', 'BEFORE': '0' * 40},
                    {'EVENT': 'push', 'BEFORE': 'f' * 40}, {'EVENT': 'push'}, {'EVENT': 'schedule'},
                    # Not a merge: a pull request's commit always is one.
                    {'EVENT': 'pull_request'}):
            with self.subTest(env=env):
                self.assertEqual(everything, self.run_script(clone, **env)[0])

    def test_here_it_prints_what_a_branch_would_run(self):
        self.git('checkout', '--quiet', '-b', 'topic')
        self.write({'apple/App/Home.swift': '\n'})
        self.commit('Change')
        result = subprocess.run([sys.executable, '-I', str(SCRIPT), 'main'], cwd=self.origin, env=self.env,
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertRegex(result.stdout, r'(?m)^apple +runs: apple/App/Home\.swift$')
        self.assertRegex(result.stdout, r'(?m)^apple-tv +skipped$')


if __name__ == '__main__':
    unittest.main()
