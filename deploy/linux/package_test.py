"""What is in the Linux packages: paths, modes, config files, scripts, versions.

The tests build real packages with the pinned nfpm from stand-in files, so
they run in seconds anywhere, and read the .deb and the .rpm themselves, so
they need neither dpkg nor rpm. With WMM_LINUX_SMOKE naming the folder
`make linux-smoke-packages` filled (build/linux-smoke), the same checks also
run on those packages, which hold the real server and ffmpeg.

  python3 -I -m unittest discover -s deploy/linux -p '*_test.py'
"""
import base64
import gzip
import importlib.util
import io
import os
import pathlib
import re
import shutil
import struct
import subprocess
import tarfile
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]

_spec = importlib.util.spec_from_file_location('linux_build', HERE / 'build.py')
build = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(build)

# Not a key: the packages only carry these bytes, and nothing here verifies with them.
STAND_IN_KEY = ('-----BEGIN PGP PUBLIC KEY BLOCK-----\n'
                'Comment: a stand-in for tests\n'
                '\n'
                + base64.b64encode(b'stand-in public key for the package tests').decode() + '\n'
                '=AAAA\n'
                '-----END PGP PUBLIC KEY BLOCK-----\n')

# path: (mode, owner, group), in both packages.
FILES = {
    '/usr/lib/watchmymedia/watchmymedia': (0o755, 'root', 'root'),
    '/usr/lib/watchmymedia/ffmpeg': (0o755, 'root', 'root'),
    '/usr/lib/watchmymedia/ffprobe': (0o755, 'root', 'root'),
    '/usr/lib/watchmymedia/VERSION': (0o644, 'root', 'root'),
    '/usr/bin/watchmymedia': (0o755, 'root', 'root'),
    '/usr/lib/systemd/system/watchmymedia.service': (0o644, 'root', 'root'),
    '/usr/lib/sysusers.d/watchmymedia.conf': (0o644, 'root', 'root'),
    # It can hold keys: root writes it, the service reads it, nobody else does.
    '/etc/watchmymedia/config.yaml': (0o640, 'root', 'watchmymedia'),
    '/usr/lib/firewalld/services/watchmymedia.xml': (0o644, 'root', 'root'),
    '/usr/share/doc/watchmymedia/LICENSE': (0o644, 'root', 'root'),
    '/usr/share/doc/watchmymedia/FFMPEG-LICENSE.md': (0o644, 'root', 'root'),
}
ONLY = {
    'deb': {'/etc/ufw/applications.d/watchmymedia': (0o644, 'root', 'root')},
    'rpm': {},
}
REPOSITORY = {
    'deb': {'/etc/apt/sources.list.d/watchmymedia.sources': (0o644, 'root', 'root'),
            '/usr/share/keyrings/watchmymedia-archive-keyring.gpg': (0o644, 'root', 'root')},
    'rpm': {'/etc/yum.repos.d/watchmymedia.repo': (0o644, 'root', 'root'),
            '/etc/pki/rpm-gpg/RPM-GPG-KEY-watchmymedia': (0o644, 'root', 'root')},
}
# Marked as config, so an update keeps a person's changes.
CONFIG = {
    'deb': {'/etc/watchmymedia/config.yaml', '/etc/ufw/applications.d/watchmymedia'},
    'rpm': {'/etc/watchmymedia/config.yaml'},
}
REPOSITORY_CONFIG = {
    'deb': {'/etc/apt/sources.list.d/watchmymedia.sources'},
    'rpm': {'/etc/yum.repos.d/watchmymedia.repo'},
}
SCRIPTS = {'preinstall': ('preinst', 1023), 'postinstall': ('postinst', 1024),
           'preremove': ('prerm', 1025), 'postremove': ('postrm', 1026)}
ELF_MACHINE = {'amd64': 62, 'arm64': 183}


def unzstd(data):
    """zstd data unpacked, with Python 3.14's module or the zstd command."""
    try:
        from compression import zstd
        return zstd.decompress(data)
    except ImportError:
        pass
    if not shutil.which('zstd'):
        raise unittest.SkipTest('reading a .deb needs Python 3.14 or the zstd command')
    return subprocess.run(['zstd', '-dc'], input=data, capture_output=True, check=True).stdout


