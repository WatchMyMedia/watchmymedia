package main

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"watchmymedia/server/internal/hwdevice"
)

func TestAdapterCommandReportsInventoryWithoutReadingServerConfiguration(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "must-not-be-created")
	t.Setenv("WATCHMYMEDIA_DATA_DIR", dir)
	t.Setenv("WATCHMYMEDIA_CONFIG", filepath.Join(t.TempDir(), "missing-config.yaml"))
	var out bytes.Buffer
	scan := func(context.Context) hwdevice.Inventory {
		return hwdevice.Inventory{Supported: true, Adapters: []hwdevice.Adapter{{Namespace: "cuda", Index: 0, Name: "Fictional GPU"}}, Diagnostics: []hwdevice.EnumerationDiagnostic{}}
	}
	if err := gpuAdaptersCommand(t.Context(), nil, &out, scan); err != nil {
		t.Fatal(err)
	}
	var i hwdevice.Inventory
	if err := json.Unmarshal(out.Bytes(), &i); err != nil || !i.Supported || len(i.Adapters) != 1 || i.Adapters[0].Index != 0 {
		t.Fatalf("lost inventory: %s %v", out.Bytes(), err)
	}
	if _, err := os.Stat(dir); !os.IsNotExist(err) {
		t.Fatal("adapter command created a data directory")
	}
	for _, args := range [][]string{{"unexpected"}, {"--native", "unknown"}} {
		if err := gpuAdaptersCommand(t.Context(), args, &out, nil); err == nil {
			t.Fatal("accepted invalid adapter command arguments")
		}
	}
}
