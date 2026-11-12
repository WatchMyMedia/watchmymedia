// Package livetv owns antenna sources, channel identity and live viewing.
// It never creates library files, watch state or file-play history.
package livetv

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"strconv"
	"strings"
	"sync"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/livetv/hdhomerun"
	"watchmymedia/server/internal/settings"
)

const AccessKey = "livetv.access"

var ErrMissing = errors.New("source or channel not found")
var ErrDisabled = errors.New("live TV access is turned off")
var ErrConflict = errors.New("this tuner is already configured")

type Source struct {
	ID          string           `json:"id"`
	Device      hdhomerun.Device `json:"device"`
	Name        string           `json:"name"`
	Address     string           `json:"address"`
	Enabled     bool             `json:"enabled"`
	RefreshedAt string           `json:"refreshedAt"`
	LastError   string           `json:"lastError"`
}

type Channel struct {
	ID        string `json:"id"`
	SourceID  string `json:"sourceId"`
	Number    string `json:"number"`
	Name      string `json:"name"`
	Enabled   bool   `json:"enabled"`
	Available bool   `json:"available"`
	Reason    string `json:"reason,omitempty"`
	// Filled from listings, only while they are on (PLAN-LIVE-TV-GUIDE.md).
	LogoURL string     `json:"logoUrl,omitempty" required:"false" doc:"The channel's logo from the listings provider: a path on this server."`
	Now     *Programme `json:"now,omitempty" required:"false" doc:"What is on now, when the channel has listings."`
	Next    *Programme `json:"next,omitempty" required:"false" doc:"What follows, when the channel has listings."`
	// The viewer's own (PLAN-LIVE-TV-GUIDE.md §5.7).
	Favourite bool `json:"favourite,omitempty" required:"false" doc:"The viewer starred this channel (API level 15)."`
}

type Inspection struct {
	Device   hdhomerun.Device `json:"device"`
	Channels int              `json:"channels"`
}

type Service struct {
	// Configuration changes and start admission share this lock. Network
	// refreshes are bounded, and lineup replacement is one DB transaction.
	mu                sync.Mutex
	q                 *db.Queries
	store             *settings.Store
	client            func(string) (*hdhomerun.Client, error)
	changed           func(source, channel, reason string)
	listeners         []func(source, channel, reason string)
	recorders         map[string]string
	recorderTakeovers map[string]func()
	recorderChannels  map[string]string
	recorderTaken     map[string]bool
	viewers           *Sessions
	// lineup hears that a tuner's lineup was read again.
	lineup func(context.Context)
}

// Listen fans configuration changes out without replacing the sessions'
// listener. Listeners must not re-enter configuration mutation methods.
func (s *Service) Listen(fn func(source, channel, reason string)) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.listeners = append(s.listeners, fn)
}
func (s *Service) notify(source, channel, reason string) {
	if s.changed != nil {
		s.changed(source, channel, reason)
	}
	for _, fn := range s.listeners {
		fn(source, channel, reason)
	}
}

// OnLineup calls fn after a tuner is added or its lineup refreshed, so
// listings can be fetched for new channels.
func (s *Service) OnLineup(fn func(context.Context)) { s.lineup = fn }

func (s *Service) lineupChanged(ctx context.Context) {
	if s.lineup != nil {
		s.lineup(context.WithoutCancel(ctx))
	}
}

// GuideAuth reads DeviceAuth from every enabled tuner for one listings
// fetch, joined as SiliconDust's documentation says, with their device IDs
// for the Email form. A tuner that doesn't answer is left out; with none
// answering it fails with hdhomerun.ErrOffline. No enabled tuner gives no
// IDs and no error.
func (s *Service) GuideAuth(ctx context.Context) (hdhomerun.Secret, []string, error) {
	rows, err := s.q.ListLiveSources(ctx)
	if err != nil {
		return hdhomerun.Secret{}, nil, err
	}
	var secrets []hdhomerun.Secret
	var ids []string
	var failed error
	for _, r := range rows {
		if r.Enabled == 0 {
			continue
		}
		ids = append(ids, r.DeviceID)
		c, err := s.client(r.Address)
		if err != nil {
			failed = err
			continue
		}
		device, auth, err := c.GuideAuth(ctx)
		if err == nil && device.ID != r.DeviceID {
			err = hdhomerun.ErrIdentity
		}
		if err != nil {
			failed = err
			continue
		}
		secrets = append(secrets, auth)
	}
	if len(ids) > 0 && len(secrets) == 0 {
		return hdhomerun.Secret{}, ids, errors.Join(hdhomerun.ErrOffline, failed)
	}
	return hdhomerun.Join(secrets...), ids, nil
}

