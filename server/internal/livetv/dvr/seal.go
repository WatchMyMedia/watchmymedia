package dvr

import (
	"os"

	"watchmymedia/server/internal/playback"
)

type outputSeal struct {
	file *os.File
	info os.FileInfo
}

func (s *outputSeal) Close() error { return s.file.Close() }

// sealOutput anchors ownership to the writer's file, then measures its final
// timestamp only after the write handle closes. Windows does not guarantee
// last-write timestamps before that close. The read handle permits deletion
// and rename, and stays held across publication without adopting a pathname.
func sealOutput(writer *os.File, path string) (*outputSeal, error) {
	info, err := writer.Stat()
	if err != nil {
		_ = writer.Close()
		return nil, err
	}
	held, err := playback.OpenShared(path)
	if err != nil {
		_ = writer.Close()
		return nil, err
	}
	current, err := held.Stat()
	if err == nil && (!current.Mode().IsRegular() || !os.SameFile(info, current)) {
		err = ErrInvalid
	}
	if err != nil {
		_ = writer.Close()
		_ = held.Close()
		return nil, err
	}
	if err = writer.Sync(); err != nil {
		_ = writer.Close()
		_ = held.Close()
		return nil, err
	}
	if err = writer.Close(); err != nil {
		_ = held.Close()
		return nil, err
	}
	final, err := held.Stat()
	if err == nil && (!os.SameFile(info, final) || final.Size() != info.Size()) {
		err = ErrInvalid
	}
	if err != nil {
		_ = held.Close()
		return nil, err
	}
	return &outputSeal{file: held, info: final}, nil
}
