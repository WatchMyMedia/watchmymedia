#!/usr/bin/env python3
"""Prints the id of the key that signed an .rpm: 16 hex digits, the end of
the signing key's or subkey's fingerprint. Nothing is printed for a package
that is not signed.

  python3 -I deploy/linux/signer.py FILE.rpm

release.sh asks this after rpm's own `rpmkeys` has verified the signature.
rpmkeys says which key the signature belongs to, and from rpm 6 that is the
key's own fingerprint, the same for every subkey; the package itself says
which subkey made it. The key has one signing subkey for the .rpm files and
another for the repositories' lists, and a release signs with the first.
"""
import struct
import sys

# The signature over the header (RSA), then the older one over header and payload.
SIGNATURE_TAGS = (268, 1002)


def signature_header(data):
    """{tag: bytes} for the binary entries of an .rpm's signature header."""
    if data[:4] != b'\xed\xab\xee\xdb':
        raise ValueError('not an rpm')
    start = 96  # after the lead
    if data[start:start + 3] != b'\x8e\xad\xe8':
        raise ValueError('no signature header where one should be')
    count, _ = struct.unpack('>II', data[start + 8:start + 16])
    store = start + 16 + 16 * count
    entries = {}
    for index in range(count):
        tag, kind, offset, number = struct.unpack('>IIII', data[start + 16 + 16 * index:start + 32 + 16 * index])
        if kind == 7:  # binary
            entries[tag] = data[store + offset:store + offset + number]
    return entries


def issuer(signature):
    """The signer's key id from a version 4 OpenPGP signature packet."""
    first = signature[0]
    if first & 0x40:  # the newer packet header
        tag, length, at = first & 0x3f, signature[1], 2
        if 192 <= length < 224:
            at = 3
        elif length == 255:
            at = 6
    else:
        tag, at = (first & 0x3c) >> 2, 1 + (1, 2, 4)[first & 3]
    body = signature[at:]
    if tag != 2 or body[0] != 4:
        raise ValueError('not a version 4 signature')
    found, at = {}, 4
    for _ in range(2):  # the hashed subpackets, then the unhashed
        end = at + 2 + int.from_bytes(body[at:at + 2], 'big')
        at += 2
        while at < end:
            length = body[at]
            at += 1
            if 192 <= length < 255:
                length = ((length - 192) << 8) + body[at] + 192
                at += 1
            elif length == 255:
                length = int.from_bytes(body[at:at + 4], 'big')
                at += 4
            found[body[at] & 0x7f] = body[at + 1:at + length]
            at += length
    # 33 is the signer's fingerprint after a version byte, 16 its key id.
    if 33 in found:
        return found[33][-8:].hex().upper()
    if 16 in found:
        return found[16].hex().upper()
    raise ValueError('the signature does not name its signer')


def signer(path):
    with open(path, 'rb') as package:
        entries = signature_header(package.read())
    for tag in SIGNATURE_TAGS:
        if tag in entries:
            return issuer(entries[tag])
    return None


if __name__ == '__main__':
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    try:
        print(signer(sys.argv[1]) or '')
    except (OSError, ValueError, IndexError, struct.error) as error:
        sys.exit(f'signer.py: {sys.argv[1]}: {error}')
