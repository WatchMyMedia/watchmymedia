package api

import (
	"context"
	"errors"
	"net/http"
	"time"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/playback"
)

type StartPairingInput struct {
	UserAgent string `header:"User-Agent" required:"false"`
	Body      struct {
		Client playback.Client `json:"client" doc:"The device to sign in; its name is what the person approving sees."`
	}
}

type PairingStart struct {
	Code       string    `json:"code" example:"KX7P4M" doc:"Show this on screen. Six characters from A-Z and 2-9 without O or I."`
	Secret     string    `json:"secret" doc:"Keep this; poll /auth/pair/claim with it. Never show it."`
	ExpiresAt  time.Time `json:"expiresAt"`
	IntervalMs int64     `json:"intervalMs" doc:"How often to poll."`
	LinkPath   string    `json:"linkPath" example:"/link?code=KX7P4M" doc:"Path of the approval page on this server, for a QR code: prefix it with the address the device used."`
}

type PairingStartOutput struct{ Body PairingStart }

type ClaimPairingInput struct {
	Body struct {
		Secret string `json:"secret" minLength:"1" maxLength:"128"`
	}
}

type PairingClaim struct {
	Status    string     `json:"status" enum:"pending,approved"`
	User      *auth.User `json:"user,omitempty"`
	SessionID string     `json:"sessionId,omitempty"`
	Token     string     `json:"token,omitempty" doc:"Approved: the device's session token, for Authorization: Bearer. Returned once."`
	ExpiresAt *time.Time `json:"expiresAt,omitempty"`
}

type PairingClaimOutput struct{ Body PairingClaim }

type PairCodeInput struct {
	Code string `path:"code" minLength:"6" maxLength:"16"`
}

type PairingInfo struct {
	Code      string    `json:"code"`
	Name      string    `json:"name" doc:"The device's name, as it described itself."`
	Product   string    `json:"product,omitempty"`
	Platform  string    `json:"platform,omitempty"`
	Version   string    `json:"version,omitempty"`
	Model     string    `json:"model,omitempty" example:"Chromecast with Google TV"`
	IP        string    `json:"ip,omitempty" doc:"The address the device asked from."`
	ExpiresAt time.Time `json:"expiresAt"`
	Approved  bool      `json:"approved"`
}

type PairingInfoOutput struct{ Body PairingInfo }

func pairingInfo(p auth.Pairing) PairingInfo {
	return PairingInfo{Code: p.Code, Name: p.Client.Name, Product: p.Client.Product, Platform: p.Client.Platform,
		Version: p.Client.Version, Model: p.Client.Model, IP: p.IP, ExpiresAt: p.ExpiresAt.UTC(), Approved: p.Approved}
}

func pairingError(err error) error {
	switch {
	case errors.Is(err, auth.ErrUnauthenticated):
		return huma.Error401Unauthorized("sign in required")
	case errors.Is(err, auth.ErrPairingNotFound):
		return huma.Error404NotFound("that code is not waiting to be linked; check it, or get a new code on the device")
	case errors.Is(err, auth.ErrPairingUsed):
		return huma.Error409Conflict("that device has already been approved")
	case errors.Is(err, auth.ErrPairingBusy):
		return huma.Error503ServiceUnavailable(err.Error())
	}
	return throttled(err)
}

func registerPairing(h huma.API, deps Deps) {
	huma.Register(h, huma.Operation{
		OperationID: "startPairing",
		Method:      http.MethodPost,
		Path:        Prefix + "/auth/pair",
		Summary:     "Start signing in a TV with a code",
		Description: "No sign-in needed. Show the code (and a QR code of linkPath), then poll /auth/pair/claim every intervalMs until someone signed in approves the code at /link or in a phone app. Codes last 10 minutes.",
		Tags:        []string{"auth"},
	}, func(ctx context.Context, in *StartPairingInput) (*PairingStartOutput, error) {
		if deps.Auth == nil {
			return nil, huma.Error503ServiceUnavailable("auth not configured")
		}
		c := sessionClient(&in.Body.Client, in.UserAgent)
		code, secret, exp, err := deps.Auth.StartPairing(ctx, c)
		if err != nil {
			return nil, pairingError(err)
		}
		return &PairingStartOutput{Body: PairingStart{Code: code, Secret: secret, ExpiresAt: exp.UTC(),
			IntervalMs: auth.PairingPoll.Milliseconds(), LinkPath: "/link?code=" + code}}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "claimPairing",
		Method:      http.MethodPost,
		Path:        Prefix + "/auth/pair/claim",
		Summary:     "Poll a pairing: pending, or approved with the device's token",
		Description: "No sign-in needed. 404 once the code has expired (start again) or after the token was handed out.",
		Tags:        []string{"auth"},
	}, func(ctx context.Context, in *ClaimPairingInput) (*PairingClaimOutput, error) {
		if deps.Auth == nil {
			return nil, huma.Error503ServiceUnavailable("auth not configured")
		}
		st, err := deps.Auth.ClaimPairing(ctx, in.Body.Secret)
		if err != nil {
			return nil, pairingError(err)
		}
		if !st.Approved {
			return &PairingClaimOutput{Body: PairingClaim{Status: "pending"}}, nil
		}
		exp := st.Grant.ExpiresAt.UTC().Truncate(time.Second)
		u := st.User
		return &PairingClaimOutput{Body: PairingClaim{Status: "approved", User: &u, SessionID: st.Grant.SessionID, Token: st.Grant.Token, ExpiresAt: &exp}}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "getPairing",
		Method:      http.MethodGet,
		Path:        Prefix + "/auth/pair/{code}",
		Summary:     "The device waiting behind a pairing code",
		Tags:        []string{"auth"},
	}, func(ctx context.Context, in *PairCodeInput) (*PairingInfoOutput, error) {
		if _, err := currentUser(ctx); err != nil {
			return nil, err
		}
		p, err := deps.Auth.PendingPairing(ctx, in.Code)
		if err != nil {
			return nil, pairingError(err)
		}
		return &PairingInfoOutput{Body: pairingInfo(p)}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "approvePairing",
		Method:      http.MethodPost,
		Path:        Prefix + "/auth/pair/{code}/approve",
		Summary:     "Sign the device behind a code in as yourself",
		Description: "The device receives its token on its next poll. Wrong codes count like failed sign-ins.",
		Tags:        []string{"auth"},
	}, func(ctx context.Context, in *PairCodeInput) (*PairingInfoOutput, error) {
		_, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		p, err := deps.Auth.ApprovePairing(ctx, in.Code)
		if err != nil {
			return nil, pairingError(err)
		}
		return &PairingInfoOutput{Body: pairingInfo(p)}, nil
	})
}