class Package:
    """What both formats can say about themselves, read from the file."""

    def __init__(self, path):
        self.path = pathlib.Path(path)
        self.kind = self.path.suffix[1:]
        self.files = {}     # path: (mode, owner, group)
        self.sizes = {}     # path: bytes
        self.folders = set()
        self.config = set()
        self.scripts = {}   # preinstall, ...: text
        self.heads = {}     # path: its first 8 KiB (.deb only)
        (self._read_deb if self.kind == 'deb' else self._read_rpm)()

    # A .deb is an ar archive of debian-binary, control.tar.gz and data.tar.zst.
    def _read_deb(self):
        members = {}
        with self.path.open('rb') as archive:
            if archive.read(8) != b'!<arch>\n':
                raise ValueError(f'{self.path.name} is not an ar archive')
            while len(header := archive.read(60)) == 60:
                size = int(header[48:58])
                members[header[:16].decode().strip().rstrip('/')] = archive.read(size)
                archive.seek(size % 2, 1)
        self.members = list(members)
        control = {}
        with tarfile.open(fileobj=io.BytesIO(gzip.decompress(members['control.tar.gz']))) as tar:
            for member in tar:
                if member.isfile():
                    control[member.name.removeprefix('./')] = tar.extractfile(member).read().decode()
        self.fields = dict(re.findall(r'(?m)^([A-Z][\w-]*): (.*)$', control['control']))
        self.name, self.version, self.arch = (self.fields[key] for key in ('Package', 'Version', 'Architecture'))
        self.release = ''
        self.config = set(control.get('conffiles', '').split())
        self.scripts = {name: control[deb] for name, (deb, _) in SCRIPTS.items() if deb in control}
        self.recommends = [item.strip() for item in self.fields.get('Recommends', '').split(',') if item.strip()]
        self.depends = [item.strip() for item in self.fields.get('Depends', '').split(',') if item.strip()]
        self.signed = any(name.startswith('_gpg') for name in members)
        with tarfile.open(fileobj=io.BytesIO(unzstd(members['data.tar.zst']))) as tar:
            for member in tar:
                path = '/' + member.name.removeprefix('./').strip('/')
                if member.isdir():
                    self.folders.add(path)
                elif member.isfile():
                    self.files[path] = (member.mode, member.uname, member.gname)
                    self.sizes[path] = member.size
                    self.heads[path] = tar.extractfile(member).read(8192)
                else:
                    raise ValueError(f'{path} is neither a file nor a folder')

    # An .rpm is a lead, a signature header and a header that lists every
    # file with its mode, owner and flags; the payload is not needed.
    def _read_rpm(self):
        data = self.path.read_bytes()
        if data[:4] != b'\xed\xab\xee\xdb':
            raise ValueError(f'{self.path.name} is not an rpm')
        signature, end = self._header(data, 96)
        header, _ = self._header(data, end + (-end) % 8)
        self.signature_tags = set(signature)
        # A signature over the header (RSA 268, DSA 267) or over header and payload (1002, 1005).
        self.signed = bool(self.signature_tags & {267, 268, 1002, 1005})
        self.name, self.version, self.release, self.arch = (header[tag] for tag in (1000, 1001, 1002, 1022))
        self.fields = {'Vendor': header.get(1011, ''), 'License': header.get(1014, ''),
                       'Packager': header.get(1015, ''), 'Homepage': header.get(1020, ''),
                       'Summary': header.get(1004, ''), 'Compressor': header.get(1125, '')}
        self.recommends = list(header.get(5046, []))
        self.depends = [f'{name} {version}'.strip() for name, version in
                        zip(header.get(1049, []), header.get(1050, []))]
        self.scripts = {name: header[tag] for name, (_, tag) in SCRIPTS.items() if tag in header}
        folders = header[1118]
        for index, base in enumerate(header[1117]):
            path = folders[header[1116][index]] + base
            mode, flags = header[1030][index] & 0xffff, header[1037][index]
            if mode & 0o170000 == 0o040000:
                self.folders.add(path)
                continue
            self.files[path] = (mode & 0o7777, header[1039][index], header[1040][index])
            self.sizes[path] = header[1028][index]
            if flags & 1:  # RPMFILE_CONFIG; 16 is RPMFILE_NOREPLACE
                self.config.add(path)
                if not flags & 16:
                    raise ValueError(f'{path} is config without noreplace: an update would overwrite it')

    @staticmethod
    def _header(data, start):
        if data[start:start + 3] != b'\x8e\xad\xe8':
            raise ValueError('no rpm header where one should be')
        count, size = struct.unpack('>II', data[start + 8:start + 16])
        store = start + 16 + 16 * count
        tags = {}
        for index in range(count):
            tag, kind, offset, number = struct.unpack('>IIII', data[start + 16 + 16 * index:start + 32 + 16 * index])
            at = store + offset
            if kind == 3:
                tags[tag] = struct.unpack(f'>{number}H', data[at:at + 2 * number])
            elif kind == 4:
                tags[tag] = struct.unpack(f'>{number}I', data[at:at + 4 * number])
            elif kind in (6, 9):
                tags[tag] = data[at:data.index(b'\0', at)].decode()
            elif kind == 8:
                strings = []
                for _ in range(number):
                    end = data.index(b'\0', at)
                    strings.append(data[at:end].decode())
                    at = end + 1
                tags[tag] = strings
            elif kind == 7:
                tags[tag] = data[at:at + number]
        return tags, store + size


