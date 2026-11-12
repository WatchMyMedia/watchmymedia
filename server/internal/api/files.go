package api

import (
	"context"
	"encoding/json"
	"net/http"
	"path/filepath"
	"time"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/livetv/dvr"
	"watchmymedia/server/internal/metadata"
	"watchmymedia/server/internal/parser"
	"watchmymedia/server/internal/probe"
)

// MediaFile is a video file found in a library, with what the scanner learned
// from its name and from ffprobe.
type MediaFile struct {
	Recording    *dvr.Provenance `json:"recording,omitempty" required:"false" doc:"When and where the server recorded this file (API level 19)."`
	ID           int64           `json:"id"`
	LibraryID    int64           `json:"libraryId"`
	Path         string          `json:"path" doc:"Absolute path on the server."`
	RelativePath string          `json:"relativePath" doc:"Path relative to the library root, forward slashes."`
	Size         int64           `json:"size" doc:"Bytes."`
	ModifiedAt   time.Time       `json:"modifiedAt"`
	Status       string          `json:"status" enum:"present,missing"`
	// Parsed is the scanner's guess from the file and folder names.
	Parsed      parser.Guess `json:"parsed"`
	Container   string       `json:"container,omitempty" example:"matroska"`
	VideoCodec  string       `json:"videoCodec,omitempty" example:"h264"`
	AudioCodec  string       `json:"audioCodec,omitempty" example:"aac"`
	Width       int64        `json:"width,omitempty"`
	Height      int64        `json:"height,omitempty"`
	DurationMs  int64        `json:"durationMs,omitempty"`
	HDR         string       `json:"hdr,omitempty" enum:",hdr10,hlg,dolbyvision" doc:"HDR kind of the main video stream."`
	DVProfile   int64        `json:"dvProfile,omitempty" example:"8" doc:"Dolby Vision profile (5, 7, 8…); omitted for other files, and for Dolby Vision files the scanner has not probed again since profiles were kept."`
	ProbeError  string       `json:"probeError,omitempty" doc:"Set when ffprobe could not read the file."`
	FirstSeenAt time.Time    `json:"firstSeenAt"`
	AddedAt     time.Time    `json:"addedAt" doc:"When the file was added: when a scan first found it, or its modified date if that was the library's first scan; a moved or renamed file keeps its date. Recently added sorts by it."`
	LastSeenAt  time.Time    `json:"lastSeenAt" doc:"When a scan last wrote this file's record (found, changed or back). Scans do not write to unchanged files."`

	MatchStatus  string `json:"matchStatus" enum:"pending,matched,unmatched,failed"`
	MatchError   string `json:"matchError,omitempty" doc:"Why the file could not be matched."`
	MatchLocked  bool   `json:"matchLocked" doc:"True when a person chose the match; automatic runs leave it alone."`
	MatchIgnored bool   `json:"matchIgnored" doc:"True when a person said the file is not library content."`
	MovieID      *int64 `json:"movieId,omitempty"`
	// Candidates are the best TMDB results considered, kept for unmatched files.
	Candidates []metadata.Candidate `json:"candidates,omitempty"`
}

// MediaFileDetail adds the full ffprobe result.
type MediaFileDetail struct {
	MediaFile
	Probe *probe.MediaInfo `json:"probe,omitempty"`
	// The first episode an episode file holds, so a client can name it.
	// Only GET /api/files/{id} fills these.
	EpisodeID     *int64 `json:"episodeId,omitempty"`
	ShowID        *int64 `json:"showId,omitempty"`
	SeasonNumber  *int64 `json:"seasonNumber,omitempty"`
	EpisodeNumber *int64 `json:"episodeNumber,omitempty"`
}

type MediaFileList struct {
	Items  []MediaFile `json:"items" nullable:"false"`
	Total  int64       `json:"total" doc:"Total files matching the filter."`
	Limit  int64       `json:"limit"`
	Offset int64       `json:"offset"`
}

type ListFilesInput struct {
	ID     int64  `path:"id" doc:"Library ID."`
	Status string `query:"status" enum:"present,missing" required:"false" doc:"Filter by status; omit for all."`
	Match  string `query:"match" enum:"pending,matched,unmatched,failed" required:"false" doc:"Filter by metadata match status; omit for all."`
	Limit  int64  `query:"limit" default:"100" minimum:"1" maximum:"500"`
	Offset int64  `query:"offset" default:"0" minimum:"0"`
}

