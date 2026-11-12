package api

import (
	"context"
	"errors"
	"net/http"
	"time"

	"github.com/danielgtaylor/huma/v2"
	"github.com/danielgtaylor/huma/v2/sse"

	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/integrations"
	"watchmymedia/server/internal/livetv/dvr"
	"watchmymedia/server/internal/livetv/guide"
	"watchmymedia/server/internal/metadata"
	"watchmymedia/server/internal/plexsync"
	"watchmymedia/server/internal/scanner"
	"watchmymedia/server/internal/trickplay"
)

// keepAliveEvery is how often the event stream sends a comment line, which
// keeps proxies from closing an idle stream and which clients ignore. Only
// the connect ping is an event: the phone and TV apps up to 0.4.1 refetch
// what they show on every ping after their first.
const keepAliveEvery = 15 * time.Second

// sessionsChangedEvent is a bus event that never reaches clients: some of
// a user's sessions were signed out, so the user's open event streams check
// their own session at once rather than at the next keep-alive.
const sessionsChangedEvent = "auth.sessions"

type sessionsChanged struct{}

func publishSessionsChanged(deps Deps, userID int64) {
	if deps.Bus != nil {
		deps.Bus.Publish(events.Event{Type: sessionsChangedEvent, Data: sessionsChanged{}, To: &events.Audience{UserID: userID, Private: true}})
	}
}

// registerEvents exposes the event bus as a server-sent events stream. Each
// event's name is the key below and its data is the matching JSON payload.
func registerEvents(h huma.API, deps Deps) {
	sse.Register(h, huma.Operation{
		OperationID: "events",
		Method:      http.MethodGet,
		Path:        Prefix + "/events",
		Summary:     "Live server events (SSE)",
		Description: "Streams scan and metadata-matching progress, library changes, playback activity, changes to the user's watch state/imported history and, to admins, Plex import state and seek thumbnails made as server-sent events. A ping is sent once on connect, when subscribed; after that a comment line every 15 seconds keeps the connection open.",
		Tags:        []string{"system"},
	}, map[string]any{
		"scan.started":    scanner.ScanStarted{},
		"scan.progress":   scanner.ScanProgress{},
		"scan.finished":   scanner.ScanFinished{},
		"scan.failed":     scanner.ScanFailed{},
		"library.changed": scanner.LibraryChanged{},
		"match.started":   metadata.MatchStarted{},
		"match.progress":  metadata.MatchProgress{},
		"match.finished":  metadata.MatchFinished{},
		"ping":            scanner.Ping{},
		// Activity events go to admins, on any network, and the person
		// watching (everyone with the household setting); clients fetch
		// /api/activity for detail.
		"activity.started": activity.Started{},
		"activity.updated": activity.Updated{},
		"activity.stopped": activity.Stopped{},
		// The signed-in user's own progress or watched marks changed.
		"watch.changed":        WatchChanged{},
		"plex.changed":         plexsync.PlexChanged{},
		"plex.history.changed": plexsync.PlexHistoryChanged{},
		// A request changed: to the person who asked and to admins. A
		// problem was reported or resolved: to admins.
		"requests.changed": integrations.RequestsChanged{},
		"problems.changed": integrations.ProblemsChanged{},
		// A file's seek thumbnails are made: to admins, for Settings.
		"trickplay.ready": trickplay.Ready{},
		// To a person's own devices: their favourite channels or Live TV
		// reminders changed (API level 15).
		guide.FavouritesChangedEvent: guide.FavouritesChanged{},
		guide.RemindersChangedEvent:  guide.RemindersChanged{},
		dvr.ChangedEvent:             dvr.Changed{},
		// The person's order of a screen's rows changed, to their own
		// devices, or the household's default, to everyone; a list was
		// made, changed or deleted, or a fetch changed its entries, to the
		// list's audience (API level 22).
		RowsChangedEvent:  RowsChanged{},
		ListsChangedEvent: ListsChanged{},
	}, func(ctx context.Context, _ *struct{}, send sse.Sender) {
		if deps.Bus == nil {
			return
		}
		user, _ := auth.UserFrom(ctx)
		token, _ := auth.SessionTokenFrom(ctx)
		valid := func() bool {
			if deps.Auth == nil {
				return false
			}
			u, err := deps.Auth.ValidateSession(ctx, token)
			if err != nil && !errors.Is(err, auth.ErrUnauthenticated) {
				return true // the database was busy: check at the next keep-alive
			}
			return err == nil && u.ID == user.ID && u.IsAdmin == user.IsAdmin
		}
		// Only the user's events reach the stream, so other users' can't
		// fill it; when it falls behind it is closed, and the client
		// reconnects and fetches again what it shows.
		ch, cancel := deps.Bus.Stream(func(e events.Event) bool { return e.For(user.ID, user.IsAdmin, deps.Net.Allowed(ctx)) })
		defer cancel()
		// A ping right away tells the client (and any proxy) the stream is
		// live and that every event from here on will arrive.
		if err := send.Data(scanner.Ping{Time: time.Now().UTC().Format(time.RFC3339)}); err != nil {
			return
		}
		ticker := time.NewTicker(keepAliveEvery)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if !valid() {
					return
				}
				if err := send.Comment("keep-alive"); err != nil {
					return
				}
			case e, ok := <-ch:
				if !ok {
					return
				}
				// The session is checked at every keep-alive, and at once
				// when the user's sessions are signed out, not per event.
				if e.Type == sessionsChangedEvent {
					if !valid() {
						return
					}
					continue
				}
				if !e.For(user.ID, user.IsAdmin, deps.Net.Allowed(ctx)) {
					continue
				}
				if err := send.Data(e.Data); err != nil {
					return
				}
			}
		}
	})
}
