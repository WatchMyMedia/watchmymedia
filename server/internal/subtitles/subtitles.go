// Package subtitles finds subtitles online for a file and keeps the ones
// people download. SubtitleSearch is free and cached; each download spends the
// server owner's OpenSubtitles quota, so nothing is downloaded that nobody
// picked, and a pick made before is served from disk.
package subtitles

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/sync/singleflight"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/opensubtitles"
	"watchmymedia/server/internal/settings"
)

// Provider is the one online provider so far.
const Provider = "opensubtitles"

// Who may download.
const (
	DownloadsEveryone = "everyone"
	DownloadsAdmins   = "admins"
)

// DefaultLanguages are searched until Settings names others.
var DefaultLanguages = []string{"en"}

// searchTTL is how long search results are reused for a file.
const searchTTL = 24 * time.Hour

var (
	ErrNotFound    = errors.New("subtitles: no such result; search again")
	ErrUnavailable = errors.New("subtitles: no online provider in this build")
)

// OnlineSubtitle is a subtitle found online for a file.
type OnlineSubtitle struct {
	ID              string `json:"id" doc:"What to download it by."`
	Language        string `json:"language" doc:"ISO 639-1, with a region for some (pt-br, zh-cn)."`
	Release         string `json:"release" doc:"The release it was made for."`
	HashMatch       bool   `json:"hashMatch" doc:"Made for this exact file, so in sync."`
	HearingImpaired bool   `json:"hearingImpaired"`
	Forced          bool   `json:"forced" doc:"Only the foreign-language parts."`
	Trusted         bool   `json:"trusted" doc:"From a trusted uploader."`
	AITranslated    bool   `json:"aiTranslated"`
	Downloads       int    `json:"downloads" doc:"How often it was downloaded on OpenSubtitles."`
	TrackID         string `json:"trackId,omitempty" doc:"Set when it is already downloaded for this file: the subtitle track to select."`
}

// Quota is what is left of the day's downloads.
type SubtitleQuota struct {
	SignedIn  bool       `json:"signedIn" doc:"Downloads count against the server's OpenSubtitles account; without one, against the server's IP (5 a day)."`
	Remaining int        `json:"remaining" doc:"Downloads left; -1 when not known yet."`
	ResetAt   *time.Time `json:"resetAt,omitempty"`
}

// SubtitleSearch is the online subtitles for a file.
type SubtitleSearch struct {
	Available   bool             `json:"available" doc:"An online provider is set up; false in builds without an API key."`
	Provider    string           `json:"provider" enum:"opensubtitles"`
	Attribution string           `json:"attribution" doc:"Show this where the results are listed."`
	Languages   []string         `json:"languages" nullable:"false"`
	Results     []OnlineSubtitle `json:"results" nullable:"false"`
	CanDownload bool             `json:"canDownload" doc:"The signed-in person may download (Settings can keep downloads to admins)."`
	Quota       SubtitleQuota    `json:"quota"`
	Error       string           `json:"error,omitempty" doc:"Why the search failed; the results are then empty."`
}

// Status is the provider's state for Settings › Subtitles.
type SubtitleStatus struct {
	Available bool                           `json:"available" doc:"This build has an OpenSubtitles API key."`
	Username  string                         `json:"username,omitempty"`
	Account   *opensubtitles.ProviderAccount `json:"account,omitempty" doc:"The signed-in account; absent when signed out or the sign-in failed."`
	Error     string                         `json:"error,omitempty"`
	Languages []string                       `json:"languages" nullable:"false"`
	Downloads string                         `json:"downloads" enum:"everyone,admins" doc:"Who may download."`
}

type Service struct {
	q        *db.Queries
	settings *settings.Store
	provider *opensubtitles.Client
	dir      string
	log      *slog.Logger

	mu       sync.Mutex
	searches map[string]*search // by file and languages
	hashes   map[string]fileHash
	// downloads shares one download among the requests for a result, so
	// two clicks spend the quota once.
	downloads singleflight.Group
}

type search struct {
	done    chan struct{}
	at      time.Time
	results []opensubtitles.Result
	err     error
}

type fileHash struct {
	size    int64
	modTime time.Time
	hash    string
}

// New returns the service; dir is where downloads are kept
// (<data>/subtitles-downloaded). Saved credentials are loaded by Load.
func New(q *db.Queries, store *settings.Store, provider *opensubtitles.Client, dir string, log *slog.Logger) *Service {
	return &Service{q: q, settings: store, provider: provider, dir: dir, log: log, searches: map[string]*search{}, hashes: map[string]fileHash{}}
}

