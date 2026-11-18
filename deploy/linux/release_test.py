"""What a release attaches for Debian, Ubuntu and Fedora.

Drives release.sh as release.yml's packages job does, from stand-in archives
and a throwaway key shaped like the real one, and reads what comes out: the
four packages, who signed the .rpm files, and the apt and dnf repositories'
lists (repository.sh), down to every hash they name.

The script is for Linux: it needs rpmkeys (the rpm package), apt-ftparchive
(apt-utils), createrepo_c and gpg. Without them these tests are skipped,
unless WMM_LINUX_RELEASE is set, as CI's job sets it after installing them:
then a missing tool fails.

  python3 -I -m unittest discover -s deploy/linux -p '*_test.py'
"""
import gzip
import hashlib
import io
import os
import pathlib
import re
import shutil
import subprocess
import tarfile
import tempfile
import unittest
import xml.etree.ElementTree as ET

import package_test

HERE = pathlib.Path(__file__).resolve().parent
TOOLS = ('rpmkeys', 'apt-ftparchive', 'createrepo_c', 'gpg')
ARCHES = {'amd64': 'x86_64', 'arm64': 'aarch64'}
REPO = '{http://linux.duke.edu/metadata/repo}'
COMMON = '{http://linux.duke.edu/metadata/common}'


def sha256(data):
    return hashlib.sha256(data).hexdigest()


class ReleaseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        missing = [tool for tool in TOOLS if not shutil.which(tool)]
        if missing and os.environ.get('WMM_LINUX_RELEASE'):
            raise RuntimeError('WMM_LINUX_RELEASE is set and these are missing: ' + ', '.join(missing))
        if missing:
            raise unittest.SkipTest('release.sh needs ' + ', '.join(missing))
        cls.tmp = tempfile.TemporaryDirectory()
        cls.dir = pathlib.Path(cls.tmp.name)
        binaries, ffmpeg = package_test.stand_ins(cls.dir)
        # The archives as `make release` packs them: the server under the
        # archive's own name, then ffmpeg, ffprobe, VERSION and the licence.
        cls.archives = cls.dir / 'archives'
        cls.archives.mkdir()
        for tag in ('v1.7.0', 'v1.7.0-rc.1'):
            for arch in ARCHES:
                name = f'watchmymedia-{tag}-linux-{arch}'
                with tarfile.open(cls.archives / f'{name}.tar.gz', 'w:gz') as tar:
                    tar.add(binaries / arch / 'watchmymedia', name)
                    for tool in ('ffmpeg', 'ffprobe', 'VERSION'):
                        tar.add(ffmpeg / f'linux-{arch}' / tool, tool)
                    tar.add(HERE.parent / 'macos/FFMPEG-LICENSE.md', 'FFMPEG-LICENSE.md')
        cls.key = cls.dir / 'key'
        subprocess.run([str(HERE / 'throwaway-key.sh'), str(cls.key)], check=True, capture_output=True)
        cls.signer = (cls.key / 'signing-key-id').read_text().strip()

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def release(self, tag, out, **env):
        """Runs release.sh with the throwaway key, as the job runs it with the real one."""
        settings = dict(os.environ, LINUX_SIGNING_KEY=str(self.key / 'secret.asc'), LINUX_SIGNING_KEY_ID=self.signer,
                        LINUX_REPOSITORY_KEY=str(self.key / 'public.asc'))
        settings.update(env)
        settings = {name: value for name, value in settings.items() if value is not None}
        return subprocess.run([str(HERE / 'release.sh'), tag, str(self.archives), str(self.dir / out)],
                              env=settings, capture_output=True, text=True)

    def test_a_release_attaches_four_packages_and_their_lists(self):
        result = self.release('v1.7.0', 'release')
        self.assertEqual(0, result.returncode, result.stderr)
        out = self.dir / 'release'
        names = {'deb-amd64': 'watchmymedia_1.7.0_amd64.deb', 'deb-arm64': 'watchmymedia_1.7.0_arm64.deb',
                 'rpm-amd64': 'watchmymedia-1.7.0-1.x86_64.rpm', 'rpm-arm64': 'watchmymedia-1.7.0-1.aarch64.rpm'}
        lists = 'watchmymedia-linux-repositories-1.7.0.tar.gz'
        self.assertEqual(set(names.values()) | {lists}, {path.name for path in out.iterdir()})
        # The server in each package is the one in that architecture's archive.
        for arch, rpm_arch in ARCHES.items():
            deb = package_test.Package(out / names[f'deb-{arch}'])
            rpm = package_test.Package(out / names[f'rpm-{arch}'])
            self.assertEqual(f'stand-in server for {arch}\n'.encode(), deb.heads['/usr/lib/watchmymedia/watchmymedia'])
            self.assertEqual(f'stand-in ffmpeg for {arch}\n'.encode(), deb.heads['/usr/lib/watchmymedia/ffmpeg'])
            self.assertEqual(('1.7.0', '1.7.0', rpm_arch), (deb.version, rpm.version, rpm.arch))
            # Signed by the subkey that was named, and carrying the key it was checked against.
            self.assertEqual(self.signer, rpm.signer)
            self.assertFalse(deb.signed)
            self.assertEqual(package_test.build.dearmor((self.key / 'public.asc').read_text()),
                             deb.heads['/usr/share/keyrings/watchmymedia-archive-keyring.gpg'])
            self.assertIn(f'{names[f"rpm-{arch}"]}: signed by {self.signer}', result.stdout)
        self.check_lists(out / lists, {key: (out / name).read_bytes() for key, name in names.items()}, names)

    def check_lists(self, archive, packages, names):
        with tarfile.open(archive) as tar:
            self.assertTrue(all(member.isfile() or member.isdir() for member in tar))
            files = {member.name: tar.extractfile(member).read() for member in tar if member.isfile()}
        # Laid out as downloads.watchmy.media serves them, with nothing signed
        # and no package: the maintainers sign Release and repomd.xml.
        shapes = [r'apt/dists/stable/Release',
                  r'apt/dists/stable/main/binary-(amd64|arm64)/(Packages|Packages\.gz|by-hash/SHA256/[0-9a-f]{64})',
                  r'rpm/(x86_64|aarch64)/repodata/(repomd\.xml|[0-9a-f]{64}-(primary|filelists|other)\.xml\.gz)']
        for name in files:
            self.assertTrue(any(re.fullmatch(shape, name) for shape in shapes), name)
        self.assertEqual(1 + 2 * 4 + 2 * 4, len(files))

        release = files['apt/dists/stable/Release'].decode()
        fields = dict(re.findall(r'(?m)^([A-Z][\w-]*): (.*)$', release))
        self.assertEqual({'Origin': 'WatchMyMedia', 'Label': 'WatchMyMedia', 'Suite': 'stable', 'Codename': 'stable',
                          'Architectures': 'amd64 arm64', 'Components': 'main', 'Acquire-By-Hash': 'yes',
                          'Description': 'The WatchMyMedia server for Debian and Ubuntu'},
                         {name: value for name, value in fields.items() if name != 'Date'})
        self.assertRegex(fields['Date'], r'^\w{3}, \d{2} \w{3} \d{4} \d{2}:\d{2}:\d{2} (\+0000|UTC)$')
        # One hash, so apt asks for by-hash/SHA256/ and nothing else.
        self.assertEqual(['SHA256:'], re.findall(r'(?m)^[A-Z][\w-]*:$', release))
        listed = re.findall(r'(?m)^ ([0-9a-f]{64}) +(\d+) (\S+)$', release)
        self.assertEqual({f'main/binary-{arch}/{name}' for arch in ARCHES for name in ('Packages', 'Packages.gz')},
                         {path for _, _, path in listed})
        for digest, size, path in listed:
            data = files[f'apt/dists/stable/{path}']
            self.assertEqual((digest, int(size)), (sha256(data), len(data)), path)
            self.assertEqual(data, files[f'apt/dists/stable/{path.rsplit("/", 1)[0]}/by-hash/SHA256/{digest}'], path)

        for arch, rpm_arch in ARCHES.items():
            with self.subTest(arch=arch):
                deb, rpm = packages[f'deb-{arch}'], packages[f'rpm-{arch}']
                index = files[f'apt/dists/stable/main/binary-{arch}/Packages']
                self.assertEqual(index, gzip.decompress(files[f'apt/dists/stable/main/binary-{arch}/Packages.gz']))
                self.assertEqual(1, index.count(b'Package: '))
                stanza = dict(re.findall(r'(?m)^([A-Z][\w-]*): (.*)$', index.decode()))
                # The file at the address the package is published at.
                self.assertEqual({'Package': 'watchmymedia', 'Version': '1.7.0', 'Architecture': arch,
                                  'Filename': f'pool/{names[f"deb-{arch}"]}', 'Size': str(len(deb)), 'SHA256': sha256(deb)},
                                 {name: stanza[name] for name in ('Package', 'Version', 'Architecture', 'Filename', 'Size', 'SHA256')})
                self.assertFalse({'MD5sum', 'SHA1', 'SHA512'} & set(stanza))

                repomd = ET.fromstring(files[f'rpm/{rpm_arch}/repodata/repomd.xml'])
                data = {item.get('type'): item for item in repomd.findall(f'{REPO}data')}
                self.assertEqual({'primary', 'filelists', 'other'}, set(data))
                for kind, item in data.items():
                    href = item.find(f'{REPO}location').get('href')
                    packed = files[f'rpm/{rpm_arch}/{href}']
                    checksum = item.find(f'{REPO}checksum')
                    self.assertEqual(('sha256', sha256(packed)), (checksum.get('type'), checksum.text), href)
                    self.assertEqual(f'repodata/{sha256(packed)}-{kind}.xml.gz', href)
                    self.assertEqual(sha256(gzip.decompress(packed)), item.find(f'{REPO}open-checksum').text, href)
                    self.assertEqual(str(len(packed)), item.find(f'{REPO}size').text, href)
                primary = ET.fromstring(gzip.decompress(
                    files[f'rpm/{rpm_arch}/' + data['primary'].find(f'{REPO}location').get('href')]))
                (package,) = primary.findall(f'{COMMON}package')
                version = package.find(f'{COMMON}version')
                self.assertEqual(('watchmymedia', rpm_arch, '1.7.0', '1'),
                                 (package.find(f'{COMMON}name').text, package.find(f'{COMMON}arch').text,
                                  version.get('ver'), version.get('rel')))
                # Beside the lists, where the package is published.
                self.assertEqual(names[f'rpm-{arch}'], package.find(f'{COMMON}location').get('href'))
                self.assertEqual(sha256(rpm), package.find(f'{COMMON}checksum').text)
                self.assertEqual(str(len(rpm)), package.find(f'{COMMON}size').get('package'))

    def test_a_pre_release_attaches_packages_under_names_github_keeps_and_no_lists(self):
        result = self.release('v1.7.0-rc.1', 'pre-release')
        self.assertEqual(0, result.returncode, result.stderr)
        out = self.dir / 'pre-release'
        # nfpm names them 1.7.0~rc.1, and GitHub would turn the "~" into ".".
        self.assertEqual({'watchmymedia_1.7.0.rc.1_amd64.deb', 'watchmymedia_1.7.0.rc.1_arm64.deb',
                          'watchmymedia-1.7.0.rc.1-1.x86_64.rpm', 'watchmymedia-1.7.0.rc.1-1.aarch64.rpm'},
                         {path.name for path in out.iterdir()})
        for path in out.iterdir():
            package = package_test.Package(path)
            # Inside, the version still sorts before 1.7.0.
            self.assertEqual('1.7.0~rc.1', package.version)
            if package.kind == 'rpm':
                self.assertEqual(self.signer, package.signer)
        self.assertIn('no repository lists are written', result.stdout)

    def test_a_package_signed_by_a_key_the_repositories_do_not_hold_is_not_attached(self):
        # The throwaway subkey signs, and the check is against the real key.
        result = self.release('v1.7.0', 'other-key', LINUX_REPOSITORY_KEY=None)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('is not signed by a key in', result.stderr)
        self.assertIn('NOKEY', result.stderr)
        self.assertEqual([], list((self.dir / 'other-key').iterdir()))

    def test_what_is_missing_stops_it_before_anything_is_built(self):
        cases = (
            ('v1.7.0', {'LINUX_SIGNING_KEY': None}, "LINUX_SIGNING_KEY should name the file"),
            ('v1.7.0', {'LINUX_SIGNING_KEY': str(self.dir / 'no-such.key')}, "LINUX_SIGNING_KEY should name the file"),
            ('v1.7.0', {'LINUX_SIGNING_KEY_ID': None}, 'LINUX_SIGNING_KEY_ID should be'),
            ('v1.7.0', {'LINUX_SIGNING_KEY_ID': self.signer[:8]}, 'LINUX_SIGNING_KEY_ID should be'),
            ('v1.7.0', {'LINUX_REPOSITORY_KEY': str(self.dir / 'no-such.asc')}, 'is missing'),
            ('v1.7.1', {}, 'watchmymedia-v1.7.1-linux-amd64.tar.gz is missing'),
            ('1.7.0', {}, 'is not a release tag'),
            ('v1.7.0-3-gabc1234', {}, 'is not a release tag'),
        )
        for index, (tag, env, message) in enumerate(cases):
            with self.subTest(tag=tag, env=sorted(env)):
                result = self.release(tag, f'missing-{index}', **env)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(message, result.stderr)
                out = self.dir / f'missing-{index}'
                self.assertEqual([], list(out.iterdir()) if out.exists() else [])
        # A key whose id is not the one named signs nothing.
        result = self.release('v1.7.0', 'other-id', LINUX_SIGNING_KEY_ID='0123456789ABCDEF')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], list((self.dir / 'other-id').iterdir()))
        # A folder that already holds something is left alone.
        (self.dir / 'taken').mkdir()
        (self.dir / 'taken/earlier.txt').write_text('x')
        result = self.release('v1.7.0', 'taken')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('is not empty', result.stderr)

    def test_the_lists_need_one_package_of_each_kind(self):
        empty = self.dir / 'no-packages'
        empty.mkdir()
        result = subprocess.run([str(HERE / 'repository.sh'), str(empty), str(self.dir / 'no-lists')],
                                capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('expected one', result.stderr)


class ScriptsTest(unittest.TestCase):
    def test_the_scripts_never_print_a_key_and_never_sign_the_lists(self):
        for name in ('release.sh', 'repository.sh'):
            script = (HERE / name).read_text()
            self.assertIn('set -eu\n', script, name)
            self.assertNotIn('set -x', script, name)
            self.assertNotRegex(script, r'(cat|echo|printf)[^\n|]*\$\{?LINUX_SIGNING_KEY\}?"? *$', name)
            # The lists' key is the maintainers', and is never in this repository.
            for marker in ('gpg ', 'InRelease', 'Release.gpg', 'repomd.xml.asc', 'LINUX_REPO_SIGNING_KEY'):
                self.assertNotIn(marker, script, f'{name}: {marker}')


if __name__ == '__main__':
    unittest.main()
