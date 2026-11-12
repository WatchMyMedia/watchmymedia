package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/danielgtaylor/huma/v2"
	"github.com/go-chi/chi/v5"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/netguard"
	"watchmymedia/server/internal/offline"
	"watchmymedia/server/internal/playback"
)

// The offline routes' media (PLAN-DOWNLOADS.md §3): the original file with
// Range for a direct download, a download session's HLS for the rest, and
// the subtitles beside them. Each fetch is checked against its record and
// counted as the download's transfer, never as a play.

// offlineMedia is a fetch of a download's media, once it is checked.
type offlineMedia struct {
	rec  db.OfflineDownload
	user auth.User
	file db.MediaFile
}

// offlineFetch checks a fetch of a download's media: the download is the
// signed-in person's (an offline token's own record, or the session's),
// they may still download, and it still stands. It answers the request
// itself when not.
func offlineFetch(w http.ResponseWriter, r *http.Request, deps Deps) (offlineMedia, bool) {
	ctx := r.Context()
	id := chi.URLParam(r, "id")
	u, ok := auth.UserFrom(ctx)
	if !ok {
		http.Error(w, "sign in required", http.StatusUnauthorized)
		return offlineMedia{}, false
	}
	if o, ok := auth.OfflineFromContext(ctx); ok && o.Record != id {
		http.Error(w, "sign in required", http.StatusUnauthorized)
		return offlineMedia{}, false
	}
	rec, err := deps.Queries.GetOfflineDownload(ctx, id)
	if err != nil || rec.UserID != u.ID {
		http.NotFound(w, r)
		return offlineMedia{}, false
	}
	may := mayDownload(ctx, deps, u)
	if !may {
		revokeForAccess(ctx, deps, u.ID)
	}
	st, err := offlineState(ctx, deps.Queries, rec, may)
	if err != nil {
		http.Error(w, "download failed", http.StatusInternalServerError)
		return offlineMedia{}, false
	}
	if err := offlineStateError(st); err != nil {
		se, _ := errors.AsType[huma.StatusError](err)
		http.Error(w, err.Error(), se.GetStatus())
		return offlineMedia{}, false
	}
	if rec.DeviceState == "queued" {
		_ = deps.Queries.MarkOfflineDownloading(ctx, rec.ID)
	}
	return offlineMedia{rec: rec, user: u, file: *st.file}, true
}

// transferInfo describes the fetch for Activity › Downloads.
func transferInfo(r *http.Request, m offlineMedia, s *playback.Session) offline.Info {
	network := netguard.NetRemote
	if n, ok := netguard.From(r.Context()); ok && !n.Proxied {
		network = netguard.Classify(n.IP)
	}
	info := offline.Info{Record: m.rec.PublicID, UserID: m.user.ID, UserName: m.user.Name, DeviceName: m.rec.DeviceName, FileID: m.file.ID,
		Item: m.rec.Item, Quality: m.rec.Quality, Mode: m.rec.Mode, Network: network, SizeBytes: m.rec.SizeBytes,
		Estimated: m.rec.Mode != string(playback.ModeDirect)}
	if s != nil {
		info.SessionID, info.Encoder = s.ID, s.Encoder
	}
	return info
}

func transferView(deps Deps, t offline.Transfer) OfflineTransfer {
	v := OfflineTransfer{DownloadID: t.Record, User: OfflineUser{ID: t.UserID, Name: t.UserName}, DeviceName: t.DeviceName, FileID: t.FileID,
		Item: offlineItem(t.Item), Quality: t.Quality, Mode: t.Mode, Network: t.Network, SizeBytes: t.SizeBytes, SizeEstimated: t.Estimated,
		BytesSent: t.BytesSent, Bandwidth: t.Bandwidth, Progress: t.Progress, StartedAt: t.StartedAt, LastSeenAt: t.LastSeenAt}
	if t.Mode == string(playback.ModeTranscode) {
		v.Encoder = t.Encoder
	}
	if t.SessionID != "" && deps.Playback != nil {
		if s, ok := deps.Playback.Get(t.SessionID); ok {
			v.Speed, _ = s.Progress()
		}
	}
	return v
}

// countOffline wraps w to count a download's bytes into its transfer and
// record. progress says how far through the download sent bytes reach.
func countOffline(deps Deps, w http.ResponseWriter, info offline.Info, recordID int64, progress func(sent int64) float64) (http.ResponseWriter, func()) {
	var sent int64
	c := &countingWriter{ResponseWriter: w, add: func(n int64) bool {
		sent += n
		deps.Offline.Add(info, n, progress(sent))
		return true
	}}
	return c, func() {
		c.flush()
		if sent > 0 {
			// The request may be gone; the count is still owed.
			if err := deps.Queries.AddOfflineBytes(context.Background(), db.AddOfflineBytesParams{BytesSent: sent, ID: recordID}); err != nil {
				deps.Log.Warn("could not count a download's bytes", "download", info.Record, "err", err)
			}
		}
	}
}

