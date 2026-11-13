// Command server runs the media server.
//
//	watchmymedia            start the server (default)
//	watchmymedia openapi    print the OpenAPI document as JSON and exit
//	watchmymedia probe-transcoder  report hardware probes without starting a server
//	watchmymedia gpu-adapters  list native Windows adapter ordinals without starting a server
//	watchmymedia version    print the build version and exit
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"sync/atomic"
	"syscall"
	"time"

	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/api"
	"watchmymedia/server/internal/artwork"
	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/backup"
	"watchmymedia/server/internal/bgwork"
	"watchmymedia/server/internal/config"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/discovery"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/ffexec"
	"watchmymedia/server/internal/images"
	"watchmymedia/server/internal/integrations"
	"watchmymedia/server/internal/library"
	"watchmymedia/server/internal/lists"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/dvr"
	"watchmymedia/server/internal/livetv/guide"
	"watchmymedia/server/internal/livetv/guide/enrich"
	"watchmymedia/server/internal/metadata"
	"watchmymedia/server/internal/netguard"
	"watchmymedia/server/internal/opensubtitles"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/plexsync"
	"watchmymedia/server/internal/probe"
	"watchmymedia/server/internal/scanner"
	"watchmymedia/server/internal/segments"
	"watchmymedia/server/internal/settings"
	onlinesubs "watchmymedia/server/internal/subtitles"
	"watchmymedia/server/internal/tmdb"
	"watchmymedia/server/internal/trailers"
	"watchmymedia/server/internal/trickplay"
	"watchmymedia/server/internal/turnstile"
	"watchmymedia/server/internal/updates"
	"watchmymedia/server/internal/upload"
	"watchmymedia/server/internal/watchstate"
	"watchmymedia/server/internal/webui"
)

// version is overridden at build time: -ldflags "-X main.version=1.2.3".
var version = "dev"

// openSubtitlesKey is WatchMyMedia's OpenSubtitles API key, set by release
// builds from a CI secret: -ldflags "-X main.openSubtitlesKey=…". The terms
// allow one key per application and no asking users for their own.
var openSubtitlesKey = ""

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func run(args []string) error {
	cmd := "serve"
	if len(args) > 0 {
		cmd = args[0]
	}
	switch cmd {
	case "serve":
		return serve()
	case "openapi":
		// OpenAPI 3.1 by default; -3.0 writes the 3.0.3 form that code
		// generators without full 3.1 support (openapi-generator) read.
		_, h := api.New(api.Deps{Version: version})
		var b []byte
		var err error
		if len(args) > 1 && args[1] == "-3.0" {
			b, err = api.OpenAPI30(h)
		} else {
			b, err = json.MarshalIndent(h.OpenAPI(), "", "  ")
		}
		if err != nil {
			return err
		}
		_, err = os.Stdout.Write(append(b, '\n'))
		return err
	case "backup":
		return backupCommand(args[1:])
	case "probe-transcoder", "gpu-adapters":
		ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer stop()
		if cmd == "gpu-adapters" {
			return gpuAdaptersCommand(ctx, args[1:], os.Stdout, newAdapterScanner())
		}
		return probeCommand(ctx, args[1:], os.Stdout, os.Stderr, newAdapterScanner())
	case "version":
		fmt.Println(version)
		return nil
	default:
		return fmt.Errorf("unknown command %q (expected serve, backup, openapi, probe-transcoder, gpu-adapters or version)", cmd)
	}
}

