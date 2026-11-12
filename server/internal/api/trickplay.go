package api

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"slices"
	"strconv"
	"strings"

	"github.com/danielgtaylor/huma/v2"
	"github.com/go-chi/chi/v5"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/settings"
	"watchmymedia/server/internal/trickplay"
)

// Seek thumbnails (PLAN-TRICKPLAY.md): a file's manifest, its sheets, and
// the job's progress and switch in Settings › Transcoding.

type TrickplayOutput struct{ Body playback.Trickplay }

type TrickplayStatusOutput struct{ Body trickplay.Status }

// sheetCache is how sheets are cached: a sheet's URL names its version, so
// it never changes.
const sheetCache = "private, max-age=31536000, immutable"

func registerTrickplay(h huma.API, r *chi.Mux, deps Deps) {
	huma.Register(h, huma.Operation{
		OperationID: "getTrickplay",
		Method:      http.MethodGet,
		Path:        Prefix + "/files/{id}/trickplay",
		Summary:     "A file's seek thumbnails",
		Description: "The manifest of the file's seek thumbnails, as the playback decision carries it; 404 until the server has made them for this version of the file.",
		Tags:        []string{"playback"},
	}, func(ctx context.Context, in *FileIDInput) (*TrickplayOutput, error) {
		row, err := deps.Queries.GetMediaFile(ctx, in.ID)
		if err != nil {
			return nil, huma.Error404NotFound("file not found")
		}
		m, ok := fileTrickplay(ctx, deps, row)
		if !ok {
			return nil, huma.Error404NotFound("no seek thumbnails for this file yet")
		}
		return &TrickplayOutput{Body: *m}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "getTrickplayStatus",
		Method:      http.MethodGet,
		Path:        Prefix + "/trickplay",
		Summary:     "How far seek thumbnails have got (admin)",
		Description: "The same as Settings.trickplay, without the rest of the settings, for following the job.",
		Tags:        []string{"settings"},
	}), func(ctx context.Context, _ *struct{}) (*TrickplayStatusOutput, error) {
		if deps.Trickplay == nil {
			return nil, huma.Error503ServiceUnavailable("seek thumbnails are not available")
		}
		s, err := deps.Trickplay.Status(ctx)
		if err != nil {
			return nil, err
		}
		return &TrickplayStatusOutput{Body: s}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID:   "deleteTrickplay",
		Method:        http.MethodDelete,
		Path:          Prefix + "/trickplay",
		Summary:       "Delete every file's seek thumbnails (admin)",
		Description:   "Frees the space now. While seek thumbnails are on they are made again, newest files first.",
		Tags:          []string{"settings"},
		DefaultStatus: http.StatusNoContent,
	}), func(ctx context.Context, _ *struct{}) (*struct{}, error) {
		if deps.Trickplay == nil {
			return nil, huma.Error503ServiceUnavailable("seek thumbnails are not available")
		}
		return nil, deps.Trickplay.DeleteAll(ctx)
	})

	// Sheets are files a player fetches directly, as images are: chi, with
	// ?access_token= allowed (mediaPath).
	r.Get(Prefix+"/files/{id}/trickplay/{version}/{sheet}", serveTrickplaySheet(deps))
	r.Head(Prefix+"/files/{id}/trickplay/{version}/{sheet}", serveTrickplaySheet(deps))
}

// fileTrickplay is the manifest of a file's seek thumbnails when they are
// made for the file as it is now.
func fileTrickplay(ctx context.Context, deps Deps, row db.MediaFile) (*playback.Trickplay, bool) {
	if deps.Trickplay == nil {
		return nil, false
	}
	t, ok := deps.Trickplay.Done(ctx, row.ID, row.Size, row.Mtime)
	if !ok {
		return nil, false
	}
	m := trickplay.Manifest(t, trickplaySheetURL(row.ID, t.Version))
	return &m, true
}