// serveOfflineStream serves a direct download's original file with Range,
// refusing it once the file on disk is not the version downloaded.
func serveOfflineStream(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		m, ok := offlineFetch(w, r, deps)
		if !ok {
			return
		}
		if m.rec.Mode != string(playback.ModeDirect) {
			http.NotFound(w, r)
			return
		}
		lib, err := deps.Queries.GetLibrary(r.Context(), m.file.LibraryID)
		if err != nil {
			http.NotFound(w, r)
			return
		}
		f, err := playback.OpenLibraryFile(lib.Path, m.file.Path)
		if err != nil {
			http.Error(w, "file not readable", http.StatusNotFound)
			return
		}
		defer func() { _ = f.Close() }()
		st, err := f.Stat()
		if err != nil {
			http.Error(w, "file not readable", http.StatusNotFound)
			return
		}
		// Changed on disk before a scan noticed: not the version downloaded.
		if st.Size() != m.rec.FileSize || db.FormatTime(st.ModTime()) != m.rec.FileMtime {
			http.Error(w, "the file of this download changed", http.StatusGone)
			return
		}
		start := rangeStart(r.Header.Get("Range"))
		size := max(st.Size(), 1)
		w, done := countOffline(deps, w, transferInfo(r, m, nil), m.rec.ID, func(sent int64) float64 { return float64(start+sent) / float64(size) })
		defer done()
		w.Header().Set("Content-Type", playback.MimeType(filepath.Ext(m.file.Path)))
		w.Header().Set("Accept-Ranges", "bytes")
		w.Header().Set("Cache-Control", "private, max-age=3600")
		http.ServeContent(w, r, filepath.Base(m.file.Path), st.ModTime(), f)
	}
}

// rangeStart is where a "bytes=N-" range starts; 0 otherwise.
func rangeStart(h string) int64 {
	from, _, ok := strings.Cut(strings.TrimPrefix(h, "bytes="), "-")
	if !ok || !strings.HasPrefix(h, "bytes=") {
		return 0
	}
	n, err := strconv.ParseInt(strings.TrimSpace(from), 10, 64)
	if err != nil || n < 0 {
		return 0
	}
	return n
}

var (
	offlineHLSRe     = regexp.MustCompile(`^(master\.m3u8|video\.m3u8|audio\.m3u8|init\.mp4|audio-init\.mp4|[va]\d{5}\.m4s|sub-[sxd]\d+\.m3u8|sub-[sxd]\d+-\d{5}\.vtt)$`)
	offlineSegmentRe = regexp.MustCompile(`^[va](\d{5})\.m4s$`)
)

// offlineSession finds a download's session, or makes it again from the
// record: the stored request decided again, as a VOD session at full
// length. playback.ErrKeyframesPending means a copied file's keyframes are
// still being read.
func offlineSession(ctx context.Context, deps Deps, m offlineMedia) (*playback.Session, error) {
	sid := downloadSessionID(m.rec.PublicID)
	if s, ok := deps.Playback.Get(sid); ok {
		return s, nil
	}
	var stored offlineRequest
	if err := json.Unmarshal([]byte(m.rec.Request), &stored); err != nil {
		return nil, err
	}
	row, _, info, err := playableFile(ctx, deps, m.file.ID)
	if err != nil {
		return nil, err
	}
	d, sidecars := offlineDecide(ctx, deps, row, info, stored.Request)
	if d.Protocol != "hls" {
		return nil, errNoVOD
	}
	opts, err := vodOptions(ctx, deps, row, info, d, stored.Request, sidecars)
	if err != nil {
		return nil, err
	}
	spec := sessionSpec(row, m.user.ID, d, stored.Profile.MaxBitrate, sidecars)
	spec.VOD, spec.SessionID, spec.StartMs = opts, sid, 0
	return deps.Playback.OpenDownload(spec)
}

// untilSlot runs f again while the transcoder has no slot for the
// download, for up to wait, and returns its last error.
func untilSlot(ctx context.Context, wait time.Duration, f func() error) error {
	deadline := time.Now().Add(wait)
	for {
		err := f()
		if !errors.Is(err, playback.ErrTooManySessions) || !time.Now().Before(deadline) {
			return err
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(min(500*time.Millisecond, time.Until(deadline))):
		}
	}
}

// offlineHLSError answers a request whose download session could not give
// what was asked.
func offlineHLSError(w http.ResponseWriter, r *http.Request, deps Deps, id string, err error) {
	retry := func(secs, msg string) {
		w.Header().Set("Retry-After", secs)
		http.Error(w, msg, http.StatusServiceUnavailable)
	}
	switch {
	case errors.Is(err, playback.ErrKeyframesPending):
		retry("10", "preparing: the file's keyframes are being read")
	case errors.Is(err, playback.ErrTooManySessions):
		retry("30", "the transcoder is busy")
	case errors.Is(err, playback.ErrStorageBudget):
		retry("10", "playback temporary storage is unavailable")
	case errors.Is(err, playback.ErrSubtitleBusy):
		retry("2", "the subtitle is being read")
	case errors.Is(err, playback.ErrStopped):
		retry("5", "the download session restarted")
	case errors.Is(err, errNoVOD):
		http.Error(w, "this file no longer downloads as HLS", http.StatusGone)
	case errors.Is(err, os.ErrNotExist):
		http.NotFound(w, r)
	default:
		if se, ok := errors.AsType[huma.StatusError](err); ok {
			http.Error(w, se.Error(), se.GetStatus())
			return
		}
		if r.Context().Err() == nil {
			deps.Log.Warn("offline download", "download", id, "name", chi.URLParam(r, "name"), "err", err)
		}
		retry("5", "download failed: "+err.Error())
	}
}

