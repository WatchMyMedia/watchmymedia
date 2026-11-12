package api

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"slices"
	"sort"
	"strings"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/library"
	"watchmymedia/server/internal/metadata"
	"watchmymedia/server/internal/parser"
)

// Group kinds, in the order the unmatched screen shows them.
const (
	GroupShow     = "show"     // pick the show or movie
	GroupEpisodes = "episodes" // the show is known; these episode numbers are not on TMDB
	GroupLookup   = "lookup"   // not looked up yet, or TMDB did not answer
	GroupIgnored  = "ignored"  // a person said these are not library content
)

var groupOrder = map[string]int{GroupShow: 0, GroupEpisodes: 1, GroupLookup: 2, GroupIgnored: 3}

// UnmatchedFile is one file of an unmatched group.
type UnmatchedFile struct {
	ID           int64  `json:"id"`
	RelativePath string `json:"relativePath"`
	Season       *int   `json:"season,omitempty"`
	Episodes     []int  `json:"episodes,omitempty"`
	AirDate      string `json:"airDate,omitempty" doc:"For files named by air date (daily shows), YYYY-MM-DD."`
	AirDateAlt   string `json:"airDateAlt,omitempty" doc:"The other reading of a date whose day and month could be either way round."`
	MatchStatus  string `json:"matchStatus" enum:"pending,unmatched,failed"`
	MatchError   string `json:"matchError,omitempty"`
	Size         int64  `json:"size"`
	DurationMs   int64  `json:"durationMs,omitempty"`
}

// GroupShowRef is the show an "episodes" group belongs to.
type GroupShowRef struct {
	ID         int64  `json:"id"`
	TMDBID     int64  `json:"tmdbId"`
	Title      string `json:"title"`
	Year       *int64 `json:"year,omitempty"`
	PosterPath string `json:"posterPath,omitempty"`
}

// UnmatchedGroup is a set of files that one decision fixes: usually a show
// folder, or a movie folder with its versions.
type UnmatchedGroup struct {
	Key    string `json:"key"`
	Kind   string `json:"kind" enum:"show,episodes,lookup,ignored"`
	Folder string `json:"folder,omitempty" doc:"Folder relative to the library root that names the show or movie; empty when the file names alone do."`
	Title  string `json:"title" doc:"Title read from the folder or file names."`
	Year   *int   `json:"year,omitempty"`
	Reason string `json:"reason" doc:"Why these files need a person, in plain words."`
	// Seasons lists the season numbers the files are named with (TV).
	Seasons    []int                `json:"seasons" nullable:"false"`
	Candidates []metadata.Candidate `json:"candidates" nullable:"false"`
	Show       *GroupShowRef        `json:"show,omitempty"`
	Files      []UnmatchedFile      `json:"files" nullable:"false"`
}

type UnmatchedGroupList struct {
	Groups []UnmatchedGroup `json:"groups" nullable:"false"`
}

type UnmatchedGroupsOutput struct {
	Body UnmatchedGroupList
}

type FileGroupBody struct {
	FileIDs []int64 `json:"fileIds" minItems:"1" maxItems:"5000"`
	TMDBID  int     `json:"tmdbId" minimum:"1" doc:"TMDB movie ID (movie libraries) or show ID (TV libraries)."`
}

type LineUpInput struct {
	ID   int64 `path:"id" doc:"Library ID."`
	Body FileGroupBody
}

type LineUpOutput struct {
	Body metadata.LineUp
}

type MatchFilesInput struct {
	ID   int64 `path:"id" doc:"Library ID."`
	Body struct {
		FileGroupBody
		Remember bool `json:"remember" required:"false" doc:"Use this show or movie for new files in the files' shared folder."`
	}
}

type MatchFilesOutput struct {
	Body metadata.GroupResult
}

type IgnoreFilesInput struct {
	ID   int64 `path:"id" doc:"Library ID."`
	Body struct {
		FileIDs []int64 `json:"fileIds" minItems:"1" maxItems:"5000"`
		Ignored bool    `json:"ignored" doc:"True to ignore the files, false to bring them back."`
	}
}