// Available reports whether there is a provider to search.
func (s *Service) Available() bool { return s != nil && s.provider.Configured() }

// Load signs the provider in with the account saved in Settings.
func (s *Service) Load(ctx context.Context) {
	user, _ := s.settings.Get(ctx, settings.KeyOpenSubtitlesUsername, "")
	pass, _ := s.settings.Get(ctx, settings.KeyOpenSubtitlesPassword, "")
	s.provider.SetCredentials(user, pass)
}

// SetAccount checks and saves an OpenSubtitles account; an empty username
// signs out.
func (s *Service) SetAccount(ctx context.Context, username, password string) error {
	username = strings.TrimSpace(username)
	if username != "" {
		if err := s.provider.Verify(ctx, username, password); err != nil {
			return err
		}
	} else {
		password = ""
	}
	if err := s.settings.Set(ctx, settings.KeyOpenSubtitlesUsername, username); err != nil {
		return err
	}
	if err := s.settings.Set(ctx, settings.KeyOpenSubtitlesPassword, password); err != nil {
		return err
	}
	s.provider.SetCredentials(username, password)
	return nil
}

// Languages are the languages searched, in order.
func (s *Service) Languages(ctx context.Context) []string {
	v, _ := s.settings.Get(ctx, settings.KeySubtitleLanguages, "")
	if langs := ParseLanguages(v); len(langs) > 0 {
		return langs
	}
	return DefaultLanguages
}

var languageRe = regexp.MustCompile(`^[a-z]{2,3}(-[a-z]{2})?$`)

// ParseLanguages reads a comma-separated list of language codes, dropping
// what is not one and repeats.
func ParseLanguages(v string) []string {
	var out []string
	seen := map[string]bool{}
	for p := range strings.SplitSeq(strings.ToLower(v), ",") {
		p = strings.TrimSpace(p)
		if languageRe.MatchString(p) && !seen[p] {
			seen[p] = true
			out = append(out, p)
		}
	}
	return out
}

// Downloads is who may download: everyone (the default) or admins.
func (s *Service) Downloads(ctx context.Context) string {
	if v, _ := s.settings.Get(ctx, settings.KeySubtitleDownloads, ""); v == DownloadsAdmins {
		return DownloadsAdmins
	}
	return DownloadsEveryone
}

// Status is the provider's state, signing in to read the account.
func (s *Service) Status(ctx context.Context) SubtitleStatus {
	st := SubtitleStatus{Available: s.Available(), Username: s.provider.Username(), Languages: s.Languages(ctx), Downloads: s.Downloads(ctx)}
	if !st.Available || st.Username == "" {
		return st
	}
	a, err := s.provider.Status(ctx)
	if err != nil {
		st.Error = err.Error()
	}
	st.Account = a
	return st
}

// Search finds subtitles for a file in langs (the server's languages when
// empty). Results are cached for a day and concurrent searches share one
// request; provider errors come back in Search.Error.
func (s *Service) Search(ctx context.Context, fileID int64, langs []string) SubtitleSearch {
	if len(langs) == 0 {
		langs = s.Languages(ctx)
	}
	out := SubtitleSearch{Available: s.Available(), Provider: Provider, Attribution: opensubtitles.Attribution, Languages: langs, Results: []OnlineSubtitle{}, Quota: s.quota()}
	if !out.Available {
		return out
	}
	results, err := s.search(ctx, fileID, langs)
	if err != nil {
		out.Error = err.Error()
		return out
	}
	have := map[string]string{}
	if rows, err := s.q.ListDownloadedSubtitles(ctx, fileID); err == nil {
		for _, r := range rows {
			if r.Provider == Provider {
				have[r.ProviderFileID] = TrackID(r.ID)
			}
		}
	}
	for _, r := range results {
		id := strconv.FormatInt(r.FileID, 10)
		out.Results = append(out.Results, OnlineSubtitle{
			ID: id, Language: r.Language, Release: r.Release, HashMatch: r.HashMatch, HearingImpaired: r.HearingImpaired,
			Forced: r.ForeignPartsOnly, Trusted: r.Trusted, AITranslated: r.AITranslated, Downloads: r.Downloads, TrackID: have[id],
		})
	}
	out.Quota = s.quota()
	return out
}

