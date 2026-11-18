#!/usr/bin/env python3
"""Builds the server's Linux packages: a .deb and an .rpm for amd64 and arm64.

`make linux-packages` builds the Linux binaries, fetches the pinned ffmpeg and
runs this. It writes the few files that differ between packages into a stage
folder, then runs the pinned nfpm (fetch-nfpm.sh) on nfpm.yaml once for each.

  python3 -I deploy/linux/build.py --version v1.7.0
      [--repository-key deploy/linux/repository-key.asc]
      [--signing-key secret.asc --signing-key-id BD64FACE28C2EF71]
      [--binaries build/linux] [--ffmpeg build/ffmpeg] [--out build/linux-packages]
      [--arch amd64] [--format deb]

--repository-key is the armored public half of the key that signs the apt and
dnf repositories (repository-key.asc here; `make linux-packages` passes it).
With it the packages carry their repository entry and the key; without it
they carry neither, so they install and never look for updates.

--signing-key is the secret key nfpm signs the .rpm with (its passphrase in
NFPM_RPM_PASSPHRASE), and --signing-key-id names the subkey that signs: the
key has one signing subkey for the .rpm files and another for the
repositories' lists, and left to itself nfpm takes the last. A .deb is not
signed, since apt trusts the repository's signed list instead.
"""
import argparse
import base64
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
# Go's name for the architecture, and rpm's.
ARCHES = {'amd64': 'x86_64', 'arm64': 'aarch64'}
FORMATS = ('deb', 'rpm')
REPOSITORY_BLOCK = re.compile(r'(?ms)^  # repository: begin\n.*?^  # repository: end\n')
CONFIG_HEADER = '''\
# WatchMyMedia's settings file. The server reads it when it starts:
#   sudo systemctl restart watchmymedia
#
# Every option is commented out, so the defaults and what you choose in
# Settings apply. To set one, remove the "# " in front of it, and in front of
# the heading it is under (the line above it that ends in a colon).
#
# The service keeps its data in /var/lib/watchmymedia and its playback cache
# in /var/cache/watchmymedia, whatever this file says. To move either, set
# WATCHMYMEDIA_DATA_DIR or WATCHMYMEDIA_TRANSCODE_DIR with
# `sudo systemctl edit watchmymedia`.
#
'''


def package_version(version):
    """The version nfpm is given, from a tag or `git describe`.

    v1.7.0 is 1.7.0 and v1.7.0-rc.1 stays a pre-release, which nfpm writes as
    1.7.0~rc.1 so it sorts before 1.7.0. A build between tags
    (v1.6.1-27-g9430ce5, with -dirty for uncommitted changes) is
    1.6.1+27.g9430ce5: newer than 1.6.1 and older than 1.6.2, where nfpm's
    own reading would call it a pre-release of 1.6.1. Anything else, such as
    the bare commit a checkout without tags gives, is 0.0.0+<that>.
    """
    match = re.fullmatch(r'v?(\d+\.\d+\.\d+)(-(?:alpha|beta|rc)\.\d+)?(?:-(\d+)-g([0-9a-f]+))?(-dirty)?', version)
    if not match:
        return '0.0.0+' + (re.sub(r'[^0-9A-Za-z]+', '.', version).strip('.') or 'dev')
    number, prerelease, commits, commit, dirty = match.groups()
    build = ([commits, 'g' + commit] if commits else []) + (['dirty'] if dirty else [])
    return number + (prerelease or '') + ('+' + '.'.join(build) if build else '')


def commented_config(example):
    """deploy/config.example.yaml with every option commented out."""
    lines = example.splitlines()
    if not lines or not lines[0].startswith('# Copy to '):
        raise ValueError('config.example.yaml no longer starts with its "Copy to" line')
    body = [line if not line.strip() or line.lstrip().startswith('#') else '# ' + line for line in lines[1:]]
    return CONFIG_HEADER + '\n'.join(body) + '\n'


def dearmor(armored):
    """Armored OpenPGP public keys as the binary keyring apt's Signed-By reads.

    The file holds one key today and may hold more, one armored block after
    another: dnf never refreshes a key it already holds for a repository, so
    replacing the lists' signer one day means adding a brand-new key beside
    this one. The keyring is the keys in the file's order, as `gpg --dearmor`
    writes them.
    """
    begin, end = '-----BEGIN PGP PUBLIC KEY BLOCK-----', '-----END PGP PUBLIC KEY BLOCK-----'
    keys, body = [], None
    for line in (line.strip() for line in armored.splitlines()):
        if body is None:
            if line == begin:
                body = []
            elif line:
                raise ValueError('the repository key must be armored OpenPGP public keys and nothing else')
        elif line == end:
            # Armor headers (Version:, Comment:) end at the first blank line.
            if '' in body:
                body = body[body.index('') + 1:]
            # The last line may be the armor's checksum, which starts with "=".
            keys.append(base64.b64decode(''.join(part for part in body if not part.startswith('=')), validate=True))
            body = None
        else:
            body.append(line)
    if body is not None or not keys:
        raise ValueError('the repository key must be armored OpenPGP public keys and nothing else')
    return b''.join(keys)


def nfpm_config(with_repository):
    text = (HERE / 'nfpm.yaml').read_text()
    if not REPOSITORY_BLOCK.search(text):
        raise ValueError('nfpm.yaml has lost its "# repository: begin" and "# repository: end" lines')
    return text if with_repository else REPOSITORY_BLOCK.sub('', text)


