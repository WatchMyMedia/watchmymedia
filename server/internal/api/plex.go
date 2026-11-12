package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/plexsync"
)

// Plex setup connects a server, maps identities and previews an import. Every
// setup/operator route requires an admin on an admin network; imported history
// has its own user-scoped routes. No reply carries a Plex credential.

type PlexSignInInput struct {
	ID string `path:"id" maxLength:"64"`
}

type PlexSignInOutput struct {
	Status int
	Body   plexsync.PlexSignIn
}

type PlexConnectionList struct {
	Items []plexsync.PlexConnection `json:"items" nullable:"false"`
}

type ListPlexConnectionsOutput struct{ Body PlexConnectionList }

type ConnectPlexInput struct{ Body plexsync.PlexConnect }

type PlexConnectionOutput struct {
	Status int
	Body   plexsync.PlexConnection
}

type PlexConnectionInput struct {
	ID int64 `path:"id"`
}

type PlexUserList struct {
	Generation int64                 `json:"generation" doc:"Send back when saving mappings."`
	Users      []plexsync.PlexPerson `json:"users" nullable:"false"`
}

type ListPlexUsersOutput struct{ Body PlexUserList }

type PlexLibraryList struct {
	Generation int64                  `json:"generation" doc:"Send back when saving mappings."`
	Libraries  []plexsync.PlexSection `json:"libraries" nullable:"false"`
}

type ListPlexLibrariesOutput struct{ Body PlexLibraryList }

type SavePlexMappingsInput struct {
	ID   int64 `path:"id"`
	Body plexsync.PlexLinks
}

type PlexPreviewInput struct {
	ID        int64  `path:"id"`
	PreviewID string `path:"previewId" maxLength:"64"`
}

type PlexPreviewOutput struct {
	Status int
	Body   plexsync.PlexPreview
}

