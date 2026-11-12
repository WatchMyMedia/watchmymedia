// Package trickplay makes seek thumbnails in the background
// (PLAN-TRICKPLAY.md): for every file, JPEG sheets of small tiles, one tile
// every few seconds, which players show above the scrubber while someone
// seeks. They are kept under <data>/trickplay, not in backups, as they can
// always be made again.
package trickplay

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"path/filepath"
	"strconv"
	"strings"
)

// AlgoVersion changes whenever the tiles change (their size, interval,
// quality or tone mapping). Raising it makes every file's thumbnails stale,
// and the job makes them again, newest files first.
const AlgoVersion = 1

// Layout is how a file's tiles are made and laid out on sheets.
type Layout struct {
	IntervalMs int64 // one tile per interval
	Width      int   // a tile's width; its height follows the picture
	Columns    int   // tiles across a sheet
	Rows       int   // tiles down a sheet
	Quality    int   // ffmpeg's JPEG -q:v, 2 (best) to 31
}

// DefaultLayout is 320-pixel tiles every 10 s, 10 × 10 to a sheet.
var DefaultLayout = Layout{IntervalMs: 10_000, Width: 320, Columns: 10, Rows: 10, Quality: 5}

// PerSheet is the tiles a full sheet holds.
func (l Layout) PerSheet() int { return l.Columns * l.Rows }

// Tiles is how many tiles a file of durationMs has: tile i covers
// [i × IntervalMs, (i+1) × IntervalMs).
func (l Layout) Tiles(durationMs int64) int {
	if durationMs <= 0 {
		return 0
	}
	return int((durationMs + l.IntervalMs - 1) / l.IntervalMs)
}

// Sheets is how many sheets hold tiles; the last is partly filled.
func (l Layout) Sheets(tiles int) int {
	return (tiles + l.PerSheet() - 1) / l.PerSheet()
}

// SheetStartMs is where a sheet's first tile is in the file.
func (l Layout) SheetStartMs(sheet int) int64 {
	return int64(sheet) * int64(l.PerSheet()) * l.IntervalMs
}

// TilesIn is how many of a file's tiles sheet holds.
func (l Layout) TilesIn(sheet, tiles int) int {
	return max(0, min(l.PerSheet(), tiles-sheet*l.PerSheet()))
}

// Tile is where the tile for a position is: its sheet, and its top-left
// corner on the sheet for tiles of width × height. A client does the same
// with the manifest (playback.Trickplay).
func (l Layout) Tile(positionMs int64, tiles, width, height int) (sheet, x, y int) {
	i := int(max(positionMs, 0) / l.IntervalMs)
	i = min(i, max(tiles-1, 0))
	cell := i % l.PerSheet()
	return i / l.PerSheet(), (cell % l.Columns) * width, (cell / l.Columns) * height
}

// How a file's HDR was mapped to SDR for its tiles (the row's tone_map).
const (
	ToneSDR      = ""         // not HDR: nothing to map
	TonePlain    = "plain"    // HDR, but ffmpeg has no zscale: converted as it is, washed out
	ToneZscale   = "zscale"   // the transcoder's CPU chain (playback.ToneMapFilters)
	ToneDVBase   = "dvbase"   // Dolby Vision 5 read as its base layer, tagged HDR10: the colours are off
	ToneTonemapx = "tonemapx" // reshaped through the RPU (playback.DoviToneMapFilter)
)

// tones is every tone mapping a version can be made with.
var tones = []string{ToneSDR, TonePlain, ToneZscale, ToneDVBase, ToneTonemapx}

// Version names one version of a file's thumbnails: a short hash of the
// file's size and modification time, as the keyframe cache keys files, the
// algorithm version, and how HDR was mapped. It names the folder and
// appears in sheet URLs, so thumbnails made again, for a changed file or
// with better tone mapping, have new URLs, and sheets can be cached for
// good.
func Version(size int64, mtime, tone string) string {
	sum := sha256.Sum256(fmt.Appendf(nil, "%d\x00%s\x00%d\x00%s", size, mtime, AlgoVersion, tone))
	return hex.EncodeToString(sum[:4])
}

// ValidVersion reports whether v looks like a Version, so a request's
// version can go into a path.
func ValidVersion(v string) bool {
	if len(v) != 8 {
		return false
	}
	_, err := hex.DecodeString(v)
	return err == nil
}

// SheetName is a sheet's file name in its folder.
func SheetName(sheet int) string { return fmt.Sprintf("%03d.jpg", sheet) }

const (
	manifestName = "manifest.json"
	tmpName      = "tmp"
)

// folderName is a version's folder: "<file id>-<version>".
func folderName(fileID int64, version string) string {
	return strconv.FormatInt(fileID, 10) + "-" + version
}

// parseFolder reads a folderName.
func parseFolder(name string) (int64, string, bool) {
	id, version, ok := strings.Cut(name, "-")
	if !ok || !ValidVersion(version) {
		return 0, "", false
	}
	n, err := strconv.ParseInt(id, 10, 64)
	return n, version, err == nil && n > 0
}

// shard spreads files over 256 folders, by ID.
func shard(fileID int64) string { return fmt.Sprintf("%02x", fileID%256) }

// FileDir is where a version of a file's finished thumbnails are.
func FileDir(root string, fileID int64, version string) string {
	return filepath.Join(root, shard(fileID), folderName(fileID, version))
}

// tmpDir is where a version's sheets are made, until all are there.
func tmpDir(root string, fileID int64, version string) string {
	return filepath.Join(root, tmpName, folderName(fileID, version))
}