def stand_ins(folder):
    """Small files where the server and ffmpeg go, for both architectures."""
    folder = pathlib.Path(folder)
    for arch in build.ARCHES:
        (folder / 'binaries' / arch).mkdir(parents=True)
        (folder / 'binaries' / arch / 'watchmymedia').write_text(f'stand-in server for {arch}\n')
        tools = folder / 'ffmpeg' / f'linux-{arch}'
        tools.mkdir(parents=True)
        for name in ('ffmpeg', 'ffprobe'):
            (tools / name).write_text(f'stand-in {name} for {arch}\n')
        (tools / 'VERSION').write_text(f'jellyfin-ffmpeg stand-in (linux-{arch})\n')
    return folder / 'binaries', folder / 'ffmpeg'


class Checks:
    """Checks shared by the stand-in packages and the ones built for the smoke test."""

    def check_package(self, package, kind, arch, repository, armored=None):
        with self.subTest(package=package.path.name):
            self.assertEqual('watchmymedia', package.name)
            self.assertEqual(arch if kind == 'deb' else build.ARCHES[arch], package.arch)

            expected = dict(FILES, **ONLY[kind], **(REPOSITORY[kind] if repository else {}))
            self.assertEqual(expected, package.files)
            # The package owns neither the data nor the cache, so removing it leaves them.
            for path in list(package.files) + list(package.folders):
                self.assertFalse(path.startswith('/var'), path)
            if kind == 'rpm':
                # Only folders of its own: rpm removes the folders a package owns.
                self.assertEqual({'/usr/lib/watchmymedia', '/etc/watchmymedia', '/usr/share/doc/watchmymedia'},
                                 package.folders)
            self.assertEqual(CONFIG[kind] | (REPOSITORY_CONFIG[kind] if repository else set()), package.config)

            for name in SCRIPTS:
                self.assertEqual((HERE / 'scripts' / f'{name}.sh').read_text().strip(),
                                 package.scripts[name].strip(), name)
            if kind == 'deb':
                self.assertEqual(['libva2', 'libva-drm2', 'libdrm2'], package.recommends)
                self.assertEqual(['libc6 (>= 2.28)'], package.depends)
                self.assertEqual('Eldwick Software <eldwicksoftware@gmail.com>', package.fields['Maintainer'])
                self.assertEqual('https://watchmy.media', package.fields['Homepage'])
                self.assertEqual(['debian-binary', 'control.tar.gz', 'data.tar.zst'], package.members)
                # apt trusts the repository's signed list, not a signature in the file.
                self.assertFalse(package.signed)
                self.check_deb_files(package, kind, repository, armored)
            else:
                self.assertEqual(['libva', 'libdrm'], package.recommends)
                self.assertIn('glibc 2.28', package.depends)
                self.assertEqual('Eldwick Software', package.fields['Vendor'])
                self.assertEqual('AGPL-3.0-only', package.fields['License'])
                self.assertEqual('zstd', package.fields['Compressor'])
                self.assertEqual('1', package.release)

    def check_deb_files(self, package, kind, repository, armored):
        text = {path: head.decode(errors='replace') for path, head in package.heads.items()}
        wrapper = text['/usr/bin/watchmymedia']
        self.assertTrue(wrapper.startswith('#!/bin/sh\n'))
        self.assertIn(f': "${{WATCHMYMEDIA_INSTALL:={kind}}}"', wrapper)
        self.assertNotIn('@INSTALL@', wrapper)
        self.assertTrue(text['/etc/watchmymedia/config.yaml'].startswith(build.CONFIG_HEADER[:200]))
        self.assertEqual((HERE / 'watchmymedia.service').read_text(),
                         text['/usr/lib/systemd/system/watchmymedia.service'])
        if repository and armored:
            self.assertEqual(build.dearmor(armored), package.heads['/usr/share/keyrings/watchmymedia-archive-keyring.gpg'])

    def check_same_files(self, deb, rpm):
        """The .rpm's payload is not read here, so its files have to be the size of the .deb's.

        (The wrapper differs in one word, deb or rpm, of the same length.)
        """
        for path in FILES:
            self.assertEqual(deb.sizes[path], rpm.sizes[path], path)