func registerPlex(h huma.API, deps Deps) {
	tags := []string{"plex"}
	huma.Register(h, adminOp(h, huma.Operation{
		OperationID:   "startPlexSignIn",
		Method:        http.MethodPost,
		Path:          Prefix + "/integrations/plex/auth",
		Summary:       "Start signing in to Plex",
		Description:   "Gives the address of Plex's own sign-in page for a new WatchMyMedia device. WatchMyMedia never asks for a Plex password. Poll getPlexSignIn until it is ready.",
		Tags:          tags,
		DefaultStatus: http.StatusCreated,
	}), func(ctx context.Context, _ *struct{}) (*PlexSignInOutput, error) {
		if err := requireConfirmed(ctx, deps, "startPlexSignIn", nil); err != nil {
			return nil, err
		}
		svc, who, err := plexAdmin(ctx, deps)
		if err != nil {
			return nil, err
		}
		in, err := svc.StartSignIn(ctx, who)
		if err != nil {
			return nil, plexError(err)
		}
		return &PlexSignInOutput{Status: http.StatusCreated, Body: in}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "getPlexSignIn",
		Method:      http.MethodGet,
		Path:        Prefix + "/integrations/plex/auth/{id}",
		Summary:     "Check a Plex sign-in",
		Description: "Only from the session that started it. Once ready, lists the servers the Plex account owns.",
		Tags:        tags,
	}), func(ctx context.Context, in *PlexSignInInput) (*PlexSignInOutput, error) {
		svc, who, err := plexAdmin(ctx, deps)
		if err != nil {
			return nil, err
		}
		out, err := svc.SignInStatus(ctx, who, in.ID)
		if err != nil {
			return nil, plexError(err)
		}
		return &PlexSignInOutput{Status: http.StatusOK, Body: out}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID:   "cancelPlexSignIn",
		Method:        http.MethodDelete,
		Path:          Prefix + "/integrations/plex/auth/{id}",
		Summary:       "Cancel a Plex sign-in",
		Tags:          tags,
		DefaultStatus: http.StatusNoContent,
	}), func(ctx context.Context, in *PlexSignInInput) (*struct{}, error) {
		svc, who, err := plexAdmin(ctx, deps)
		if err != nil {
			return nil, err
		}
		svc.CancelSignIn(who, in.ID)
		return nil, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "listPlexConnections",
		Method:      http.MethodGet,
		Path:        Prefix + "/integrations/plex/connections",
		Summary:     "List the connected Plex server",
		Description: "At most one for now.",
		Tags:        tags,
	}), func(ctx context.Context, _ *struct{}) (*ListPlexConnectionsOutput, error) {
		svc, _, err := plexAdmin(ctx, deps)
		if err != nil {
			return nil, err
		}
		list, err := svc.Connections(ctx)
		if err != nil {
			return nil, err
		}
		return &ListPlexConnectionsOutput{Body: PlexConnectionList{Items: list}}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID:   "connectPlex",
		Method:        http.MethodPost,
		Path:          Prefix + "/integrations/plex/connections",
		Summary:       "Connect a Plex server",
		Description:   "From a ready sign-in, a server the account owns, at one of its addresses or another one. Checks the address answers as that server first. Connecting the same server again (after its grant was lost, or a restore) keeps its mappings. Imports nothing.",
		Tags:          tags,
		DefaultStatus: http.StatusCreated,
	}), func(ctx context.Context, in *ConnectPlexInput) (*PlexConnectionOutput, error) {
		if err := requireConfirmed(ctx, deps, "connectPlex", nil); err != nil {
			return nil, err
		}
		svc, who, err := plexAdmin(ctx, deps)
		if err != nil {
			return nil, err
		}
		c, err := svc.Connect(ctx, who, in.Body)
		if err != nil {
			return nil, plexError(err)
		}
		setAdminAuditTarget(ctx, c.ID)
		return &PlexConnectionOutput{Status: http.StatusCreated, Body: c}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID:   "disconnectPlex",
		Method:        http.MethodDelete,
		Path:          Prefix + "/integrations/plex/connections/{id}",
		Summary:       "Disconnect the Plex server",
		Description:   "Removes the connection, its mappings and its grant. WatchMyMedia stays in the Plex account's authorized devices until removed there.",
		Tags:          tags,
		DefaultStatus: http.StatusNoContent,
	}), func(ctx context.Context, in *PlexConnectionInput) (*struct{}, error) {
		if err := requireConfirmed(ctx, deps, "disconnectPlex", in.ID); err != nil {
			return nil, err
		}
		svc, _, err := plexAdmin(ctx, deps)
		if err != nil {
			return nil, err
		}
		return nil, plexError(svc.Disconnect(ctx, in.ID))
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "listPlexUsers",
		Method:      http.MethodGet,
		Path:        Prefix + "/integrations/plex/connections/{id}/users",
		Summary:     "List the people on the Plex server",
		Description: "The owner, Home members (managed profiles included) and the users the server is shared with, each once, with their kind and whether they can be mapped.",
		Tags:        tags,
	}), func(ctx context.Context, in *PlexConnectionInput) (*ListPlexUsersOutput, error) {
		svc, _, err := plexAdmin(ctx, deps)
		if err != nil {
			return nil, err
		}
		generation, people, err := svc.People(ctx, in.ID)
		if err != nil {
			return nil, plexError(err)
		}
		return &ListPlexUsersOutput{Body: PlexUserList{Generation: generation, Users: people}}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "listPlexLibraries",
		Method:      http.MethodGet,
		Path:        Prefix + "/integrations/plex/connections/{id}/libraries",
		Summary:     "List the Plex server's movie and TV libraries",
		Tags:        tags,
	}), func(ctx context.Context, in *PlexConnectionInput) (*ListPlexLibrariesOutput, error) {
		svc, _, err := plexAdmin(ctx, deps)
		if err != nil {
			return nil, err
		}
		generation, sections, err := svc.Libraries(ctx, in.ID)
		if err != nil {
			return nil, plexError(err)
		}
		return &ListPlexLibrariesOutput{Body: PlexLibraryList{Generation: generation, Libraries: sections}}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "savePlexMappings",
		Method:      http.MethodPut,
		Path:        Prefix + "/integrations/plex/connections/{id}/mappings",
		Summary:     "Map Plex people and libraries to local ones",
		Description: "Replaces every mapping. Each person must be ready, each local account and Plex person mapped once, each library to a local one of the same type. Refused (409) when generation is not the current one.",
		Tags:        tags,
	}), func(ctx context.Context, in *SavePlexMappingsInput) (*PlexConnectionOutput, error) {
		svc, _, err := plexAdmin(ctx, deps)
		if err != nil {
			return nil, err
		}
		c, err := svc.SaveLinks(ctx, in.ID, in.Body)
		if err != nil {
			return nil, plexError(err)
		}
		return &PlexConnectionOutput{Status: http.StatusOK, Body: c}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID:   "startPlexPreview",
		Method:        http.MethodPost,
		Path:          Prefix + "/integrations/plex/connections/{id}/previews",
		Summary:       "Preview importing the mapped people's watched marks",
		Description:   "Runs in the background; poll getPlexPreview. Writes nothing. One at a time per connection.",
		Tags:          tags,
		DefaultStatus: http.StatusAccepted,
	}), func(ctx context.Context, in *PlexConnectionInput) (*PlexPreviewOutput, error) {
		svc, who, err := plexAdmin(ctx, deps)
		if err != nil {
			return nil, err
		}
		p, err := svc.StartPreviewFor(ctx, in.ID, who)
		if err != nil {
			return nil, plexError(err)
		}
		return &PlexPreviewOutput{Status: http.StatusAccepted, Body: p}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "getPlexPreview",
		Method:      http.MethodGet,
		Path:        Prefix + "/integrations/plex/connections/{id}/previews/{previewId}",
		Summary:     "Read a preview",
		Description: "Kept for 15 minutes after it finishes, and stale once the mappings change.",
		Tags:        tags,
	}), func(ctx context.Context, in *PlexPreviewInput) (*PlexPreviewOutput, error) {
		svc, _, err := plexAdmin(ctx, deps)
		if err != nil {
			return nil, err
		}
		p, err := svc.GetPreview(ctx, in.ID, in.PreviewID)
		if err != nil {
			return nil, plexError(err)
		}
		return &PlexPreviewOutput{Status: http.StatusOK, Body: p}, nil
	})
	registerPlexRuns(h, deps)
}

// plexAdmin is the Plex service and the admin asking, with a digest of the
// session they asked in (a sign-in stays with the session that started it).
func plexAdmin(ctx context.Context, deps Deps) (*plexsync.Service, plexsync.Requester, error) {
	u, err := requireAdmin(ctx)
	if err != nil {
		return nil, plexsync.Requester{}, err
	}
	if deps.Plex == nil {
		return nil, plexsync.Requester{}, huma.Error404NotFound("Plex connections are off")
	}
	who := plexsync.Requester{UserID: u.ID}
	if token, ok := auth.SessionTokenFrom(ctx); ok {
		sum := sha256.Sum256([]byte(token))
		who.Session = hex.EncodeToString(sum[:])
	}
	return deps.Plex, who, nil
}

// plexError turns a refusal into its status with its reason code.
func plexError(err error) error {
	e, ok := errors.AsType[*plexsync.Error](err)
	if !ok {
		return err
	}
	switch e.Kind {
	case plexsync.NotFound:
		return huma.Error404NotFound(e.Code)
	case plexsync.Invalid:
		return huma.Error422UnprocessableEntity(e.Code)
	case plexsync.Conflict:
		return huma.Error409Conflict(e.Code)
	}
	return huma.Error502BadGateway(e.Code)
}
