package api

import (
	"context"
	"database/sql"
	"errors"
	"net"
	"net/http"
	"slices"
	"strconv"

	"github.com/danielgtaylor/huma/v2"
	"github.com/go-chi/chi/v5"

	"watchmymedia/server/internal/integrations"
	"watchmymedia/server/internal/integrations/apiclient"
)

// Integrations are Radarr, Sonarr and Seerr (PLAN-ARR.md §3): admins connect
// them on the settings page; Radarr's and Sonarr's webhooks call
// /api/integrations/{id}/webhook.

type IntegrationList struct {
	Items []integrations.Connection `json:"items" nullable:"false"`
	// ServerAddresses are the addresses an app may reach this server at,
	// best first, for the webhook.
	ServerAddresses []string `json:"serverAddresses" nullable:"false"`
}

type ListIntegrationsOutput struct{ Body IntegrationList }

type IntegrationBody struct {
	Name    *string                `json:"name,omitempty" minLength:"1" maxLength:"64"`
	URL     *string                `json:"url,omitempty" maxLength:"512" doc:"The app's address, with any URL base: http://nas:7878/radarr."`
	APIKey  *string                `json:"apiKey,omitempty" maxLength:"128" doc:"Settings → General → API Key in the app. Omit to keep the saved one."`
	PathMap *integrations.PathMap  `json:"pathMap,omitempty" maxItems:"32"`
	Targets *[]integrations.Target `json:"targets,omitempty" maxItems:"64"`
}

type CreateIntegrationInput struct {
	Body struct {
		Kind string `json:"kind" enum:"radarr,sonarr,seerr" doc:"One Seerr at most; it takes no path mappings or targets."`
		IntegrationBody
	}
}

type UpdateIntegrationInput struct {
	ID   int64 `path:"id"`
	Body IntegrationBody
}

type IntegrationIDInput struct {
	ID int64 `path:"id"`
}

type IntegrationOutput struct{ Body integrations.Connection }

type CreateIntegrationOutput struct {
	Status int
	Body   integrations.Connection
}

type IntegrationDetail struct {
	integrations.Connection
	Details      *integrations.Details `json:"details,omitempty" doc:"Read from the app now; absent when it could not be reached."`
	DetailsError string                `json:"detailsError,omitempty"`
}

type IntegrationDetailOutput struct{ Body IntegrationDetail }

type TestIntegrationInput struct {
	Body struct {
		Kind   string `json:"kind" enum:"radarr,sonarr,seerr"`
		URL    string `json:"url" minLength:"1" maxLength:"512"`
		APIKey string `json:"apiKey,omitempty" maxLength:"128" doc:"Omit with id to test the saved key."`
		ID     int64  `json:"id,omitempty" doc:"A saved connection whose key to use when apiKey is omitted."`
	}
}

type SeerrUserList struct {
	Items []integrations.SeerrUser `json:"items" nullable:"false"`
}

type ConnectWebhookInput struct {
	ID   int64 `path:"id"`
	Body struct {
		Address string `json:"address" minLength:"1" maxLength:"512" doc:"This server's address as the app reaches it: http://192.168.1.20:27587."`
	}
}