def source_date_epoch():
    """The commit's time, so the same commit builds the same files."""
    if os.environ.get('SOURCE_DATE_EPOCH'):
        return os.environ['SOURCE_DATE_EPOCH']
    try:
        result = subprocess.run(['git', '-C', str(ROOT), 'log', '-1', '--format=%ct'],
                                capture_output=True, text=True, check=False)
    except OSError:  # no git, as in a bare container
        return ''
    return result.stdout.strip() if result.returncode == 0 else ''


def build(version, binaries, ffmpeg, out, repository_key=None, signing_key=None, signing_key_id=None,
          arches=tuple(ARCHES), formats=FORMATS, nfpm=None):
    """Builds the packages into out and returns their paths."""
    if bool(signing_key) != bool(signing_key_id):
        raise ValueError('a signing key needs the id of the subkey to sign with, and the id needs the key')
    if signing_key_id and not re.fullmatch(r'[0-9A-Fa-f]{16}', signing_key_id):
        raise ValueError('the signing subkey id is 16 hex digits, the last 16 of its fingerprint')
    out = pathlib.Path(out).resolve()
    nfpm = nfpm or subprocess.run([str(HERE / 'fetch-nfpm.sh')], check=True, capture_output=True,
                                  text=True).stdout.strip()
    armored = pathlib.Path(repository_key).read_text() if repository_key else None
    keyring = dearmor(armored) if armored else None
    config = commented_config((ROOT / 'deploy/config.example.yaml').read_text())
    wrapper = (HERE / 'watchmymedia.sh').read_text()
    if wrapper.count('@INSTALL@') != 2:
        raise ValueError('watchmymedia.sh should name @INSTALL@ once in its comment and once in its code')
    epoch = source_date_epoch()
    out.mkdir(parents=True, exist_ok=True)
    packages = []
    with tempfile.TemporaryDirectory(prefix='watchmymedia-packages-') as tmp:
        tmp = pathlib.Path(tmp)
        (tmp / 'nfpm.yaml').write_text(nfpm_config(with_repository=armored is not None))
        for arch in arches:
            server = pathlib.Path(binaries).resolve() / arch / 'watchmymedia'
            tools = pathlib.Path(ffmpeg).resolve() / ('linux-' + arch)
            for path in (server, tools / 'ffmpeg', tools / 'ffprobe', tools / 'VERSION'):
                if not path.is_file():
                    raise FileNotFoundError(f'{path} is missing: `make linux-packages` builds and fetches it')
            for kind in formats:
                stage = tmp / f'{kind}-{arch}'
                stage.mkdir()
                # The comment's @INSTALL@ goes too, so the installed file names only its own kind.
                (stage / 'watchmymedia').write_text(
                    wrapper.replace('# build.py writes deb or rpm in place of @INSTALL@.\n', '')
                    .replace('@INSTALL@', kind))
                (stage / 'config.yaml').write_text(config)
                if armored:
                    (stage / 'watchmymedia-archive-keyring.gpg').write_bytes(keyring)
                    (stage / 'RPM-GPG-KEY-watchmymedia').write_text(armored)
                target = tmp / 'out' / f'{kind}-{arch}'
                target.mkdir(parents=True)
                env = dict(os.environ, WMM_VERSION=package_version(version), WMM_ARCH=arch,
                           WMM_SERVER=str(server), WMM_FFMPEG=str(tools), WMM_STAGE=str(stage),
                           WMM_SIGNING_KEY=str(pathlib.Path(signing_key).resolve()) if signing_key else '',
                           WMM_SIGNING_KEY_ID=(signing_key_id or '').lower())
                if epoch:
                    env['SOURCE_DATE_EPOCH'] = epoch
                # nfpm reads the files nfpm.yaml names from the folder it runs in.
                subprocess.run([nfpm, 'package', '--config', str(tmp / 'nfpm.yaml'), '--packager', kind,
                                '--target', str(target)], cwd=HERE, env=env, check=True,
                               stdout=subprocess.DEVNULL)
                (built,) = target.iterdir()
                # One version in the folder: an earlier build's file would otherwise sit beside this one.
                for earlier in out.glob(f'watchmymedia_*_{arch}.deb' if kind == 'deb'
                                        else f'watchmymedia-*.{ARCHES[arch]}.rpm'):
                    earlier.unlink()
                shutil.move(str(built), str(out / built.name))
                packages.append(out / built.name)
    return packages


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--version', required=True, help='the release tag, or what `git describe` printed')
    parser.add_argument('--binaries', default=ROOT / 'build/linux',
                        help='the folder with <arch>/watchmymedia (default: build/linux)')
    parser.add_argument('--ffmpeg', default=ROOT / 'build/ffmpeg',
                        help='the folder with linux-<arch>/ffmpeg, ffprobe and VERSION (default: build/ffmpeg)')
    parser.add_argument('--out', default=ROOT / 'build/linux-packages',
                        help='where the packages go (default: build/linux-packages)')
    parser.add_argument('--repository-key', help="the repositories' armored public key")
    parser.add_argument('--signing-key', help='the OpenPGP secret key that signs the .rpm')
    parser.add_argument('--signing-key-id', help='the id of the subkey to sign with: 16 hex digits')
    parser.add_argument('--arch', action='append', choices=sorted(ARCHES), help='default: both')
    parser.add_argument('--format', action='append', choices=FORMATS, help='default: both')
    args = parser.parse_args()
    try:
        packages = build(args.version, args.binaries, args.ffmpeg, args.out, args.repository_key,
                         args.signing_key, args.signing_key_id, args.arch or tuple(ARCHES),
                         args.format or FORMATS)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        sys.exit(f'linux packages: {error}')
    for package in packages:
        print(f'{package.stat().st_size / 1e6:6.1f} MB  {package}')


if __name__ == '__main__':
    main()
