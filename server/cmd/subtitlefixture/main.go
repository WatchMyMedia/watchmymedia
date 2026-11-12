// subtitlefixture runs a disposable loopback server and a controllable fictional
// subtitle provider for real-browser acceptance. It never calls a real provider.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"io"
	"log/slog"
	"maps"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/api"
	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/ffexec"
	"watchmymedia/server/internal/opensubtitles"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/probe"
	"watchmymedia/server/internal/settings"
	"watchmymedia/server/internal/subtitles"
	"watchmymedia/server/internal/tmdb"
	"watchmymedia/server/internal/webui"
)

type providerState struct {
	mu                                                   sync.Mutex
	searches, downloads, downloadAttempts                int
	automaticAttempts, automaticCancels, automaticActive int
	gate                                                 chan struct{}
	mode                                                 string
	providerURL                                          string
	manifest                                             map[string]any
}

func main() {
	port := flag.Int("port", 0, "Loopback server port; 0 chooses an unused port")
	providerPort := flag.Int("provider-port", 0, "Loopback provider/control port; 0 chooses an unused port")
	media := flag.String("media", "", "Optional fictional H264/AAC MP4 to copy without sidecars; otherwise generate one")
	flag.Parse()
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := run(ctx, *port, *providerPort, *media); err != nil {
		slog.Error("subtitle browser fixture", "error", err)
		os.Exit(1)
	}
}

