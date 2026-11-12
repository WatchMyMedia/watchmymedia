package auth

import (
	"os"
	"testing"
)

// TestMain lowers the cost of new password hashes: the real ones take
// 64 MiB and three passes each, and these tests sign in hundreds of times.
// TestPasswordHashing checks the real parameters.
func TestMain(m *testing.M) {
	argon = argonParams{time: 1, memory: 64, threads: 1, keyLen: 32}
	os.Exit(m.Run())
}
