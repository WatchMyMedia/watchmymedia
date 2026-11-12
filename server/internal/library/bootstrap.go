// Package library holds the domain services for movies, shows and the
// media files behind them, and seeds a new server's libraries from config.
package library

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"strings"

	"watchmymedia/server/internal/config"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/settings"
)

const (
	TypeMovie = "movie"
	TypeTV    = "tv"
)

// EnsureFromConfig adds the folders named in the configuration as libraries
// once, on a server that has none: a new server set up from its config, or
// the Docker image's /movies and /tv. After that the libraries are the
// admin's, made and removed in setup and Settings, and the configuration is
// not read again, so a library removed there stays removed
// (PLAN-ONBOARDING.md §2.3). Only folders that exist and hold something are
// added, so an empty or missing mount becomes no library; while none has
// been added, the next start tries again, as an external drive may simply
// be unmounted.
func EnsureFromConfig(ctx context.Context, q *db.Queries, cfg config.LibrariesConfig, log *slog.Logger) error {
	seeded, err := q.GetSetting(ctx, settings.KeyLibrariesSeeded)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	if seeded == "1" {
		return nil
	}
	has, err := q.HasLibraries(ctx)
	if err != nil {
		return err
	}
	seed := []struct{ typ, name, path string }{
		{TypeMovie, "Movies", cfg.Movies},
		{TypeTV, "TV Shows", cfg.TV},
	}
	for _, s := range seed {
		if has || s.path == "" {
			continue
		}
		if reason := unusable(s.path); reason != "" {
			log.Warn("library folder from config not added", "type", s.typ, "path", s.path, "reason", reason)
			continue
		}
		lib, err := q.CreateLibrary(ctx, db.CreateLibraryParams{Type: s.typ, Name: s.name, Path: s.path})
		if err != nil {
			return fmt.Errorf("add %s library: %w", s.typ, err)
		}
		log.Info("library added from config", "id", lib.ID, "type", lib.Type, "path", lib.Path)
	}
	if has, err = q.HasLibraries(ctx); err != nil || !has {
		return err
	}
	return MarkSeeded(ctx, q)
}

// MarkSeeded records that the server has had a library, so the configured
// folders are never added again; adding a library from the API calls it too.
func MarkSeeded(ctx context.Context, q *db.Queries) error {
	return q.SetSetting(ctx, db.SetSettingParams{Key: settings.KeyLibrariesSeeded, Value: "1"})
}

// unusable says why a configured folder can't become a library yet, or "".
func unusable(dir string) string {
	st, err := os.Stat(dir)
	switch {
	case err != nil:
		return "folder not found"
	case !st.IsDir():
		return "not a folder"
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return "folder can't be read: " + err.Error()
	}
	for _, e := range entries {
		if !strings.HasPrefix(e.Name(), ".") {
			return ""
		}
	}
	return "folder is empty"
}
