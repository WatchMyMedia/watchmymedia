"""Keep trusted CI routing explicit and concurrent runners isolated."""
import ast
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


def evaluate_runner(expression, context):
    if not expression.startswith('${{'):
        return expression
    expression = expression[3:-2].strip()
    expression = re.sub(r"fromJSON\('([^']+)'\)", lambda match: repr(json.loads(match[1])), expression)
    expression = re.sub(r'github\.[a-zA-Z_.]+', lambda match: repr(context[match[0]]), expression)
    expression = expression.replace('&&', ' and ').replace('||', ' or ')

    def evaluate(node):
        if isinstance(node, ast.Constant):
            return node.value
        if isinstance(node, ast.List):
            return [evaluate(value) for value in node.elts]
        if isinstance(node, ast.Compare) and len(node.ops) == 1 and isinstance(node.ops[0], ast.Eq):
            return evaluate(node.left) == evaluate(node.comparators[0])
        if isinstance(node, ast.BoolOp):
            value = evaluate(node.values[0])
            for right in node.values[1:]:
                if isinstance(node.op, ast.And):
                    value = value and evaluate(right)
                elif isinstance(node.op, ast.Or):
                    value = value or evaluate(right)
                else:
                    raise AssertionError('Unsupported runner operator')
            return value
        raise AssertionError('Unsupported runner expression: ' + ast.dump(node))

    return evaluate(ast.parse(expression, mode='eval').body)