// backupCommand writes an archive of the data directory to the given file
// (or into <data_dir>/backups when no file is given), for cron jobs.
func backupCommand(args []string) error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	conn, err := db.Open(cfg.DBPath())
	if err != nil {
		return err
	}
	defer func() { _ = conn.Close() }()
	ctx := context.Background()
	if len(args) == 0 {
		path, err := backup.Save(ctx, conn, cfg.DataDir, 0)
		if err != nil {
			return err
		}
		fmt.Println(path)
		return nil
	}
	// The archive holds password and session hashes and API keys, so only
	// its owner may read it, as with the nightly backups. The path is the
	// operator's own argument.
	f, err := os.OpenFile(args[0], os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600) //nolint:gosec // G703, G304: an operator's command-line argument
	if err != nil {
		return err
	}
	if err := backup.Write(ctx, conn, cfg.DataDir, f); err != nil {
		_ = f.Close()
		return err
	}
	return f.Close()
}

func serve() error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	log := newLogger(cfg.Log)
	slog.SetDefault(log)

	for _, dir := range []string{cfg.DataDir, cfg.Transcode.Dir} {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return fmt.Errorf("create %s: %w", dir, err)
		}
	}
	// ffmpeg and ffprobe die with the server. On macOS that takes pid files,
	// read here for what a server killed before it could stop them left.
	procs := filepath.Join(cfg.DataDir, "processes")
	if n := ffexec.KillLeftovers(procs); n > 0 {
		log.Warn("stopped ffmpeg processes an earlier run left behind", "count", n)
	}
	if err := ffexec.Track(procs); err != nil {
		return fmt.Errorf("create %s: %w", procs, err)
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	// The macOS app runs the server with a pipe on stdin: if the app dies
	// without stopping the server, the pipe closes and the server follows.
	if os.Getenv(config.EnvPrefix+"EXIT_ON_STDIN_EOF") == "1" {
		go func() {
			_, _ = io.Copy(io.Discard, os.Stdin)
			log.Info("stdin closed; shutting down")
			stop()
		}()
	}

	conn, err := db.Open(cfg.DBPath())
	if err != nil {
		return err
	}
	defer func() { _ = conn.Close() }()
	if err := db.Migrate(ctx, conn); err != nil {
		return err
	}
	queries := db.New(conn)

	if err := library.EnsureFromConfig(ctx, queries, cfg.Libraries, log); err != nil {
		return err
	}

	bus := events.NewBus()
	// Values saved from the settings page win over config.yaml and env.
	store := settings.New(queries)
	tmdbKey, _ := store.Get(ctx, settings.KeyTMDBAPIKey, cfg.TMDB.APIKey)
	hwaccel, hwdevice, err := store.HardwarePreference(ctx, cfg.Transcode.HWAccel, cfg.Transcode.HWDevice)
	if err != nil {
		return fmt.Errorf("hardware preference: %w", err)
	}
	tmdbClient := tmdb.New(tmdbKey, cfg.TMDB.BaseURL)
	// Every change to watch state goes through one service, which keeps
	// local choices safe from imports.
	watch := watchstate.New(queries, bus)
	// Plex imports stay off until an admin applies a preview and enables them.
	plexLink, err := plexsync.New(ctx, queries, watch, cfg.DataDir, plexsync.Options{Log: log, Bus: bus})
	if err != nil {
		return fmt.Errorf("plex: %w", err)
	}
	plexDone := make(chan struct{})
	go func() { defer close(plexDone); plexLink.Run(ctx) }()
	matcher := metadata.New(queries, tmdbClient, bus, log, metadata.Options{Workers: 4, Watch: watch})
	go matcher.Run(ctx)

	prober := &probe.Prober{Path: cfg.FFmpeg.FFprobe}
	sc := scanner.New(queries, prober, bus, log, scanner.Options{
		Workers:      cfg.Scan.Workers,
		StableFor:    cfg.Scan.StableFor,
		Interval:     api.ScanInterval(ctx, store, cfg.Scan.Interval),
		MatchesAfter: matcher.Configured,
	})
	go sc.Run(ctx)
	links := integrations.New(queries, sc, log)
	if err := links.Load(ctx); err != nil {
		return fmt.Errorf("integrations: %w", err)
	}
	links.Configure(tmdbClient, bus)
	matcher.SetHints(links.Hint)
	go links.Run(ctx)
	if cfg.Scan.DetectSegments {
		detector := segments.NewDetector(queries, conn, bus, segments.Decoder{FFmpeg: cfg.FFmpeg.FFmpeg, Nice: true}, log)
		go detector.Run(ctx)
	}
	// Movies' TMDB trailers come with their details when they are matched;
	// this asks again for those matched before, and for recent films.
	trailerJob := trailers.New(queries, tmdbClient, log, api.TrailersYouTube(ctx, store))
	go trailerJob.Run(ctx)
	// Lists that rows come from are fetched again in the background, from
	// TMDB and, with the admin's key, MDBList (PLAN-ROWS.md 7.2).
	mdbKey, _ := store.Get(ctx, settings.KeyListsMDBListKey, "")
	listService := lists.New(lists.Options{Queries: queries, Conn: conn, TMDB: tmdbClient, MDBList: lists.NewMDBList(mdbKey, lists.MDBListOptions{}),
		Bus: bus, Log: log, Disabled: !cfg.Lists.Enabled, Changed: api.ListsChangedPublisher(bus)})
	listsDone := make(chan struct{})
	go func() { defer close(listsDone); listService.Run(ctx) }()
	imageBase := cfg.TMDB.ImageBaseURL
	if imageBase == "" {
		imageBase = tmdb.DefaultImageBaseURL
	}
	customArt := artwork.New(filepath.Join(cfg.DataDir, backup.ArtworkDir))
	imageCache := images.New(filepath.Join(cfg.DataDir, "images"), imageBase).WithCustom(customArt)
	// Pictures left by an item that has since been removed or rematched.
	if err := api.SweepArtwork(ctx, queries, customArt, imageCache.Dir()); err != nil {
		log.Warn("could not clear unused posters", "err", err)
	}

	sessions := playback.NewManager(cfg.FFmpeg.FFmpeg, cfg.Transcode.Dir, playback.Capabilities{}, cfg.Transcode.IdleTimeout, cfg.Transcode.MaxSessions, log)
	sessions.SetResourceLimits(playback.ResourceLimits{MaxCacheBytes: cfg.Transcode.MaxCacheBytes,
		MaxSessionBytes: cfg.Transcode.MaxSessionBytes, MinFreeBytes: cfg.Transcode.MinFreeBytes,
		EventAhead: cfg.Transcode.EventAhead, EventKeepBehind: cfg.Transcode.EventKeepBehind})
	sessions.SetDownloadLimit(cfg.Transcode.MaxDownloadSessions)
	sessions.SetAdapterScanner(newAdapterScanner())
	// Encoder detection takes seconds; the server listens meanwhile, and
	// what needs the result waits for it.
	sessions.DetectOnDeviceInBackground(hwaccel, hwdevice, filepath.Join(cfg.DataDir, "transcoder.json"))
	if err := sessions.Clean(); err != nil {
		return fmt.Errorf("clear old transcode sessions: %w", err)
	}
	sessionsDone := make(chan struct{})
	go func() {
		sessions.Run(ctx)
		close(sessionsDone)
	}()
	uploadPolicy, err := store.Upload(ctx, settings.UploadPreference{Bps: cfg.Network.RemoteUploadBps, Enforce: cfg.Network.EnforceUpload})
	if err != nil {
		return fmt.Errorf("load remote upload policy: %w", err)
	}
	uploadLimiter := upload.New(uploadPolicy.Bps, uploadPolicy.Enforce)
	tracker := activity.New(conn, bus, log, activity.Options{Sessions: sessions, Config: api.ActivityConfig(ctx, store), OnEnd: uploadLimiter.Release,
		UpgradeReady: func(playID string, remote bool, bitrate int64) bool {
			if !remote || !uploadLimiter.Snapshot().Enforcing {
				return true
			}
			return uploadLimiter.Available(playID) >= bitrate+bitrate*2/5
		},
	})
	go tracker.Run(ctx)
	subtitles := playback.NewSubtitles(cfg.FFmpeg.FFmpeg, filepath.Join(cfg.DataDir, "subtitles"))
	osKey := openSubtitlesKey
	if cfg.OpenSubtitles.APIKey != "" {
		osKey = cfg.OpenSubtitles.APIKey
	}
	online := onlinesubs.New(queries, store, opensubtitles.New(osKey, "WatchMyMedia v"+version, cfg.OpenSubtitles.BaseURL),
		filepath.Join(cfg.DataDir, backup.DownloadedSubtitlesDir), log)
	online.Load(ctx)
	log.Info("online subtitles", "available", online.Available())
	keyframes := playback.NewKeyframes(cfg.FFmpeg.FFprobe, filepath.Join(cfg.DataDir, "keyframes"), log)
	// Seek thumbnails are made in the background, at low priority, and
	// wait while anything plays. Live TV and recordings are made further
	// down, while the thumbnail job already runs, so its check reads them
	// through atomic pointers.
	var liveNow atomic.Pointer[livetv.Sessions]
	var recordingNow atomic.Pointer[dvr.Service]
	thumbs := trickplay.New(queries, conn, bus, log, trickplay.Options{
		FFmpeg:  cfg.FFmpeg.FFmpeg,
		Dir:     filepath.Join(cfg.DataDir, "trickplay"),
		Enabled: api.TrickplayEnabled(ctx, store),
		ToneMapping: func() trickplay.ToneMapping {
			c := sessions.Capabilities()
			return trickplay.ToneMapping{CPU: c.CPUToneMap(), Dovi: c.DoviToneMap}
		},
		Busy: func() bool {
			if tracker.Playing() > 0 || len(sessions.Sessions()) > 0 {
				return true
			}
			if l := liveNow.Load(); l != nil && len(l.Activity(livetv.Owner{}, true)) > 0 {
				return true
			}
			r := recordingNow.Load()
			return r != nil && r.Active() > 0
		},
	})
	thumbsDone := make(chan struct{})
	go func() {
		thumbs.Run(ctx)
		close(thumbsDone)
	}()

	authSvc := auth.New(queries, cfg.Auth.SessionTTL, cfg.Auth.SecureCookies)
	authSvc.SetDeviceTTL(cfg.Auth.DeviceSessionTTL)
	// Turnstile keys in config or env win over the ones saved in Settings.
	bot := turnstile.New()
	bot.Disabled = cfg.Auth.Turnstile.Disabled
	if cfg.Auth.Turnstile.SiteKey != "" {
		bot.Fixed = true
		bot.SetKeys(cfg.Auth.Turnstile.SiteKey, cfg.Auth.Turnstile.SecretKey)
	} else {
		site, _ := store.Get(ctx, settings.KeyTurnstileSiteKey, "")
		secret, _ := store.Get(ctx, settings.KeyTurnstileSecretKey, "")
		bot.SetKeys(site, secret)
	}
	authSvc.SetChallenge(bot)
	if err := loadMediaKey(ctx, store, authSvc); err != nil {
		return err
	}
	go func() {
		t := time.NewTicker(time.Hour)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				if err := authSvc.Reap(ctx); err != nil {
					log.Warn("reap sessions", "err", err)
				}
			}
		}
	}()
	if need, _ := authSvc.SetupRequired(ctx); need {
		log.Info("no accounts yet: open the web UI to create the first admin")
	}
	go backup.RunNightly(ctx, conn, cfg.DataDir, func() int {
		v, _ := store.Get(ctx, settings.KeyBackupKeep, "7")
		n, _ := strconv.Atoi(v)
		return n
	}, log)

	// Announce the server to apps on the local network, unless it only
	// listens on loopback (behind Tailscale Serve or a proxy).
	var advertiser *discovery.Advertiser
	if cfg.Network.Discovery && !loopbackOnly(cfg.Host) {
		id, err := store.ServerID(ctx)
		if err != nil {
			return fmt.Errorf("server id: %w", err)
		}
		advertiser, err = discovery.New(discovery.Info{ID: id, Name: store.ServerName(ctx), Version: version, APILevel: api.APILevel, Port: cfg.Port}, log)
		if err != nil {
			log.Warn("local network announcement unavailable", "err", err)
		} else {
			go advertiser.Run(ctx)
		}
	}

	// Off, the checker gets no address, so it never fetches but still says
	// how this server was installed.
	updatesURL := cfg.Updates.URL
	if !cfg.Updates.Enabled {
		updatesURL = ""
	}
	checker := updates.New(updatesURL, version, cfg.Updates.Install)
	liveSources := livetv.New(queries, store, nil)
	liveSessions := livetv.NewSessions(liveSources, sessions, livetv.SessionOptions{
		Log:          log,
		ShareSources: cfg.LiveTV.ShareSources,
		FFmpeg:       cfg.FFmpeg.FFmpeg, FFprobe: cfg.FFmpeg.FFprobe, Dir: filepath.Join(cfg.Transcode.Dir, playback.SessionsDir, "live"),
		OnEnd: uploadLimiter.Release,
		OnAdmit: func(ctx context.Context, id, quality string) error {
			i, ok := netguard.From(ctx)
			if ok && !i.Proxied && netguard.Classify(i.IP) == netguard.NetLocal {
				return nil
			}
			bps := playback.StepByID(quality).MaxBitrate + 500_000
			_, err := uploadLimiter.Reserve(id, bps+bps/4)
			return err
		},
		OnAdmitDemand: func(_ context.Context, id string, bps int64, remote bool) error {
			if !remote {
				return nil
			}
			_, err := uploadLimiter.Reserve(id, bps)
			return err
		},
		Validate: func(ctx context.Context, id string) (livetv.Owner, error) {
			u, err := authSvc.ValidateSignIn(ctx, id)
			return livetv.Owner{UserID: u.ID, SignInID: u.SignInID, Name: u.Name, Admin: u.IsAdmin}, err
		},
	})
	sessions.SetLiveCache(filepath.Join(cfg.Transcode.Dir, playback.SessionsDir, "live"), liveSessions.StopForStorage)
	liveNow.Store(liveSessions)
	liveDone := make(chan struct{})
	go func() { liveSessions.Run(ctx); close(liveDone) }()
	defer liveSessions.Close()
	recordings := dvr.New(queries, store, liveSources, liveSessions, dvr.Options{Scanner: sc, Bus: bus, Watch: watch, Log: log})
	recordingNow.Store(recordings)
	matcher.SetHints(metadata.ChainHints(links.Hint, recordings.Hint))
	recordingsDone := make(chan struct{})
	go func() { defer close(recordingsDone); recordings.Run(ctx) }()
	defer recordings.Close()
	ads := segments.NewAdDetector(queries, conn, bus, log, segments.AdOptions{FFmpeg: cfg.FFmpeg.FFmpeg, Candidates: recordings.AdCandidates, Enabled: func(ctx context.Context) bool { v, err := recordings.Settings(ctx); return err == nil && v.FindAds }, Gate: bgwork.Gate{Dir: cfg.DataDir, MinFree: 5 << 30, Busy: func() bool {
		return tracker.Playing() > 0 || len(sessions.Sessions()) > 0 || len(liveSessions.Activity(livetv.Owner{}, true)) > 0 || recordings.Active() > 0
	}}})
	adsDone := make(chan struct{})
	go func() { defer close(adsDone); ads.Run(ctx) }()

	// Listings are off until an admin turns them on; then only the server
	// contacts SiliconDust, about once a day. After each fetch, TMDB
	// pictures and descriptions are matched to them unless the admin's
	// switch is off.
	var listings *guide.Service
	enricher := enrich.New(queries, tmdbClient, enrich.Options{Log: log, On: func(ctx context.Context) bool { return listings.TMDB(ctx) }})
	listings = guide.New(queries, store, liveSources, guide.Options{
		Log: log, ArtDir: filepath.Join(cfg.DataDir, "live-tv-art"), Enrich: enricher.Enrich, Bus: bus,
	})
	liveSources.OnLineup(listings.LineupChanged)
	listings.OnSaved(func(ctx context.Context) {
		if err := recordings.Reconcile(ctx); err != nil {
			log.Warn("recording schedule refresh failed", "err", err)
		}
	})
	listingsDone := make(chan struct{})
	go func() { listings.Run(ctx); close(listingsDone) }()
	guard := netguard.New(cfg.Network.TrustedProxyPrefixes, cfg.Network.AdminPrefixes, cfg.Network.AdminExcluded...)
	remoteAdmin, err := store.Get(ctx, settings.KeyRemoteAdmin, "1")
	if err != nil {
		return fmt.Errorf("read remote admin setting: %w", err)
	}
	guard.SetRemoteAdmin(remoteAdmin != "0")
	if cfg.Network.RemoteAdmin != nil {
		guard.SetRemoteAdmin(*cfg.Network.RemoteAdmin)
	}
	router, _ := api.New(api.Deps{Version: version, Host: cfg.Updates.Host, Queries: queries, Log: log, Bus: bus, Scanner: sc, Matcher: matcher,
		AdaptiveHLS: cfg.Transcode.AdaptiveHLS, Images: imageCache.Handler(), Artwork: customArt, ImagesDir: imageCache.Dir(), Playback: sessions, Subtitles: subtitles, OnlineSubtitles: online, Keyframes: keyframes, Prober: prober, Auth: authSvc,
		Settings: store, TMDB: tmdbClient, Conn: conn, DataDir: cfg.DataDir, DefaultHWAccel: cfg.Transcode.HWAccel, DefaultHWDevice: cfg.Transcode.HWDevice,
		Net: guard, RemoteAdminConfig: cfg.Network.RemoteAdmin,
		Upload: uploadLimiter,
		HSTS:   cfg.Auth.SecureCookies, Activity: tracker, Discovery: discoveryDep(advertiser), Turnstile: bot,
		Cast: api.CastOptions{Enabled: cfg.Cast.Enabled, AppID: cfg.Cast.AppID, URL: cfg.Cast.URL, ReceiverOrigins: cfg.Cast.ReceiverOrigins,
			Host: cfg.Host, Port: cfg.Port},
		LiveTV: liveSources, LiveSessions: liveSessions, Recordings: recordings, Guide: listings, Updates: checker, Integrations: links, Watch: watch, Plex: plexLink, Trickplay: thumbs, Trailers: trailerJob, Lists: listService})
	log.Info("network", "trusted_proxies", cfg.Network.TrustedProxies, "admin_networks", cfg.Network.AdminNetworks, "secure_cookies", cfg.Auth.SecureCookies)
	log.Info("sign-in", "turnstile", bot.Required(), "turnstile_from_config", bot.Fixed, "turnstile_disabled", bot.Disabled)
	log.Info("updates", "enabled", cfg.Updates.Enabled, "url", cfg.Updates.URL, "install", cfg.Updates.Install, "host", cfg.Updates.Host)
	log.Info("lists", "enabled", cfg.Lists.Enabled, "mdblist", listService.MDBList().Configured())
	log.Info("cast", "enabled", cfg.Cast.Enabled, "app_id", cfg.Cast.AppID, "url", cfg.Cast.URL, "receiver_origins", cfg.Cast.ReceiverOrigins)
	router.Handle("/*", webui.Handler())

	// Request contexts derive from baseCtx so long-lived responses (the SSE
	// event stream, later HLS sessions) end as soon as shutdown begins instead
	// of holding the server open until the shutdown deadline.
	baseCtx, cancelRequests := context.WithCancel(context.Background())
	defer cancelRequests()
	srv := &http.Server{
		Addr:        net.JoinHostPort(cfg.Host, strconv.Itoa(cfg.Port)),
		Handler:     router,
		BaseContext: func(net.Listener) context.Context { return baseCtx },
		// Only the header read and idle keep-alive connections are bounded.
		// Whole-request/response timeouts would cut off long media streams,
		// so those stay unset; without IdleTimeout an idle connection, and
		// its file handle, was kept until the client closed it.
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       2 * time.Minute,
	}

	errCh := make(chan error, 1)
	go func() {
		log.Info("server listening", "addr", srv.Addr, "version", version, "data_dir", cfg.DataDir)
		if err := srv.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
		close(errCh)
	}()

	select {
	case err := <-errCh:
		return err
	case <-ctx.Done():
	}

	log.Info("shutting down")
	cancelRequests()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	err = srv.Shutdown(shutdownCtx)
	select {
	case <-plexDone:
	case <-shutdownCtx.Done():
		log.Warn("Plex sync did not stop in time")
	}
	// Stop what still runs ffmpeg, so the server leaves none behind.
	select {
	case <-listingsDone:
	case <-shutdownCtx.Done():
		log.Warn("live TV listings did not stop in time")
	}
	select {
	case <-liveDone:
	case <-shutdownCtx.Done():
		log.Warn("live sessions did not stop in time")
	}
	select {
	case <-sessionsDone:
	case <-shutdownCtx.Done():
		log.Warn("playback sessions did not stop in time")
	}
	select {
	case <-thumbsDone:
	case <-shutdownCtx.Done():
		log.Warn("seek thumbnails did not stop in time")
	}
	select {
	case <-recordingsDone:
	case <-shutdownCtx.Done():
		log.Warn("recordings did not stop in time")
	}
	select {
	case <-adsDone:
	case <-shutdownCtx.Done():
		log.Warn("ad detection did not stop in time")
	}
	select {
	case <-listsDone:
	case <-shutdownCtx.Done():
		log.Warn("the list refresh did not stop in time")
	}
	keyframes.Close()
	subtitles.Close()
	if advertiser != nil {
		// Let the goodbye go out, so apps drop the server from their list now.
		select {
		case <-advertiser.Done():
		case <-time.After(2 * time.Second):
		}
	}
	return err
}

