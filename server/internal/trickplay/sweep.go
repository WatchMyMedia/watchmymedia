package trickplay

import (
	"context"
	"os"
	"path/filepath"

	"watchmymedia/server/internal/db"
)

func (g *Generator) sweepLogged(ctx context.Context) {
	if n, err := g.Sweep(ctx); err != nil {
		g.log.Warn("could not sweep seek thumbnails", "err", err)
	} else if n > 0 {
		g.log.Info("swept seek thumbnails", "folders", n)
	}
}

// Sweep makes the folder match the database. Thumbnails of files missing
// for 30 days go with their rows; folders of files that are gone, of other
// versions, or with no done row go; done rows whose folder is gone go, so
// the job makes them again; and half-made sheets go unless they are for
// the file's current version, which a later run resumes. It returns how
// many folders it removed. Run calls it only between runs.
func (g *Generator) Sweep(ctx context.Context) (int, error) {
	g.work.Lock()
	defer g.work.Unlock()
	rows, err := g.q.ListTrickplayForSweep(ctx)
	if err != nil {
		return 0, err
	}
	cutoff := g.now().Add(-missingFor)
	gone := func(fileStatus, updatedAt string) bool {
		t := db.ParseTime(updatedAt)
		return fileStatus == "missing" && !t.IsZero() && t.Before(cutoff)
	}
	done := map[int64]string{}
	for _, r := range rows {
		if gone(r.FileStatus, r.FileUpdatedAt) {
			if err := g.q.DeleteTrickplay(ctx, r.MediaFileID); err != nil {
				return 0, err
			}
			continue
		}
		if r.Status == "done" {
			done[r.MediaFileID] = r.Version
		}
	}
	removed := 0
	remove := func(path string) {
		if os.RemoveAll(path) == nil {
			removed++
		}
	}
	shards, err := os.ReadDir(g.dir)
	if err != nil && !os.IsNotExist(err) {
		return 0, err
	}
	onDisk := map[int64]bool{}
	for _, s := range shards {
		if !s.IsDir() || len(s.Name()) != 2 {
			continue
		}
		parent := filepath.Join(g.dir, s.Name())
		entries, err := os.ReadDir(parent)
		if err != nil {
			return removed, err
		}
		for _, e := range entries {
			id, version, ok := parseFolder(e.Name())
			if !ok {
				continue
			}
			if v, kept := done[id]; kept && v == version {
				onDisk[id] = true
				continue
			}
			remove(filepath.Join(parent, e.Name()))
		}
	}
	for id := range done {
		if !onDisk[id] {
			if err := g.q.DeleteTrickplay(ctx, id); err != nil {
				return removed, err
			}
		}
	}
	tmp := filepath.Join(g.dir, tmpName)
	entries, err := os.ReadDir(tmp)
	if err != nil && !os.IsNotExist(err) {
		return removed, err
	}
	for _, e := range entries {
		id, version, ok := parseFolder(e.Name())
		if !ok {
			remove(filepath.Join(tmp, e.Name()))
			continue
		}
		f, err := g.q.GetMediaFile(ctx, id)
		keep := err == nil && isVersionOf(version, f.Size, f.Mtime) && done[id] != version && !gone(f.Status, f.UpdatedAt)
		if !keep {
			remove(filepath.Join(tmp, e.Name()))
		}
	}
	return removed, nil
}

// isVersionOf reports whether version is one of the file's as it is now,
// with any tone mapping: half-made sheets of it are resumed.
func isVersionOf(version string, size int64, mtime string) bool {
	for _, tone := range tones {
		if Version(size, mtime, tone) == version {
			return true
		}
	}
	return false
}