func New(q *db.Queries, store *settings.Store, client func(string) (*hdhomerun.Client, error)) *Service {
	if client == nil {
		client = func(address string) (*hdhomerun.Client, error) { return hdhomerun.New(address, nil) }
	}
	return &Service{q: q, store: store, client: client}
}

func newID() string { b := make([]byte, 16); _, _ = rand.Read(b); return hex.EncodeToString(b) }
func flag(b bool) int64 {
	if b {
		return 1
	}
	return 0
}

func (s *Service) Access(ctx context.Context) string {
	access, err := s.store.Get(ctx, AccessKey, "admins")
	if err != nil || (access != "admins" && access != "everyone") {
		return "none"
	}
	return access
}
func (s *Service) Allowed(ctx context.Context, admin bool) bool {
	access := s.Access(ctx)
	return access == "everyone" || (access == "admins" && admin)
}
func (s *Service) SetAccess(ctx context.Context, access string) error {
	if access != "none" && access != "admins" && access != "everyone" {
		return ErrDisabled
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := s.store.Set(ctx, AccessKey, access); err != nil {
		return err
	}
	// Existing sessions recheck individual policy too. Conservatively stop
	// them on every policy change, including everyone -> admins.
	s.notify("", "", "access_changed")
	return nil
}
func (s *Service) Sources(ctx context.Context) ([]Source, error) {
	rows, err := s.q.ListLiveSources(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]Source, 0, len(rows))
	for _, r := range rows {
		out = append(out, sourceView(r))
	}
	return out, nil
}
func sourceView(r db.LiveTvSource) Source {
	return Source{ID: r.ID, Device: hdhomerun.Device{ID: r.DeviceID, Name: r.Name, Model: r.Model, Firmware: r.Firmware, Tuners: r.TunerCount}, Name: r.Name, Address: r.Address, Enabled: r.Enabled != 0, RefreshedAt: r.RefreshedAt, LastError: r.LastError}
}
func (s *Service) inspect(ctx context.Context, address string) (*hdhomerun.Client, hdhomerun.Device, []hdhomerun.Channel, error) {
	c, err := s.client(strings.TrimSpace(address))
	if err != nil {
		return nil, hdhomerun.Device{}, nil, err
	}
	device, err := c.Discover(ctx)
	if err != nil {
		return nil, device, nil, err
	}
	channels, err := c.Lineup(ctx)
	return c, device, channels, err
}
func (s *Service) Inspect(ctx context.Context, address string) (Inspection, error) {
	_, device, channels, err := s.inspect(ctx, address)
	return Inspection{Device: device, Channels: len(channels)}, err
}
func (s *Service) Add(ctx context.Context, address, name string) (Source, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	c, device, channels, err := s.inspect(ctx, address)
	if err != nil {
		return Source{}, err
	}
	rows, err := s.q.ListLiveSources(ctx)
	if err != nil {
		return Source{}, err
	}
	for _, r := range rows {
		if r.DeviceID == device.ID {
			return Source{}, ErrConflict
		}
	}
	name = strings.TrimSpace(name)
	if name == "" {
		name = device.Name
	}
	if len(name) > 128 {
		return Source{}, hdhomerun.ErrDevice
	}
	var row db.LiveTvSource
	err = s.q.Transaction(ctx, func(q *db.Queries) error {
		var err error
		row, err = q.CreateLiveSource(ctx, db.CreateLiveSourceParams{ID: newID(), DeviceID: device.ID, Name: name, Address: c.Address(), Model: device.Model, Firmware: device.Firmware, TunerCount: device.Tuners})
		if err != nil {
			return err
		}
		if err := reconcile(ctx, q, row.ID, device, channels); err != nil {
			return err
		}
		return q.SetSetting(ctx, db.SetSettingParams{Key: "livetv.setup_complete", Value: "1"})
	})
	if err != nil {
		return Source{}, err
	}
	row, err = s.q.GetLiveSource(ctx, row.ID)
	s.lineupChanged(ctx)
	return sourceView(row), err
}
func (s *Service) Refresh(ctx context.Context, id string) (Source, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	row, err := s.q.GetLiveSource(ctx, id)
	if err != nil {
		return Source{}, err
	}
	_, device, channels, err := s.inspect(ctx, row.Address)
	if err == nil && device.ID != row.DeviceID {
		err = hdhomerun.ErrIdentity
	}
	if err != nil {
		// Only adapter errors enter stored diagnostics, never arbitrary I/O text.
		_ = s.q.FailedLiveSource(ctx, db.FailedLiveSourceParams{ID: id, LastError: err.Error()})
		return Source{}, err
	}
	if err := s.q.Transaction(ctx, func(q *db.Queries) error { return reconcile(ctx, q, id, device, channels) }); err != nil {
		return Source{}, err
	}
	row, err = s.q.GetLiveSource(ctx, id)
	s.lineupChanged(ctx)
	return sourceView(row), err
}
func reconcile(ctx context.Context, q *db.Queries, id string, device hdhomerun.Device, channels []hdhomerun.Channel) error {
	if err := q.UnavailableLiveChannels(ctx, id); err != nil {
		return err
	}
	now := db.FormatTime(time.Now())
	for _, c := range channels {
		major, minor, _ := strings.Cut(c.Number, ".")
		a, _ := strconv.ParseInt(major, 10, 64)
		b, _ := strconv.ParseInt(minor, 10, 64)
		if err := q.UpsertLiveChannel(ctx, db.UpsertLiveChannelParams{ID: newID(), SourceID: id, ProviderKey: c.Key, Number: c.Number, Name: c.Name, StreamPath: c.Path, Protected: flag(c.Protected), Major: a, Minor: b, LastSeenAt: now}); err != nil {
			return err
		}
	}
	return q.RefreshedLiveSource(ctx, db.RefreshedLiveSourceParams{ID: id, Model: device.Model, Firmware: device.Firmware, TunerCount: device.Tuners, RefreshedAt: now})
}
func (s *Service) Update(ctx context.Context, id, address, name string, enabled bool) (Source, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	row, err := s.q.GetLiveSource(ctx, id)
	if err != nil {
		return Source{}, err
	}
	name = strings.TrimSpace(name)
	if name == "" || len(name) > 128 {
		return Source{}, hdhomerun.ErrDevice
	}
	c, err := s.client(address)
	if err != nil {
		return Source{}, err
	}
	if c.Address() != row.Address {
		device, err := c.Discover(ctx)
		if err != nil {
			return Source{}, err
		}
		if device.ID != row.DeviceID {
			return Source{}, hdhomerun.ErrIdentity
		}
	}
	if err := s.q.UpdateLiveSource(ctx, db.UpdateLiveSourceParams{ID: id, Address: c.Address(), Name: name, Enabled: flag(enabled)}); err != nil {
		return Source{}, err
	}
	if !enabled || c.Address() != row.Address {
		s.notify(id, "", "source_changed")
	}
	row, err = s.q.GetLiveSource(ctx, id)
	return sourceView(row), err
}
func (s *Service) Remove(ctx context.Context, id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.notify(id, "", "source_removed")
	return s.q.DeleteLiveSource(ctx, id)
}
func (s *Service) EnableChannel(ctx context.Context, id string, enabled bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, err := s.q.GetLiveChannel(ctx, id); err != nil {
		return err
	}
	if err := s.q.EnableLiveChannel(ctx, db.EnableLiveChannelParams{ID: id, Enabled: flag(enabled)}); err != nil {
		return err
	}
	if !enabled {
		s.notify("", id, "channel_disabled")
	}
	return nil
}
func (s *Service) Channels(ctx context.Context, admin bool) ([]Channel, error) {
	rows, err := s.q.ListLiveChannels(ctx)
	if err != nil {
		return nil, err
	}
	sources, err := s.q.ListLiveSources(ctx)
	if err != nil {
		return nil, err
	}
	enabled := map[string]bool{}
	for _, source := range sources {
		enabled[source.ID] = source.Enabled != 0
	}
	out := make([]Channel, 0, len(rows))
	for _, r := range rows {
		if !admin && (r.Enabled == 0 || r.Protected != 0 || !enabled[r.SourceID]) {
			continue
		}
		reason := ""
		switch {
		case r.Protected != 0:
			reason = "protected_channel"
		case r.Available == 0:
			reason = "unavailable"
		case !enabled[r.SourceID]:
			reason = "source_disabled"
		}
		out = append(out, Channel{ID: r.ID, SourceID: r.SourceID, Number: r.Number, Name: r.Name, Enabled: r.Enabled != 0, Available: reason == "", Reason: reason})
	}
	return out, nil
}

// SetupComplete remembers the explicit Live-TV-only setup choice even if
// its tuner is later disabled, offline or removed.
func (s *Service) SetupComplete(ctx context.Context) bool {
	v, err := s.store.Get(ctx, "livetv.setup_complete", "0")
	return err == nil && v == "1"
}