func registerIntegrations(h huma.API, r chi.Router, deps Deps) {
	if deps.Integrations != nil {
		r.Post(integrations.WebhookPath, webhookHandler(deps.Integrations))
		r.Put(integrations.WebhookPath, webhookHandler(deps.Integrations))
	}

	huma.Register(h, huma.Operation{
		OperationID: "listIntegrations",
		Method:      http.MethodGet,
		Path:        Prefix + "/integrations",
		Summary:     "List the Radarr, Sonarr and Seerr connections",
		Tags:        []string{"integrations"},
	}, func(ctx context.Context, _ *struct{}) (*ListIntegrationsOutput, error) {
		svc, err := integrationsAdmin(ctx, deps)
		if err != nil {
			return nil, err
		}
		return &ListIntegrationsOutput{Body: IntegrationList{Items: svc.List(), ServerAddresses: serverAddresses(requestFrom(ctx), deps)}}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID:   "createIntegration",
		Method:        http.MethodPost,
		Path:          Prefix + "/integrations",
		Summary:       "Connect Radarr, Sonarr or Seerr",
		Description:   "Checks the app with the key before saving it.",
		Tags:          []string{"integrations"},
		DefaultStatus: http.StatusCreated,
	}, func(ctx context.Context, in *CreateIntegrationInput) (*CreateIntegrationOutput, error) {
		if err := requireConfirmed(ctx, deps, "createIntegration", nil); err != nil {
			return nil, err
		}
		svc, err := integrationsAdmin(ctx, deps)
		if err != nil {
			return nil, err
		}
		b := in.Body.IntegrationBody
		c, err := svc.Create(ctx, integrations.Input{Kind: in.Body.Kind, Name: b.Name, URL: b.URL, APIKey: b.APIKey, PathMap: b.PathMap, Targets: b.Targets})
		if err != nil {
			return nil, integrationError(err)
		}
		setAdminAuditTarget(ctx, c.ID)
		return &CreateIntegrationOutput{Status: http.StatusCreated, Body: c}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "getIntegration",
		Method:      http.MethodGet,
		Path:        Prefix + "/integrations/{id}",
		Summary:     "Get a connection with its root folders and quality profiles",
		Tags:        []string{"integrations"},
	}, func(ctx context.Context, in *IntegrationIDInput) (*IntegrationDetailOutput, error) {
		svc, err := integrationsAdmin(ctx, deps)
		if err != nil {
			return nil, err
		}
		c, ok := svc.Get(in.ID)
		if !ok {
			return nil, huma.Error404NotFound("integration not found")
		}
		out := &IntegrationDetailOutput{Body: IntegrationDetail{Connection: c}}
		if d, err := svc.Details(ctx, in.ID); err != nil {
			out.Body.DetailsError = err.Error()
		} else {
			out.Body.Details = &d
		}
		return out, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "updateIntegration",
		Method:      http.MethodPatch,
		Path:        Prefix + "/integrations/{id}",
		Summary:     "Change a connection",
		Description: "A new address or key is checked with the app before it is saved.",
		Tags:        []string{"integrations"},
	}, func(ctx context.Context, in *UpdateIntegrationInput) (*IntegrationOutput, error) {
		if err := requireConfirmed(ctx, deps, "updateIntegration", in.ID); err != nil {
			return nil, err
		}
		svc, err := integrationsAdmin(ctx, deps)
		if err != nil {
			return nil, err
		}
		b := in.Body
		c, err := svc.Update(ctx, in.ID, integrations.Input{Name: b.Name, URL: b.URL, APIKey: b.APIKey, PathMap: b.PathMap, Targets: b.Targets})
		if err != nil {
			return nil, integrationError(err)
		}
		return &IntegrationOutput{Body: c}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID:   "deleteIntegration",
		Method:        http.MethodDelete,
		Path:          Prefix + "/integrations/{id}",
		Summary:       "Disconnect an app",
		Description:   "The webhook it has is refused from now on; remove it in the app.",
		Tags:          []string{"integrations"},
		DefaultStatus: http.StatusNoContent,
	}, func(ctx context.Context, in *IntegrationIDInput) (*struct{}, error) {
		if err := requireConfirmed(ctx, deps, "deleteIntegration", in.ID); err != nil {
			return nil, err
		}
		svc, err := integrationsAdmin(ctx, deps)
		if err != nil {
			return nil, err
		}
		if _, ok := svc.Get(in.ID); !ok {
			return nil, huma.Error404NotFound("integration not found")
		}
		return nil, svc.Delete(ctx, in.ID)
	})

	huma.Register(h, huma.Operation{
		OperationID: "testIntegration",
		Method:      http.MethodPost,
		Path:        Prefix + "/integrations/test",
		Summary:     "Check an app's address and key",
		Tags:        []string{"integrations"},
	}, func(ctx context.Context, in *TestIntegrationInput) (*TestOutput, error) {
		if err := requireConfirmed(ctx, deps, "testIntegration", in.Body.ID); err != nil {
			return nil, err
		}
		svc, err := integrationsAdmin(ctx, deps)
		if err != nil {
			return nil, err
		}
		key := in.Body.APIKey
		if key == "" && in.Body.ID > 0 {
			row, err := deps.Queries.GetIntegration(ctx, in.Body.ID)
			if err != nil {
				return nil, notFound(err, "integration not found")
			}
			key = row.ApiKey
		}
		// The app refusing is the answer to the test, not a failed request.
		res := TestResult{OK: false}
		if st, err := svc.Check(ctx, in.Body.Kind, in.Body.URL, key); err != nil {
			res.Message = err.Error()
		} else {
			res = TestResult{OK: true, Message: st.AppName + " " + st.Version}
		}
		return &TestOutput{Body: res}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "connectIntegrationWebhook",
		Method:      http.MethodPost,
		Path:        Prefix + "/integrations/{id}/webhook/connect",
		Summary:     "Create the webhook in Radarr or Sonarr",
		Description: "The app sends a test event to the address before it saves the webhook, so an address it cannot reach is refused with its message.",
		Tags:        []string{"integrations"},
	}, func(ctx context.Context, in *ConnectWebhookInput) (*IntegrationOutput, error) {
		if err := requireConfirmed(ctx, deps, "connectIntegrationWebhook", in.ID); err != nil {
			return nil, err
		}
		svc, err := integrationsAdmin(ctx, deps)
		if err != nil {
			return nil, err
		}
		if err := svc.ConnectWebhook(ctx, in.ID, in.Body.Address); err != nil {
			return nil, integrationError(err)
		}
		c, _ := svc.Get(in.ID)
		return &IntegrationOutput{Body: c}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "listSeerrUsers",
		Method:      http.MethodGet,
		Path:        Prefix + "/integrations/seerr/users",
		Summary:     "List Seerr's users, to link people to",
		Description: "Read from Seerr now. Link a person with updateUser's seerrUserId.",
		Tags:        []string{"integrations"},
	}, func(ctx context.Context, _ *struct{}) (*struct{ Body SeerrUserList }, error) {
		svc, err := integrationsAdmin(ctx, deps)
		if err != nil {
			return nil, err
		}
		users, err := svc.SeerrUsers(ctx)
		if err != nil {
			return nil, integrationError(err)
		}
		return &struct{ Body SeerrUserList }{Body: SeerrUserList{Items: users}}, nil
	})
}

