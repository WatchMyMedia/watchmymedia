"""Keep CI on GitHub's own machines, and concurrent runners isolated."""
import json
import os
import pathlib
import plistlib
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


class WorkflowIsolationTest(unittest.TestCase):
    def test_every_job_runs_on_githubs_own_machines(self):
        # The repository is public, so a pull request from anyone runs here.
        # Every job names one of GitHub's machines, fresh for each job, and
        # no workflow can reach a self-hosted runner, a maintainer's Mac or
        # anything chosen at run time.
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        jobs = dict(re.findall(r'(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',
                               workflow.split('jobs:\n', 1)[1]))
        hosted = dict.fromkeys(('changes', 'web', 'contract', 'docker', 'scripts', 'android', 'server', 'server-race'), 'ubuntu-latest')
        # macOS runners are the scarce ones: only what needs a Mac runs there.
        hosted['server-macos'] = 'macos-26'
        # The Apple apps build on the image with the Xcode the App Store takes.
        hosted.update(dict.fromkeys(('apple', 'apple-tv'), 'xcode-27'))
        hosted.update(dict.fromkeys(('windows-server', 'windows-live-tv', 'windows-app', 'windows-signing'), 'windows-latest'))
        # The Linux packages install in privileged systemd containers, which
        # need a Linux Docker host: GitHub's, one for each architecture. Its
        # matrix names the runners; the test of that job reads them.
        hosted['linux-packages'] = 'ubuntu-latest'
        hosted['linux-packages-install'] = '${{ matrix.runner }}'
        self.assertEqual(set(hosted), set(jobs))
        for job, body in jobs.items():
            with self.subTest(job=job):
                self.assertEqual(hosted[job], re.search(r'(?m)^    runs-on: (.+)$', body).group(1))
        for path in sorted((ROOT / '.github/workflows').glob('*.y*ml')):
            text = path.read_text()
            with self.subTest(workflow=path.name):
                for word in ('self-hosted', 'mac-studio', 'repository.private', 'vars.CI_RUNNER'):
                    self.assertNotIn(word, text)
                for runner in re.findall(r'(?m)^\s+runs-on: (.+)$', text):
                    if runner != '${{ matrix.runner }}':
                        self.assertNotIn('${{', runner)

    def test_every_job_runs_only_for_what_it_checks(self):
        # Each job waits on the changes job and runs when its own paths, this
        # workflow or a manual run say so, so a docs or template change does
        # not run the server's race tests or build the apps.
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        jobs = dict(re.findall(r'(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',
                               workflow.split('jobs:\n', 1)[1]))
        gated = {'server': 'server', 'server-macos': 'server', 'server-race': 'race', 'contract': 'contract', 'web': 'web',
                 'docker': 'docker', 'scripts': 'scripts', 'android': 'android', 'apple': 'apple',
                 'apple-tv': 'apple-tv', 'windows-server': 'windows', 'windows-live-tv': 'windows',
                 'windows-app': 'windows', 'linux-packages': 'linux-packages',
                 'linux-packages-install': 'linux-packages'}
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

    def test_linux_packages_install_on_every_supported_release_and_architecture(self):
        # Supported means named and tested: each release the packages claim,
        # on amd64 and arm64, installs the packages this run built. Nothing
        # is published, and the throwaway key's secret half never leaves the
        # job that made it.
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        jobs = dict(re.findall(r'(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',
                               workflow.split('jobs:\n', 1)[1]))
        build, install = jobs['linux-packages'], jobs['linux-packages-install']
        self.assertIn('        run: make linux-smoke-packages PATH="$PATH"\n', build)
        # The same run drives the release's script (deploy/linux/release.sh)
        # on stand-ins, with the tools the release's packages job installs:
        # set, WMM_LINUX_RELEASE makes a missing tool a failure, not a skip.
        self.assertIn("          WMM_LINUX_SMOKE: build/linux-smoke\n"
                      "          WMM_LINUX_RELEASE: required\n"
                      "        run: python3 -I -m unittest discover -s deploy/linux -p '*_test.py' -v\n", build)
        self.assertLess(build.index('make linux-smoke-packages'), build.index('WMM_LINUX_SMOKE'))
        tools = 'sudo apt-get install -y --no-install-recommends rpm apt-utils createrepo-c\n'
        self.assertLess(build.index(tools), build.index('WMM_LINUX_RELEASE'))
        release = (ROOT / '.github/workflows/release.yml').read_text()
        self.assertIn(tools, release.split('\n  packages:\n', 1)[1].split('\n  macos:\n', 1)[0])
        uploads = re.findall(r'(?m)^          name: (linux-packages-\S+)\n          path: \|\n((?:            .*\n)+)', build)
        self.assertEqual(['linux-packages-deb-amd64', 'linux-packages-deb-arm64',
                          'linux-packages-rpm-amd64', 'linux-packages-rpm-arm64'], [name for name, _ in uploads])
        for name, paths in uploads:
            with self.subTest(artifact=name):
                self.assertEqual(['build/linux-smoke/older/', 'build/linux-smoke/current/', 'build/linux-smoke/key/public.asc'],
                                 [re.sub(r'\*.*$', '', path.strip()) for path in paths.splitlines()])
        for job in (build, install):
            for marker in ('secret.asc', 'environment:', 'gh release', 'action-gh-release', 'wrangler', 'id-token'):
                self.assertNotIn(marker, job)
        self.assertIn("        image: ['debian:12', 'debian:13', 'ubuntu:24.04', 'ubuntu:26.04', 'fedora:44', 'fedora:45']\n", install)
        self.assertIn('        arch: [amd64, arm64]\n', install)
        self.assertIn('          - arch: amd64\n            runner: ubuntu-latest\n'
                      '          - arch: arm64\n            runner: ubuntu-24.04-arm\n', install)
        self.assertIn('      fail-fast: false\n', install)
        self.assertIn("          name: linux-packages-${{ startsWith(matrix.image, 'fedora') && 'rpm' || 'deb' }}-${{ matrix.arch }}\n"
                      "          path: build/linux-smoke\n", install)
        self.assertIn('        run: deploy/linux/smoke.sh "${{ matrix.image }}"\n', install)
        # The same six releases the smoke script and the make target name.
        for path in ('deploy/linux/smoke.sh', 'deploy/linux/packages.mk'):
            self.assertIn('debian:12, debian:13, ubuntu:24.04, ubuntu:26.04, fedora:44', (ROOT / path).read_text(), path)
        self.assertIn('deploy/linux', jobs['scripts'])

    def test_linux_package_jobs_run_only_for_what_the_packages_are_built_from(self):
        # Thirteen jobs, so the list that starts them is deliberate: the
        # packaging with its make targets, the files it packs from
        # elsewhere, and how the server starts and finds its folders, config
        # and ffmpeg, which the wrapper and the service rely on. A wider
        # entry here (the root Makefile, all of server/ or web/) starts them
        # for changes they do not test; the server, web and docker jobs
        # cover what the packages hold of those.
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        filters = workflow.split('filters: |', 1)[1]
        block = re.search(r'(?m)^            linux-packages:\n((?:              - .*\n)+)', filters).group(1)
        self.assertEqual(['deploy/linux/**', 'deploy/macos/fetch-ffmpeg.sh', 'deploy/macos/FFMPEG-LICENSE.md',
                          'deploy/config.example.yaml', 'LICENSE', 'server/cmd/server/**',
                          'server/internal/config/**', '.github/workflows/ci.yml'],
                         re.findall(r"- '([^']+)'", block))
        # Both jobs are started by that one list and nothing else.
        jobs = dict(re.findall(r'(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',
                               workflow.split('jobs:\n', 1)[1]))
        for name in ('linux-packages', 'linux-packages-install'):
            self.assertEqual(["needs.changes.outputs.linux-packages == 'true'"],
                             re.findall(r"needs\.changes\.outputs\.[\w-]+ == 'true'", jobs[name]), name)
        self.assertIn("      linux-packages: ${{ github.event_name == 'workflow_dispatch' || "
                      "steps.filter.outputs.linux-packages == 'true' }}\n", jobs['changes'])
        # What the list leaves out is still what the packages are built with:
        # the Linux make targets are under deploy/linux, not in the root Makefile.
        makefile = (ROOT / 'Makefile').read_text()
        self.assertIn('deploy/linux/packages.mk', makefile)
        for target in ('ffmpeg-linux', 'linux-packages', 'linux-smoke-packages', 'linux-packages-smoke'):
            self.assertNotRegex(makefile, rf'(?m)^{target}:')
            self.assertRegex((ROOT / 'deploy/linux/packages.mk').read_text(), rf'(?m)^{target}:')

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
        for name in ('server', 'windows-server', 'windows-live-tv'):
            self.assertNotIn('actions/download-artifact@', jobs[name])
        for name in ('windows-server', 'windows-live-tv'):
            self.assertRegex(jobs[name], r'(?m)^    needs: changes$')

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

    def test_windows_jobs_split_the_work_once(self):
        # Three jobs at once instead of one after the other: the Go tests and
        # the VOD qualification in windows-server, the Live TV qualification in
        # windows-live-tv, the app, its installers and their smoke tests in
        # windows-app, each step in exactly one of them.
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        jobs = dict(re.findall(r'(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',
                               workflow.split('jobs:\n', 1)[1]))
        owners = {
            'windows-server': ("go test -skip 'Speed$' ./...", "go test -p 1 -run 'Speed$' ./...",
                               "-run '^TestRemuxSession$' -count=50", 'TestVODLadder',
                               'probe-transcoder', 'windows-transcoder-report'),
            'windows-live-tv': ('TestLiveLadder', 'WMM_LIVE_QUALIFICATION_CLAIMS'),
            'windows-app': ('hardware-check-test.ps1', 'signing-test.ps1', 'windows/build.sh', 'TestPinnedUpdaterDLL',
                            'hardware-check.ps1 -AppDir', 'installer.sh', 'platform-smoke.ps1', 'windows/smoke.ps1',
                            'installer-smoke.ps1', 'windows-development-installer', 'windows-tray-logs'),
        }
        for owner, markers in owners.items():
            for marker in markers:
                for name in owners:
                    with self.subTest(marker=marker, job=name):
                        (self.assertIn if name == owner else self.assertNotIn)(marker, jobs[name])

    def test_windows_qualifications_use_the_test_cache_on_pull_requests(self):
        # -count turns Go's test cache off. Without it, a pull request that
        # changes nothing a qualification is built from reuses main's pass;
        # main and manual runs clear the cache first, so they always run it.
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        jobs = dict(re.findall(r'(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',
                               workflow.split('jobs:\n', 1)[1]))
        live = "go test -tags streamingacceptance ./internal/livetv/dvr -run '^TestLiveLadder.*Qualification$'"
        vod = ("go test -tags streamingacceptance ./internal/playback "
               "-run '^TestVODLadder(FullFileQualification|RejectsUnboundedCopiedAudio)$'")
        live_tv, server = jobs['windows-live-tv'], jobs['windows-server']
        # Once on every run, cacheable; then once more, uncached, outside pull requests.
        self.assertEqual(2, live_tv.count(live))
        first, second = re.findall(r'(?m)^      - name: (.+)\n((?:        .*\n)+)', live_tv.split('go clean -testcache', 1)[1])[:2]
        self.assertIn(live + ' -v\n', first[1])
        self.assertNotIn('if:', first[1])
        self.assertIn(live + ' -count=1 -v\n', second[1])
        self.assertIn("        if: github.event_name != 'pull_request'\n", second[1])
        self.assertEqual(1, server.count(vod))
        self.assertIn(vod + ' -v\n', server)
        for job in (live_tv, server):
            self.assertLess(job.index('go clean -testcache'), job.index('streamingacceptance'))

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

    def test_release_builds_and_signs_the_linux_packages_in_their_job(self):
        # The .rpm files are signed with the packages' own subkey, checked
        # against the committed public key, and only then attached. The key
        # that signs the repositories' lists is never in this repository.
        workflow = (ROOT / '.github/workflows/release.yml').read_text()
        jobs = dict(re.findall(r'(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',
                               workflow.split('jobs:\n', 1)[1]))
        packages = jobs['packages']
        self.assertIn('    needs: [check, binaries]\n    runs-on: ubuntu-latest\n    environment: release\n'
                      '    permissions:\n      contents: write\n', packages)
        # It packs the server and ffmpeg of the archives the binaries job made.
        self.assertIn('          name: linux-archives\n          path: dist/watchmymedia-*-linux-*.tar.gz\n'
                      '          if-no-files-found: error\n', jobs['binaries'])
        self.assertIn('          name: linux-archives\n          path: ${{ runner.temp }}/linux-archives\n', packages)
        # The key reaches one step, is written outside the checkout and is
        # removed whatever happens; nothing prints it.
        self.assertEqual(1, workflow.count('secrets.LINUX_PACKAGE_SIGNING_KEY'))
        write = re.search(r"(?ms)- name: Write the packages' signing key\n(.*?)(?=      -)", packages).group(1)
        self.assertIn('LINUX_PACKAGE_SIGNING_KEY: ${{ secrets.LINUX_PACKAGE_SIGNING_KEY }}', write)
        self.assertEqual(['if [ -z "$LINUX_PACKAGE_SIGNING_KEY" ]; then',
                          '(umask 077 && printf \'%s\\n\' "$LINUX_PACKAGE_SIGNING_KEY" > "$RUNNER_TEMP/linux-packages.key")'],
                         [line.strip() for line in write.splitlines() if '$LINUX_PACKAGE_SIGNING_KEY' in line])
        self.assertEqual(2, packages.count('$LINUX_PACKAGE_SIGNING_KEY'))
        self.assertIn('      - name: Remove the signing key\n        if: always()\n'
                      '        run: rm -f "$RUNNER_TEMP/linux-packages.key"\n', packages)
        self.assertNotIn('set -x', packages)
        # The subkey is named, and is the one the package test holds the committed key to.
        self.assertIn('          LINUX_SIGNING_KEY: ${{ runner.temp }}/linux-packages.key\n'
                      '          LINUX_SIGNING_KEY_ID: BD64FACE28C2EF71\n', packages)
        rpm_subkey = re.search(r"(?m)^RPM_SUBKEY = '([0-9A-F]{40})'", (ROOT / 'deploy/linux/package_test.py').read_text()).group(1)
        self.assertEqual('BD64FACE28C2EF71', rpm_subkey[-16:])
        self.assertIn('deploy/linux/release.sh "$GITHUB_REF_NAME" "$RUNNER_TEMP/linux-archives" build/linux-release\n', packages)
        order = ['actions/download-artifact@', 'apt-get install', 'linux-packages.key")', 'deploy/linux/release.sh',
                 'rm -f "$RUNNER_TEMP/linux-packages.key"', 'action-gh-release@']
        positions = [packages.index(marker) for marker in order]
        self.assertEqual(sorted(positions), positions)
        # Only what the script checked leaves the job.
        outputs = re.findall(r'(?ms)- uses: (?:softprops/action-gh-release|actions/upload-artifact)@[^\n]+\n'
                             r'        with:\n(?:          (?!files|path)[^\n]*\n)*          (?:files|path): ([^\n]+)', packages)
        self.assertEqual(['build/linux-release/*'], outputs)
        self.assertIn('          fail_on_unmatched_files: true\n', packages)
        # The script checks each signature before it fills the folder, and
        # writes lists for a release only.
        script = (ROOT / 'deploy/linux/release.sh').read_text()
        for marker in ('rpmkeys --dbpath "$work/rpmdb" --import "$repository_key"', '--checksig',
                       'python3 -I "$here/signer.py" "$rpm"', '"$here/repository.sh"'):
            self.assertIn(marker, script)
        self.assertLess(script.index('--checksig'), script.index('python3 -I "$here/signer.py"'))
        self.assertLess(script.index('python3 -I "$here/signer.py"'), script.index('"$out/'))
        for other in set(jobs) - {'packages'}:
            # Without comments: the one above a job is read with the job before it.
            steps = re.sub(r'(?m)^ *#.*\n', '', jobs[other])
            for marker in ('LINUX_PACKAGE_SIGNING_KEY', 'LINUX_SIGNING_KEY', 'linux-packages.key', 'deploy/linux/release.sh'):
                with self.subTest(job=other, marker=marker):
                    self.assertNotIn(marker, steps)
        for path in sorted((ROOT / '.github/workflows').glob('*.yml')):
            self.assertNotIn('LINUX_REPO_SIGNING_KEY', path.read_text(), path.name)

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
        self.assertEqual({'check', 'binaries', 'packages', 'macos', 'image', 'ios', 'android', 'windows'}, set(jobs))
        # check only reads the tag and the changelog: it must not wait on the
        # scarce macOS runners, since every other job waits on it.
        self.assertRegex(jobs['check'], r'(?m)^    runs-on: ubuntu-latest$')
        for name, job in jobs.items():
            with self.subTest(job=name):
                self.assertRegex(job, r'(?m)^    runs-on: (macos-26|xcode-27|ubuntu-latest|windows-latest)$')
                # Every job that reads a secret runs in an environment that admits only v* tags.
                if 'secrets.' in job.replace('secrets.GITHUB_TOKEN', ''):
                    self.assertRegex(job, r'(?m)^    environment: (release|windows-signing)$')
                if 'packages: write' in job:
                    self.assertEqual('image', name)

    def test_the_app_store_upload_uses_the_xcode_ci_tests_with(self):
        # App Store Connect warns about iPhone builds made with the iOS 26 SDK
        # (ITMS-90725, v1.6.1's 1.9.2) and from April 2027 refuses them. The
        # release's ios job and CI's Apple jobs pick their Xcode with one
        # script, before anything builds, on the image for its major version.
        script = (ROOT / 'apple/scripts/select-xcode.sh').read_text()
        version = re.search(r'(?m)^version=(\d+)\.\d+$', script)
        self.assertIsNotNone(version)
        major = int(version.group(1))
        self.assertGreaterEqual(major, 27)

        def jobs_of(path):
            workflow = (ROOT / path).read_text()
            return dict(re.findall(r'(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',
                                   workflow.split('jobs:\n', 1)[1]))

        release, ci = jobs_of('.github/workflows/release.yml'), jobs_of('.github/workflows/ci.yml')
        hosted_only = "        if: runner.environment == 'github-hosted'\n"
        for name, job, condition in (('release ios', release['ios'], ''),
                                     ('ci apple', ci['apple'], hosted_only),
                                     ('ci apple-tv', ci['apple-tv'], hosted_only)):
            with self.subTest(job=name):
                self.assertRegex(job, rf"(?m)^    runs-on: .*\bxcode-{major}\b")
                step = ('      - name: Use the release Xcode\n' + condition
                        + '        run: apple/scripts/select-xcode.sh\n')
                self.assertIn(step, job)
                self.assertLess(job.index(step), job.index('apple/scripts/install-xcodegen.sh'))

    def test_go_caches_are_saved_only_by_main_on_hosted_runners(self):
        # Pull requests read main's Go caches and never write one, so no pull
        # request, fork or not, can seed what the next run on main restores.
        # main and manual runs clear Go's test results and run every test.
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        jobs = dict(re.findall(r'(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',
                               workflow.split('jobs:\n', 1)[1]))
        self.assertNotIn('actions/cache@', workflow)
        setups = re.findall(r'(?m)uses: actions/setup-go@[^\n]*\n        with:\n((?:          .*\n)+)', workflow)
        self.assertEqual(9, len(setups))
        for setup in setups:
            self.assertIn('          cache: false\n', setup)
        save_if = ("if: \"!cancelled() && runner.environment == 'github-hosted' && "
                   "github.event_name == 'push' && github.ref == 'refs/heads/main'\"")
        cached = {'server', 'server-macos', 'server-race', 'contract', 'windows-server', 'windows-live-tv', 'windows-app'}
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
        self.assertEqual(3, len(pnpm))
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

    def test_app_store_archives_need_no_certificate_and_keep_entitlements(self):
        # A signed archive signs with an Apple Development certificate, and a
        # release runner starts with none, so with -allowProvisioningUpdates
        # Xcode asked Apple for a new one on every release until the team
        # reached its limit (v1.7.0's ios job: "Choose a certificate to
        # revoke"). Both apps archive without a certificate; only the export
        # signs, for the App Store, which needs none in the runner's keychain.
        makefile = (ROOT / 'Makefile').read_text()

        def recipe(target):
            # The target's own lines only, up to the first line without a tab.
            return re.search(r'(?m)^' + re.escape(target) + r':[^\n]*\n((?:\t[^\n]*\n)+)', makefile).group(1)

        for archive, upload in (('apple-archive', 'apple-upload'), ('apple-tv-archive', 'apple-tv-upload')):
            with self.subTest(target=archive):
                self.assertIn(' archive\n', recipe(archive))
                self.assertIn('CODE_SIGNING_ALLOWED=NO', recipe(archive))
                self.assertNotIn('-allowProvisioningUpdates', recipe(archive))
                self.assertNotIn('APPLE_AUTH', recipe(archive))
                self.assertRegex(makefile, rf'(?m)^{re.escape(upload)}: {re.escape(archive)} ')
                self.assertIn('-exportArchive', recipe(upload))
                self.assertIn('-exportOptionsPlist apple/ExportOptions.plist', recipe(upload))
                self.assertIn('-allowProvisioningUpdates $(APPLE_AUTH)', recipe(upload))
        with (ROOT / 'apple/ExportOptions.plist').open('rb') as f:
            options = plistlib.load(f)
        self.assertEqual('app-store-connect', options['method'])
        self.assertEqual('automatic', options['signingStyle'])
        self.assertEqual('6R8GZD78G3', options['teamID'])
        # The export signs a bundle with the entitlements its archived
        # signature has, and an unsigned one has none: the Apple TV app and
        # its Top Shelf extension lost their App Group in every upload from
        # 1.8.7 to 1.10.0. So each bundle with an entitlements file is signed
        # ad hoc with it after archiving, extensions before the app that
        # holds them.
        archives = recipe('apple-archive') + recipe('apple-tv-archive')
        files = re.findall(r'(?m)^\s+CODE_SIGN_ENTITLEMENTS: (\S+)$', (ROOT / 'apple/project.yml').read_text())
        self.assertLessEqual({'TV/WatchMyMediaTV.entitlements', 'TopShelf/TopShelf.entitlements'}, set(files))
        for name in files:
            with self.subTest(entitlements=name):
                self.assertTrue((ROOT / 'apple' / name).is_file())
                self.assertRegex(archives, rf'codesign -f -s - --entitlements apple/{re.escape(name)} ')
        self.assertNotIn('--deep', archives)
        tv = recipe('apple-tv-archive')
        self.assertLess(tv.index('TopShelf.entitlements'), tv.index('WatchMyMediaTV.entitlements'))
        self.assertLess(tv.index(' archive\n'), tv.index('codesign'))
        workflow = (ROOT / '.github/workflows/release.yml').read_text()
        ios = re.search(r'(?ms)^  ios:\n(.*?)(?=^  [\w-]+:\n)', workflow).group(1)
        self.assertIn('make apple-upload VERSION=${GITHUB_REF_NAME}', ios)
        self.assertNotIn('xcodebuild', ios)

    def test_build_context_excludes_nested_credentials_and_client_outputs(self):
        rules = set((ROOT / '.dockerignore').read_text().splitlines())
        for rule in ('android', 'apple', '**/.env', '**/.env.*', '**/secrets',
                     '**/*.p8', '**/*.p12', '**/*.key', '**/*.keystore', '**/*.jks',
                     '**/*.db', '**/*.db-*', '**/*.log'):
            self.assertIn(rule, rules)


if __name__ == '__main__':
    unittest.main()
