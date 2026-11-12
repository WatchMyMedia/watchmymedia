package api

import (
	"context"
	"database/sql"
	"errors"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/db"
)

// notFound is a 404 with msg when err says there is no such row. Any other
// error passes through, to be logged and answered with a bare 500, rather
// than telling the client that something which exists is missing.
func notFound(err error, msg string) error {
	if errors.Is(err, sql.ErrNoRows) {
		return huma.Error404NotFound(msg)
	}
	return err
}

// getLibrary is the library with id, or a 404.
func getLibrary(ctx context.Context, deps Deps, id int64) (db.Library, error) {
	lib, err := deps.Queries.GetLibrary(ctx, id)
	return lib, notFound(err, "library not found")
}

// fileAndLibrary is the file with id and its library, or a 404.
func fileAndLibrary(ctx context.Context, deps Deps, id int64) (db.MediaFile, db.Library, error) {
	f, err := deps.Queries.GetMediaFile(ctx, id)
	if err != nil {
		return f, db.Library{}, notFound(err, "file not found")
	}
	lib, err := deps.Queries.GetLibrary(ctx, f.LibraryID)
	return f, lib, err
}