// integrationsAdmin is the integrations service, for admins only.
func integrationsAdmin(ctx context.Context, deps Deps) (*integrations.Service, error) {
	_, s, err := requester(ctx, deps, true)
	return s, err
}

func webhookHandler(svc *integrations.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, err := strconv.ParseInt(chi.URLParam(r, "id"), 10, 64)
		if err != nil {
			http.NotFound(w, r)
			return
		}
		svc.ServeWebhook(w, r, id)
	}
}

// integrationError turns what can go wrong talking to an app into a status
// whose message the settings page shows as it is.
func integrationError(err error) error {
	var ae *apiclient.Error
	var ne net.Error
	if status, reason, ok := integrations.IsRefused(err); ok {
		return huma.NewError(status, reason)
	}
	switch reason, ok := integrations.IsInvalid(err); {
	case ok:
		return huma.Error422UnprocessableEntity(reason)
	case integrations.IsNotFound(err), errors.Is(err, sql.ErrNoRows):
		return huma.Error404NotFound("integration not found")
	case errors.As(err, &ae) && ae.App == "Seerr":
		return huma.Error502BadGateway("Seerr said: " + ae.Error())
	case errors.As(err, &ae):
		return huma.Error502BadGateway("the app said: " + ae.Error())
	case errors.As(err, &ne):
		return huma.Error502BadGateway("could not reach the app: " + err.Error())
	}
	return err
}

// serverAddresses are the addresses an app may reach this server at: the LAN
// ones first, then the one this page was opened at, then loopback for an app
// on the same machine (not in a container).
func serverAddresses(r *http.Request, deps Deps) []string {
	out := []string{}
	add := func(u string) {
		if u != "" && !slices.Contains(out, u) {
			out = append(out, u)
		}
	}
	port := strconv.Itoa(deps.Cast.Port)
	for _, a := range lanAddrs(deps.Cast.Host) {
		add("http://" + net.JoinHostPort(a.String(), port))
	}
	if r != nil {
		add(ownOrigin(r, deps.Net))
	}
	if deps.Cast.Port > 0 {
		add("http://127.0.0.1:" + port)
	}
	return out
}