type ListFilesOutput struct {
	Body MediaFileList
}

type FileIDInput struct {
	ID int64 `path:"id" doc:"Media file ID."`
}

type GetFileOutput struct {
	Body MediaFileDetail
}

type SearchCandidatesInput struct {
	ID    int64  `path:"id" doc:"Media file ID."`
	Query string `query:"q" required:"false" doc:"Title to search for; defaults to the parsed title."`
	Year  int    `query:"year" required:"false" minimum:"1800" doc:"Year to narrow the search; 0 for any."`
}

type CandidateList struct {
	Items []metadata.Candidate `json:"items" nullable:"false"`
}

type SearchCandidatesOutput struct {
	Body CandidateList
}

type ApplyMatchInput struct {
	ID   int64 `path:"id" doc:"Media file ID."`
	Body struct {
		TMDBID   int   `json:"tmdbId" minimum:"1" doc:"TMDB movie ID (movie libraries) or show ID (TV libraries)."`
		Season   *int  `json:"season,omitempty" doc:"Season number; defaults to what the file name says."`
		Episodes []int `json:"episodes,omitempty" doc:"Episode numbers; default to what the file name says."`
	}
}

func registerFiles(h huma.API, deps Deps) {
	huma.Register(h, huma.Operation{
		OperationID: "listLibraryFiles",
		Method:      http.MethodGet,
		Path:        Prefix + "/libraries/{id}/files",
		Summary:     "List the video files in a library",
		Tags:        []string{"files"},
	}, func(ctx context.Context, in *ListFilesInput) (*ListFilesOutput, error) {
		lib, err := getLibrary(ctx, deps, in.ID)
		if err != nil {
			return nil, err
		}
		var status, match any
		if in.Status != "" {
			status = in.Status
		}
		if in.Match != "" {
			match = in.Match
		}
		total, err := deps.Queries.CountMediaFiles(ctx, db.CountMediaFilesParams{LibraryID: lib.ID, Status: status, MatchStatus: match})
		if err != nil {
			return nil, err
		}
		rows, err := deps.Queries.ListMediaFiles(ctx, db.ListMediaFilesParams{LibraryID: lib.ID, Status: status, MatchStatus: match, Limit: in.Limit, Offset: in.Offset})
		if err != nil {
			return nil, err
		}
		out := &ListFilesOutput{}
		out.Body.Total, out.Body.Limit, out.Body.Offset = total, in.Limit, in.Offset
		out.Body.Items = make([]MediaFile, 0, len(rows))
		for _, r := range rows {
			out.Body.Items = append(out.Body.Items, mediaFileFromDB(r, lib.Path))
		}
		var fs []*MediaFile
		for i := range out.Body.Items {
			fs = append(fs, &out.Body.Items[i])
		}
		if err := decorateRecordingFiles(ctx, deps, fs); err != nil {
			return nil, err
		}
		return out, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "getFile",
		Method:      http.MethodGet,
		Path:        Prefix + "/files/{id}",
		Summary:     "Get a video file with its full probe result",
		Tags:        []string{"files"},
	}, func(ctx context.Context, in *FileIDInput) (*GetFileOutput, error) {
		row, lib, err := fileAndLibrary(ctx, deps, in.ID)
		if err != nil {
			return nil, err
		}
		out := &GetFileOutput{Body: MediaFileDetail{MediaFile: mediaFileFromDB(row, lib.Path)}}
		if row.Probe != nil {
			if info, err := probe.Parse(*row.Probe); err == nil {
				out.Body.Probe = info
			}
		}
		if row.MovieID == nil {
			if e, err := deps.Queries.GetFileEpisode(ctx, row.ID); err == nil {
				out.Body.EpisodeID, out.Body.ShowID = &e.EpisodeID, &e.ShowID
				out.Body.SeasonNumber, out.Body.EpisodeNumber = &e.SeasonNumber, &e.EpisodeNumber
			}
		}
		if err := decorateRecordingFiles(ctx, deps, []*MediaFile{&out.Body.MediaFile}); err != nil {
			return nil, err
		}
		return out, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "searchFileCandidates",
		Method:      http.MethodGet,
		Path:        Prefix + "/files/{id}/candidates",
		Summary:     "Search TMDB for possible matches for a file",
		Tags:        []string{"files"},
	}, func(ctx context.Context, in *SearchCandidatesInput) (*SearchCandidatesOutput, error) {
		row, lib, err := fileAndLibrary(ctx, deps, in.ID)
		if err != nil {
			return nil, err
		}
		if err := tmdbReady(deps); err != nil {
			return nil, err
		}
		query, year := in.Query, (*int)(nil)
		if in.Year > 0 {
			y := in.Year
			year = &y
		}
		if query == "" {
			g := mediaFileFromDB(row, lib.Path).Parsed
			query = g.Title
			if year == nil {
				year = g.Year
			}
		}
		return searchCandidates(ctx, deps, lib.Type, query, year)
	})

	huma.Register(h, huma.Operation{
		OperationID: "applyFileMatch",
		Method:      http.MethodPost,
		Path:        Prefix + "/files/{id}/match",
		Summary:     "Match a file to a TMDB item chosen by the user",
		Description: "Links the file to the given movie or show and locks the match so automatic matching leaves it alone.",
		Tags:        []string{"files"},
	}, func(ctx context.Context, in *ApplyMatchInput) (*GetFileOutput, error) {
		row, lib, err := fileAndLibrary(ctx, deps, in.ID)
		if err != nil {
			return nil, err
		}
		if row.ExtraKind != nil {
			return nil, huma.Error422UnprocessableEntity("this file is a trailer, which goes with its movie and isn't matched")
		}
		if err := tmdbReady(deps); err != nil {
			return nil, err
		}
		if err := deps.Matcher.Apply(ctx, lib, row, in.Body.TMDBID, in.Body.Season, in.Body.Episodes); err != nil {
			return nil, huma.Error422UnprocessableEntity(err.Error())
		}
		row, err = deps.Queries.GetMediaFile(ctx, in.ID)
		if err != nil {
			return nil, err
		}
		publishChanged(deps, lib)
		return &GetFileOutput{Body: MediaFileDetail{MediaFile: mediaFileFromDB(row, lib.Path)}}, nil
	})
}