// iframeStream is the I-frame stream a VOD session for playback offers
// (PLAN-TRICKPLAY.md §7.3): the file's seek thumbnails, when they are made
// and ffmpeg has libx264 to encode them; nil otherwise. Downloads offer
// none.
func iframeStream(ctx context.Context, deps Deps, row db.MediaFile, durationMs int64) *playback.IFrameStream {
	if deps.Trickplay == nil || deps.Playback == nil || !slices.Contains(deps.Playback.CapabilitiesContext(ctx).Encoders, "libx264") {
		return nil
	}
	t, ok := deps.Trickplay.Done(ctx, row.ID, row.Size, row.Mtime)
	if !ok {
		return nil
	}
	return deps.Trickplay.IFrameStream(t, durationMs)
}

// serveIFrames serves a VOD session's I-frame track, which its playlist
// lists by byte range, making it the first time.
func serveIFrames(w http.ResponseWriter, r *http.Request, s *playback.Session) {
	t, err := s.IFrameTrack(r.Context())
	if errors.Is(err, os.ErrNotExist) {
		http.NotFound(w, r)
		return
	}
	if err != nil {
		slog.Default().Warn("seek thumbnails as I-frames", "session", s.ID, "err", err)
		http.Error(w, "seek thumbnails failed: "+err.Error(), http.StatusServiceUnavailable)
		return
	}
	f, err := os.Open(t.Path) //nolint:gosec // G304, G703: the path is built from the file's ID and its recorded version
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer func() { _ = f.Close() }()
	st, err := f.Stat()
	if err != nil {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Cache-Control", "private, max-age=3600")
	w.Header().Set("Content-Type", "video/mp4")
	http.ServeContent(w, r, "", st.ModTime(), f)
}

// trickplaySheetURL is a version's sheet URL, with {sheet} for the client
// to fill.
func trickplaySheetURL(fileID int64, version string) string {
	return fmt.Sprintf("%s/files/%d/trickplay/%s/{sheet}.jpg", Prefix, fileID, version)
}

// isTrickplaySheet reports whether p is a sheet's path.
func isTrickplaySheet(p string) bool {
	return strings.HasPrefix(p, Prefix+"/files/") && strings.Contains(p, "/trickplay/") && strings.HasSuffix(p, ".jpg")
}

// serveTrickplaySheet serves one sheet of a version of a file's thumbnails,
// cached for good; an old version, or a sheet it does not have, is 404.
func serveTrickplaySheet(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, err := strconv.ParseInt(chi.URLParam(r, "id"), 10, 64)
		name, isJPEG := strings.CutSuffix(chi.URLParam(r, "sheet"), ".jpg")
		sheet, serr := strconv.Atoi(name)
		if err != nil || !isJPEG || serr != nil || deps.Trickplay == nil {
			http.NotFound(w, r)
			return
		}
		version := chi.URLParam(r, "version")
		path, ok := deps.Trickplay.SheetPath(r.Context(), id, version, sheet)
		if !ok {
			http.NotFound(w, r)
			return
		}
		f, err := os.Open(path) //nolint:gosec // G304: the path is built from the file's ID and a checked version
		if err != nil {
			http.NotFound(w, r)
			return
		}
		defer func() { _ = f.Close() }()
		st, err := f.Stat()
		if err != nil {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Cache-Control", sheetCache)
		w.Header().Set("ETag", fmt.Sprintf(`"%s-%d"`, version, sheet))
		w.Header().Set("Content-Type", "image/jpeg")
		http.ServeContent(w, r, "", st.ModTime(), f)
	}
}

// TrickplayEnabled is the Seek thumbnails setting: on unless turned off.
func TrickplayEnabled(ctx context.Context, store *settings.Store) bool {
	if store == nil {
		return true
	}
	v, _ := store.Get(ctx, settings.KeyTrickplayEnabled, "1")
	return v != "0"
}

// saveTrickplayEnabled turns making seek thumbnails on or off.
func saveTrickplayEnabled(ctx context.Context, deps Deps, on bool) error {
	if deps.Settings == nil {
		return huma.Error503ServiceUnavailable("settings are not available")
	}
	v := "0"
	if on {
		v = "1"
	}
	if err := deps.Settings.Set(ctx, settings.KeyTrickplayEnabled, v); err != nil {
		return err
	}
	if deps.Trickplay != nil {
		deps.Trickplay.SetEnabled(on)
	}
	return nil
}
