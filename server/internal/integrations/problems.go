package integrations

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"path/filepath"
	"slices"
	"strings"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
)

// Problem is a report of something wrong with a movie or episode (§9).
type Problem struct {
	ID         int64     `json:"id"`
	ReportedBy string    `json:"reportedBy"`
	ItemType   string    `json:"itemType" enum:"movie,episode"`
	ItemID     int64     `json:"itemId"`
	FileID     int64     `json:"fileId,omitempty"`
	Kind       string    `json:"kind" enum:"video,audio,subtitles,other"`
	Note       string    `json:"note"`
	Details    string    `json:"details" doc:"What the server knew about the file: its name, codecs and resolution."`
	Title      string    `json:"title" doc:"The movie, or the show and episode."`
	ShowID     int64     `json:"showId,omitempty"`
	Season     int64     `json:"season,omitempty"`
	CreatedAt  time.Time `json:"createdAt"`
	// SeerrIssueID is set when the report became a Seerr issue; it is not
	// in the Inbox then, and ID is 0.
	SeerrIssueID int64 `json:"seerrIssueId,omitempty" required:"false" doc:"The Seerr issue the report became; it is not in the Inbox then, and id is 0."`
}

// ProblemsChanged is sent to admins when a problem is reported or resolved.
type ProblemsChanged struct {
	ProblemID int64 `json:"problemId"`
}

// Report records a problem with a movie or episode, and the file it played,
// for the admins' Inbox. With Seerr connected it becomes a Seerr issue
// instead, when Seerr can take it.
func (s *Service) Report(ctx context.Context, p Person, itemType string, itemID, fileID int64, kind, note string) (Problem, error) {
	var file *int64
	details := ""
	if fileID > 0 {
		f, err := s.q.GetMediaFile(ctx, fileID)
		if err != nil {
			return Problem{}, invalid("no such file")
		}
		if !s.fileOf(ctx, f, itemType, itemID) {
			return Problem{}, invalid("that file is not this item's")
		}
		file, details = &fileID, describe(f)
	}
	if _, err := s.titleOf(ctx, itemType, itemID); err != nil {
		return Problem{}, invalid("no such %s", itemType)
	}
	if c := s.seerrConn(); c != nil {
		if pr, ok := s.reportSeerr(ctx, c, p, itemType, itemID, kind, note, details); ok {
			return pr, nil
		}
	}
	row, err := s.q.CreateProblem(ctx, db.CreateProblemParams{UserID: p.ID, ItemType: itemType, ItemID: itemID,
		MediaFileID: file, Kind: kind, Note: strings.TrimSpace(note), Details: details})
	if err != nil {
		return Problem{}, err
	}
	s.announceProblem(row.ID)
	list, err := s.Problems(ctx)
	if err != nil {
		return Problem{}, err
	}
	for _, pr := range list {
		if pr.ID == row.ID {
			return pr, nil
		}
	}
	return Problem{ID: row.ID}, nil
}

// fileOf reports whether f is a file of the movie or episode.
func (s *Service) fileOf(ctx context.Context, f db.MediaFile, itemType string, itemID int64) bool {
	if itemType == "movie" {
		return f.MovieID != nil && *f.MovieID == itemID
	}
	eps, err := s.q.ListEpisodeIDsForFile(ctx, f.ID)
	if err != nil {
		return false
	}
	return slices.Contains(eps, itemID)
}

func describe(f db.MediaFile) string {
	parts := []string{filepath.Base(f.Path)}
	if f.VideoCodec != nil {
		parts = append(parts, strings.ToUpper(*f.VideoCodec))
	}
	if f.AudioCodec != nil {
		parts = append(parts, strings.ToUpper(*f.AudioCodec))
	}
	if f.Height != nil && *f.Height > 0 {
		parts = append(parts, fmt.Sprintf("%dp", *f.Height))
	}
	return strings.Join(parts, " · ")
}

type itemTitle struct {
	title          string
	showID, season int64
}

func (s *Service) titleOf(ctx context.Context, itemType string, itemID int64) (itemTitle, error) {
	if itemType == "movie" {
		m, err := s.q.GetMovie(ctx, itemID)
		if err != nil {
			return itemTitle{}, err
		}
		return itemTitle{title: m.Title}, nil
	}
	e, err := s.q.GetEpisode(ctx, itemID)
	if err != nil {
		return itemTitle{}, err
	}
	sh, err := s.q.GetShow(ctx, e.ShowID)
	if err != nil {
		return itemTitle{}, err
	}
	se, err := s.q.GetSeason(ctx, e.SeasonID)
	if err != nil {
		return itemTitle{}, err
	}
	t := fmt.Sprintf("%s · S%02dE%02d", sh.Title, se.Number, e.Number)
	if e.Title != "" {
		t += " · " + e.Title
	}
	return itemTitle{title: t, showID: sh.ID, season: se.Number}, nil
}

// Problems lists the open problems, oldest first.
func (s *Service) Problems(ctx context.Context) ([]Problem, error) {
	rows, err := s.q.ListOpenProblems(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]Problem, 0, len(rows))
	for _, r := range rows {
		t, err := s.titleOf(ctx, r.ItemType, r.ItemID)
		if err != nil {
			t.title = "(no longer in the library)"
		}
		p := Problem{ID: r.ID, ReportedBy: r.UserName, ItemType: r.ItemType, ItemID: r.ItemID, Kind: r.Kind, Note: r.Note,
			Details: r.Details, Title: t.title, ShowID: t.showID, Season: t.season}
		if r.MediaFileID != nil {
			p.FileID = *r.MediaFileID
		}
		p.CreatedAt, _ = time.Parse(time.RFC3339Nano, r.CreatedAt)
		out = append(out, p)
	}
	return out, nil
}

// Resolve closes a problem.
func (s *Service) Resolve(ctx context.Context, admin Person, id int64) error {
	n, err := s.q.ResolveProblem(ctx, db.ResolveProblemParams{ResolvedBy: &admin.ID, ID: id})
	if errors.Is(err, sql.ErrNoRows) || (err == nil && n == 0) {
		return errNotFound
	}
	if err == nil {
		s.announceProblem(id)
	}
	return err
}

// OpenProblems is how many problems are open.
func (s *Service) OpenProblems(ctx context.Context) int64 {
	n, _ := s.q.CountOpenProblems(ctx)
	return n
}

func (s *Service) announceProblem(id int64) {
	s.mu.RLock()
	bus := s.bus
	s.mu.RUnlock()
	if bus != nil {
		// No user: Audience with UserID 0 reaches admins only.
		bus.Publish(events.Event{Type: "problems.changed", Data: ProblemsChanged{ProblemID: id}, To: &events.Audience{}})
	}
}
