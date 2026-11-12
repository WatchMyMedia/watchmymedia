// Package auth owns accounts, cookie sessions and the request middleware
// that puts the current user on the context.
package auth

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"fmt"
	"strings"

	"golang.org/x/crypto/argon2"
)

// MinPasswordLength applies to new passwords. Older, shorter ones still
// sign in.
const MinPasswordLength = 8

// argonParams are the costs of an argon2id hash.
type argonParams struct {
	time    uint32
	memory  uint32 // KiB
	threads uint8
	keyLen  uint32
}

// defaultArgon is what new hashes cost.
var defaultArgon = argonParams{time: 3, memory: 64 * 1024, threads: 2, keyLen: 32}

// argon is the cost new hashes are made with: defaultArgon, except in this
// package's tests, which lower it. Each hash records its own parameters, so
// hashes made with either verify.
var argon = defaultArgon

// dummyHash is checked against when a name is unknown, so that takes as
// long as a wrong password. It is made with the current parameters.
func dummyHash() string { return encodeHash(argon, make([]byte, 16), make([]byte, argon.keyLen)) }

// HashPassword returns an argon2id hash in the standard encoded form.
func HashPassword(password string) (string, error) {
	return hashWith(argon, password)
}

func hashWith(a argonParams, password string) (string, error) {
	if len(password) < MinPasswordLength {
		return "", &InvalidError{fmt.Sprintf("password must be at least %d characters", MinPasswordLength)}
	}
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	return encodeHash(a, salt, argon2.IDKey([]byte(password), salt, a.time, a.memory, a.threads, a.keyLen)), nil
}

func encodeHash(a argonParams, salt, key []byte) string {
	return fmt.Sprintf("$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s", argon2.Version, a.memory, a.time, a.threads,
		base64.RawStdEncoding.EncodeToString(salt), base64.RawStdEncoding.EncodeToString(key))
}

// VerifyPassword reports whether password matches the encoded hash.
func VerifyPassword(encoded, password string) bool {
	parts := strings.Split(encoded, "$")
	if len(parts) != 6 || parts[1] != "argon2id" {
		return false
	}
	var mem, t uint32
	var p uint8
	if _, err := fmt.Sscanf(parts[3], "m=%d,t=%d,p=%d", &mem, &t, &p); err != nil {
		return false
	}
	salt, err := base64.RawStdEncoding.DecodeString(parts[4])
	if err != nil {
		return false
	}
	want, err := base64.RawStdEncoding.DecodeString(parts[5])
	if err != nil {
		return false
	}
	got := argon2.IDKey([]byte(password), salt, t, mem, p, uint32(len(want)))
	return subtle.ConstantTimeCompare(got, want) == 1
}