func registerUnmatched(h huma.API, deps Deps) {
	huma.Register(h, huma.Operation{
		OperationID: "listUnmatchedGroups",
		Method:      http.MethodGet,
		Path:        Prefix + "/libraries/{id}/unmatched",
		Summary:     "List a library's unmatched files, grouped by the decision that fixes them",
		Tags:        []string{"files"},
	}, func(ctx context.Context, in *struct {
		ID int64 `path:"id" doc:"Library ID."`
	}) (*UnmatchedGroupsOutput, error) {
		lib, err := getLibrary(ctx, deps, in.ID)
		if err != nil {
			return nil, err
		}
		rows, err := deps.Queries.ListFilesNeedingMatch(ctx, lib.ID)
		if err != nil {
			return nil, err
		}
		overrides, err := deps.Queries.ListMatchOverrides(ctx, lib.ID)
		if err != nil {
			return nil, err
		}
		groups, err := buildUnmatchedGroups(ctx, deps.Queries, lib, rows, overrides)
		if err != nil {
			return nil, err
		}
		return &UnmatchedGroupsOutput{Body: UnmatchedGroupList{Groups: groups}}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "lineUpFiles",
		Method:      http.MethodPost,
		Path:        Prefix + "/libraries/{id}/lineup",
		Summary:     "Preview how files would line up with a TMDB show or movie",
		Description: "Saves nothing. For TV, counts per season how many files name an episode the show has on TMDB.",
		Tags:        []string{"files"},
	}), func(ctx context.Context, in *LineUpInput) (*LineUpOutput, error) {
		lib, files, err := loadGroupFiles(ctx, deps, in.ID, in.Body.FileIDs)
		if err != nil {
			return nil, err
		}
		lu, err := deps.Matcher.LineUp(ctx, lib, files, in.Body.TMDBID)
		if err != nil {
			return nil, huma.Error502BadGateway(err.Error())
		}
		return &LineUpOutput{Body: lu}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "matchFiles",
		Method:      http.MethodPost,
		Path:        Prefix + "/libraries/{id}/match-files",
		Summary:     "Match several files to a TMDB show or movie chosen by the user",
		Description: "Each episode is linked by the season and episode in its own name, and locked. Files whose episode is not on TMDB stay unmatched under the show. With remember, new files in the files' shared folder use the same show or movie.",
		Tags:        []string{"files"},
	}), func(ctx context.Context, in *MatchFilesInput) (*MatchFilesOutput, error) {
		lib, files, err := loadGroupFiles(ctx, deps, in.ID, in.Body.FileIDs)
		if err != nil {
			return nil, err
		}
		res, err := deps.Matcher.ApplyGroup(ctx, lib, files, in.Body.TMDBID, in.Body.Remember)
		if err != nil {
			return nil, huma.Error502BadGateway(err.Error())
		}
		publishChanged(deps, lib)
		return &MatchFilesOutput{Body: res}, nil
	})
}

func registerIgnore(h huma.API, deps Deps) {
	huma.Register(h, adminOp(h, huma.Operation{
		OperationID:   "ignoreFiles",
		Method:        http.MethodPost,
		Path:          Prefix + "/libraries/{id}/ignore-files",
		Summary:       "Ignore files that are not library content, or bring them back",
		Description:   "Ignored files are skipped by matching and left out of the unmatched count. Matching one by hand clears the flag.",
		Tags:          []string{"files"},
		DefaultStatus: http.StatusNoContent,
	}), func(ctx context.Context, in *IgnoreFilesInput) (*struct{}, error) {
		lib, err := getLibrary(ctx, deps, in.ID)
		if err != nil {
			return nil, err
		}
		flag := int64(0)
		if in.Body.Ignored {
			flag = 1
		}
		ids := uniqueIDs(in.Body.FileIDs)
		states := make(map[int64]db.ListFileMatchStatesRow, len(ids))
		err = inChunks(ids, func(chunk []int64) error {
			rows, err := deps.Queries.ListFileMatchStates(ctx, chunk)
			for _, r := range rows {
				states[r.ID] = r
			}
			return err
		})
		if err != nil {
			return nil, err
		}
		for _, id := range ids {
			f, ok := states[id]
			if !ok || f.LibraryID != lib.ID {
				return nil, huma.Error422UnprocessableEntity(fmt.Sprintf("file %d is not in this library", id))
			}
			if f.MatchStatus == metadata.StatusMatched {
				return nil, huma.Error422UnprocessableEntity(fmt.Sprintf("file %d is matched; only unmatched files can be ignored", id))
			}
		}
		err = deps.Queries.Transaction(ctx, func(q *db.Queries) error {
			return inChunks(ids, func(chunk []int64) error {
				_, err := q.SetMediaFilesIgnored(ctx, db.SetMediaFilesIgnoredParams{MatchIgnored: flag, LibraryID: lib.ID, Ids: chunk})
				return err
			})
		})
		if err != nil {
			return nil, err
		}
		publishChanged(deps, lib)
		return nil, nil
	})
}

