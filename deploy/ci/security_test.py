"""Keep trusted CI routing explicit and concurrent runners isolated."""
import ast
import json
import os
import pathlib
import re
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
        hosted = dict.fromkeys(('changes', 'web', 'contract', 'docker', 'android'), 'ubuntu-latest')
        hosted.update(dict.fromkeys(('server', 'server-race', 'apple', 'apple-tv'), 'macos-26'))
        hosted['windows'] = hosted['windows-signing'] = 'windows-latest'
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

    def test_windows_installer_embeds_the_web_build_from_the_same_run(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        jobs = dict(re.findall(r'(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',
                               workflow.split('jobs:\n', 1)[1]))
        self.assertIn('needs: [changes, web]', jobs['windows'])
        self.assertIn('name: windows-embedded-web', jobs['web'])
        self.assertIn('path: web/dist/', jobs['web'])
        self.assertIn('actions/download-artifact@', jobs['windows'])
        self.assertIn('name: windows-embedded-web', jobs['windows'])
        self.assertIn('path: server/internal/webui/dist', jobs['windows'])
        self.assertNotIn('windows/build.sh --smoke', jobs['windows'])
        self.assertNotIn('actions/download-artifact@', jobs['server'])

    def test_workflow_keeps_read_only_permissions_and_fork_boundary(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertRegex(workflow, r'(?m)^  pull_request:$')
        self.assertNotIn('pull_request_target:', workflow)
        self.assertNotIn('secrets.', workflow)
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
        for name, job in jobs.items():
            with self.subTest(job=name):
                self.assertRegex(job, r'(?m)^    runs-on: (macos-26|ubuntu-latest|windows-latest)$')
                # Every job that reads a secret runs in an environment that admits only v* tags.
                if 'secrets.' in job.replace('secrets.GITHUB_TOKEN', ''):
                    self.assertRegex(job, r'(?m)^    environment: (release|windows-signing)$')
                if 'packages: write' in job:
                    self.assertEqual('image', name)

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
        for variable, directory, count in (('GOCACHE', 'go-build', 3),
                                           ('GOMODCACHE', 'go-mod', 3),
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
            env = dict(os.environ, PATH=str(path) + os.pathsep + os.environ['PATH'],
                       STUB_DIR=tmp, CREATED_ID=created)
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

    def test_git_ignores_private_files_at_nested_paths(self):
        for path in ('nested/.env', 'nested/.env.production', 'nested/signing.p8',
                     'nested/signing.p12', 'nested/private.key', 'nested/private.pem',
                     'nested/release.jks', 'nested/release.keystore', '.claude/launch.json'):
            result = subprocess.run(['git', 'check-ignore', '--no-index', '-q', path], cwd=ROOT)
            self.assertEqual(0, result.returncode, path)

    def test_tv_diagnostics_override_preserves_tests_results_and_failures(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        tv = re.search(r'(?ms)^  apple-tv:\n(.*?)(?=^  windows:)', workflow).group(1)
        step = re.search(r'(?ms)- name: Build and test on an Apple TV simulator\n(.*?)(?=      -)', tv).group(1)
        self.assertIn('timeout-minutes: 20', step)
        self.assertIn('make apple-tv-test-sim APPLE_TV_TEST_DIAGNOSTICS=never PATH="$PATH"', step)
        self.assertNotIn('continue-on-error', tv)
        self.assertIn('path: apple/build/TVTests.xcresult', tv)

        # Run the real make target against command stand-ins: check the argv
        # and a failing xcodebuild exit without touching a simulator or Xcode.
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp)
            (path / 'apple/scripts').mkdir(parents=True)
            commands = {
                'apple/scripts/simulator.sh': 'echo 00000000-0000-0000-0000-000000000001',
                'xcodegen': 'exit 0',
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
            failed = subprocess.run(command + ['APPLE_TV_TEST_DIAGNOSTICS=never'], cwd=tmp,
                                    env=dict(env, STUB_EXIT='65'), capture_output=True)
            self.assertNotEqual(0, failed.returncode)
            self.assertIn(b'Error 65', failed.stderr)

    def test_build_context_excludes_nested_credentials_and_client_outputs(self):
        rules = set((ROOT / '.dockerignore').read_text().splitlines())
        for rule in ('android', 'apple', '**/.env', '**/.env.*', '**/secrets',
                     '**/*.p8', '**/*.p12', '**/*.key', '**/*.keystore', '**/*.jks',
                     '**/*.db', '**/*.db-*', '**/*.log'):
            self.assertIn(rule, rules)


if __name__ == '__main__':
    unittest.main()
