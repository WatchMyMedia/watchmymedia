// livetvfixture serves an isolated, temporary server and a fictional HDHomeRun
// for browser checks. It never discovers or contacts a real tuner.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"sync"
	"syscall"
	"time"

	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/api"
	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/dvr"
	"watchmymedia/server/internal/livetv/guide"
	"watchmymedia/server/internal/livetv/guide/guidetest"
	"watchmymedia/server/internal/livetv/hdhomerun"
	"watchmymedia/server/internal/livetv/hdhomerun/hdhrtest"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/probe"
	"watchmymedia/server/internal/scanner"
	"watchmymedia/server/internal/settings"
	"watchmymedia/server/internal/tmdb"
	"watchmymedia/server/internal/upload"
	"watchmymedia/server/internal/watchstate"
	"watchmymedia/server/internal/webui"
)

func main() {
	port := flag.Int("port", 28197, "Loopback HTTP port")
	ffmpeg := flag.String("ffmpeg", "ffmpeg", "ffmpeg executable")
	playbackFFmpeg := flag.String("streaming-playback-ffmpeg", "", "Test-only playback encoder override; fixture media still uses -ffmpeg")
	receiver := flag.String("receiver-origin", "", "A Cast receiver's origin, such as http://127.0.0.1:5174, to turn casting on for; empty leaves it off")
	recording := flag.Bool("dvr", false, "Seed recording folders, fictional accounts and a tuner for DVR acceptance")
	streaming := flag.Bool("streaming", false, "Add generated library files and test-only bandwidth controls")
	shareSources := flag.Bool("share-sources", false, "Use staged same-channel tuner sharing on this fictional fixture")
	duration := flag.Int("streaming-seconds", 180, "Generated streaming file length in seconds (10-2160)")
	limit := flag.Int("streaming-slots", 8, "Playback session limit for the streaming fixture (1-16)")
	extended := flag.Bool("streaming-extended", false, "Also generate MPEG-4 adaptive and HEVC Main 10 HDR compatibility clips (requires libx265)")
	originalHD := flag.Bool("streaming-original-hd", false, "Also generate a 16-minute direct-playable 720p H.264/AAC MP4 for adaptive return-to-Original checks")
	liveHD := flag.Bool("streaming-live-hd", false, "Use a 720-line interlaced fictional source for shaped native Live TV acceptance (requires -streaming)")
	freeOverride := flag.Int64("streaming-free-bytes", -1, "Test-only temporary-volume free-byte override; -1 uses the real filesystem")
	ffprobe := flag.String("ffprobe", "ffprobe", "ffprobe executable")
	flag.Parse()
	if err := run(*port, *ffmpeg, *ffprobe, *receiver, *recording, *streaming, *shareSources, streamingTitles{seconds: *duration, extended: *extended, originalHD: *originalHD}, *limit, *freeOverride, *liveHD, *playbackFFmpeg); err != nil {
		slog.Error("live TV fixture", "error", err)
		os.Exit(1)
	}
}