func run(parent context.Context, port, providerPort int, media string) error {
	ctx, cancel := context.WithCancel(parent)
	defer cancel()
	listen := func(port int) (net.Listener, error) {
		return (&net.ListenConfig{}).Listen(ctx, "tcp4", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
	}
	listener, err := listen(port)
	if err != nil {
		return err
	}
	defer func() { _ = listener.Close() }()
	providerListener, err := listen(providerPort)
	if err != nil {
		return err
	}
	defer func() { _ = providerListener.Close() }()
	dir, err := os.MkdirTemp("", "wmm-subtitle-browser-fixture-")
	if err != nil {
		return err
	}
	defer func() { _ = os.RemoveAll(dir) }()
	clip := filepath.Join(dir, "synthetic.mp4")
	if media == "" {
		err = ffexec.Command(ctx, "ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=24", "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=48000", "-t", "120", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "38", "-threads", "1", "-c:a", "aac", "-b:a", "32k", "-movflags", "+faststart", clip).Run()
	} else {
		err = copyMedia(media, clip)
	}
	if err != nil {
		return err
	}
	prober := &probe.Prober{}
	info, err := prober.Probe(ctx, clip)
	if err != nil {
		return err
	}
	if len(info.Video) != 1 || info.Video[0].Codec != "h264" || len(info.Audio) != 1 || info.Audio[0].Codec != "aac" || len(info.Subtitles) != 0 {
		return errors.New("fixture needs one H264 video, one AAC audio and no embedded subtitles")
	}
	encoded, err := probe.Encode(info)
	if err != nil {
		return err
	}
	conn, err := db.Open(filepath.Join(dir, "fixture.db"))
	if err != nil {
		return err
	}
	defer func() { _ = conn.Close() }()
	if err := db.Migrate(ctx, conn); err != nil {
		return err
	}
	q, bus, log := db.New(conn), events.NewBus(), slog.Default()
	store := settings.New(q)
	if err := store.Set(ctx, settings.KeyServerName, "Meadow Subtitle Browser Fixture"); err != nil {
		return err
	}
	serverID, err := store.ServerID(ctx)
	if err != nil {
		return err
	}
	authService := auth.New(q, time.Hour, false)
	if err := authService.SetMediaKey(auth.NewMediaKey()); err != nil {
		return err
	}
	for _, account := range []struct {
		name  string
		admin bool
	}{{"Fixture Admin", true}, {"Fixture Viewer", false}} {
		user, err := authService.CreateUser(ctx, account.name, "fixture-password", account.admin)
		if err != nil {
			return err
		}
		for key, value := range map[string]string{"subtitles.mode": "on", "subtitles.languages": `["en"]`, "subtitles.automaticDownload": "true"} {
			if err := q.UpsertUserPreference(ctx, db.UpsertUserPreferenceParams{UserID: user.ID, Key: key, Value: value}); err != nil {
				return err
			}
		}
	}
	mediaDir := filepath.Join(dir, "Movies")
	if err := os.MkdirAll(mediaDir, 0o700); err != nil {
		return err
	}
	library, err := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "movie", Name: "Movies", Path: mediaDir})
	if err != nil {
		return err
	}
	items := []map[string]any{}
	for index, title := range []string{"Cedar Lanterns", "Riverglass", "Hollow Shore"} {
		path := filepath.Join(mediaDir, title+".mp4")
		if err := os.Link(clip, path); err != nil {
			return err
		}
		stat, err := os.Stat(path)
		if err != nil {
			return err
		}
		movie, err := q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: library.ID, TmdbID: 910001 + int64(index), Title: title, Genres: "[]", Credits: "[]", Trailers: "[]", Overview: "A fictional film for subtitle browser checks."})
		if err != nil {
			return err
		}
		probeJSON, now := string(encoded), db.FormatTime(time.Now())
		file, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: library.ID, Path: path, Size: stat.Size(), Mtime: now, AddedAt: now, Parsed: "{}", Probe: &probeJSON, LastSeenAt: now, SeenInScan: 1})
		if err != nil {
			return err
		}
		if err := q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &movie.ID, MatchLocked: 1, ID: file.ID}); err != nil {
			return err
		}
		items = append(items, map[string]any{"title": title, "movieId": movie.ID, "fileId": file.ID})
	}
	state := &providerState{gate: make(chan struct{}), mode: "success", providerURL: "http://" + providerListener.Addr().String()}
	state.manifest = map[string]any{"fixture": "subtitle-browser-fixture-v1", "version": "subtitle-browser-fixture", "serverId": serverID,
		"server": "http://" + listener.Addr().String(), "provider": state.providerURL, "items": items, "pid": os.Getpid()}
	providerMux := http.NewServeMux()
	providerMux.HandleFunc("GET /api/v1/subtitles", state.search)
	providerMux.HandleFunc("POST /api/v1/download", state.download)
	providerMux.HandleFunc("GET /subtitle.srt", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("1\n00:00:00,000 --> 00:02:59,000\nFictional exact-file subtitle\n"))
	})
	providerMux.HandleFunc("GET /_fixture/status", state.status)
	providerMux.HandleFunc("POST /_fixture/search/{action}", state.control)
	providerMux.HandleFunc("POST /_fixture/provider/{action}", state.control)
	providerServer := &http.Server{Handler: providerMux, ReadHeaderTimeout: 5 * time.Second, BaseContext: func(net.Listener) context.Context { return ctx }}
	go func() { _ = providerServer.Serve(providerListener) }()
	defer func() { _ = providerServer.Close() }()
	defer state.release()
	online := subtitles.New(q, store, opensubtitles.New("fictional-fixture-key", "WatchMyMedia vfixture", state.providerURL+"/api/v1"), filepath.Join(dir, "subtitles-downloaded"), log)
	conversions := playback.NewSubtitles("ffmpeg", filepath.Join(dir, "subtitles"))
	defer conversions.Close()
	manager := playback.NewManager("ffmpeg", filepath.Join(dir, "transcode"), playback.Capabilities{FFmpeg: "ffmpeg", Encoder: "libx264", Encoders: []string{"libx264"}, HWAccel: "software", MaxHeight: 1080}, time.Minute, 2, log)
	tracker := activity.New(conn, bus, log, activity.Options{Sessions: manager, Config: api.ActivityConfig(ctx, store)})
	var workers sync.WaitGroup
	workers.Go(func() { manager.Run(ctx) })
	workers.Go(func() { tracker.Run(ctx) })
	defer func() { cancel(); workers.Wait() }()
	router, _ := api.New(api.Deps{Version: "subtitle-browser-fixture", Queries: q, Conn: conn, DataDir: dir, Log: log, Bus: bus,
		Auth: authService, Settings: store, TMDB: tmdb.New("", ""), Playback: manager, Activity: tracker, Subtitles: conversions, OnlineSubtitles: online, DefaultHWAccel: "none"})
	router.Get("/_fixture/status", state.status)
	router.Handle("/*", webui.Handler())
	server := &http.Server{Handler: state.instrumentAutomatic(router), ReadHeaderTimeout: 5 * time.Second, BaseContext: func(net.Listener) context.Context { return ctx }}
	done := make(chan error, 1)
	go func() { done <- server.Serve(listener) }()
	writeJSON(os.Stdout, state.manifest)
	select {
	case err := <-done:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	case <-ctx.Done():
		shutdown, stop := context.WithTimeout(context.Background(), 5*time.Second)
		defer stop()
		return server.Shutdown(shutdown)
	}
}