class WorkflowIsolationTest(unittest.TestCase):
    def test_runner_routing_for_trusted_and_untrusted_events(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        jobs = dict(re.findall(r'(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',
                               workflow.split('jobs:\n', 1)[1]))
        hosted = dict.fromkeys(('changes', 'web', 'contract', 'docker', 'scripts', 'android', 'server', 'server-race'), 'ubuntu-latest')
        # macOS runners are the scarce ones: only what needs a Mac runs there.
        hosted.update(dict.fromkeys(('server-macos', 'apple', 'apple-tv'), 'macos-26'))
        hosted['windows-server'] = hosted['windows-app'] = hosted['windows-signing'] = 'windows-latest'
        self.assertEqual(set(hosted), set(jobs))
        studio = ['self-hosted', 'macOS', 'ARM64', 'mac-studio']
        # Exercise the actual runs-on expressions, not a second routing helper.
        cases = (
            ('pull_request', True, 'owner/project', 'refs/pull/1/merge', True),
            ('pull_request', True, 'fork/project', 'refs/pull/1/merge', False),
            ('pull_request', True, '', 'refs/pull/1/merge', False),
            ('push', True, '', 'refs/heads/main', True),
            ('push', True, '', 'refs/heads/feature', False),
            ('workflow_dispatch', True, '', 'refs/heads/feature', True),
            ('pull_request_target', True, 'owner/project', 'refs/heads/main', False),
        )
        for job, body in jobs.items():
            runner = re.search(r'(?m)^    runs-on: (.+)$', body).group(1)
            for event, private, head, ref, trusted in cases:
                for visibility in (private, False):
                    context = {'github.event_name': event,
                               'github.event.repository.private': visibility,
                               'github.event.pull_request.head.repo.full_name': head,
                               'github.repository': 'owner/project', 'github.ref': ref}
                    expected = studio if trusted and visibility and not job.startswith('windows') else hosted[job]
                    with self.subTest(job=job, event=event, private=visibility, head=head, ref=ref):
                        self.assertEqual(expected, evaluate_runner(runner, context))

    def test_every_job_runs_only_for_what_it_checks(self):
        # Each job waits on the changes job and runs when its own paths, this
        # workflow or a manual run say so, so a docs or template change does
        # not run the server's race tests or build the apps.
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        jobs = dict(re.findall(r'(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',
                               workflow.split('jobs:\n', 1)[1]))
        gated = {'server': 'server', 'server-macos': 'server', 'server-race': 'race', 'contract': 'contract', 'web': 'web',
                 'docker': 'docker', 'scripts': 'scripts', 'android': 'android', 'apple': 'apple',
                 'apple-tv': 'apple-tv', 'windows-server': 'windows', 'windows-app': 'windows'}
        self.assertEqual(set(gated) | {'changes', 'windows-signing'}, set(jobs))
        for job, output in gated.items():
            with self.subTest(job=job):
                self.assertRegex(jobs[job], r'(?m)^    needs: (changes|\[changes[^\]]*\])$')
                self.assertIn(f"needs.changes.outputs.{output} == 'true'", jobs[job])
        outputs = dict(re.findall(r"(?m)^      ([\w-]+): (\$\{\{ github\.event_name == 'workflow_dispatch' \|\|.*)$", jobs['changes']))
        # apple-api gates a step, not a job: the apple job's API client check.
        self.assertEqual(set(gated.values()) | {'apple-api'}, set(outputs))
        self.assertIn("needs.changes.outputs.apple-api == 'true'", jobs['apple'])
        filters = jobs['changes'].split('filters: |', 1)[1]
        for output in outputs:
            with self.subTest(output=output):
                block = re.search(rf'(?ms)^            {output}:\n((?:              - .*\n)+)', filters).group(1)
                if output != 'scripts':
                    self.assertIn("'.github/workflows/ci.yml'", block)
        # windows-app installs the web job's build, so web runs with it.
        self.assertIn("steps.filter.outputs.windows == 'true'", outputs['web'])

    def test_windows_installer_embeds_the_web_build_from_the_same_run(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        jobs = dict(re.findall(r'(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',
                               workflow.split('jobs:\n', 1)[1]))
        self.assertIn('needs: [changes, web]', jobs['windows-app'])
        self.assertIn('name: windows-embedded-web', jobs['web'])
        self.assertIn('path: web/dist/', jobs['web'])
        self.assertIn('actions/download-artifact@', jobs['windows-app'])
        self.assertIn('name: windows-embedded-web', jobs['windows-app'])
        self.assertIn('path: server/internal/webui/dist', jobs['windows-app'])
        self.assertNotIn('windows/build.sh --smoke', jobs['windows-app'])
        # The server's tests need no web build, so they don't wait for one.
        for name in ('server', 'windows-server'):
            self.assertNotIn('actions/download-artifact@', jobs[name])
        self.assertRegex(jobs['windows-server'], r'(?m)^    needs: changes$')

    def test_server_tests_run_on_linux_and_macos_keeps_its_own_code(self):
        # Every Go test, the lint and the generated-code check run on Linux;
        # the scarce macOS runner tests only the packages with macOS code,
        # and that code under the race detector.
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        jobs = dict(re.findall(r'(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',
                               workflow.split('jobs:\n', 1)[1]))
        server, mac = jobs['server'], jobs['server-macos']
        for marker in ("go test -skip 'Speed$' ./...\n", "go test -p 1 -run 'Speed$' ./...", 'golangci/golangci-lint-action@',
                       'cmd/sqlc@v1.31.1 generate', 'govulncheck@', 'GOOS=windows GOARCH=amd64 go vet ./...'):
            with self.subTest(marker=marker):
                self.assertIn(marker, server)
                self.assertNotIn(marker, mac)
        self.assertIn("go test -skip 'Speed$' ./internal/ffexec ./internal/scanner ./internal/playback "
                      "./internal/livetv ./internal/livetv/dvr\n", mac)
        self.assertIn('go test -race -short ./internal/ffexec', mac)
        self.assertIn("go test -race -short -run 'Watch' ./internal/scanner", mac)
        self.assertIn('deploy/macos/fetch-ffmpeg.sh arm64', mac)
        # Every package with a file built only for macOS is one the macOS job tests.
        tested = set(re.search(r"go test -skip 'Speed\$' ((?:\./internal/\S+ ?)+)", mac).group(1).split())
        for path in sorted((ROOT / 'server').rglob('*_darwin.go')):
            with self.subTest(file=path.name):
                self.assertIn('./' + path.parent.relative_to(ROOT / 'server').as_posix(), tested)
        # The image is built from what the Linux job tested.
        self.assertIn('needs: [changes, server, web]', jobs['docker'])

    def test_windows_server_and_app_split_the_work_once(self):
        # Two jobs at once instead of one after the other: the Go tests and
        # qualifications in windows-server, the app, its installers and their
        # smoke tests in windows-app, each step in exactly one of them.
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        jobs = dict(re.findall(r'(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',
                               workflow.split('jobs:\n', 1)[1]))
        server, app = jobs['windows-server'], jobs['windows-app']
        for marker in ("go test -skip 'Speed$' ./...", "go test -p 1 -run 'Speed$' ./...",
                       "-run '^TestRemuxSession$' -count=50", 'TestLiveLadder', 'TestVODLadder',
                       'probe-transcoder', 'windows-transcoder-report'):
            with self.subTest(marker=marker):
                self.assertIn(marker, server)
                self.assertNotIn(marker, app)
        for marker in ('hardware-check-test.ps1', 'signing-test.ps1', 'windows/build.sh', 'TestPinnedUpdaterDLL',
                       'hardware-check.ps1 -AppDir', 'installer.sh', 'platform-smoke.ps1', 'windows/smoke.ps1',
                       'installer-smoke.ps1', 'windows-development-installer', 'windows-tray-logs'):
            with self.subTest(marker=marker):
                self.assertIn(marker, app)
                self.assertNotIn(marker, server)
        # The Live TV qualification runs twice on main and manual runs, once on PRs.
        self.assertIn("-run '^TestLiveLadder.*Qualification$' -count=${{ github.event_name == 'pull_request' && 1 || 2 }} -v", server)

    def test_workflow_keeps_read_only_permissions_and_fork_boundary(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertRegex(workflow, r'(?m)^  pull_request:$')
        self.assertNotIn('pull_request_target:', workflow)
        # One secret only: the key that encrypts Gradle's configuration cache,
        # read by setup-gradle in the android job. Nothing else in CI may read
        # a secret, so a pull request's code can't reach one that matters.
        self.assertEqual(['secrets.GRADLE_ENCRYPTION_KEY'], re.findall(r'secrets\.\w+', workflow))
        self.assertEqual(1, workflow.count('secrets'))  # no secrets['…'] or toJSON(secrets)
        android = re.search(r'(?ms)^  android:\n(.*?)(?=^  [\w-]+:\n)', workflow).group(1)
        self.assertIn('          cache-encryption-key: ${{ secrets.GRADLE_ENCRYPTION_KEY }}\n', android)
        self.assertNotIn('vars.CI_RUNNER', workflow)
        self.assertRegex(workflow, r'permissions:\n  contents: read\n')

    def test_windows_signing_runs_only_when_asked_with_its_environment(self):
        # The only job that can sign as Eldwick Software LLC: a manual run that
        # asks for it, through the windows-signing environment's OIDC identity.
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        jobs = dict(re.findall(r'(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',
                               workflow.split('jobs:\n', 1)[1]))
        signing = jobs['windows-signing']
        self.assertRegex(workflow, r"(?m)^      sign_windows:\n(?:        .+\n)*        type: boolean\n        default: false$")
        self.assertIn("    if: github.event_name == 'workflow_dispatch' && inputs.sign_windows\n", signing)
        self.assertIn('    environment: windows-signing\n', signing)
        self.assertIn('    permissions:\n      contents: read\n      id-token: write\n', signing)
        self.assertEqual(1, workflow.count('id-token:'))
        self.assertEqual(1, workflow.count('environment: windows-signing'))
        for other in set(jobs) - {'windows-signing'}:
            for marker in ('azure/login@', 'sign.ps1', '--sign', 'setup-signing.ps1', 'ARTIFACT_SIGNING_'):
                with self.subTest(job=other, marker=marker):
                    self.assertNotIn(marker, jobs[other])
        login = re.search(r'(?ms)- uses: azure/login@[^\n]+\n        with:\n(.*?)(?=      -)', signing).group(1)
        self.assertEqual({'client-id': '${{ vars.AZURE_SIGNING_CLIENT_ID }}',
                          'tenant-id': '${{ vars.AZURE_TENANT_ID }}',
                          'allow-no-subscriptions': 'true'},
                         dict(re.findall(r'(?m)^          ([\w-]+): (.+)$', login)))
        # Nothing CI-only is signed, and the gate runs before the upload.
        self.assertNotIn('--ci-server-host', signing)
        order = ['windows/build.sh', 'setup-signing.ps1', 'azure/login@', 'sign.ps1 -Path',
                 'installer.sh --sign', 'verify-installer.ps1', 'signing-smoke.ps1', 'actions/upload-artifact@']
        positions = [signing.index(marker) for marker in order]
        self.assertEqual(sorted(positions), positions)

    def test_release_signs_windows_only_in_its_job(self):
        workflow = (ROOT / '.github/workflows/release.yml').read_text()
        jobs = dict(re.findall(r'(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',
                               workflow.split('jobs:\n', 1)[1]))
        windows = jobs['windows']
        self.assertIn('    runs-on: windows-latest\n', windows)
        self.assertIn('    environment: windows-signing\n', windows)
        self.assertIn('    permissions:\n      contents: write\n      id-token: write\n', windows)
        self.assertEqual(1, workflow.count('id-token:'))
        self.assertEqual(1, workflow.count('environment: windows-signing'))
        for other in set(jobs) - {'windows'}:
            for marker in ('azure/login@', 'sign.ps1', '--sign', 'setup-signing.ps1', 'ARTIFACT_SIGNING_'):
                with self.subTest(job=other, marker=marker):
                    self.assertNotIn(marker, jobs[other])
        login = re.search(r'(?ms)- uses: azure/login@[^\n]+\n        with:\n(.*?)(?=      -)', windows).group(1)
        self.assertEqual({'client-id': '${{ vars.AZURE_SIGNING_CLIENT_ID }}',
                          'tenant-id': '${{ vars.AZURE_TENANT_ID }}',
                          'allow-no-subscriptions': 'true'},
                         dict(re.findall(r'(?m)^          ([\w-]+): (.+)$', login)))
        self.assertNotIn('--ci-server-host', windows)
        order = ['windows/build.sh', 'setup-signing.ps1', 'azure/login@', 'sign.ps1 -Path',
                 'installer.sh --sign', 'verify-installer.ps1', 'action-gh-release@']
        positions = [windows.index(marker) for marker in order]
        self.assertEqual(sorted(positions), positions)
        # Only the name verify-installer.ps1 gives a checked installer leaves the job.
        outputs = re.findall(r'(?ms)- uses: (?:softprops/action-gh-release|actions/upload-artifact)@[^\n]+\n'
                             r'        with:\n(?:          (?!files|path)[^\n]*\n)*          (?:files|path): ([^\n]+)', windows)
        self.assertEqual(['build/windows-installer/WatchMyMedia-*-x64-setup.exe'], outputs)

    def test_release_runs_on_hosted_runners_without_caches_and_with_least_permissions(self):
        # A release is built from the tagged commit alone, on a machine nobody
        # else has used, and a job can write only what it publishes.
        workflow = (ROOT / '.github/workflows/release.yml').read_text()
        self.assertIn('\non:\n  push:\n    tags: ["v*"]\n\n', workflow)
        self.assertIn('\npermissions:\n  contents: read\n', workflow)
        self.assertNotIn('self-hosted', workflow)
        self.assertNotIn('actions/cache@', workflow)
        self.assertNotIn('type=gha', workflow)
        self.assertEqual(set(), set(re.findall(r'(?m)^          cache: (.+)$', workflow)) - {'false'})
        jobs = dict(re.findall(r'(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',
                               workflow.split('jobs:\n', 1)[1]))
        self.assertEqual({'check', 'binaries', 'macos', 'image', 'ios', 'android', 'windows'}, set(jobs))
        # check only reads the tag and the changelog: it must not wait on the
        # scarce macOS runners, since every other job waits on it.
        self.assertRegex(jobs['check'], r'(?m)^    runs-on: ubuntu-latest$')
        for name, job in jobs.items():
            with self.subTest(job=name):
                self.assertRegex(job, r'(?m)^    runs-on: (macos-26|ubuntu-latest|windows-latest)$')
                # Every job that reads a secret runs in an environment that admits only v* tags.
                if 'secrets.' in job.replace('secrets.GITHUB_TOKEN', ''):
                    self.assertRegex(job, r'(?m)^    environment: (release|windows-signing)$')
                if 'packages: write' in job:
                    self.assertEqual('image', name)

    def test_go_caches_are_saved_only_by_main_on_hosted_runners(self):
        # Pull requests read main's Go caches and never write one, so no pull
        # request, fork or not, can seed what the next run on main restores.
        # main and manual runs clear Go's test results and run every test.
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        jobs = dict(re.findall(r'(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',
                               workflow.split('jobs:\n', 1)[1]))
        self.assertNotIn('actions/cache@', workflow)
        setups = re.findall(r'(?m)uses: actions/setup-go@[^\n]*\n        with:\n((?:          .*\n)+)', workflow)
        self.assertEqual(7, len(setups))
        for setup in setups:
            self.assertIn('          cache: false\n', setup)
        save_if = ("if: \"!cancelled() && runner.environment == 'github-hosted' && "
                   "github.event_name == 'push' && github.ref == 'refs/heads/main'\"")
        cached = {'server', 'server-macos', 'server-race', 'contract', 'windows-server', 'windows-app'}
        for name, job in jobs.items():
            with self.subTest(job=name):
                if name not in cached:
                    self.assertNotIn('go-cache', job)
                    continue
                self.assertEqual(1, job.count('uses: actions/cache/restore@'))
                self.assertEqual(1, job.count('uses: actions/cache/save@'))
                save = job.split('uses: actions/cache/save@', 1)[1]
                self.assertIn(save_if, save)
                self.assertIn('key: ${{ steps.go-cache.outputs.key }}', save)
                # The save comes last, after every step that fills the cache.
                self.assertNotIn('\n      - ', save)
                self.assertIn('echo "key=$prefix$SUMS-$GITHUB_SHA"', job)
                if name != 'contract':
                    self.assertIn("if: github.event_name != 'pull_request'\n"
                                  "        working-directory: server\n"
                                  "        run: go clean -testcache\n", job)
                    self.assertLess(job.index('go clean -testcache'), job.index('go test'))

    def test_gradle_caches_are_saved_only_by_main_on_hosted_runners(self):
        # setup-gradle caches Gradle's home and the configuration cache on
        # GitHub's machines, written only by pushes to main like the Go
        # caches. setup-java's cache never saved: GRADLE_USER_HOME moves.
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        android = re.search(r'(?ms)^  android:\n(.*?)(?=^  [\w-]+:\n)', workflow).group(1)
        setup_java = re.search(r'(?m)uses: actions/setup-java@[^\n]*\n        with:\n((?:          .*\n)+)', android).group(1)
        self.assertNotIn('cache', setup_java)
        self.assertEqual(1, android.count('uses: gradle/actions/setup-gradle@'))
        setup = android.split('uses: gradle/actions/setup-gradle@', 1)[1].split('\n      - ', 1)[0]
        self.assertIn("if: runner.environment == 'github-hosted'", setup)
        self.assertIn("cache-read-only: ${{ !(github.event_name == 'push' && github.ref == 'refs/heads/main') }}", setup)
        self.assertIn('cache-encryption-key: ${{ secrets.GRADLE_ENCRYPTION_KEY }}', setup)
        self.assertNotIn('cache-write-only', setup)
        self.assertLess(android.index('GRADLE_USER_HOME='), android.index('setup-gradle@'))
        self.assertLess(android.index('setup-gradle@'), android.index('./gradlew'))

    def test_actions_are_pinned_to_commits(self):
        # A tag can be moved to other code and a commit cannot. Release jobs
        # hold signing keys, so every action runs at a commit someone
        # reviewed, its release in a comment that Dependabot keeps current.
        for path in sorted((ROOT / '.github/workflows').glob('*.yml')):
            actions = re.findall(r'(?m)^\s*(?:- )?uses: (\S+)(.*)$', path.read_text())
            self.assertTrue(actions, path.name)
            for action, comment in actions:
                with self.subTest(workflow=path.name, action=action):
                    self.assertRegex(action, r'^[\w.-]+/[\w./-]+@[0-9a-f]{40}$')
                    self.assertRegex(comment, r'^ # v\d+\.\d+\.\d+$')

    def test_tools_and_android_sdk_use_runner_owned_directories(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        pnpm = re.findall(r'(?ms)- uses: pnpm/action-setup@[^\n]+\n(.*?)(?=      -)', workflow)
        self.assertEqual(2, len(pnpm))
        for setup in pnpm:
            self.assertIn('dest: ${{ runner.temp }}/setup-pnpm', setup)
        android = re.search(r'(?ms)^  android:\n(.*?)(?=^  apple:)', workflow).group(1)
        for variable, directory in (('ANDROID_HOME', 'android-sdk'),
                                    ('ANDROID_SDK_ROOT', 'android-sdk'),
                                    ('GRADLE_USER_HOME', 'gradle')):
            self.assertIn(variable + '=$RUNNER_TOOL_CACHE/' + directory, android)
        for variable, directory, count in (('GOCACHE', 'go-build', 4),
                                           ('GOMODCACHE', 'go-mod', 4),
                                           ('GOLANGCI_LINT_CACHE', 'golangci-lint', 1)):
            self.assertEqual(count, workflow.count(variable + '=$RUNNER_TOOL_CACHE/' + directory))
        setup = re.search(r"(?ms)- name: Keep Android tools[^\n]*\n        run: \|\n(.*?)(?=      -)", android).group(1)
        with tempfile.TemporaryDirectory() as tmp:
            for runner in ('mac-studio-1', 'mac-studio-2'):
                tool_cache = pathlib.Path(tmp) / runner
                output = pathlib.Path(tmp) / (runner + '.env')
                env = dict(os.environ, RUNNER_TOOL_CACHE=str(tool_cache), GITHUB_ENV=str(output),
                           ANDROID_HOME='/shared/sdk', ANDROID_SDK_ROOT='/shared/sdk',
                           GRADLE_USER_HOME='/shared/gradle')
                subprocess.run(['sh', '-eu', '-c', textwrap.dedent(setup)], env=env, check=True)
                values = dict(line.split('=', 1) for line in output.read_text().splitlines())
                self.assertEqual(str(tool_cache / 'android-sdk'), values['ANDROID_HOME'])
                self.assertEqual(values['ANDROID_HOME'], values['ANDROID_SDK_ROOT'])
                self.assertEqual(str(tool_cache / 'gradle'), values['GRADLE_USER_HOME'])
        docker = re.search(r'(?ms)^  docker:\n(.*?)(?=^  android:)', workflow).group(1)
        self.assertIn('deploy/ci/prepare-docker.py', docker)
        self.assertIn('DOCKER_CONFIG=$RUNNER_TEMP/docker-ci', docker)
        self.assertLess(docker.index('deploy/ci/prepare-docker.py'), docker.index('docker/setup-buildx-action'))
        self.assertIn('push: false', docker)

    def test_docker_config_keeps_context_without_shared_credentials_or_plugin_writes(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = pathlib.Path(tmp) / 'machine'
            source.mkdir()
            (source / 'contexts').mkdir()
            (source / 'cli-plugins').mkdir()
            original = {'currentContext': 'synthetic-engine',
                        'auths': {'example.invalid': {'auth': 'synthetic-secret'}},
                        'credsStore': 'osxkeychain', 'credHelpers': {'example.invalid': 'helper'}}
            (source / 'config.json').write_text(json.dumps(original))
            for runner in ('one', 'two'):
                destination = pathlib.Path(tmp) / runner
                subprocess.run([sys.executable, '-I', str(ROOT / 'deploy/ci/prepare-docker.py'),
                                str(source), str(destination)], check=True)
                config = json.loads((destination / 'config.json').read_text())
                self.assertEqual({'https://index.docker.io/v1/': {}}, config['auths'])
                self.assertNotIn('credsStore', config)
                self.assertNotIn('credHelpers', config)
                self.assertEqual('synthetic-engine', config['currentContext'])
                self.assertEqual((source / 'contexts').resolve(), (destination / 'contexts').resolve())
                self.assertIn(str((source / 'cli-plugins').resolve()), config['cliPluginsExtraDirs'])
                self.assertFalse((destination / 'cli-plugins').exists())
                self.assertEqual(0o600, (destination / 'config.json').stat().st_mode & 0o777)
            self.assertEqual(original, json.loads((source / 'config.json').read_text()))

    def test_simulators_are_selected_or_created_for_each_runner(self):
        base = '00000000-0000-0000-0000-000000000001'
        mine = '00000000-0000-0000-0000-000000000002'
        created = '00000000-0000-0000-0000-000000000003'
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp)
            devices = {'devices': {'com.apple.CoreSimulator.SimRuntime.iOS-26-0': [
                {'udid': base, 'deviceTypeIdentifier': 'com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro'}]}}
            (path / 'devices.json').write_text(json.dumps(devices))
            (path / 'devices.txt').write_text(
                f'    iPhone 17 Pro ({base}) (Shutdown)\n'
                f'    iPhone 17 Pro (mac-studio-1) ({mine}) (Shutdown)\n')
            stub = path / 'xcrun'
            stub.write_text('''#!/bin/sh
case "$*" in
  "simctl list devices available") cat "$STUB_DIR/devices.txt" ;;
  "simctl list devices -j") cat "$STUB_DIR/devices.json" ;;
  "simctl create "*) printf '%s\\n' "$*" >> "$STUB_DIR/created"; echo "$CREATED_ID" ;;
  *) exit 98 ;;
esac
''')
            stub.chmod(0o755)
            # The Mac Studio's runners, whatever machine runs this test.
            env = dict(os.environ, PATH=str(path) + os.pathsep + os.environ['PATH'],
                       STUB_DIR=tmp, CREATED_ID=created, RUNNER_ENVIRONMENT='self-hosted')
            script = str(ROOT / 'apple/scripts/simulator.sh')
            existing = subprocess.check_output(['sh', script, 'iPhone 17 Pro'],
                                               env=dict(env, RUNNER_NAME='mac-studio-1'), text=True)
            self.assertEqual(mine, existing.strip())
            self.assertFalse((path / 'created').exists())
            new = subprocess.check_output(['sh', script, 'iPhone 17 Pro'],
                                          env=dict(env, RUNNER_NAME='mac-studio-2'), text=True)
            self.assertEqual(created, new.strip())
            self.assertIn('iPhone 17 Pro (mac-studio-2)', (path / 'created').read_text())

            # Without the asked-for device the fallback copies Xcode's own,
            # never a runner's copy listed after it: that made each run copy
            # the last run's copy under a longer name, a new device every run.
            stale = '00000000-0000-0000-0000-000000000004'
            (path / 'created').unlink()
            (path / 'devices.txt').write_text(
                f'    iPhone 17 Pro ({base}) (Shutdown)\n'
                f'    iPhone 17 Pro (mac-studio-1) ({mine}) (Shutdown)\n'
                f'    iPhone 17 Pro (mac-studio-1) (mac-studio-2) ({stale}) (Shutdown)\n')
            reused = subprocess.check_output(['sh', script, 'iPhone 16 Pro'], stderr=subprocess.DEVNULL,
                                             env=dict(env, RUNNER_NAME='mac-studio-1'), text=True)
            self.assertEqual(mine, reused.strip())
            self.assertFalse((path / 'created').exists())
            new = subprocess.check_output(['sh', script, 'iPhone 16 Pro'], stderr=subprocess.DEVNULL,
                                          env=dict(env, RUNNER_NAME='mac-studio-2'), text=True)
            self.assertEqual(created, new.strip())
            self.assertEqual('simctl create iPhone 17 Pro (mac-studio-2) '
                             'com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro '
                             'com.apple.CoreSimulator.SimRuntime.iOS-26-0\n', (path / 'created').read_text())

            # A GitHub-hosted runner is a new machine each job: it uses
            # Xcode's own device rather than making a copy that never booted.
            (path / 'created').unlink()
            hosted = subprocess.check_output(['sh', script, 'iPhone 17 Pro'],
                                             env=dict(env, RUNNER_NAME='GitHub Actions 1000001',
                                                      RUNNER_ENVIRONMENT='github-hosted'), text=True)
            self.assertEqual(base, hosted.strip())
            self.assertFalse((path / 'created').exists())

    def test_every_cache_is_saved_only_by_main_pushes_on_hosted_runners(self):
        # Whatever the cache, a pull request (a fork's included) only reads
        # what main saved, and the Mac Studio keeps its own disk instead.
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        steps = re.findall(r'(?m)^      - uses: actions/cache/(restore|save)@[^\n]*\n((?:        .*\n)+)', workflow)
        self.assertEqual({'restore', 'save'}, {kind for kind, _ in steps})
        for kind, body in steps:
            with self.subTest(kind=kind, step=body.splitlines()[0]):
                guard = re.search(r'(?m)^        if: (.+)$', body).group(1)
                self.assertIn("runner.environment == 'github-hosted'", guard)
                if kind == 'save':
                    self.assertIn("github.event_name == 'push' && github.ref == 'refs/heads/main'", guard)

    def test_git_ignores_private_files_at_nested_paths(self):
        for path in ('nested/.env', 'nested/.env.production', 'nested/signing.p8',
                     'nested/signing.p12', 'nested/private.key', 'nested/private.pem',
                     'nested/release.jks', 'nested/release.keystore', '.claude/launch.json'):
            result = subprocess.run(['git', 'check-ignore', '--no-index', '-q', path], cwd=ROOT)
            self.assertEqual(0, result.returncode, path)

    def test_tv_diagnostics_override_preserves_tests_results_and_failures(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        tv = re.search(r'(?ms)^  apple-tv:\n(.*?)(?=^  [\w-]+:\n)', workflow).group(1)
        step = re.search(r'(?ms)- name: Build and test on an Apple TV simulator\n(.*?)(?=      -)', tv).group(1)
        self.assertIn('timeout-minutes: 30', step)
        self.assertIn("make apple-tv-test-sim-ci TV_UI_SHARD=${{ matrix.share }}/2 TV_KIT_TESTS=${{ matrix.share == 1 && 'yes' || 'no' }} "
                      "APPLE_TV_TEST_DIAGNOSTICS=never APPLE_TEST_FLAGS='-enableCodeCoverage NO' PATH=\"$PATH\"", step)
        self.assertNotIn('continue-on-error', tv)
        # Two shares, both run whatever the other does; the Release build once.
        self.assertIn('fail-fast: false', tv)
        self.assertIn('share: [1, 2]', tv)
        release = re.search(r'(?ms)- name: Build for release\n(.*?)(?=      -)', tv).group(1)
        self.assertIn('if: matrix.share == 2', release)
        self.assertIn('name: apple-tv-test-results-${{ matrix.share }}', tv)
        self.assertIn('apple/build/TVTests.xcresult', tv)
        self.assertIn('apple/build/TVUITests.xcresult', tv)

        # Run the real make target against command stand-ins: check the argv
        # and a failing xcodebuild exit without touching a simulator or Xcode.
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp)
            (path / 'apple/scripts').mkdir(parents=True)
            commands = {
                'apple/scripts/simulator.sh': 'echo 00000000-0000-0000-0000-000000000001',
                'xcodegen': 'exit 0',
                # The early boot: a failure there must not stop the tests.
                'xcrun': 'exit 1',
                'xcodebuild': 'printf "%s\\n" "$@" > "$CAPTURE_PATH"\nexit "$STUB_EXIT"',
            }
            for name, script in commands.items():
                command = path / name
                command.write_text('#!/bin/sh\n' + script + '\n')
                command.chmod(0o755)
            capture = path / 'arguments'
            env = dict(os.environ, CAPTURE_PATH=str(capture), STUB_EXIT='0')
            command = ['make', '-f', str(ROOT / 'Makefile'), 'apple-tv-test-sim',
                       'PATH=' + tmp + os.pathsep + os.environ['PATH']]
            for policy in ('', 'never', 'on-failure'):
                with self.subTest(policy=policy):
                    invocation = command + ['APPLE_TV_TEST_DIAGNOSTICS=' + policy]
                    subprocess.run(invocation, cwd=tmp, env=env, check=True, capture_output=True)
                    args = capture.read_text().splitlines()
                    self.assertEqual('test', args[-1])
                    self.assertEqual('WatchMyMedia TV', args[args.index('-scheme') + 1])
                    self.assertEqual('apple/build/TVTests.xcresult', args[args.index('-resultBundlePath') + 1])
                    self.assertFalse(any(arg.startswith(('-only-testing', '-skip-testing')) for arg in args))
                    if policy:
                        self.assertEqual(policy, args[args.index('-collect-test-diagnostics') + 1])
                    else:
                        self.assertNotIn('-collect-test-diagnostics', args)
            subprocess.run(command + ['APPLE_TEST_FLAGS=-enableCodeCoverage NO'], cwd=tmp, env=env,
                           check=True, capture_output=True)
            args = capture.read_text().splitlines()
            self.assertEqual('NO', args[args.index('-enableCodeCoverage') + 1])
            self.assertEqual('test', args[-1])
            failed = subprocess.run(command + ['APPLE_TV_TEST_DIAGNOSTICS=never'], cwd=tmp,
                                    env=dict(env, STUB_EXIT='65'), capture_output=True)
            self.assertNotEqual(0, failed.returncode)
            self.assertIn(b'Error 65', failed.stderr)

    def test_tv_ci_target_builds_once_and_runs_kit_and_ui_tests_apart(self):
        # CI's target: one build-for-testing, the Kit's tests on the one
        # simulator, then only the UI tests on parallel clones; a failure in
        # either run fails the target, and the other still runs.
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp)
            (path / 'apple/scripts').mkdir(parents=True)
            commands = {
                'apple/scripts/simulator.sh': 'echo 00000000-0000-0000-0000-000000000001',
                'xcodegen': 'exit 0',
                'xcrun': 'exit 1',
                # One line of arguments per call; FAIL_ON names the action that fails.
                'xcodebuild': 'echo "$*" >> "$CAPTURE_PATH"\n'
                              'for last; do :; done\n'
                              'case " $* " in *" $FAIL_ON "*) [ -n "$FAIL_ON" ] && exit 65;; esac\n'
                              'exit 0',
            }
            for name, script in commands.items():
                command = path / name
                command.write_text('#!/bin/sh\n' + script + '\n')
                command.chmod(0o755)
            capture = path / 'arguments'
            env = dict(os.environ, CAPTURE_PATH=str(capture), FAIL_ON='')
            command = ['make', '-f', str(ROOT / 'Makefile'), 'apple-tv-test-sim-ci',
                       'APPLE_TV_TEST_DIAGNOSTICS=never', 'APPLE_TEST_FLAGS=-enableCodeCoverage NO',
                       'PATH=' + tmp + os.pathsep + os.environ['PATH']]
            subprocess.run(command, cwd=tmp, env=env, check=True, capture_output=True)
            build, kit, ui = capture.read_text().splitlines()
            self.assertTrue(build.endswith('build-for-testing'), build)
            self.assertIn('-enableCodeCoverage NO', build)
            self.assertTrue(kit.endswith('test-without-building'), kit)
            self.assertIn('-skip-testing:WatchMyMediaTVUITests', kit)
            self.assertIn('-resultBundlePath apple/build/TVTests.xcresult', kit)
            self.assertNotIn('-parallel-testing-enabled', kit)
            self.assertIn('-collect-test-diagnostics never', kit)
            self.assertTrue(ui.endswith('test-without-building'), ui)
            self.assertIn('-only-testing:WatchMyMediaTVUITests', ui)
            self.assertIn('-resultBundlePath apple/build/TVUITests.xcresult', ui)
            self.assertNotIn('-parallel-testing', ui, 'one simulator on CI by default')
            capture.unlink()
            subprocess.run(command + ['TV_UI_WORKERS=2'], cwd=tmp, env=env, check=True, capture_output=True)
            ui = capture.read_text().splitlines()[2]
            self.assertIn('-parallel-testing-enabled YES -parallel-testing-worker-count 2', ui)
            self.assertNotIn('-parallel-testing', capture.read_text().splitlines()[1], 'never the Kit tests')
            for failing in ('-skip-testing:WatchMyMediaTVUITests', '-only-testing:WatchMyMediaTVUITests'):
                with self.subTest(failing=failing):
                    capture.unlink()
                    failed = subprocess.run(command, cwd=tmp, env=dict(env, FAIL_ON=failing), capture_output=True)
                    self.assertNotEqual(0, failed.returncode)
                    self.assertEqual(3, len(capture.read_text().splitlines()), 'both test runs still go ahead')
            capture.unlink()
            failed = subprocess.run(command, cwd=tmp, env=dict(env, FAIL_ON='build-for-testing'), capture_output=True)
            self.assertNotEqual(0, failed.returncode)
            self.assertEqual(1, len(capture.read_text().splitlines()), 'no tests without a build')

            # The two CI shares: the real share script over two stand-in classes.
            shutil.copy(ROOT / 'apple/scripts/tv-ui-shard.py', path / 'apple/scripts/tv-ui-shard.py')
            (path / 'apple/TVUITests').mkdir(parents=True)
            (path / 'apple/TVUITests/A.swift').write_text(
                'final class ATests: XCTestCase {\n    func testOne() {}\n    func testTwo() {}\n}\n')
            (path / 'apple/TVUITests/B.swift').write_text('final class BTests: XCTestCase {\n    func testThree() {}\n}\n')
            capture.unlink()
            subprocess.run(command + ['TV_UI_SHARD=1/2'], cwd=tmp, env=env, check=True, capture_output=True)
            build, kit, ui = capture.read_text().splitlines()
            self.assertIn('-skip-testing:WatchMyMediaTVUITests', kit)
            self.assertIn('-only-testing:WatchMyMediaTVUITests/ATests', ui)
            self.assertNotIn('BTests', ui)
            capture.unlink()
            subprocess.run(command + ['TV_UI_SHARD=2/2', 'TV_KIT_TESTS=no'], cwd=tmp, env=env, check=True,
                           capture_output=True)
            build, ui = capture.read_text().splitlines()
            self.assertTrue(build.endswith('build-for-testing'), build)
            self.assertIn('-only-testing:WatchMyMediaTVUITests/BTests', ui)
            self.assertNotIn('ATests', ui)
            self.assertNotIn('-skip-testing', ui)
            capture.unlink()
            failed = subprocess.run(command + ['TV_UI_SHARD=3/2'], cwd=tmp, env=env, capture_output=True)
            self.assertNotEqual(0, failed.returncode, 'a share that does not exist fails')
            self.assertFalse(capture.exists(), 'and builds nothing')

    def test_apple_jobs_build_release_unsigned_after_their_tests(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        makefile = (ROOT / 'Makefile').read_text()
        for job, target, platform in (('apple', 'apple-build-release', 'iOS'), ('apple-tv', 'apple-tv-build-release', 'tvOS')):
            with self.subTest(job=job):
                body = re.search(r'(?ms)^  ' + re.escape(job) + r':\n(.*?)(?=^  [\w-]+:\n)', workflow).group(1)
                step = re.search(r'(?ms)- name: Build for release\n(.*?)(?=      -)', body).group(1)
                self.assertIn(f'run: make {target} PATH="$PATH"', step)
                tests = re.search(r'-test-sim(?:-ci)? ', body)
                self.assertIsNotNone(tests)
                self.assertLess(tests.start(), body.index('- name: Build for release'))
                recipe = re.search(r'(?ms)^' + re.escape(target) + r':.*?\n((?:\t.*\n)+)', makefile).group(1)
                self.assertIn('-configuration Release', recipe)
                self.assertIn(f"-destination 'generic/platform={platform}'", recipe)
                self.assertIn('CODE_SIGNING_ALLOWED=NO', recipe)
                self.assertIn('apple/scripts/check-release-binary.sh', recipe)

    def test_build_context_excludes_nested_credentials_and_client_outputs(self):
        rules = set((ROOT / '.dockerignore').read_text().splitlines())
        for rule in ('android', 'apple', '**/.env', '**/.env.*', '**/secrets',
                     '**/*.p8', '**/*.p12', '**/*.key', '**/*.keystore', '**/*.jks',
                     '**/*.db', '**/*.db-*', '**/*.log'):
            self.assertIn(rule, rules)


if __name__ == '__main__':
    unittest.main()
