package api

import (
	"database/sql"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"path/filepath"
	"testing"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/db"
)

func TestNotFound(t *testing.T) {
	t.Parallel()
	var se huma.StatusError
	if err := notFound(fmt.Errorf("get: %w", sql.ErrNoRows), "movie not found"); !errors.As(err, &se) || se.GetStatus() != http.StatusNotFound {
		t.Errorf("no rows -> %v, want a 404", err)
	}
	boom := errors.New("disk I/O error")
	if err := notFound(boom, "movie not found"); !errors.Is(err, boom) {
		t.Errorf("other error -> %v, want it passed through", err)
	}
	if notFound(nil, "movie not found") != nil {
		t.Error("nil -> an error")
	}
}

// A database failure looking up a library is not a missing library.
func TestLookupsPassDatabaseErrorsThrough(t *testing.T) {
	t.Parallel()
	conn, err := db.Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	if err := db.Migrate(t.Context(), conn); err != nil {
		t.Fatal(err)
	}
	deps := Deps{Queries: db.New(conn)}
	var se huma.StatusError
	if _, err := getLibrary(t.Context(), deps, 7); !errors.As(err, &se) || se.GetStatus() != http.StatusNotFound {
		t.Errorf("missing library -> %v, want a 404", err)
	}
	if _, _, err := fileAndLibrary(t.Context(), deps, 7); !errors.As(err, &se) || se.GetStatus() != http.StatusNotFound {
		t.Errorf("missing file -> %v, want a 404", err)
	}
	_ = conn.Close()
	if _, err := getLibrary(t.Context(), deps, 7); err == nil || errors.As(err, &se) {
		t.Errorf("closed database -> %v, want its error", err)
	}
	if _, _, err := fileAndLibrary(t.Context(), deps, 7); err == nil || errors.As(err, &se) {
		t.Errorf("closed database -> %v, want its error", err)
	}
}

// statusOf is the HTTP status an error answers with: 500 for one that is
// not a huma status, 0 for nil.
func statusOf(err error) int {
	var se huma.StatusError
	switch {
	case err == nil:
		return 0
	case errors.As(err, &se):
		return se.GetStatus()
	}
	return http.StatusInternalServerError
}

func discardLog() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }
