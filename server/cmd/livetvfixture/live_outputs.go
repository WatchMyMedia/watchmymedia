package main

import (
	"crypto/subtle"
	"encoding/json"
	"io"
	"net/http"

	"github.com/go-chi/chi/v5"
	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/livetv"
)

func (s *streamingFixture) registerLiveOutputs(router chi.Router, accounts *auth.Service, sessions *livetv.Sessions) {
	router.Post("/_fixture/streaming/live-output", func(w http.ResponseWriter, r *http.Request) {
		if subtle.ConstantTimeCompare([]byte(r.Header.Get("X-WMM-Fixture-Key")), []byte(s.manifest.ControlKey)) != 1 {
			http.Error(w, "fixture control key required", http.StatusForbidden)
			return
		}
		token := auth.TokenFrom(r)
		if token == "" {
			http.Error(w, "fixture sign-in required", http.StatusUnauthorized)
			return
		}
		user, err := accounts.ValidateSession(r.Context(), token)
		if err != nil {
			http.Error(w, "fixture sign-in required", http.StatusUnauthorized)
			return
		}
		var in struct {
			Channel string `json:"channelId"`
			Quality string `json:"quality"`
			Request string `json:"requestId"`
			Ladder  bool   `json:"ladder"`
		}
		decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 2048))
		decoder.DisallowUnknownFields()
		if decoder.Decode(&in) != nil || decoder.Decode(&struct{}{}) != io.EOF || in.Channel == "" || in.Request == "" {
			http.Error(w, "invalid fixture live output", http.StatusBadRequest)
			return
		}
		profile := &livetv.LiveProfile{SegmentFormats: []string{"fmp4"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}, MaxAudioChannels: 2}
		owner := livetv.Owner{UserID: user.ID, SignInID: user.SignInID, Name: user.Name, Admin: user.IsAdmin}
		start := sessions.StartSharedOutput
		if in.Ladder {
			start = sessions.StartLiveLadder
		}
		view, err := start(r.Context(), owner, in.Channel, in.Quality, in.Request, "Fixture", profile)
		if err != nil {
			http.Error(w, "live output unavailable", http.StatusServiceUnavailable)
			return
		}
		ready, err := sessions.WaitReady(r.Context(), owner, view.ID)
		if err != nil || ready.State != "playing" {
			_ = sessions.Stop(owner, view.ID)
			http.Error(w, "live output not ready", http.StatusServiceUnavailable)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		_ = json.NewEncoder(w).Encode(ready)
	})
}