// serveOfflineHLS serves a transcoding download's session: its playlists,
// init segment and segments, made again from the record when the session
// is gone. A remade session's init segment that differs from the first
// one's means the device must start over (PLAN-DOWNLOADS.md §3.3).
func serveOfflineHLS(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		name := chi.URLParam(r, "name")
		if deps.Playback == nil || !offlineHLSRe.MatchString(name) {
			http.NotFound(w, r)
			return
		}
		m, ok := offlineFetch(w, r, deps)
		if !ok {
			return
		}
		if m.rec.Mode == string(playback.ModeDirect) {
			http.NotFound(w, r)
			return
		}
		ctx := r.Context()
		s, err := offlineSession(ctx, deps, m)
		if err != nil {
			offlineHLSError(w, r, deps, m.rec.PublicID, err)
			return
		}
		s.Touch()
		seg := offlineSegmentRe.FindStringSubmatch(name)
		if name == playback.VODInit || name == playback.VODAudioInit || seg != nil {
			var hash string
			if err := untilSlot(ctx, offlineSlotWait, func() (err error) { hash, err = s.InitHash(ctx); return err }); err != nil {
				offlineHLSError(w, r, deps, m.rec.PublicID, err)
				return
			}
			state, wasRestart := m.rec.State, m.rec.State == offlineRestart
			switch {
			case m.rec.InitHash == "":
				err = deps.Queries.SetOfflineInitHash(ctx, db.SetOfflineInitHashParams{InitHash: hash, State: state, ID: m.rec.ID})
			case hash != m.rec.InitHash:
				state, wasRestart = offlineRestart, false
				deps.Log.Warn("download session's init segment differs; the device must start over", "download", m.rec.PublicID)
				err = deps.Queries.SetOfflineInitHash(ctx, db.SetOfflineInitHashParams{InitHash: hash, State: state, ID: m.rec.ID})
			}
			if err != nil {
				offlineHLSError(w, r, deps, m.rec.PublicID, err)
				return
			}
			if state == offlineRestart {
				// The device fetching the init segment again, once told,
				// has started over; anything else must.
				if name != playback.VODInit || !wasRestart {
					http.Error(w, "this download must start over", http.StatusConflict)
					return
				}
				if err := deps.Queries.RestartOfflineDownload(ctx, m.rec.ID); err != nil {
					offlineHLSError(w, r, deps, m.rec.PublicID, err)
					return
				}
			}
		}
		var b []byte
		var ct string
		if err := untilSlot(ctx, offlineSlotWait, func() (err error) { b, ct, err = s.VODFile(ctx, name); return err }); err != nil {
			offlineHLSError(w, r, deps, m.rec.PublicID, err)
			return
		}
		if strings.HasSuffix(name, ".m3u8") {
			w.Header().Set("Cache-Control", "no-cache")
			// Relative links drop the query string: pass the token on.
			if o, ok := auth.OfflineFromContext(ctx); ok {
				b = tokenizePlaylist(b, o.Token)
			}
		} else {
			w.Header().Set("Cache-Control", "private, max-age=3600")
		}
		progress := -1.0
		if seg != nil && s.Segments() > 0 {
			k, _ := strconv.Atoi(seg[1])
			progress = float64(k+1) / float64(s.Segments())
		}
		w, done := countOffline(deps, w, transferInfo(r, m, s), m.rec.ID, func(int64) float64 { return progress })
		defer done()
		w.Header().Set("Content-Type", ct)
		w.Header().Set("Content-Length", strconv.Itoa(len(b)))
		_, _ = w.Write(b) //nolint:gosec // G705: a playlist or media segment the server made, sent with its media type
	}
}

// serveOfflineSubtitle serves one of a download's subtitles: sidecar and
// downloaded ones, which an original file does not hold, fetched beside it.
func serveOfflineSubtitle(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		m, ok := offlineFetch(w, r, deps)
		if !ok {
			return
		}
		writeSubtitle(w, r, deps, m.file, chi.URLParam(r, "track"))
	}
}

// offlineRoute names the download a media path belongs to, for the
// middleware: /api/offline/{id}/stream, /hls/{name} and /subtitles/{track}.
func offlineRoute(p string) (string, bool) {
	rest, ok := strings.CutPrefix(p, Prefix+"/offline/")
	if !ok {
		return "", false
	}
	id, sub, ok := strings.Cut(rest, "/")
	if !ok || id == "downloads" || id == "" {
		return "", false
	}
	if sub == "stream" || (strings.HasPrefix(sub, "hls/") && !strings.Contains(sub[4:], "/")) ||
		(strings.HasPrefix(sub, "subtitles/") && !strings.Contains(sub[len("subtitles/"):], "/")) {
		return id, true
	}
	return "", false
}
