package plexsync

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"watchmymedia/server/internal/plex"
)

// CredentialsDir is the connection grants' folder under the data folder.
// Backups leave it out, and it is the server's alone: 0700 with 0600 files,
// or an owner-only access list on Windows. That is access control, not
// encryption against the machine's administrator.
const CredentialsDir = "plex-credentials"

// grant is what a connection keeps secret: the device identity Plex
// registered at sign-in. Every other credential (the owner's renewed
// sign-in, server tokens, Home profile tokens) is derived from it when it
// is needed and kept only in memory.
type grant struct {
	ClientIdentifier string
	Key              *plex.DeviceKey
}

func (grant) String() string   { return "plexsync.grant{redacted}" }
func (grant) GoString() string { return "plexsync.grant{redacted}" }

// errNoCredential is a missing or unreadable grant file: the connection has
// to be connected again.
var errNoCredential = errors.New("plexsync: credential unavailable")

// credStore keeps one file per grant, named by an opaque reference that the
// database holds instead of the secret.
type credStore struct{ dir string }

var credRef = regexp.MustCompile(`^[0-9a-f]{32}$`)

func openCredStore(dataDir string) (*credStore, error) {
	dir := filepath.Join(dataDir, CredentialsDir)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	if err := securePath(dir, true); err != nil {
		return nil, err
	}
	return &credStore{dir: dir}, nil
}

type grantFile struct {
	Version          int    `json:"v"`
	ClientIdentifier string `json:"clientIdentifier"`
	DeviceKeySeed    string `json:"deviceKeySeed"`
}

// put writes g under a new reference: to a temporary file first, synced and
// renamed into place, so a crash leaves either nothing or a whole file. A
// file whose reference never reaches the database is an orphan that
// reconcile removes, never a live credential.
func (s *credStore) put(g grant) (string, error) {
	seed := g.Key.Seed()
	if g.ClientIdentifier == "" || seed == nil {
		return "", errors.New("plexsync: incomplete grant")
	}
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	ref := hex.EncodeToString(b[:])
	data, err := json.Marshal(grantFile{Version: 1, ClientIdentifier: g.ClientIdentifier, DeviceKeySeed: base64.StdEncoding.EncodeToString(seed)})
	if err != nil {
		return "", err
	}
	tmp := filepath.Join(s.dir, "."+ref+".tmp")
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return "", err
	}
	if err := writeSynced(f, tmp, data); err != nil {
		_ = os.Remove(tmp)
		return "", err
	}
	if err := os.Rename(tmp, s.path(ref)); err != nil {
		_ = os.Remove(tmp)
		return "", err
	}
	syncDir(s.dir)
	return ref, nil
}

func writeSynced(f *os.File, path string, data []byte) error {
	if err := securePath(path, false); err != nil {
		_ = f.Close()
		return err
	}
	if _, err := f.Write(data); err != nil {
		_ = f.Close()
		return err
	}
	if err := f.Sync(); err != nil {
		_ = f.Close()
		return err
	}
	return f.Close()
}

func (s *credStore) get(ref string) (grant, error) {
	if !credRef.MatchString(ref) {
		return grant{}, errNoCredential
	}
	data, err := os.ReadFile(s.path(ref))
	if err != nil {
		return grant{}, errNoCredential
	}
	var file grantFile
	if json.Unmarshal(data, &file) != nil || file.Version != 1 || file.ClientIdentifier == "" {
		return grant{}, errNoCredential
	}
	seed, err := base64.StdEncoding.DecodeString(file.DeviceKeySeed)
	if err != nil {
		return grant{}, errNoCredential
	}
	key, err := plex.DeviceKeyFromSeed(seed)
	if err != nil {
		return grant{}, errNoCredential
	}
	return grant{ClientIdentifier: file.ClientIdentifier, Key: key}, nil
}

// remove deletes a grant; one already gone is not an error.
func (s *credStore) remove(ref string) error {
	if !credRef.MatchString(ref) {
		return nil
	}
	if err := os.Remove(s.path(ref)); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}

// reconcile removes grant files no connection refers to, and leftover
// temporary files, once they are older than grace: after a crash between
// writing a grant and saving its reference, or after a restore from a
// backup, which clears every reference.
func (s *credStore) reconcile(live map[string]bool, grace time.Duration, now time.Time) (int, error) {
	entries, err := os.ReadDir(s.dir)
	if err != nil {
		return 0, err
	}
	removed := 0
	for _, e := range entries {
		name := e.Name()
		ref, isGrant := strings.CutSuffix(name, ".json")
		if isGrant && (!credRef.MatchString(ref) || live[ref]) {
			continue
		}
		if !isGrant && (!strings.HasPrefix(name, ".") || !strings.HasSuffix(name, ".tmp")) {
			continue
		}
		info, err := e.Info()
		if err != nil || e.IsDir() || now.Sub(info.ModTime()) < grace {
			continue
		}
		if err := os.Remove(filepath.Join(s.dir, name)); err == nil {
			removed++
		}
	}
	return removed, nil
}

func (s *credStore) path(ref string) string { return filepath.Join(s.dir, ref+".json") }

func syncDir(dir string) {
	if d, err := os.Open(dir); err == nil {
		_ = d.Sync()
		_ = d.Close()
	}
}
