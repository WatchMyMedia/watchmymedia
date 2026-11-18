#!/bin/sh
# Makes a throwaway OpenPGP key for tests: RSA 4096 with no passphrase, the
# type the real repository key is. It signs test packages and stands in for
# the repositories' public key; nothing made with it is ever published.
# gpg works in a keyring of its own that is deleted afterwards.
#
#   deploy/linux/throwaway-key.sh OUTDIR   -> OUTDIR/public.asc, OUTDIR/secret.asc
set -eu

out=${1:?usage: throwaway-key.sh OUTDIR}
mkdir -p "$out"
# A short path: gpg's agent socket lives in it, and a socket's path is limited.
home=$(mktemp -d /tmp/wmm-key.XXXXXX)
trap 'gpgconf --homedir "$home" --kill all >/dev/null 2>&1 || true; rm -rf "$home"' EXIT

gpg --homedir "$home" --batch --quiet --pinentry-mode loopback --passphrase '' \
  --quick-generate-key 'WatchMyMedia test packages (throwaway) <packages@example.invalid>' rsa4096 sign never
gpg --homedir "$home" --batch --quiet --armor --export > "$out/public.asc"
gpg --homedir "$home" --batch --quiet --pinentry-mode loopback --passphrase '' \
  --armor --export-secret-keys > "$out/secret.asc"
chmod 600 "$out/secret.asc"
echo "throwaway key -> $out/public.asc, $out/secret.asc"
