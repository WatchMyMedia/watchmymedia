// Package backup writes and prunes archives of the data directory: a
// consistent copy of the SQLite database, config.yaml, and the subtitles
// downloaded from online providers (each cost a download from the day's
// quota). Artwork and converted subtitles are left out since they can be
// made again, and so are the Plex connection's grants
// (plexsync.CredentialsDir): a restored server connects Plex again.
package backup

import (
	"archive/tar"
	"compress/gzip"
	"context"
	"database/sql"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	_ "modernc.org/sqlite" // the "sqlite" driver, for scrubbing the copy
)

const prefix = "watchmymedia-backup-"

// DownloadedSubtitlesDir is where online subtitles are kept, under the data
// directory, and their folder in an archive.
const DownloadedSubtitlesDir = "subtitles-downloaded"

// ArtworkDir is where the pictures admins chose are kept, under the data
// directory.
const ArtworkDir = "artwork"

// Write streams a .tar.gz of the database and config to w.
func Write(ctx context.Context, conn *sql.DB, dataDir string, w io.Writer) error {
	tmp, err := os.MkdirTemp(dataDir, ".backup-*")
	if err != nil {
		return err
	}
	defer func() { _ = os.RemoveAll(tmp) }()
	snapshot := filepath.Join(tmp, "watchmymedia.db")
	// VACUUM INTO produces a consistent copy without stopping writers.
	if _, err := conn.ExecContext(ctx, "VACUUM INTO ?", snapshot); err != nil {
		return fmt.Errorf("snapshot database: %w", err)
	}
	if err := scrub(ctx, snapshot); err != nil {
		return fmt.Errorf("snapshot database: %w", err)
	}
	gz := gzip.NewWriter(w)
	tw := tar.NewWriter(gz)
	if err := addFile(tw, snapshot, "watchmymedia.db"); err != nil {
		return err
	}
	if cfg := filepath.Join(dataDir, "config.yaml"); exists(cfg) {
		if err := addFile(tw, cfg, "config.yaml"); err != nil {
			return err
		}
	}
	// What cannot be fetched again: downloaded subtitles, and the pictures
	// admins chose in place of TMDB's (PLAN-POSTERS.md).
	for _, name := range []string{DownloadedSubtitlesDir, ArtworkDir} {
		err = filepath.WalkDir(filepath.Join(dataDir, name), func(path string, d os.DirEntry, err error) error {
			if err != nil || d.IsDir() {
				return err
			}
			rel, err := filepath.Rel(dataDir, path)
			if err != nil {
				return err
			}
			return addFile(tw, path, filepath.ToSlash(rel))
		})
		if err != nil && !os.IsNotExist(err) {
			return err
		}
	}
	if err := tw.Close(); err != nil {
		return err
	}
	return gz.Close()
}

// scrubbed is what the archive's copy of the database must not keep. The
// Plex grants stay out of the archive, so the copy must not refer to them
// or have sync on: restoring it leaves the connection waiting for a new
// sign-in (PLAN-PLEX-SYNC.md, "Security, credentials and recovery
// controls"). Only the copy changes, never the live database.
var scrubbed = []string{
	`UPDATE plex_connections SET credential_ref = NULL, sync_enabled = 0, problem = 'restored_from_backup'`,
}

func scrub(ctx context.Context, snapshot string) error {
	conn, err := sql.Open("sqlite", snapshot)
	if err != nil {
		return err
	}
	defer func() { _ = conn.Close() }()
	conn.SetMaxOpenConns(1)
	for _, stmt := range scrubbed {
		if _, err := conn.ExecContext(ctx, stmt); err != nil {
			return err
		}
	}
	// Closing the last connection writes everything back into the file.
	return conn.Close()
}

// Save writes an archive into <dataDir>/backups and prunes older ones
// beyond keep. It returns the archive path.
//
// An archive holds password and session hashes, the media token key, the
// *arr API keys and the OpenSubtitles password, so the folder is the
// server's alone (0700, tightened if it was made wider) and so is each
// archive (0600).
func Save(ctx context.Context, conn *sql.DB, dataDir string, keep int) (string, error) {
	dir := filepath.Join(dataDir, "backups")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	//nolint:gosec // G302: a folder, which needs its x bit; 0700 is its owner alone.
	if err := os.Chmod(dir, 0o700); err != nil {
		return "", err
	}
	name := prefix + strings.ReplaceAll(time.Now().UTC().Format("20060102-150405.000"), ".", "-") + ".tar.gz"
	path := filepath.Join(dir, name)
	for i := 1; exists(path); i++ {
		path = filepath.Join(dir, strings.TrimSuffix(name, ".tar.gz")+fmt.Sprintf("-%d.tar.gz", i))
	}
	f, err := os.OpenFile(path+".tmp", os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return "", err
	}
	if err := f.Chmod(0o600); err != nil { // a leftover from before was made wider
		_ = f.Close()
		return "", err
	}
	if err := Write(ctx, conn, dataDir, f); err != nil {
		_ = f.Close()
		_ = os.Remove(path + ".tmp")
		return "", err
	}
	if err := f.Close(); err != nil {
		return "", err
	}
	if err := os.Rename(path+".tmp", path); err != nil {
		return "", err
	}
	if keep > 0 {
		prune(dir, keep)
	}
	return path, nil
}

// List returns existing archives, newest first.
func List(dataDir string) []Info {
	entries, _ := os.ReadDir(filepath.Join(dataDir, "backups"))
	var out []Info
	for _, e := range entries {
		if e.IsDir() || !strings.HasPrefix(e.Name(), prefix) || !strings.HasSuffix(e.Name(), ".tar.gz") {
			continue
		}
		if st, err := e.Info(); err == nil {
			out = append(out, Info{Name: e.Name(), Size: st.Size(), ModTime: st.ModTime()})
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ModTime.After(out[j].ModTime) })
	return out
}

type Info struct {
	Name    string    `json:"name"`
	Size    int64     `json:"size"`
	ModTime time.Time `json:"modTime"`
}

func prune(dir string, keep int) {
	list := List(filepath.Dir(dir))
	for i := keep; i < len(list); i++ {
		_ = os.Remove(filepath.Join(dir, list[i].Name))
	}
}

// RunNightly saves a backup shortly after start and then once a day.
func RunNightly(ctx context.Context, conn *sql.DB, dataDir string, keep func() int, log *slog.Logger) {
	first := time.After(5 * time.Minute)
	ticker := time.NewTicker(24 * time.Hour)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-first:
		case <-ticker.C:
		}
		k := keep()
		if k <= 0 {
			continue
		}
		if path, err := Save(ctx, conn, dataDir, k); err != nil {
			log.Error("nightly backup failed", "err", err)
		} else {
			log.Info("backup written", "path", path)
		}
	}
}

func addFile(tw *tar.Writer, path, name string) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer func() { _ = f.Close() }()
	st, err := f.Stat()
	if err != nil {
		return err
	}
	if err := tw.WriteHeader(&tar.Header{Name: name, Mode: 0o600, Size: st.Size(), ModTime: st.ModTime()}); err != nil {
		return err
	}
	_, err = io.Copy(tw, f)
	return err
}

func exists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}
