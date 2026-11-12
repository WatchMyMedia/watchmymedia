package plex

import (
	"context"
	"encoding/json"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// ServerIdentity identifies a PMS installation, not its owner or a user. A
// matching machine identifier is not proof that a restored database kept IDs.
type ServerIdentity struct {
	MachineIdentifier string `json:"machineIdentifier"`
	Version           string `json:"version"`
	Claimed           bool   `json:"claimed"`
}

// Identity uses the unauthenticated PMS identity endpoint. A subsequent
// authenticated read is still needed to establish access to the server.
func (c *Client) Identity(ctx context.Context) (ServerIdentity, error) {
	var response struct {
		Container *ServerIdentity `json:"MediaContainer"`
	}
	anonymous := *c
	anonymous.token = ""
	if err := anonymous.get(ctx, "/identity", nil, &response); err != nil {
		return ServerIdentity{}, err
	}
	if response.Container == nil || response.Container.MachineIdentifier == "" || response.Container.Version == "" {
		return ServerIdentity{}, &Error{Kind: "invalid_response"}
	}
	return *response.Container, nil
}

// VerifyIdentity binds an explicit endpoint to the selected installation.
func (c *Client) VerifyIdentity(ctx context.Context, expectedMachineID string) (ServerIdentity, error) {
	if expectedMachineID == "" {
		return ServerIdentity{}, &Error{Kind: "invalid_input"}
	}
	identity, err := c.Identity(ctx)
	if err != nil {
		return ServerIdentity{}, err
	}
	if identity.MachineIdentifier != expectedMachineID {
		return ServerIdentity{}, &Error{Kind: "identity_mismatch"}
	}
	return identity, nil
}

type LibrarySection struct {
	ID    string `json:"key"`
	UUID  string `json:"uuid"`
	Title string `json:"title"`
	Type  string `json:"type"`
	Agent string `json:"agent"`
}

func (s *LibrarySection) UnmarshalJSON(data []byte) error {
	type fields LibrarySection
	var wire struct {
		*fields
		ID pmsID `json:"key"`
	}
	wire.fields = (*fields)(s)
	if err := json.Unmarshal(data, &wire); err != nil {
		return err
	}
	s.ID = string(wire.ID)
	return nil
}

// Libraries returns sections visible to this client's credential only. Display
// titles are never used as user or library identifiers.
func (c *Client) Libraries(ctx context.Context) ([]LibrarySection, error) {
	var response struct {
		Container *struct {
			Size      *int             `json:"size"`
			Directory []LibrarySection `json:"Directory"`
		} `json:"MediaContainer"`
	}
	if err := c.get(ctx, "/library/sections", nil, &response); err != nil {
		return nil, err
	}
	container := response.Container
	if container == nil || container.Size == nil || *container.Size != len(container.Directory) {
		return nil, &Error{Kind: "invalid_response"}
	}
	seen := make(map[string]bool, len(container.Directory))
	for _, section := range container.Directory {
		if !positiveID(section.ID) || section.Type == "" || seen[section.ID] {
			return nil, &Error{Kind: "invalid_response"}
		}
		seen[section.ID] = true
	}
	return container.Directory, nil
}

// Paging places hard bounds on a probe. Exhausting a bound is an error, not a
// successful truncated import. Zero fields use bounded defaults.
type Paging struct {
	PageSize int
	MaxPages int
	MaxItems int
}

func (p Paging) normalized() (Paging, error) {
	if p.PageSize == 0 {
		p.PageSize = 200
	}
	if p.MaxPages == 0 {
		p.MaxPages = 100
	}
	if p.MaxItems == 0 {
		p.MaxItems = 20000
	}
	if p.PageSize < 1 || p.PageSize > 1000 || p.MaxPages < 1 || p.MaxPages > 1000 || p.MaxItems < 1 || p.MaxItems > 100000 {
		return Paging{}, &Error{Kind: "invalid_input"}
	}
	return p, nil
}

// Item contains only source facts. In particular an episode's IDs identify the
// episode, not its show, and Plex numbering is not automatically TMDB ordering.
type Item struct {
	RatingKey            string
	Type                 string
	LibrarySectionID     string
	Title                string
	GUID                 string
	ExternalIDs          ExternalIDs
	ViewCount            *int64
	ViewOffsetMS         *int64
	DurationMS           *int64
	LastViewedAt         *int64
	ParentRatingKey      string
	GrandparentRatingKey string
	SeasonNumber         *int64
	EpisodeNumber        *int64
	EditionTitle         string
	EpisodeOrdering      string
}

// Watched reports positive evidence from the current item response. Missing
// viewCount stays unknown here: PlexAPI defaults it to zero, but the spike must
// establish the selected server/endpoint's omission semantics before relying on
// that convention. Neither resume position nor history implies completion.
func (i Item) Watched() (watched, known bool) {
	if i.ViewCount == nil {
		return false, false
	}
	return *i.ViewCount > 0, true
}

type Snapshot struct {
	Items []Item
	Pages int
}

// Snapshot reads movie or episode leaves with the client's user-scoped token.
// There is intentionally no accountID override: an owner snapshot cannot be
// relabeled as another person's snapshot. Successful paging is a complete
// traversal, not an atomic point-in-time guarantee while Plex is changing.
func (c *Client) Snapshot(ctx context.Context, section LibrarySection, paging Paging) (Snapshot, error) {
	if !positiveID(section.ID) || (section.Type != "movie" && section.Type != "show") {
		return Snapshot{}, &Error{Kind: "invalid_input"}
	}
	itemType, typeID := "movie", "1"
	if section.Type == "show" {
		itemType, typeID = "episode", "4"
	}
	query := url.Values{"type": {typeID}, "includeGuids": {"1"}, "sort": {"titleSort:asc"}}
	rows, pages, err := c.metadataPages(ctx, "/library/sections/"+section.ID+"/all", query, paging, false, section.ID)
	if err != nil {
		return Snapshot{}, err
	}
	result := Snapshot{Pages: pages, Items: make([]Item, 0, len(rows))}
	for _, row := range rows {
		if row.Type != itemType || row.LibrarySectionID != section.ID {
			return Snapshot{}, &Error{Kind: "scope_mismatch"}
		}
		item, err := row.item()
		if err != nil {
			return Snapshot{}, err
		}
		result.Items = append(result.Items, item)
	}
	return result, nil
}

// Metadata fetches one exact PMS item. For episode matching, callers can fetch
// GrandparentRatingKey once per show and inspect the show IDs separately. The
// returned episodeOrdering is merely evidence, never a verified mapping rule.
func (c *Client) Metadata(ctx context.Context, ratingKey string) (Item, error) {
	if !positiveID(ratingKey) {
		return Item{}, &Error{Kind: "invalid_input"}
	}
	var response metadataResponse
	if err := c.get(ctx, "/library/metadata/"+ratingKey, url.Values{"includeGuids": {"1"}}, &response); err != nil {
		return Item{}, err
	}
	container := response.Container
	if container == nil || container.Size == nil || *container.Size != 1 || len(container.Metadata) != 1 || container.Metadata[0].RatingKey != ratingKey {
		return Item{}, &Error{Kind: "invalid_response"}
	}
	if err := inheritSection(container.Metadata, string(container.LibrarySectionID)); err != nil {
		return Item{}, err
	}
	return container.Metadata[0].item()
}

// HistoryQuery.AccountID is the PMS-local history identity, not a plex.tv
// account/profile ID. Their relationship must be established separately.
type HistoryQuery struct {
	AccountID int64
	SectionID string
	Since     time.Time
	Paging    Paging
}

type HistoryEvent struct {
	HistoryKey string
	AccountID  int64
	ViewedAt   time.Time
	Item       Item
}

type HistorySnapshot struct {
	Events             []HistoryEvent
	Pages              int
	SkippedUnsupported int
}

// History can use a permitted admin credential with an explicit account filter.
// Every response row is checked against that identity; ignored filters fail the
// entire read. Retained events never establish an item's current watched state.
func (c *Client) History(ctx context.Context, in HistoryQuery) (HistorySnapshot, error) {
	if in.AccountID <= 0 || (in.SectionID != "" && !positiveID(in.SectionID)) || (!in.Since.IsZero() && in.Since.Unix() < 0) {
		return HistorySnapshot{}, &Error{Kind: "invalid_input"}
	}
	query := url.Values{"accountID": {strconv.FormatInt(in.AccountID, 10)}, "sort": {"viewedAt:asc"}}
	if in.SectionID != "" {
		query.Set("librarySectionID", in.SectionID)
	}
	if !in.Since.IsZero() {
		query.Set("viewedAt>", strconv.FormatInt(in.Since.Unix()-1, 10))
	}
	rows, pages, err := c.metadataPages(ctx, "/status/sessions/history/all", query, in.Paging, true, in.SectionID)
	if err != nil {
		return HistorySnapshot{}, err
	}
	result := HistorySnapshot{Pages: pages, Events: make([]HistoryEvent, 0, len(rows))}
	for _, row := range rows {
		if row.AccountID == nil || *row.AccountID != in.AccountID || (in.SectionID != "" && row.LibrarySectionID != in.SectionID) {
			return HistorySnapshot{}, &Error{Kind: "scope_mismatch"}
		}
		if row.ViewedAt == nil || *row.ViewedAt <= 0 {
			return HistorySnapshot{}, &Error{Kind: "invalid_response"}
		}
		viewed := time.Unix(*row.ViewedAt, 0).UTC()
		if !in.Since.IsZero() && viewed.Before(in.Since.Truncate(time.Second)) {
			return HistorySnapshot{}, &Error{Kind: "scope_mismatch"}
		}
		if row.Type != "movie" && row.Type != "episode" {
			result.SkippedUnsupported++
			continue
		}
		// History payloads are not a current-state snapshot even if a server
		// happens to include state-shaped fields alongside historical data.
		row.ViewCount, row.ViewOffset, row.LastViewedAt = nil, nil, nil
		item, err := row.toItem(false)
		if err != nil {
			return HistorySnapshot{}, err
		}
		result.Events = append(result.Events, HistoryEvent{HistoryKey: row.HistoryKey, AccountID: *row.AccountID, ViewedAt: viewed, Item: item})
	}
	return result, nil
}

type rawMetadata struct {
	RatingKey        string `json:"ratingKey"`
	Type             string `json:"type"`
	LibrarySectionID string `json:"librarySectionID"`
	Title            string `json:"title"`
	GUID             string `json:"guid"`
	Guids            []struct {
		ID string `json:"id"`
	} `json:"Guid"`
	ViewCount            *int64 `json:"viewCount"`
	ViewOffset           *int64 `json:"viewOffset"`
	Duration             *int64 `json:"duration"`
	LastViewedAt         *int64 `json:"lastViewedAt"`
	ParentRatingKey      string `json:"parentRatingKey"`
	GrandparentRatingKey string `json:"grandparentRatingKey"`
	ParentIndex          *int64 `json:"parentIndex"`
	Index                *int64 `json:"index"`
	EditionTitle         string `json:"editionTitle"`
	EpisodeOrdering      string `json:"episodeOrdering"`
	HistoryKey           string `json:"historyKey"`
	AccountID            *int64 `json:"accountID"`
	ViewedAt             *int64 `json:"viewedAt"`
}

func (r rawMetadata) item() (Item, error) {
	return r.toItem(true)
}

func (r rawMetadata) toItem(requireRatingKey bool) (Item, error) {
	if (requireRatingKey && r.RatingKey == "") || (r.RatingKey != "" && !positiveID(r.RatingKey)) || (r.Type != "movie" && r.Type != "episode" && r.Type != "show") {
		return Item{}, &Error{Kind: "invalid_response"}
	}
	for _, value := range []*int64{r.ViewCount, r.ViewOffset, r.Duration, r.LastViewedAt, r.ParentIndex, r.Index} {
		if value != nil && *value < 0 {
			return Item{}, &Error{Kind: "invalid_response"}
		}
	}
	guids := make([]string, 0, len(r.Guids)+1)
	guids = append(guids, r.GUID)
	for _, guid := range r.Guids {
		guids = append(guids, guid.ID)
	}
	return Item{
		RatingKey: r.RatingKey, Type: r.Type, LibrarySectionID: r.LibrarySectionID,
		Title: r.Title, GUID: r.GUID, ExternalIDs: NormalizeGUIDs(guids),
		ViewCount: r.ViewCount, ViewOffsetMS: r.ViewOffset, DurationMS: r.Duration,
		LastViewedAt: r.LastViewedAt, ParentRatingKey: r.ParentRatingKey,
		GrandparentRatingKey: r.GrandparentRatingKey, SeasonNumber: r.ParentIndex,
		EpisodeNumber: r.Index, EditionTitle: r.EditionTitle, EpisodeOrdering: r.EpisodeOrdering,
	}, nil
}

type metadataResponse struct {
	Container *struct {
		Size             *int          `json:"size"`
		TotalSize        *int          `json:"totalSize"`
		Offset           *int          `json:"offset"`
		LibrarySectionID pmsID         `json:"librarySectionID"`
		Metadata         []rawMetadata `json:"Metadata"`
	} `json:"MediaContainer"`
}

func (c *Client) metadataPages(ctx context.Context, path string, query url.Values, in Paging, history bool, sectionID string) ([]rawMetadata, int, error) {
	paging, err := in.normalized()
	if err != nil {
		return nil, 0, err
	}
	var rows []rawMetadata
	seen := make(map[string]bool)
	total := -1
	for page := 0; page < paging.MaxPages; page++ {
		query.Set("X-Plex-Container-Start", strconv.Itoa(len(rows)))
		query.Set("X-Plex-Container-Size", strconv.Itoa(paging.PageSize))
		var response metadataResponse
		if err := c.get(ctx, path, query, &response); err != nil {
			return nil, 0, err
		}
		container := response.Container
		if container == nil || container.Size == nil || *container.Size != len(container.Metadata) || *container.Size > paging.PageSize {
			return nil, 0, &Error{Kind: "invalid_response"}
		}
		if sectionID != "" && container.LibrarySectionID != "" && string(container.LibrarySectionID) != sectionID {
			return nil, 0, &Error{Kind: "scope_mismatch"}
		}
		if err := inheritSection(container.Metadata, string(container.LibrarySectionID)); err != nil {
			return nil, 0, err
		}
		if container.TotalSize == nil {
			return nil, 0, &Error{Kind: "unsupported_paging"}
		}
		if *container.TotalSize < 0 {
			return nil, 0, &Error{Kind: "invalid_response"}
		}
		if (container.Offset == nil && len(rows) != 0) || (container.Offset != nil && *container.Offset != len(rows)) {
			return nil, 0, &Error{Kind: "pagination_drift"}
		}
		if total < 0 {
			total = *container.TotalSize
		}
		if *container.TotalSize != total {
			return nil, 0, &Error{Kind: "pagination_drift"}
		}
		if total > paging.MaxItems || len(rows)+len(container.Metadata) > paging.MaxItems {
			return nil, 0, &Error{Kind: "limit_exceeded"}
		}
		if len(rows)+len(container.Metadata) > total || (len(container.Metadata) == 0 && len(rows) < total) {
			return nil, 0, &Error{Kind: "pagination_drift"}
		}
		for _, row := range container.Metadata {
			key := row.RatingKey
			validKey := positiveID(key)
			if history {
				key = row.HistoryKey
				validKey = strings.HasPrefix(key, "/status/sessions/history/") && positiveID(strings.TrimPrefix(key, "/status/sessions/history/"))
			}
			if !validKey {
				return nil, 0, &Error{Kind: "invalid_response"}
			}
			if seen[key] {
				return nil, 0, &Error{Kind: "pagination_drift"}
			}
			seen[key] = true
		}
		rows = append(rows, container.Metadata...)
		if len(rows) == total {
			return rows, page + 1, nil
		}
	}
	return nil, 0, &Error{Kind: "limit_exceeded"}
}

// PMS can place librarySectionID on the enclosing container instead of each
// item. Carry that explicit fact down, while refusing conflicting child scope.
func inheritSection(rows []rawMetadata, sectionID string) error {
	if sectionID == "" {
		return nil
	}
	for index := range rows {
		if rows[index].LibrarySectionID != "" && rows[index].LibrarySectionID != sectionID {
			return &Error{Kind: "scope_mismatch"}
		}
		rows[index].LibrarySectionID = sectionID
	}
	return nil
}

func positiveID(value string) bool {
	if value == "" || value[0] == '0' {
		return false
	}
	for _, char := range value {
		if char < '0' || char > '9' {
			return false
		}
	}
	number, err := strconv.ParseInt(value, 10, 64)
	return err == nil && number > 0
}

// ExternalIDs preserves namespaces. Conflicts and unknown providers remain
// visible; a caller must not select the first candidate or match by title.
type ExternalIDs struct {
	TMDB     string
	TVDB     string
	IMDb     string
	Conflict bool
	Unknown  bool
}

// NormalizeGUIDs accepts exact item-level external IDs only. Legacy episode
// paths such as thetvdb://show/season/episode are deliberately not collapsed into
// an episode ID or a TMDB show ID. Plex GUIDs need metadata-provider resolution.
func NormalizeGUIDs(guids []string) ExternalIDs {
	var ids ExternalIDs
	for _, raw := range guids {
		if raw == "" {
			continue
		}
		parsed, err := url.Parse(raw)
		if err != nil || parsed.User != nil || parsed.Fragment != "" || parsed.Opaque != "" || (parsed.Path != "" && parsed.Path != "/") {
			ids.Unknown = true
			continue
		}
		var target *string
		switch strings.ToLower(parsed.Scheme) {
		case "tmdb", "com.plexapp.agents.themoviedb":
			target = &ids.TMDB
		case "tvdb", "com.plexapp.agents.thetvdb":
			target = &ids.TVDB
		case "imdb", "com.plexapp.agents.imdb":
			target = &ids.IMDb
		default:
			ids.Unknown = true
			continue
		}
		id := parsed.Host
		valid := positiveID(id)
		if target == &ids.IMDb {
			valid = strings.HasPrefix(id, "tt") && len(id) >= 9 && allDigits(id[2:])
		}
		if !valid {
			ids.Unknown = true
			continue
		}
		if *target != "" && *target != id {
			ids.Conflict = true
		} else {
			*target = id
		}
	}
	return ids
}

func allDigits(value string) bool {
	if value == "" {
		return false
	}
	for _, char := range value {
		if char < '0' || char > '9' {
			return false
		}
	}
	return true
}

// PMS serializers use both strings and JSON integers for identifiers. Decode
// only exact decimal integers; float coercion can silently change an identity.
type pmsID string

func (id *pmsID) UnmarshalJSON(data []byte) error {
	value := string(data)
	if len(data) > 0 && data[0] == '"' {
		if err := json.Unmarshal(data, &value); err != nil {
			return &Error{Kind: "invalid_response"}
		}
	}
	if !positiveID(value) {
		return &Error{Kind: "invalid_response"}
	}
	*id = pmsID(value)
	return nil
}

func (r *rawMetadata) UnmarshalJSON(data []byte) error {
	type fields rawMetadata
	var wire struct {
		*fields
		RatingKey            pmsID `json:"ratingKey"`
		LibrarySectionID     pmsID `json:"librarySectionID"`
		ParentRatingKey      pmsID `json:"parentRatingKey"`
		GrandparentRatingKey pmsID `json:"grandparentRatingKey"`
	}
	wire.fields = (*fields)(r)
	if err := json.Unmarshal(data, &wire); err != nil {
		return err
	}
	r.RatingKey = string(wire.RatingKey)
	r.LibrarySectionID = string(wire.LibrarySectionID)
	r.ParentRatingKey = string(wire.ParentRatingKey)
	r.GrandparentRatingKey = string(wire.GrandparentRatingKey)
	return nil
}

// HistoryPage is one bounded history read for a durable importer. Until fixes
// the upper edge while pages are committed. Own omits the admin account filter:
// callers use a verified person's own credential and still validate every row.
type HistoryPageQuery struct {
	HistoryQuery
	Until  time.Time
	Offset int
	Own    bool
}
type HistoryPage struct {
	Events            []HistoryEvent
	NextOffset, Total int
	Complete          bool
}

func (c *Client) HistoryPage(ctx context.Context, in HistoryPageQuery) (HistoryPage, error) {
	if in.AccountID <= 0 || !positiveID(in.SectionID) || in.Offset < 0 || in.Offset > 10000000 || in.Until.IsZero() {
		return HistoryPage{}, &Error{Kind: "invalid_input"}
	}
	paging, err := in.Paging.normalized()
	if err != nil {
		return HistoryPage{}, err
	}
	query := url.Values{"sort": {"viewedAt:asc"}, "librarySectionID": {in.SectionID}, "viewedAt<": {strconv.FormatInt(in.Until.Unix()+1, 10)}, "X-Plex-Container-Start": {strconv.Itoa(in.Offset)}, "X-Plex-Container-Size": {strconv.Itoa(paging.PageSize)}}
	if !in.Own {
		query.Set("accountID", strconv.FormatInt(in.AccountID, 10))
	}
	if !in.Since.IsZero() {
		query.Set("viewedAt>", strconv.FormatInt(in.Since.Unix()-1, 10))
	}
	var response metadataResponse
	if err := c.get(ctx, "/status/sessions/history/all", query, &response); err != nil {
		return HistoryPage{}, err
	}
	v := response.Container
	if v == nil || v.Size == nil || v.TotalSize == nil || *v.TotalSize < 0 || v.Offset == nil || *v.Offset != in.Offset || *v.Size != len(v.Metadata) || *v.Size > paging.PageSize || in.Offset+len(v.Metadata) > *v.TotalSize || (len(v.Metadata) == 0 && in.Offset < *v.TotalSize) {
		return HistoryPage{}, &Error{Kind: "pagination_drift"}
	}
	if v.LibrarySectionID != "" && string(v.LibrarySectionID) != in.SectionID {
		return HistoryPage{}, &Error{Kind: "scope_mismatch"}
	}
	if err := inheritSection(v.Metadata, string(v.LibrarySectionID)); err != nil {
		return HistoryPage{}, err
	}
	out := HistoryPage{Total: *v.TotalSize, NextOffset: in.Offset + len(v.Metadata), Events: []HistoryEvent{}}
	seen := map[string]bool{}
	for _, row := range v.Metadata {
		if row.AccountID == nil || *row.AccountID != in.AccountID || row.LibrarySectionID != in.SectionID {
			return HistoryPage{}, &Error{Kind: "scope_mismatch"}
		}
		if !strings.HasPrefix(row.HistoryKey, "/status/sessions/history/") || !positiveID(strings.TrimPrefix(row.HistoryKey, "/status/sessions/history/")) || seen[row.HistoryKey] || row.ViewedAt == nil || *row.ViewedAt <= 0 {
			return HistoryPage{}, &Error{Kind: "invalid_response"}
		}
		seen[row.HistoryKey] = true
		at := time.Unix(*row.ViewedAt, 0).UTC()
		if at.Before(in.Since.Truncate(time.Second)) || at.After(in.Until) {
			return HistoryPage{}, &Error{Kind: "scope_mismatch"}
		}
		if row.Type != "movie" && row.Type != "episode" {
			continue
		}
		row.ViewCount, row.ViewOffset, row.LastViewedAt = nil, nil, nil
		item, err := row.toItem(false)
		if err != nil {
			return HistoryPage{}, err
		}
		out.Events = append(out.Events, HistoryEvent{HistoryKey: row.HistoryKey, AccountID: *row.AccountID, ViewedAt: at, Item: item})
	}
	out.Complete = out.NextOffset == out.Total
	return out, nil
}