func run(port int, ffmpeg, ffprobe, receiver string, recording, streaming, shareSources bool, titles streamingTitles, limit int, freeOverride int64, liveHD bool, playbackFFmpeg string) error {
	if streaming && (titles.seconds < 10 || titles.seconds > 2160 || limit < 1 || limit > 16) {
		return errors.New("streaming fixture needs 10-2160 seconds and 1-16 playback slots")
	}
	if liveHD && !streaming {
		return errors.New("HD live acceptance requires the isolated streaming fixture")
	}
	if playbackFFmpeg != "" && !streaming {
		return errors.New("playback encoder override requires the isolated streaming fixture")
	}
	if playbackFFmpeg == "" {
		playbackFFmpeg = ffmpeg
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	recording = recording || streaming
	dir, err := os.MkdirTemp("", "wmm-live-tv-fixture-")
	if err != nil {
		return err
	}
	defer func() { _ = os.RemoveAll(dir) }()
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
	if err := store.Set(ctx, settings.KeyServerName, "Meadow Live TV fixture"); err != nil {
		return err
	}
	authSvc := auth.New(q, time.Hour, false)
	if err := authSvc.SetMediaKey(auth.NewMediaKey()); err != nil {
		return err
	}
	fake := hdhrtest.New(hdhrtest.Broadcast(ffmpeg))
	if liveHD {
		fake.Stream = hdhrtest.BroadcastHD(ffmpeg)
	}
	fake.Channels = append(fake.Channels,
		hdhrtest.Channel{GuideNumber: "22.1", GuideName: "Cedar Sports", URL: "http://tuner.test:5004/auto/v22.1"},
		hdhrtest.Channel{GuideNumber: "42.3", GuideName: "Willow Cinema", URL: "http://tuner.test:5004/auto/v42.3"},
		// The fake feed doesn't list 33.1, so it shows No listings.
		hdhrtest.Channel{GuideNumber: "33.1", GuideName: "Harbor Shop", URL: "http://tuner.test:5004/auto/v33.1"},
		hdhrtest.Channel{GuideNumber: "60.1", GuideName: "Protected channel", URL: "http://tuner.test:5004/auto/v60.1", DRM: 1})
	tuner := httptest.NewServer(fake)
	defer tuner.Close()
	sources := livetv.New(q, store, func(address string) (*hdhomerun.Client, error) {
		return hdhomerun.New(address, hdhrtest.Transport(tuner.URL))
	})
	slots := 2
	if streaming {
		slots = limit
	}
	manager := playback.NewManager(playbackFFmpeg, filepath.Join(dir, "transcode"), playback.Capabilities{FFmpeg: playbackFFmpeg, Encoder: "libx264", Encoders: []string{"libx264"}, HWAccel: "software", MaxHeight: 1080}, time.Minute, slots, log)
	if streaming && freeOverride >= 0 {
		limits := playback.DefaultResourceLimits()
		limits.Free = func(string) (uint64, error) { return uint64(freeOverride), nil }
		manager.SetResourceLimits(limits)
	}
	uploadPolicy := upload.New(0, false)
	tracker := activity.New(conn, bus, log, activity.Options{Sessions: manager, Config: api.ActivityConfig(ctx, store), OnEnd: uploadPolicy.Release})
	live := livetv.NewSessions(sources, manager, livetv.SessionOptions{FFmpeg: ffmpeg, FFprobe: ffprobe, Dir: filepath.Join(dir, "live"), ShareSources: shareSources, Validate: func(ctx context.Context, id string) (livetv.Owner, error) {
		u, err := authSvc.ValidateSignIn(ctx, id)
		return livetv.Owner{UserID: u.ID, SignInID: u.SignInID, Name: u.Name, Admin: u.IsAdmin}, err
	}, OnEnd: uploadPolicy.Release})
	manager.SetLiveCache(filepath.Join(dir, "live"), live.StopForStorage)
	// Listings come from a fake SiliconDust and picture host, so the guide
	// shows fictional programmes once a tuner is added. They are on from
	// the start and fetched as soon as the lineup is read.
	silicon := httptest.NewServer(&guidetest.SiliconDust{Feed: guidetest.Feed(time.Now(), guidetest.StandardChannels)})
	defer silicon.Close()
	pictures := httptest.NewServer(guidetest.NewPictures())
	defer pictures.Close()
	listings := guide.New(q, store, sources, guide.Options{Log: log, ArtDir: filepath.Join(dir, "live-tv-art"), Bus: bus,
		Transport: guidetest.Transport(map[string]string{"api.hdhomerun.com": silicon.URL, "img.hdhomerun.com": pictures.URL})})
	sources.OnLineup(listings.Fetch)
	if err := listings.Configure(ctx, true, true, ""); err != nil {
		return err
	}
	watch := watchstate.New(q, bus)
	prober := &probe.Prober{Path: ffprobe}
	sc := scanner.New(q, prober, bus, log, scanner.Options{Workers: 1, StableFor: time.Second, Interval: 24 * time.Hour})
	recordings := dvr.New(q, store, sources, live, dvr.Options{Scanner: sc, Bus: bus, Watch: watch, Log: log})
	var fixtureTV db.Library
	if recording {
		if _, err := authSvc.CreateUser(ctx, "Fixture Admin", "fixture-password", true); err != nil {
			return err
		}
		if _, err := authSvc.CreateUser(ctx, "Fixture Viewer", "fixture-password", false); err != nil {
			return err
		}
		if err := os.MkdirAll(filepath.Join(dir, "TV"), 0700); err != nil {
			return err
		}
		fixtureTV, err = q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "Fixture recordings", Path: filepath.Join(dir, "TV")})
		if err != nil {
			return err
		}
		if _, err = sources.Add(ctx, "tuner.test", "Meadow antenna"); err != nil {
			return err
		}
		if err = sources.SetAccess(ctx, "everyone"); err != nil {
			return err
		}
		config, err := recordings.Settings(ctx)
		if err != nil {
			return err
		}
		config.Enabled, config.Access, config.TVLibraryID, config.TVPath = true, "everyone", fixtureTV.ID, fixtureTV.Path
		config.PadBefore, config.PadAfter, config.MinFreeBytes = 0, 0, 0
		if err = recordings.SetSettings(ctx, config); err != nil {
			return err
		}
	}
	var streams *streamingFixture
	var keyframes *playback.Keyframes
	var conversions *playback.Subtitles
	if streaming {
		streams, err = seedStreaming(ctx, q, store, authSvc, prober, ffmpeg, dir, titles)
		if err != nil {
			return err
		}
		if liveHD {
			streams.manifest.Notes = append(streams.manifest.Notes, "Live input is opt-in synthetic 1280x720 interlaced MPEG-2/AC-3 at a 5 Mbps video target; not physical-tuner or 4K capacity evidence.")
		}
		keyframes = playback.NewKeyframes(ffprobe, filepath.Join(dir, "keyframes"), log)
		defer keyframes.Close()
		conversions = playback.NewSubtitles(ffmpeg, filepath.Join(dir, "subtitles"))
		defer conversions.Close()
	}
	var workers sync.WaitGroup
	workers.Go(func() { sc.Run(ctx) })
	workers.Go(func() { recordings.Run(ctx) })
	workers.Go(func() { manager.Run(ctx) })
	workers.Go(func() { tracker.Run(ctx) })
	workers.Go(func() { live.Run(ctx) })
	workers.Go(func() { listings.Run(ctx) })
	defer func() { cancel(); recordings.Close(); live.Close(); workers.Wait() }()
	// A receiver served on its own origin can play the fixture's channels
	// with a cast token (PLAN-LIVE-TV.md §4.1).
	var cast api.CastOptions
	if receiver != "" {
		cast = api.CastOptions{Enabled: true, URL: fmt.Sprintf("http://127.0.0.1:%d", port), ReceiverOrigins: []string{receiver}, Host: "127.0.0.1", Port: port}
	}
	router, _ := api.New(api.Deps{AdaptiveHLS: streaming, Version: "live-tv-fixture", Queries: q, Conn: conn, DataDir: dir, Log: log, Bus: bus, Auth: authSvc, Settings: store, TMDB: tmdb.New("", ""), Playback: manager, Activity: tracker, LiveTV: sources, LiveSessions: live, Guide: listings, Recordings: recordings, Scanner: sc, Prober: prober, Watch: watch, DefaultHWAccel: "none", Cast: cast, Keyframes: keyframes, Subtitles: conversions, Upload: uploadPolicy})
	var handler http.Handler = router
	if streams != nil {
		streams.register(router, manager, fake, tracker, q, dir)
		streams.registerLiveOutputs(router, authSvc, live)
		handler = streams.shaper.Middleware(router)
	} else if recording {
		router.Get("/_fixture/status", func(w http.ResponseWriter, r *http.Request) {
			channels, _ := sources.Channels(r.Context(), false)
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"fixture": "dvr-fixture-v1", "tvLibraryId": fixtureTV.ID, "channels": channels})
		})
	}
	router.Handle("/*", webui.Handler())
	listener, err := (&net.ListenConfig{}).Listen(ctx, "tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
	if err != nil {
		return err
	}
	server := &http.Server{Handler: handler, ReadHeaderTimeout: 5 * time.Second, BaseContext: func(net.Listener) context.Context { return ctx }}
	done := make(chan error, 1)
	if streams != nil {
		streams.manifest.BaseURL = "http://" + listener.Addr().String()
	}
	go func() { done <- server.Serve(listener) }()
	if streams != nil {
		if err := json.NewEncoder(os.Stdout).Encode(streams.manifest); err != nil {
			_ = server.Close()
			return err
		}
	}
	log.Info("fictional live TV fixture ready", "url", fmt.Sprintf("http://%s", listener.Addr()), "tuner", "tuner.test", "data", dir)
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