func mediaFileFromDB(r db.MediaFile, libraryRoot string) MediaFile {
	f := MediaFile{
		ID:           r.ID,
		LibraryID:    r.LibraryID,
		Path:         r.Path,
		Size:         r.Size,
		ModifiedAt:   db.ParseTime(r.Mtime),
		Status:       r.Status,
		Container:    deref(r.Container),
		VideoCodec:   deref(r.VideoCodec),
		AudioCodec:   deref(r.AudioCodec),
		Width:        deref(r.Width),
		Height:       deref(r.Height),
		DurationMs:   deref(r.DurationMs),
		HDR:          r.Hdr,
		DVProfile:    deref(r.DvProfile),
		ProbeError:   deref(r.ProbeError),
		FirstSeenAt:  db.ParseTime(r.FirstSeenAt),
		AddedAt:      db.ParseTime(r.AddedAt),
		LastSeenAt:   db.ParseTime(r.LastSeenAt),
		MatchStatus:  r.MatchStatus,
		MatchError:   deref(r.MatchError),
		MatchLocked:  r.MatchLocked == 1,
		MatchIgnored: r.MatchIgnored == 1,
		MovieID:      r.MovieID,
	}
	if r.MatchCandidates != nil {
		_ = json.Unmarshal([]byte(*r.MatchCandidates), &f.Candidates)
	}
	if rel, err := filepath.Rel(libraryRoot, r.Path); err == nil {
		f.RelativePath = filepath.ToSlash(rel)
	} else {
		f.RelativePath = r.Path
	}
	if err := json.Unmarshal([]byte(r.Parsed), &f.Parsed); err != nil || f.Parsed.Kind == "" {
		f.Parsed = parser.Guess{Kind: parser.KindUnknown, Title: f.RelativePath}
	}
	return f
}

func deref[T any](p *T) T {
	if p == nil {
		var zero T
		return zero
	}
	return *p
}

// One indexed lookup decorates a response, regardless of how many files it
// contains. Browsing a season never makes a request or query per episode.
func decorateRecordingFiles(ctx context.Context, deps Deps, files []*MediaFile) error {
	if deps.Recordings == nil || len(files) == 0 {
		return nil
	}
	ids := make([]int64, 0, len(files))
	for _, file := range files {
		ids = append(ids, file.ID)
	}
	metadata, err := deps.Recordings.Metadata(ctx, ids)
	if err != nil {
		return err
	}
	for _, file := range files {
		file.Recording = metadata[file.ID]
	}
	return nil
}