class PackageContentsTest(Checks, unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.dir = pathlib.Path(cls.tmp.name)
        cls.binaries, cls.ffmpeg = stand_ins(cls.dir)
        cls.key = cls.dir / 'public.asc'
        cls.key.write_text(STAND_IN_KEY)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def build(self, version, out, **options):
        paths = build.build(version, self.binaries, self.ffmpeg, self.dir / out, **options)
        return {(path.suffix[1:], next(arch for arch, rpm in build.ARCHES.items()
                                       if arch in path.name or rpm in path.name)): Package(path) for path in paths}

    def test_a_release_build_has_everything_in_its_place(self):
        packages = self.build('v1.7.0', 'release', repository_key=self.key)
        self.assertEqual({'watchmymedia_1.7.0_amd64.deb', 'watchmymedia_1.7.0_arm64.deb',
                          'watchmymedia-1.7.0-1.x86_64.rpm', 'watchmymedia-1.7.0-1.aarch64.rpm'},
                         {package.path.name for package in packages.values()})
        for (kind, arch), package in packages.items():
            self.assertEqual('1.7.0', package.version)
            self.check_package(package, kind, arch, repository=True, armored=STAND_IN_KEY)
        for arch in build.ARCHES:
            self.check_same_files(packages['deb', arch], packages['rpm', arch])
            # Each package holds its own architecture's files.
            self.assertEqual(f'stand-in server for {arch}\n'.encode(),
                             packages['deb', arch].heads['/usr/lib/watchmymedia/watchmymedia'])
            self.assertEqual(f'jellyfin-ffmpeg stand-in (linux-{arch})\n'.encode(),
                             packages['deb', arch].heads['/usr/lib/watchmymedia/VERSION'])

    def test_a_build_without_the_key_has_no_repository_entry(self):
        packages = self.build('v1.7.0', 'plain')
        for (kind, arch), package in packages.items():
            self.check_package(package, kind, arch, repository=False)
            for path in REPOSITORY[kind]:
                self.assertNotIn(path, package.files)
            self.assertFalse(package.signed)

    def test_versions_sort_as_releases_do(self):
        # What nfpm is given, and what it writes in each format.
        cases = (
            ('v1.7.0', '1.7.0', '1.7.0'),
            # A pre-release sorts before its release in both formats.
            ('v1.7.0-rc.1', '1.7.0-rc.1', '1.7.0~rc.1'),
            ('v1.7.0-beta.2', '1.7.0-beta.2', '1.7.0~beta.2'),
            # A build between tags is newer than the tag it follows.
            ('v1.6.1-27-g9430ce5', '1.6.1+27.g9430ce5', '1.6.1+27.g9430ce5'),
            ('v1.6.1-27-g9430ce5-dirty', '1.6.1+27.g9430ce5.dirty', '1.6.1+27.g9430ce5.dirty'),
            ('v1.7.0-rc.1-3-gabcdef0', '1.7.0-rc.1+3.gabcdef0', '1.7.0~rc.1+3.gabcdef0'),
            ('v1.7.0-dirty', '1.7.0+dirty', '1.7.0+dirty'),
            # A checkout without tags, as CI's is.
            ('9430ce5', '0.0.0+9430ce5', '0.0.0+9430ce5'),
            ('dev', '0.0.0+dev', '0.0.0+dev'),
        )
        for tag, given, written in cases:
            with self.subTest(tag=tag):
                self.assertEqual(given, build.package_version(tag))
                packages = self.build(tag, 'versions-' + tag, arches=('amd64',))
                deb, rpm = packages['deb', 'amd64'], packages['rpm', 'amd64']
                self.assertEqual(written, deb.version)
                self.assertEqual(f'watchmymedia_{written}_amd64.deb', deb.path.name)
                self.assertEqual((written, '1'), (rpm.version, rpm.release))
                self.assertEqual(f'watchmymedia-{written}-1.x86_64.rpm', rpm.path.name)

    def test_the_rpm_is_signed_when_given_a_key(self):
        if not shutil.which('gpg'):
            self.skipTest('making a throwaway key needs gpg')
        key = self.dir / 'key'
        subprocess.run([str(HERE / 'throwaway-key.sh'), str(key)], check=True, capture_output=True)
        packages = self.build('v1.7.0', 'signed', arches=('amd64',), repository_key=key / 'public.asc',
                              signing_key=key / 'secret.asc')
        self.assertTrue(packages['rpm', 'amd64'].signed)
        self.assertFalse(packages['deb', 'amd64'].signed)
        armored = (key / 'public.asc').read_text()
        self.check_package(packages['deb', 'amd64'], 'deb', 'amd64', repository=True, armored=armored)
        # The keyring apt reads is gpg's own binary form of the same key.
        dearmored = subprocess.run(['gpg', '--dearmor'], input=armored.encode(), capture_output=True, check=True)
        self.assertEqual(dearmored.stdout, build.dearmor(armored))

    def test_a_key_that_is_not_one_armored_public_key_is_refused(self):
        for text in ('', 'not a key', STAND_IN_KEY.replace('PUBLIC', 'PRIVATE')):
            with self.subTest(text=text[:30]):
                with self.assertRaises(ValueError):
                    build.dearmor(text)
        self.assertEqual(b'stand-in public key for the package tests', build.dearmor(STAND_IN_KEY))


class SourceFilesTest(unittest.TestCase):
    def test_every_option_in_the_packaged_config_is_commented_out(self):
        example = (ROOT / 'deploy/config.example.yaml').read_text()
        config = build.commented_config(example)
        self.assertTrue(config.startswith(build.CONFIG_HEADER))
        body = config[len(build.CONFIG_HEADER):].splitlines()
        options = 0
        # The example's own lines, each either as it was (a comment or blank) or with "# " put before it.
        for source, line in zip(example.splitlines()[1:], body, strict=True):
            if line.strip():
                self.assertTrue(line.lstrip().startswith('#'), line)
            self.assertIn(line, (source, '# ' + source))
            options += line != source
        self.assertGreater(options, 40)
        self.assertIn('# port: 27587', body)
        with self.assertRaises(ValueError):
            build.commented_config('port: 27587\n')

    def test_the_account_is_the_same_before_and_after_unpacking(self):
        # preinstall.sh makes the account before the package's sysusers file exists.
        lines = [line for line in (HERE / 'sysusers.conf').read_text().splitlines()
                 if line and not line.startswith('#')]
        self.assertEqual(['u watchmymedia - "WatchMyMedia server" /var/lib/watchmymedia',
                          'm watchmymedia render', 'm watchmymedia video'], lines)
        self.assertIn("<<'EOF'\n" + '\n'.join(lines) + '\nEOF\n', (HERE / 'scripts/preinstall.sh').read_text())

    def test_the_service_is_the_one_the_plan_gives(self):
        unit = (HERE / 'watchmymedia.service').read_text()
        for line in ('User=watchmymedia', 'Group=watchmymedia', 'ExecStart=/usr/bin/watchmymedia',
                     'StateDirectory=watchmymedia', 'CacheDirectory=watchmymedia', 'Restart=on-failure',
                     'NoNewPrivileges=yes', 'PrivateTmp=yes', 'ProtectSystem=full',
                     'After=network-online.target remote-fs.target', 'WantedBy=multi-user.target'):
            self.assertIn(line + '\n', unit)
        # Libraries live in /home, /mnt and /media and the server writes to
        # them, and the GPU's device nodes have to stay reachable.
        for setting in ('ProtectHome', 'ProtectSystem=strict', 'PrivateDevices', 'DeviceAllow', 'ReadOnlyPaths'):
            self.assertNotIn(setting, unit)

    def test_the_wrapper_fills_in_only_what_is_unset(self):
        wrapper = (HERE / 'watchmymedia.sh').read_text().replace('@INSTALL@', 'deb')
        with tempfile.TemporaryDirectory() as tmp:
            script = pathlib.Path(tmp) / 'watchmymedia'
            # Print the environment instead of starting a server.
            script.write_text(wrapper.replace('exec /usr/lib/watchmymedia/watchmymedia "$@"',
                                              'env | grep ^WATCHMYMEDIA_ | sort; echo "args: $*"'))
            def run(**env):
                return subprocess.run(['sh', str(script), 'backup'], env={'PATH': os.environ['PATH'], **env},
                                      capture_output=True, text=True, check=True).stdout.splitlines()
            self.assertEqual(['WATCHMYMEDIA_CONFIG=/etc/watchmymedia/config.yaml',
                              'WATCHMYMEDIA_DATA_DIR=/var/lib/watchmymedia',
                              'WATCHMYMEDIA_INSTALL=deb',
                              'WATCHMYMEDIA_TRANSCODE_DIR=/var/cache/watchmymedia',
                              'args: backup'], run())
            self.assertEqual(['WATCHMYMEDIA_CONFIG=/etc/watchmymedia/config.yaml',
                              'WATCHMYMEDIA_DATA_DIR=/srv/media/data',
                              'WATCHMYMEDIA_INSTALL=binary',
                              'WATCHMYMEDIA_TRANSCODE_DIR=/var/cache/watchmymedia',
                              'args: backup'],
                             run(WATCHMYMEDIA_DATA_DIR='/srv/media/data', WATCHMYMEDIA_INSTALL='binary'))
        self.assertIn('exec /usr/lib/watchmymedia/watchmymedia "$@"\n', wrapper)

    def test_the_repository_entries_point_at_the_signed_repositories(self):
        sources = dict(re.findall(r'(?m)^([\w-]+): (.*)$', (HERE / 'watchmymedia.sources').read_text()))
        self.assertEqual({'Types': 'deb', 'URIs': 'https://downloads.watchmy.media/apt', 'Suites': 'stable',
                          'Components': 'main', 'Architectures': 'amd64 arm64',
                          'Signed-By': '/usr/share/keyrings/watchmymedia-archive-keyring.gpg'}, sources)
        self.assertIn(sources['Signed-By'], REPOSITORY['deb'])
        repo = (HERE / 'watchmymedia.repo').read_text()
        self.assertIn('[watchmymedia]\n', repo)
        settings = dict(re.findall(r'(?m)^(\w+)=(.*)$', repo))
        self.assertEqual({'name': 'WatchMyMedia', 'baseurl': 'https://downloads.watchmy.media/rpm/$basearch',
                          'enabled': '1', 'gpgcheck': '1', 'repo_gpgcheck': '1',
                          'gpgkey': 'file:///etc/pki/rpm-gpg/RPM-GPG-KEY-watchmymedia',
                          'skip_if_unavailable': '1'}, settings)
        self.assertIn(settings['gpgkey'].removeprefix('file://'), REPOSITORY['rpm'])

    def test_the_firewall_files_only_name_the_port(self):
        self.assertIn('<port protocol="tcp" port="27587"/>', (HERE / 'firewalld.xml').read_text())
        self.assertIn('[WatchMyMedia]\n', (HERE / 'ufw.profile').read_text())
        self.assertIn('ports=27587/tcp\n', (HERE / 'ufw.profile').read_text())
        # Nothing the package runs opens a firewall or changes SELinux.
        for script in sorted((HERE / 'scripts').glob('*.sh')):
            for command in ('firewall-cmd', 'ufw ', 'setenforce', 'semanage', 'setsebool', 'chcon'):
                self.assertNotIn(command, script.read_text(), script.name)

    def test_nfpm_is_pinned_by_version_and_checksum(self):
        script = (HERE / 'fetch-nfpm.sh').read_text()
        self.assertRegex(script, r'(?m)^VERSION=\d+\.\d+\.\d+$')
        sums = re.findall(r'(?m)^SHA256_(\w+)=([0-9a-f]{64})$', script)
        self.assertEqual(['DARWIN_ARM64', 'DARWIN_X86_64', 'LINUX_ARM64', 'LINUX_X86_64'], [name for name, _ in sums])
        self.assertEqual(4, len({digest for _, digest in sums}))

    def test_the_release_archives_pack_ffmpeg_for_linux_too(self):
        makefile = (ROOT / 'Makefile').read_text()
        recipe = re.search(r'(?m)^release:(.*)\n((?:\t.*\n)+)', makefile)
        self.assertIn('ffmpeg-linux', recipe.group(1))
        # One branch packs every platform but Windows, from build/ffmpeg/<os>-<arch>.
        self.assertIn('-C ../build/ffmpeg/$$os-$$arch ffmpeg ffprobe VERSION '
                      '-C ../../../deploy/macos FFMPEG-LICENSE.md', recipe.group(2))
        self.assertEqual(1, recipe.group(2).count('tar '))


@unittest.skipUnless(os.environ.get('WMM_LINUX_SMOKE'), 'set WMM_LINUX_SMOKE to the folder make linux-smoke-packages filled')
class BuiltPackagesTest(Checks, unittest.TestCase):
    """The packages the smoke test installs: the real server and ffmpeg."""

    def packages(self, name):
        folder = pathlib.Path(os.environ['WMM_LINUX_SMOKE']) / name
        found = {}
        for path in sorted(folder.iterdir()):
            arch = next(arch for arch, rpm in build.ARCHES.items() if f'_{arch}.' in path.name or f'.{rpm}.' in path.name)
            found[path.suffix[1:], arch] = Package(path)
        self.assertEqual({(kind, arch) for kind in build.FORMATS for arch in build.ARCHES}, set(found))
        return found

    def test_the_older_build_is_plain_and_the_current_one_is_made_as_a_release(self):
        armored = (pathlib.Path(os.environ['WMM_LINUX_SMOKE']) / 'key/public.asc').read_text()
        older, current = self.packages('older'), self.packages('current')
        for (kind, arch), package in older.items():
            self.check_package(package, kind, arch, repository=False)
            self.assertFalse(package.signed)
        for (kind, arch), package in current.items():
            self.check_package(package, kind, arch, repository=True, armored=armored)
            self.assertEqual(kind == 'rpm', package.signed, package.path.name)
            self.assertNotEqual(older[kind, arch].version, package.version)
        for arch, machine in ELF_MACHINE.items():
            deb = current['deb', arch]
            self.check_same_files(deb, current['rpm', arch])
            for name in ('watchmymedia', 'ffmpeg', 'ffprobe'):
                with self.subTest(arch=arch, file=name):
                    head = deb.heads[f'/usr/lib/watchmymedia/{name}']
                    self.assertEqual(b'\x7fELF', head[:4])
                    self.assertEqual(machine, struct.unpack('<H', head[18:20])[0])
            # The pinned ffmpeg, not a stand-in: tens of megabytes each.
            self.assertGreater(deb.sizes['/usr/lib/watchmymedia/ffmpeg'], 50_000_000)
            pinned = re.search(r'(?m)^VERSION=(\S+)$', (ROOT / 'deploy/macos/fetch-ffmpeg.sh').read_text()).group(1)
            self.assertEqual(f'jellyfin-ffmpeg {pinned} (linux-{arch})\n'.encode(),
                             deb.heads['/usr/lib/watchmymedia/VERSION'])


if __name__ == '__main__':
    unittest.main()
