#!/bin/sh
# Makes a throwaway OpenPGP key for tests, shaped like the real one
# (repository-key.asc): an RSA 4096 primary key that only certifies, and two
# signing subkeys, the first for the .rpm files and the second for the
# repositories' lists. No passphrase. What a release is given is the first
# subkey's secret part alone, so that is what this writes; the primary key's
# secret part never leaves gpg's keyring here, which is deleted afterwards.
# Nothing signed with this key is ever published.
#
#   deploy/linux/throwaway-key.sh OUTDIR
#     OUTDIR/public.asc      the public key, both subkeys in it
#     OUTDIR/secret.asc      the first signing subkey's secret part
#     OUTDIR/signing-key-id  that subkey's id, 16 hex digits
set -eu

out=${1:?usage: throwaway-key.sh OUTDIR}
mkdir -p "$out"
# A short path: gpg's agent socket lives in it, and a socket's path is limited.
home=$(mktemp -d /tmp/wmm-key.XXXXXX)
trap 'gpgconf --homedir "$home" --kill all >/dev/null 2>&1 || true; rm -rf "$home"' EXIT

key() { gpg --homedir "$home" --batch --quiet --pinentry-mode loopback --passphrase '' "$@"; }
# The fingerprints gpg lists, the primary key's first and then each subkey's.
fingerprints() { key --list-keys --with-colons --with-subkey-fingerprints | awk -F: '$1 == "fpr" { print $10 }'; }

key --quick-generate-key 'WatchMyMedia test packages (throwaway) <packages@example.invalid>' rsa4096 cert never
primary=$(fingerprints | sed -n 1p)
key --quick-add-key "$primary" rsa4096 sign never
key --quick-add-key "$primary" rsa4096 sign never
packages=$(fingerprints | sed -n 2p)

key --armor --export > "$out/public.asc"
# The "!" keeps gpg to this subkey: without it, it exports every subkey.
key --armor --export-secret-subkeys "$packages!" > "$out/secret.asc"
chmod 600 "$out/secret.asc"
printf '%s\n' "$packages" | cut -c25-40 > "$out/signing-key-id"
echo "throwaway key -> $out/public.asc, $out/secret.asc (subkey $(cat "$out/signing-key-id"))"