func copyMedia(source, destination string) error {
	in, err := os.Open(source)
	if err != nil {
		return err
	}
	defer func() { _ = in.Close() }()
	out, err := os.OpenFile(destination, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	_, err = io.Copy(out, in)
	closeErr := out.Close()
	return errors.Join(err, closeErr)
}

func writeJSON(w io.Writer, value any) { _ = json.NewEncoder(w).Encode(value) }

func (s *providerState) search(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	s.searches++
	gate := s.gate
	s.mu.Unlock()
	if gate != nil {
		select {
		case <-gate:
		case <-r.Context().Done():
			return
		}
	}
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write([]byte(`{"data":[{"attributes":{"language":"en","download_count":10,"release":"Exact.Fictional.Release","moviehash_match":true,"files":[{"file_id":77}]}}]}`))
}

func (s *providerState) download(w http.ResponseWriter, _ *http.Request) {
	s.mu.Lock()
	s.downloadAttempts++
	mode := s.mode
	if mode == "success" {
		s.downloads++
	}
	s.mu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	if mode == "quota" {
		w.WriteHeader(http.StatusNotAcceptable)
		_, _ = w.Write([]byte(`{"message":"Fictional quota used up","remaining":0}`))
		return
	}
	if mode == "error" {
		w.WriteHeader(http.StatusServiceUnavailable)
		_, _ = w.Write([]byte(`{"message":"Fictional provider unavailable"}`))
		return
	}
	writeJSON(w, map[string]any{"link": s.providerURL + "/subtitle.srt", "remaining": 99})
}

func (s *providerState) release() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.gate != nil {
		close(s.gate)
		s.gate = nil
	}
}

func (s *providerState) status(w http.ResponseWriter, _ *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	status := map[string]any{}
	maps.Copy(status, s.manifest)
	status["searches"], status["downloads"], status["downloadAttempts"], status["searchPaused"], status["mode"] = s.searches, s.downloads, s.downloadAttempts, s.gate != nil, s.mode
	status["automaticAttempts"], status["automaticCancels"], status["automaticActive"] = s.automaticAttempts, s.automaticCancels, s.automaticActive
	writeJSON(w, status)
}

// Count actual production automatic-route calls separately from the manual
// picker's concurrent provider search. Restore the exact body before Huma reads
// it; this wrapper observes requests and does not implement subtitle behavior.
func (s *providerState) instrumentAutomatic(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || !strings.HasPrefix(r.URL.Path, "/api/files/") || !strings.HasSuffix(r.URL.Path, "/subtitles/automatic") {
			next.ServeHTTP(w, r)
			return
		}
		body, err := io.ReadAll(io.LimitReader(r.Body, (1<<20)+1))
		_ = r.Body.Close()
		if err != nil {
			http.Error(w, "could not read fixture request", http.StatusBadRequest)
			return
		}
		if len(body) > 1<<20 {
			http.Error(w, "fixture request body too large", http.StatusRequestEntityTooLarge)
			return
		}
		r.Body = io.NopCloser(bytes.NewReader(body))
		var request struct {
			Cancel bool `json:"cancel"`
		}
		_ = json.Unmarshal(body, &request)
		s.mu.Lock()
		if request.Cancel {
			s.automaticCancels++
		} else {
			s.automaticAttempts++
			s.automaticActive++
		}
		s.mu.Unlock()
		if !request.Cancel {
			defer func() {
				s.mu.Lock()
				s.automaticActive--
				s.mu.Unlock()
			}()
		}
		next.ServeHTTP(w, r)
	})
}

func (s *providerState) control(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	switch r.PathValue("action") {
	case "pause":
		if s.gate == nil {
			s.gate = make(chan struct{})
		}
	case "release", "resume":
		if s.gate != nil {
			close(s.gate)
			s.gate = nil
		}
	case "success", "quota", "error":
		s.mode = r.PathValue("action")
	default:
		http.Error(w, "unknown fixture action", http.StatusBadRequest)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	writeJSON(w, map[string]any{"searchPaused": s.gate != nil, "mode": s.mode})
}
