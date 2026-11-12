package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"runtime"
	"slices"
	"time"

	"watchmymedia/server/internal/config"
	"watchmymedia/server/internal/hwdevice"
	"watchmymedia/server/internal/playback"
)

// probeCommand only uses synthetic temporary media. It does not load the
// library database/configuration, listen on a port, or contact a tuner/server.
func probeCommand(ctx context.Context, args []string, out, stderr io.Writer, scan hwdevice.Scanner) error {
	flags := flag.NewFlagSet("probe-transcoder", flag.ContinueOnError)
	flags.SetOutput(stderr)
	ffmpeg := flags.String("ffmpeg", config.ResolveTool("ffmpeg"), "FFmpeg binary to test (bundled binary preferred)")
	hw := flags.String("hwaccel", "auto", "auto, software, videotoolbox, nvenc, qsv, vaapi or amf")
	device := flags.Int("device", -1, "backend-specific adapter index; -1 uses its default")
	if err := flags.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return nil
		}
		return err
	}
	if *ffmpeg == "" {
		return errors.New("FFmpeg path must not be empty")
	}
	if flags.NArg() != 0 {
		return errors.New("probe-transcoder takes flags only")
	}
	switch *hw {
	case "auto", "software", "videotoolbox", "nvenc", "qsv", "vaapi", "amf":
	default:
		return fmt.Errorf("unknown hardware backend %q", *hw)
	}
	if err := hwdevice.Validate(*hw, device); err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(ctx, 2*time.Minute)
	defer cancel()
	var inventory *hwdevice.Inventory
	if scan != nil {
		i := scan(ctx)
		inventory = &i
	}
	caps := playback.DetectOnDevice(ctx, *ffmpeg, *hw, hwdevice.Index(device), slog.New(slog.NewTextHandler(stderr, nil)))
	report := struct {
		Version          string                        `json:"version"`
		Platform         string                        `json:"platform"`
		Preference       string                        `json:"preference"`
		Device           *int                          `json:"requestedDevice,omitempty"`
		Capabilities     playback.Capabilities         `json:"capabilities"`
		Diagnostics      []playback.HardwareDiagnostic `json:"diagnostics"`
		HardwareAdapters *hwdevice.Inventory           `json:"hardwareAdapters,omitempty"`
	}{version, runtime.GOOS + "/" + runtime.GOARCH, *hw, hwdevice.Index(device), caps, caps.Diagnostics, inventory}
	encoder := json.NewEncoder(out)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(report); err != nil {
		return err
	}
	if ctx.Err() != nil {
		return ctx.Err()
	}
	if !slices.Contains(caps.Encoders, caps.Encoder) {
		return errors.New("no usable H.264 encoder; inspect the probe diagnostics")
	}
	return nil
}
