package api

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"slices"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/hwdevice"
	"watchmymedia/server/internal/playback"
)

func TestHardwarePreferenceAPI(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	var st Settings
	put := func(body string, want int) {
		t.Helper()
		res := doJSON(t, srv.client, http.MethodPut, srv.URL+"/api/settings", body, &st)
		if res.StatusCode != want {
			t.Fatalf("%s -> %d, want %d", body, res.StatusCode, want)
		}
	}
	put(`{"hwaccel":"nvenc","hwDevice":0}`, http.StatusOK)
	if st.HWAccel != "nvenc" || st.HWDevice == nil || *st.HWDevice != 0 {
		t.Fatalf("adapter zero was lost: %+v", st)
	}
	put(`{"hwaccel":"auto","hwDevice":0}`, http.StatusUnprocessableEntity)
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/settings", "", &st)
	if st.HWAccel != "nvenc" || st.HWDevice == nil || *st.HWDevice != 0 {
		t.Fatal("invalid pair changed the saved preference")
	}
	put(`{"hwaccel":"software"}`, http.StatusOK)
	// Decode into a fresh value: omitted optional fields do not overwrite old Go pointers.
	st = Settings{}
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/settings", "", &st)
	if st.HWAccel != "software" || st.HWDevice != nil {
		t.Fatalf("backend change retained an adapter: %+v", st)
	}
	put(`{"hwaccel":"nvenc","hwDevice":-1}`, http.StatusOK)
	put(`{"hwDevice":-2}`, http.StatusUnprocessableEntity)
	res := doJSON(t, http.DefaultClient, http.MethodPut, srv.URL+"/api/settings", `{"hwaccel":"nvenc","hwDevice":0}`, nil)
	if res.StatusCode != http.StatusUnauthorized {
		t.Fatalf("anonymous adapter change -> %d", res.StatusCode)
	}
}

func TestHardwareInventoryIsOptionalAndAdminOnly(t *testing.T) {
	t.Parallel()
	const canary = "fictional-private-adapter"
	srv := newTestServerWith(t, func(d *Deps) {
		d.Playback = playback.NewManager("watchmymedia-test-missing-ffmpeg", t.TempDir(), playback.Capabilities{}, time.Minute, 1, slog.New(slog.DiscardHandler))
		d.Playback.SetAdapterScanner(func(context.Context) hwdevice.Inventory {
			return hwdevice.Inventory{Supported: true, Adapters: []hwdevice.Adapter{{Namespace: "cuda", Index: 0, Name: canary}}, Diagnostics: []hwdevice.EnumerationDiagnostic{{Namespace: "cuda", OK: true}}}
		})
		d.Playback.DetectInBackground("software", "")
		d.Playback.Capabilities()
	})
	var st Settings
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/settings", "", &st)
	if st.HardwareAdapters == nil || len(st.HardwareAdapters.Adapters) != 1 || st.HardwareAdapters.Adapters[0].Name != canary {
		t.Fatalf("admin inventory missing: %+v", st.HardwareAdapters)
	}
	if res := doJSON(t, http.DefaultClient, http.MethodGet, srv.URL+"/api/settings", "", nil); res.StatusCode != http.StatusUnauthorized {
		t.Fatal("anonymous inventory access was accepted")
	}
	var public map[string]any
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/playback/capabilities", "", &public)
	b, err := json.Marshal(public)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(b), canary) || strings.Contains(string(b), "hardwareAdapters") {
		t.Fatal("adapter inventory leaked through playback capabilities")
	}
	var doc struct {
		Components struct {
			Schemas map[string]struct {
				Required   []string
				Properties map[string]json.RawMessage
			}
		}
	}
	doJSON(t, srv.client, http.MethodGet, srv.URL+OpenAPIPath+".json", "", &doc)
	schema, exists := doc.Components.Schemas["Settings"]
	if !exists || len(schema.Properties["hardwareAdapters"]) == 0 {
		t.Fatal("missing inventory contract")
	}
	if slices.Contains(schema.Required, "hardwareAdapters") {
		t.Fatal("inventory is required on older-server replies")
	}
}

func TestHardwareDiagnosticsAreAdminOnly(t *testing.T) {
	t.Parallel()
	const detail = "synthetic-driver-diagnostic-canary"
	c := playback.Capabilities{Encoder: "libx264", HWAccel: "software", Encoders: []string{"libx264"}, MaxHeight: 1080, Diagnostics: []playback.HardwareDiagnostic{{Encoder: "h264_nvenc", Stage: "encode", Detail: detail}}}
	srv := newTestServerWith(t, func(d *Deps) {
		d.Playback = playback.NewManager("unused", t.TempDir(), c, time.Minute, 1, slog.New(slog.DiscardHandler))
	})
	var st Settings
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/settings", "", &st)
	if len(st.HardwareDiagnostics) != 1 || st.HardwareDiagnostics[0].Detail != detail {
		t.Fatalf("admin diagnostics missing: %+v", st.HardwareDiagnostics)
	}
	var public map[string]any
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/playback/capabilities", "", &public)
	raw, err := json.Marshal(public)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(raw), detail) || strings.Contains(strings.ToLower(string(raw)), "diagnostic") {
		t.Fatal("admin probe diagnostics leaked in public capabilities")
	}

}