func (s *Service) quota() SubtitleQuota {
	q := SubtitleQuota{SignedIn: s.provider.Username() != "", Remaining: -1}
	if a := s.provider.Cached(); a != nil {
		q.Remaining = a.RemainingDownload
		if !a.ResetAt.IsZero() {
			t := a.ResetAt
			q.ResetAt = &t
		}
	}
	return q
}

func searchKey(fileID int64, langs []string) string {
	return strconv.FormatInt(fileID, 10) + "|" + strings.Join(langs, ",")
}

func (s *Service) search(ctx context.Context, fileID int64, langs []string) ([]opensubtitles.Result, error) {
	key := searchKey(fileID, langs)
	s.mu.Lock()
	c := s.searches[key]
	if c != nil {
		select {
		case <-c.done:
			if c.err != nil || time.Since(c.at) > searchTTL {
				c = nil
			}
		default:
		}
	}
	if c == nil {
		for k, old := range s.searches {
			select {
			case <-old.done:
				if time.Since(old.at) > searchTTL {
					delete(s.searches, k)
				}
			default:
			}
		}
		c = &search{done: make(chan struct{})}
		s.searches[key] = c
		s.mu.Unlock()
		// The search is shared, so it outlives the request that started it.
		go func() {
			ctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 30*time.Second)
			defer cancel()
			c.results, c.err = s.query(ctx, fileID, langs)
			c.at = time.Now()
			close(c.done)
		}()
	} else {
		s.mu.Unlock()
	}
	select {
	case <-c.done:
		return c.results, c.err
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

// query asks the provider: by TMDB IDs when the file is matched, else by
// its name, with the file's hash either way.
func (s *Service) query(ctx context.Context, fileID int64, langs []string) ([]opensubtitles.Result, error) {
	t, err := s.q.GetFileSubtitleTarget(ctx, fileID)
	if err != nil {
		return nil, err
	}
	q := opensubtitles.Query{Languages: langs, MovieHash: s.hash(t.Path)}
	switch {
	case t.ShowTmdbID != nil:
		q.ParentTMDBID, q.Season, q.Episode = *t.ShowTmdbID, deref(t.SeasonNumber), deref(t.EpisodeNumber)
	case t.MovieTmdbID != nil:
		q.TMDBID = *t.MovieTmdbID
		if t.MovieImdbID != nil {
			q.IMDBID = *t.MovieImdbID
		}
	default:
		q.Query = strings.TrimSuffix(filepath.Base(t.Path), filepath.Ext(t.Path))
	}
	results, err := s.provider.Search(ctx, q)
	if len(results) > 30 {
		results = results[:30]
	}
	return results, err
}

func deref(p *int64) int64 {
	if p == nil {
		return 0
	}
	return *p
}

// hash is the file's moviehash, remembered while its size and date stay.
func (s *Service) hash(path string) string {
	st, err := os.Stat(path)
	if err != nil {
		return ""
	}
	s.mu.Lock()
	h, ok := s.hashes[path]
	s.mu.Unlock()
	if ok && h.size == st.Size() && h.modTime.Equal(st.ModTime()) {
		return h.hash
	}
	sum, err := opensubtitles.Hash(path)
	if err != nil {
		s.log.Warn("subtitle hash", "path", path, "err", err)
		return ""
	}
	s.mu.Lock()
	s.hashes[path] = fileHash{st.Size(), st.ModTime(), sum}
	s.mu.Unlock()
	return sum
}

// TrackID is a downloaded subtitle's track ID in a playback decision.
func TrackID(id int64) string { return "d" + strconv.FormatInt(id, 10) }

// downloadTimeout bounds a download that requests share.
const downloadTimeout = time.Minute

// Download keeps the result for the file, downloading it unless it was
// before. The result must come from a search for this file. Requests for
// the same result at once share one download, which carries on if the
// request that started it goes.
func (s *Service) Download(ctx context.Context, fileID int64, resultID string, userID int64) (db.DownloadedSubtitle, error) {
	if !s.Available() {
		return db.DownloadedSubtitle{}, ErrUnavailable
	}
	key := strconv.FormatInt(fileID, 10) + "|" + resultID
	v, err, _ := s.downloads.Do(key, func() (any, error) {
		ctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), downloadTimeout)
		defer cancel()
		return s.download(ctx, fileID, resultID, userID)
	})
	if err != nil {
		return db.DownloadedSubtitle{}, err
	}
	return v.(db.DownloadedSubtitle), nil
}

