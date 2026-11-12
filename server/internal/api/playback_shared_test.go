package api

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestEventPreparationAdvancesAfterFinalSegmentRange(t *testing.T) {
	t.Parallel()
	path := filepath.Join(t.TempDir(), "seg00001.m4s")
	if err := os.WriteFile(path, make([]byte, 200), 0o600); err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		name, method, header string
		status               int
		complete             bool
	}{
		{"full", http.MethodGet, "", http.StatusOK, true},
		{"first range", http.MethodGet, "bytes=0-99", http.StatusPartialContent, false},
		{"last range", http.MethodGet, "bytes=100-199", http.StatusPartialContent, true},
		{"head", http.MethodHead, "", http.StatusOK, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			req := httptest.NewRequest(tc.method, "/seg", nil)
			if tc.header != "" {
				req.Header.Set("Range", tc.header)
			}
			response := httptest.NewRecorder()
			got := serveShared(response, req, path)
			if response.Code != tc.status || got != tc.complete {
				t.Fatalf("status=%d complete=%t", response.Code, got)
			}
		})
	}
}
