package trickplay

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strings"

	"watchmymedia/server/internal/ffexec"
	"watchmymedia/server/internal/playback"
)

// sheetArgs is the ffmpeg command line for one sheet (PLAN-TRICKPLAY.md
// §3.2). Only keyframes are decoded. The input seek lands on the keyframe
// at or before the sheet's start (-noaccurate_seek keeps it), and fps with
// round=up makes tile i the last keyframe at or before its time, repeating
// one where a GOP is longer than the interval. HDR is mapped to SDR as the
// transcoder does (s.tone): Dolby Vision 5 through its RPU with tonemapx
// before scaling, on the frames the RPU was written for; otherwise with
// zscale after scaling, which is slow on large frames, Dolby Vision 5's
// base layer tagged as HDR10 first so that zscale runs on it. The
// sheet then gets exactly its tiles: the last one repeated where the file
// ends before fps made them all (the last keyframe can be a GOP before the
// end), cut to the count, and black after them to fill the grid, which
// tile needs to finish a sheet (it leaves unused cells undrawn otherwise).
// Black costs next to nothing; repeated tiles would cost 5–7 KB each.
func (g *Generator) sheetArgs(s sheetJob) []string {
	l := g.layout
	a := []string{"-threads", "2", "-skip_frame", "nokey"}
	if start := l.SheetStartMs(s.sheet); start > 0 {
		a = append(a, "-noaccurate_seek", "-ss", ffexec.Seconds(start))
	}
	a = append(a, "-i", s.path, "-map", "0:v:0", "-an", "-sn", "-dn")
	vf := []string{fmt.Sprintf("fps=fps=1000/%d:start_time=0:round=up", l.IntervalMs)}
	if s.tone == ToneTonemapx {
		vf = append(vf, playback.DoviToneMapFilter)
	}
	vf = append(vf, fmt.Sprintf("scale=%d:trunc(ow/dar/2)*2", l.Width), "setsar=1")
	switch s.tone {
	case ToneDVBase:
		vf = append(vf, playback.DVBaseAsHDR10Filter)
		fallthrough
	case ToneZscale:
		vf = append(vf, playback.ToneMapFilters()...)
		vf = append(vf, "format=yuv420p")
	}
	vf = append(vf,
		fmt.Sprintf("tpad=stop=%d:stop_mode=clone", s.tiles),
		fmt.Sprintf("trim=end_frame=%d", s.tiles),
		fmt.Sprintf("tpad=stop=%d:stop_mode=add:color=black", l.PerSheet()),
		fmt.Sprintf("tile=%dx%d", l.Columns, l.Rows))
	a = append(a, "-vf", strings.Join(vf, ","), "-frames:v", "1", "-q:v", fmt.Sprint(l.Quality),
		"-f", "image2", "-update", "1", "-y", s.out+".part")
	return ffexec.FFmpegArgs(a...)
}

// ffmpegSheet makes one sheet at low priority, written under another name
// and renamed when complete, so a run stopped halfway leaves no sheet that
// looks made. ErrNoDecoder means ffmpeg could not run at all.
func (g *Generator) ffmpegSheet(ctx context.Context, s sheetJob) error {
	ctx, cancel := context.WithTimeout(ctx, sheetTimeout)
	defer cancel()
	part := s.out + ".part"
	defer func() { _ = os.Remove(part) }()
	cmd := ffexec.Command(ctx, g.ffmpeg, g.sheetArgs(s)...)
	cmd.LowPriority = true
	if err := cmd.Run(); err != nil {
		if ctx.Err() != nil {
			if errors.Is(ctx.Err(), context.DeadlineExceeded) {
				return fmt.Errorf("sheet %d took over %s", s.sheet, sheetTimeout)
			}
			return ctx.Err()
		}
		msg := cmd.Tail()
		if !ffexec.RanOnFile(err, msg) {
			return fmt.Errorf("%w: %w: %s", ErrNoDecoder, err, firstLine(msg))
		}
		if exit, ok := errors.AsType[*ffexec.ExitError](err); ok {
			err = exit.Err
		}
		if line := firstLine(msg); line != "" {
			return fmt.Errorf("ffmpeg: %s", line)
		}
		return fmt.Errorf("ffmpeg: %w", err)
	}
	if st, err := os.Stat(part); err != nil || st.Size() == 0 {
		return fmt.Errorf("ffmpeg made no picture for sheet %d", s.sheet)
	}
	return os.Rename(part, s.out)
}
