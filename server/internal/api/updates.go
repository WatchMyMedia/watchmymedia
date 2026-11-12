package api

import (
	"context"
	"net/http"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/updates"
)

// UpdateStatus is Check for updates in Settings › General.
type UpdateStatus struct {
	Enabled bool `json:"enabled" doc:"Checking is on (updates.enabled in config); when off, Settings hides the button."`
	updates.Status
}

type UpdateStatusOutput struct{ Body UpdateStatus }

func registerUpdates(h huma.API, deps Deps) {
	// With checking off, the checker has no address but still knows this
	// build and how it was installed.
	checker := deps.Updates
	if checker == nil {
		checker = updates.New("", deps.Version, "")
	}
	enabled := checker.URL != ""
	status := func(s updates.Status) *UpdateStatusOutput {
		return &UpdateStatusOutput{Body: UpdateStatus{Enabled: enabled, Status: s}}
	}

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "getUpdateStatus",
		Method:      http.MethodGet,
		Path:        Prefix + "/settings/updates",
		Summary:     "What the last update check found (admin)",
		Description: "Does not check: only POST /settings/updates/check reaches the internet.",
		Tags:        []string{"settings"},
	}), func(ctx context.Context, _ *struct{}) (*UpdateStatusOutput, error) {
		return status(checker.Status()), nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "checkForUpdates",
		Method:      http.MethodPost,
		Path:        Prefix + "/settings/updates/check",
		Summary:     "Check for a newer release (admin)",
		Description: "Reads the release list at updates.url and compares it with this build. A failed check answers 200 with error set and the last answer that worked.",
		Tags:        []string{"settings"},
	}), func(ctx context.Context, _ *struct{}) (*UpdateStatusOutput, error) {
		if !enabled {
			return nil, huma.Error409Conflict("checking for updates is off (updates.enabled in config)")
		}
		return status(checker.Check(ctx)), nil
	})
}
