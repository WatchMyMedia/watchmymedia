package settings

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"os"
	"regexp"
	"strings"

	"watchmymedia/server/internal/db"
)

const (
	// KeyServerID is a random ID made on first start. Apps remember servers
	// by it, so a server keeps its identity when its address changes.
	KeyServerID = "server.id"
	// KeyServerName is the name apps show in their server list.
	KeyServerName = "server.name"
)

// ServerID returns the server's ID, making and storing one the first time.
// Of two first calls at once, the one stored first wins and both return it.
func (s *Store) ServerID(ctx context.Context) (string, error) {
	id, err := s.Get(ctx, KeyServerID, "")
	if err != nil || id != "" {
		return id, err
	}
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	if err := s.q.AddSetting(ctx, db.AddSettingParams{Key: KeyServerID, Value: hex.EncodeToString(b)}); err != nil {
		return "", err
	}
	return s.q.GetSetting(ctx, KeyServerID)
}

// ServerName returns the name set in Settings, else one from the host name.
func (s *Store) ServerName(ctx context.Context) string {
	if v, _ := s.Get(ctx, KeyServerName, ""); v != "" {
		return v
	}
	return DefaultServerName()
}

// A Docker container's host name is its ID, which means nothing to anyone.
var containerID = regexp.MustCompile(`^[0-9a-f]{12,64}$`)

// DefaultServerName is the machine's host name without ".local", or
// "WatchMyMedia" where the host name is a container ID.
func DefaultServerName() string {
	h, err := os.Hostname()
	if err != nil {
		return "WatchMyMedia"
	}
	h = strings.TrimSuffix(strings.TrimSuffix(h, "."), ".local")
	if h == "" || containerID.MatchString(h) || h == "localhost" {
		return "WatchMyMedia"
	}
	return h
}