func (s *Service) download(ctx context.Context, fileID int64, resultID string, userID int64) (db.DownloadedSubtitle, error) {
	byResult := db.GetDownloadedSubtitleByProviderFileParams{MediaFileID: fileID, Provider: Provider, ProviderFileID: resultID}
	row, err := s.q.GetDownloadedSubtitleByProviderFile(ctx, byResult)
	if err == nil {
		if _, statErr := os.Stat(row.Path); statErr == nil {
			return row, nil
		}
		_ = s.q.DeleteDownloadedSubtitle(ctx, db.DeleteDownloadedSubtitleParams{ID: row.ID, MediaFileID: fileID})
	} else if !errors.Is(err, sql.ErrNoRows) {
		return db.DownloadedSubtitle{}, err
	}
	r, ok := s.cachedResult(fileID, resultID)
	if !ok {
		return db.DownloadedSubtitle{}, ErrNotFound
	}
	got, err := s.provider.Download(ctx, r.FileID)
	if err != nil {
		return db.DownloadedSubtitle{}, err
	}
	dir := filepath.Join(s.dir, strconv.FormatInt(fileID, 10))
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return db.DownloadedSubtitle{}, err
	}
	path := filepath.Join(dir, fmt.Sprintf("%s-%d.%s.srt", Provider, r.FileID, r.Language))
	if err := os.WriteFile(path, got.Data, 0o644); err != nil { //nolint:gosec // G306: a subtitle, as public as the video it belongs to
		return db.DownloadedSubtitle{}, err
	}
	var by *int64
	if userID > 0 {
		by = &userID
	}
	row, err = s.q.InsertDownloadedSubtitle(ctx, db.InsertDownloadedSubtitleParams{
		MediaFileID: fileID, Provider: Provider, ProviderFileID: resultID, Language: r.Language, Release: r.Release,
		HearingImpaired: b2i(r.HearingImpaired), Forced: b2i(r.ForeignPartsOnly), HashMatch: b2i(r.HashMatch), Path: path, DownloadedBy: by,
	})
	if errors.Is(err, sql.ErrNoRows) {
		// Kept meanwhile by a request this one did not share with; the
		// file is the same.
		return s.q.GetDownloadedSubtitleByProviderFile(ctx, byResult)
	}
	return row, err
}

func b2i(b bool) int64 {
	if b {
		return 1
	}
	return 0
}

// cachedResult finds a result among the file's cached searches.
func (s *Service) cachedResult(fileID int64, resultID string) (opensubtitles.Result, bool) {
	prefix := strconv.FormatInt(fileID, 10) + "|"
	s.mu.Lock()
	defer s.mu.Unlock()
	for key, c := range s.searches {
		if !strings.HasPrefix(key, prefix) {
			continue
		}
		select {
		case <-c.done:
		default:
			continue
		}
		for _, r := range c.results {
			if strconv.FormatInt(r.FileID, 10) == resultID {
				return r, true
			}
		}
	}
	return opensubtitles.Result{}, false
}

// List is the file's downloaded subtitles.
func (s *Service) List(ctx context.Context, fileID int64) []db.DownloadedSubtitle {
	if s == nil {
		return nil
	}
	rows, err := s.q.ListDownloadedSubtitles(ctx, fileID)
	if err != nil {
		s.log.Warn("downloaded subtitles", "file", fileID, "err", err)
		return nil
	}
	return rows
}

// Get is one downloaded subtitle of the file.
func (s *Service) Get(ctx context.Context, fileID, id int64) (db.DownloadedSubtitle, error) {
	if s == nil {
		return db.DownloadedSubtitle{}, ErrUnavailable
	}
	return s.q.GetDownloadedSubtitle(ctx, db.GetDownloadedSubtitleParams{ID: id, MediaFileID: fileID})
}

// Delete removes a downloaded subtitle and its file.
func (s *Service) Delete(ctx context.Context, fileID, id int64) error {
	row, err := s.Get(ctx, fileID, id)
	if err != nil {
		return err
	}
	if err := s.q.DeleteDownloadedSubtitle(ctx, db.DeleteDownloadedSubtitleParams{ID: id, MediaFileID: fileID}); err != nil {
		return err
	}
	_ = os.Remove(row.Path)
	return nil
}