// loadGroupFiles checks that TMDB is set up and every file belongs to the library.
func loadGroupFiles(ctx context.Context, deps Deps, libraryID int64, ids []int64) (db.Library, []db.MediaFile, error) {
	lib, err := getLibrary(ctx, deps, libraryID)
	if err != nil {
		return lib, nil, err
	}
	if err := tmdbReady(deps); err != nil {
		return lib, nil, err
	}
	ids = uniqueIDs(ids)
	byID := make(map[int64]db.MediaFile, len(ids))
	err = inChunks(ids, func(chunk []int64) error {
		rows, err := deps.Queries.ListMediaFilesByIDs(ctx, chunk)
		for _, f := range rows {
			byID[f.ID] = f
		}
		return err
	})
	if err != nil {
		return lib, nil, err
	}
	files := make([]db.MediaFile, 0, len(ids))
	for _, id := range ids {
		f, ok := byID[id]
		if !ok || f.LibraryID != lib.ID {
			return lib, nil, huma.Error422UnprocessableEntity(fmt.Sprintf("file %d is not in this library", id))
		}
		if f.ExtraKind != nil {
			return lib, nil, huma.Error422UnprocessableEntity(fmt.Sprintf("file %d is a trailer, which goes with its movie and isn't matched", id))
		}
		files = append(files, f)
	}
	return lib, files, nil
}

// uniqueIDs keeps the first of each ID, in order.
func uniqueIDs(ids []int64) []int64 {
	seen := make(map[int64]bool, len(ids))
	out := make([]int64, 0, len(ids))
	for _, id := range ids {
		if !seen[id] {
			seen[id] = true
			out = append(out, id)
		}
	}
	return out
}

// chunkSize bounds the IDs one query binds, well under SQLite's limit on
// variables (32,766 in the driver, 999 in builds before 3.32).
const chunkSize = 900

// inChunks calls fn with ids a chunk at a time, stopping at an error.
func inChunks(ids []int64, fn func([]int64) error) error {
	for chunk := range slices.Chunk(ids, chunkSize) {
		if err := fn(chunk); err != nil {
			return err
		}
	}
	return nil
}

// buildUnmatchedGroups groups files by the folder that names them (or, for
// files named on their own, by the parsed title) and by what is wrong.
func buildUnmatchedGroups(ctx context.Context, q *db.Queries, lib db.Library, rows []db.ListFilesNeedingMatchRow, overrides []db.MatchOverride) ([]UnmatchedGroup, error) {
	override := map[string]int64{}
	for _, o := range overrides {
		override[o.Folder] = o.TmdbID
	}
	// The show each TV folder is already linked to, looked up once per folder.
	shows := map[string]*GroupShowRef{}
	showFor := func(folder string) (*GroupShowRef, error) {
		if lib.Type != library.TypeTV || folder == "" {
			return nil, nil
		}
		if s, ok := shows[folder]; ok {
			return s, nil
		}
		var show db.Show
		var err error
		if id, ok := override[folder]; ok {
			show, err = q.GetShowByTMDB(ctx, db.GetShowByTMDBParams{LibraryID: lib.ID, TmdbID: id})
		} else {
			prefix, end := metadata.FolderRange(lib, folder)
			show, err = q.GetShowForFolder(ctx, db.GetShowForFolderParams{LibraryID: lib.ID, Prefix: prefix, PrefixEnd: end})
		}
		var ref *GroupShowRef
		if err == nil {
			ref = &GroupShowRef{ID: show.ID, TMDBID: show.TmdbID, Title: show.Title, Year: show.Year, PosterPath: deref(show.PosterPath)}
		} else if !errors.Is(err, sql.ErrNoRows) {
			return nil, err
		}
		shows[folder] = ref
		return ref, nil
	}

	byKey := map[string]*UnmatchedGroup{}
	var order []*UnmatchedGroup
	for _, r := range rows {
		var g parser.Guess
		_ = json.Unmarshal([]byte(r.Parsed), &g)
		rel := metadata.RelPath(lib.Path, r.Path)
		folder := parser.MatchFolder(lib.Type, rel)

		kind := GroupShow
		var show *GroupShowRef
		if r.MatchIgnored == 1 {
			kind = GroupIgnored
		} else if r.MatchStatus != metadata.StatusUnmatched {
			kind = GroupLookup
		} else {
			s, err := showFor(folder)
			if err != nil {
				return nil, err
			}
			if s != nil {
				kind, show = GroupEpisodes, s
			}
		}
		name := folder
		if name == "" {
			name = "title:" + strings.ToLower(g.Title)
			if lib.Type == library.TypeMovie && g.Year != nil {
				name += fmt.Sprintf(" (%d)", *g.Year)
			}
		}
		key := kind + ":" + name
		grp := byKey[key]
		if grp == nil {
			grp = &UnmatchedGroup{Key: key, Kind: kind, Folder: folder, Title: g.Title, Year: g.Year, Show: show,
				Seasons: []int{}, Candidates: []metadata.Candidate{}, Files: []UnmatchedFile{}}
			if g.Kind == parser.KindUnknown && folder == "" {
				grp.Title = ""
			}
			byKey[key] = grp
			order = append(order, grp)
		}
		if len(grp.Candidates) == 0 && r.MatchCandidates != nil {
			_ = json.Unmarshal([]byte(*r.MatchCandidates), &grp.Candidates)
		}
		if g.Season != nil && !slices.Contains(grp.Seasons, *g.Season) {
			grp.Seasons = append(grp.Seasons, *g.Season)
		}
		grp.Files = append(grp.Files, UnmatchedFile{
			ID: r.ID, RelativePath: rel, Season: g.Season, Episodes: g.Episodes, AirDate: g.AirDate, AirDateAlt: g.AirDateAlt, MatchStatus: r.MatchStatus,
			MatchError: deref(r.MatchError), Size: r.Size, DurationMs: deref(r.DurationMs),
		})
	}

	out := make([]UnmatchedGroup, 0, len(order))
	for _, grp := range order {
		sort.Ints(grp.Seasons)
		grp.Reason = groupReason(lib.Type, grp)
		out = append(out, *grp)
	}
	sort.SliceStable(out, func(i, j int) bool {
		if groupOrder[out[i].Kind] != groupOrder[out[j].Kind] {
			return groupOrder[out[i].Kind] < groupOrder[out[j].Kind]
		}
		return strings.ToLower(out[i].Title+out[i].Folder) < strings.ToLower(out[j].Title+out[j].Folder)
	})
	return out, nil
}