// loopbackOnly reports whether the listen address is a loopback address.
func loopbackOnly(host string) bool {
	if host == "localhost" {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

// discoveryDep keeps a nil advertiser a nil interface.
func discoveryDep(a *discovery.Advertiser) interface{ Rename(string) } {
	if a == nil {
		return nil
	}
	return a
}

func newLogger(cfg config.LogConfig) *slog.Logger {
	var level slog.Level
	if err := level.UnmarshalText([]byte(cfg.Level)); err != nil {
		level = slog.LevelInfo
	}
	opts := &slog.HandlerOptions{Level: level}
	var h slog.Handler
	if cfg.Format == "json" {
		h = slog.NewJSONHandler(os.Stdout, opts)
	} else {
		h = slog.NewTextHandler(os.Stdout, opts)
	}
	return slog.New(h)
}

// loadMediaKey gives the auth service the stored media token key, or makes
// and stores a new one when there is none, as on a first start or after
// restoring a backup (which leaves the key out).
func loadMediaKey(ctx context.Context, store *settings.Store, a *auth.Service) error {
	key, _ := store.Get(ctx, settings.KeyMediaTokenKey, "")
	if a.SetMediaKey(key) == nil {
		return nil
	}
	key = auth.NewMediaKey()
	if err := store.Set(ctx, settings.KeyMediaTokenKey, key); err != nil {
		return fmt.Errorf("store media token key: %w", err)
	}
	return a.SetMediaKey(key)
}
