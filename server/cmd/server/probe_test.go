package main

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"watchmymedia/server/internal/hwdevice"
)

func TestProbeRejectsAnAmbiguousDeviceBeforeRunningFFmpeg(t *testing.T) {
	var out, stderr bytes.Buffer
	if err := probeCommand(t.Context(), []string{"-hwaccel", "auto", "-device", "0", "-ffmpeg", "not-installed"}, &out, &stderr, nil); err == nil || out.Len() != 0 {
		t.Fatal("ambiguous device was accepted")
	}
}

func TestProbeReportsMissingFFmpegWithoutOpeningADataDirectory(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "must-not-be-created")
	t.Setenv("WATCHMYMEDIA_DATA_DIR", dir)
	var out, stderr bytes.Buffer
	scan := func(context.Context) hwdevice.Inventory {
		return hwdevice.Inventory{Supported: true, Adapters: []hwdevice.Adapter{{Namespace: "directx", Index: 2, Name: "Fictional GPU"}}}
	}
	if err := probeCommand(context.Background(), []string{"-hwaccel", "software", "-ffmpeg", "watchmymedia-test-missing-ffmpeg"}, &out, &stderr, scan); err == nil {
		t.Fatal("missing FFmpeg was reported as healthy")
	}
	if _, err := os.Stat(dir); !os.IsNotExist(err) {
		t.Fatal("probe created the configured data directory")
	}
	var report map[string]any
	if err := json.Unmarshal(out.Bytes(), &report); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out.String(), "diagnostics") || !strings.Contains(out.String(), "encoders") {
		t.Fatal("missing executable diagnostics were lost")
	}
	if !strings.Contains(out.String(), "Fictional GPU") {
		t.Fatal("standalone probe lost adapter inventory")
	}
}