// groupReason explains a group in words a person can act on.
func groupReason(libraryType string, g *UnmatchedGroup) string {
	what := "show"
	if libraryType == library.TypeMovie {
		what = "movie"
	}
	switch g.Kind {
	case GroupIgnored:
		return "Ignored: left out of matching and of the unmatched count."
	case GroupLookup:
		for _, f := range g.Files {
			if f.MatchStatus == metadata.StatusFailed {
				return "TMDB did not answer: " + f.MatchError
			}
		}
		return "Not looked up on TMDB yet."
	case GroupEpisodes:
		for _, f := range g.Files {
			if f.AirDate == "" && (f.Season == nil || len(f.Episodes) == 0) {
				return "Some file names have no season and episode numbers."
			}
		}
		for _, f := range g.Files {
			if strings.HasSuffix(f.MatchError, metadata.PickOne) {
				return "Some dates fit more than one episode, such as two that aired the same day. Pick the right one for each."
			}
		}
		if anyDated(g.Files) {
			return fmt.Sprintf("TMDB lists no episode of %s on some of these air dates. It may not have them yet.", g.Show.Title)
		}
		return fmt.Sprintf("%s has no episodes with these numbers on TMDB.", g.Show.Title)
	}
	if g.Title == "" {
		return fmt.Sprintf("Neither the folder nor the file names say which %s this is.", what)
	}
	// Such files are not looked up, so they have no candidates either.
	if libraryType == library.TypeTV && len(g.Seasons) == 0 && !anyDated(g.Files) {
		return "The file names have no season and episode numbers."
	}
	c := g.Candidates
	if len(c) == 0 {
		return fmt.Sprintf("TMDB has no results for “%s”.", g.Title)
	}
	if len(c) > 1 && strings.EqualFold(c[0].Title, c[1].Title) && math.Abs(c[0].Score-c[1].Score) < 0.05 {
		if g.Year == nil {
			return fmt.Sprintf("More than one %s has this name and the folder has no year. Pick one, or add the year to the folder name.", what)
		}
		return fmt.Sprintf("More than one %s has this name.", what)
	}
	if c[0].Year > 0 {
		return fmt.Sprintf("No result was close enough to match on its own. The nearest was %s (%d).", c[0].Title, c[0].Year)
	}
	return fmt.Sprintf("No result was close enough to match on its own. The nearest was %s.", c[0].Title)
}

func anyDated(files []UnmatchedFile) bool {
	for _, f := range files {
		if f.AirDate != "" {
			return true
		}
	}
	return false
}
