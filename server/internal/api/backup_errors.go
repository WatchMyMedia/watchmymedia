package api

import (
	"errors"
	"io/fs"
	"net/http"
	"syscall"

	"github.com/danielgtaylor/huma/v2"
	"watchmymedia/server/internal/db"
)

// backupError says in words why a backup could not be written into the data
// folder when the reason is the disk: out of space (507), or a folder the
// server may not write to (500). It is nil for any other error, which is
// passed on as it is.
func backupError(err error) error {
	switch {
	case err == nil:
		return nil
	case db.IsDiskFull(err) || errors.Is(err, syscall.ENOSPC) || diskFull(err):
		return huma.NewError(http.StatusInsufficientStorage, "not enough space in the data folder for a backup")
	case errors.Is(err, fs.ErrPermission) || errors.Is(err, syscall.EROFS):
		// fs.ErrPermission covers EACCES and EPERM, and access denied on Windows.
		return huma.Error500InternalServerError("the server can't write to the data folder")
	}
	return nil
}
