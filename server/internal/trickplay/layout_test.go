package trickplay

import (
	"path/filepath"
	"testing"
)

func TestLayoutMaths(t *testing.T) {
	t.Parallel()
	l := DefaultLayout
	for _, c := range []struct {
		durationMs    int64
		tiles, sheets int
		last          int // tiles on the last sheet
	}{
		{0, 0, 0, 0},
		{1, 1, 1, 1},
		{10_000, 1, 1, 1},
		{10_001, 2, 1, 2},
		{60_000, 6, 1, 6},
		{45 * 60_000, 270, 3, 70},
		{1_000_000, 100, 1, 100},
		{1_000_001, 101, 2, 1},
		{7_235_000, 724, 8, 24}, // the plan's example: a 2-hour film
	} {
		tiles := l.Tiles(c.durationMs)
		sheets := l.Sheets(tiles)
		if tiles != c.tiles || sheets != c.sheets {
			t.Errorf("%d ms: %d tiles on %d sheets, want %d on %d", c.durationMs, tiles, sheets, c.tiles, c.sheets)
			continue
		}
		if sheets > 0 {
			if got := l.TilesIn(sheets-1, tiles); got != c.last {
				t.Errorf("%d ms: last sheet holds %d tiles, want %d", c.durationMs, got, c.last)
			}
			if got := l.TilesIn(sheets, tiles); got != 0 {
				t.Errorf("%d ms: a sheet past the last holds %d tiles", c.durationMs, got)
			}
		}
	}
	if got := l.SheetStartMs(3); got != 3_000_000 {
		t.Errorf("sheet 3 starts at %d ms", got)
	}
}

// A position maps to the tile whose interval holds it: its sheet and its
// corner on the sheet, counted along the rows, as clients do with the
// manifest.
func TestTileForPosition(t *testing.T) {
	t.Parallel()
	l := DefaultLayout
	const tiles, w, h = 724, 320, 180
	for _, c := range []struct {
		posMs       int64
		sheet, x, y int
		comment     string
	}{
		{0, 0, 0, 0, "start"},
		{9_999, 0, 0, 0, "still the first tile"},
		{10_000, 0, 320, 0, "second tile"},
		{95_000, 0, 9 * 320, 0, "end of the first row"},
		{100_000, 0, 0, 180, "second row"},
		{999_999, 0, 9 * 320, 9 * 180, "last tile of the first sheet"},
		{1_000_000, 1, 0, 0, "first tile of the second sheet"},
		{7_235_000, 7, 3 * 320, 2 * 180, "the last tile"},
		{9_000_000, 7, 3 * 320, 2 * 180, "past the end: the last tile"},
		{-5, 0, 0, 0, "before the start"},
	} {
		sheet, x, y := l.Tile(c.posMs, tiles, w, h)
		if sheet != c.sheet || x != c.x || y != c.y {
			t.Errorf("%s (%d ms): sheet %d at %d,%d, want %d at %d,%d", c.comment, c.posMs, sheet, x, y, c.sheet, c.x, c.y)
		}
	}
}

func TestVersionAndFolders(t *testing.T) {
	t.Parallel()
	v := Version(1234, "2026-11-01T10:00:00Z", ToneSDR)
	if !ValidVersion(v) || v != Version(1234, "2026-11-01T10:00:00Z", ToneSDR) {
		t.Fatalf("version %q is not stable or not valid", v)
	}
	for _, other := range []string{Version(1235, "2026-11-01T10:00:00Z", ToneSDR), Version(1234, "2026-11-01T10:00:01Z", ToneSDR),
		Version(1234, "2026-11-01T10:00:00Z", ToneZscale)} {
		if other == v {
			t.Errorf("a changed file, or other tone mapping, kept version %q", v)
		}
	}
	for _, bad := range []string{"", "9c1e07a", "9c1e07a2a", "zzzzzzzz", "../../x"} {
		if ValidVersion(bad) {
			t.Errorf("%q passes as a version", bad)
		}
	}
	dir := FileDir("/data/trickplay", 300, v)
	if want := filepath.Join("/data/trickplay", "2c", "300-"+v); dir != want {
		t.Errorf("folder %s, want %s", dir, want)
	}
	if id, got, ok := parseFolder(filepath.Base(dir)); !ok || id != 300 || got != v {
		t.Errorf("parseFolder(%s) = %d %s %v", filepath.Base(dir), id, got, ok)
	}
	for _, bad := range []string{"300", "x-" + v, "0-" + v, "300-nothex!"} {
		if _, _, ok := parseFolder(bad); ok {
			t.Errorf("parseFolder(%q) accepted", bad)
		}
	}
	if SheetName(7) != "007.jpg" || SheetName(1234) != "1234.jpg" {
		t.Errorf("sheet names %s %s", SheetName(7), SheetName(1234))
	}
}
