package plexsync

import (
	"sort"

	"watchmymedia/server/internal/plex"
)

// Kinds of Plex person (PLAN-PLEX-SYNC.md, "Identity and media matching").
const (
	KindOwner   = "owner"
	KindAccount = "account" // an invited user or Home member who signs in to plex.tv
	KindManaged = "managed" // a Home profile with no plex.tv sign-in of its own
)

// Statuses of a Plex person for linking.
const (
	StatusReady = "ready"
	// StatusNeedsProfileAuthorization: an account WatchMyMedia has no way
	// to read through the owner; it would need the person's own sign-in.
	StatusNeedsProfileAuthorization = "needs_profile_authorization"
	// StatusNeedsPIN: a PIN-protected Home profile. Reading it would need
	// the PIN at every run, and WatchMyMedia never stores one.
	StatusNeedsPIN    = "needs_pin"
	StatusUnavailable = "unavailable"
)

// How a ready person's own watch state is read, never through another's.
const (
	accessOwner  = "owner"  // the owner's server credential
	accessShared = "shared" // their server-specific shared credential
	accessHome   = "home"   // a Home switch from the owner, verified each time
)

// PlexPerson is someone on the connected Plex server. PlexUserID is the
// plex.tv or Home user ID; HistoryAccountID is the separate ID the server's
// own history uses for them (1 for the owner), 0 when it doesn't list them.
// Title is Plex's display text, for a person to choose by and nothing else.
type PlexPerson struct {
	PlexUserID       int64  `json:"plexUserId" doc:"The person's Plex user ID, which links them."`
	Kind             string `json:"kind,omitempty" enum:"owner,account,managed" doc:"owner; account: signs in to plex.tv (invited, or a Home member); managed: a Home profile with no plex.tv sign-in. Absent when Plex lists the person with conflicting kinds."`
	Title            string `json:"title" doc:"Plex's display name, to recognise the person by. Never used to match anyone."`
	Status           string `json:"status" enum:"ready,needs_profile_authorization,needs_pin,unavailable" doc:"Only ready people can be linked."`
	Reason           string `json:"reason,omitempty" doc:"Why the person isn't ready, as a reason code."`
	HistoryAccountID int64  `json:"historyAccountId,omitempty" doc:"The person's account in the server's own play history; absent when the server doesn't list them."`
	LinkedUserID     int64  `json:"linkedUserId,omitempty" doc:"The local account they are linked to."`
	access           string
}

// discoverPeople merges the server's owner, its Home members (managed
// profiles included) and the users it is shared with, by exact Plex user
// ID: someone on both lists is one person. Names are never compared. The
// same ID listed with different kinds is unavailable rather than guessed.
//
// owner and resource must come from the connection's verified owner grant,
// shared from that owner's shared_servers for resource, and inventory from
// the verified server's /accounts.
func discoverPeople(owner plex.Account, resource plex.Resource, home, users []plex.HomeUser, shared map[int64]bool, inventory []plex.HistoryAccount) []PlexPerson {
	homeRows := make(map[int64]plex.HomeUser, len(home))
	for _, u := range home {
		homeRows[u.ID] = u
	}
	userRows := make(map[int64]plex.HomeUser, len(users))
	for _, u := range users {
		userRows[u.ID] = u
	}
	ids := map[int64]bool{owner.ID: true}
	for id := range homeRows {
		ids[id] = true
	}
	for id := range userRows {
		ids[id] = true
	}
	out := make([]PlexPerson, 0, len(ids))
	for id := range ids {
		h, inHome := homeRows[id]
		u, inUsers := userRows[id]
		p := PlexPerson{PlexUserID: id, Title: owner.Title}
		if inUsers {
			p.Title = u.Title
		}
		if inHome {
			p.Title = h.Title
		}
		// Kinds from each source must agree. Only the verified owner's ID
		// is the owner: a Home admin with another ID is a conflict, never
		// read as the owner. The Home list decides the kind of anyone it
		// lists; a Home profile on the shared list without a sign-in
		// adds nothing to it.
		var kinds []string
		if id == owner.ID {
			kinds = append(kinds, KindOwner)
		}
		if inHome && !h.Guest && id != owner.ID {
			switch {
			case h.Admin:
				kinds = append(kinds, KindOwner)
			case h.HasLogin:
				kinds = append(kinds, KindAccount)
			default:
				kinds = append(kinds, KindManaged)
			}
		}
		if inUsers {
			switch {
			case u.HasLogin || !u.Home:
				// A share needs a plex.tv account.
				kinds = append(kinds, KindAccount)
			case !inHome:
				kinds = append(kinds, KindManaged)
			}
		}
		switch {
		case inHome && h.Guest:
			p.Status, p.Reason = StatusUnavailable, "guest"
		case len(kinds) == 0:
			p.Status, p.Reason = StatusUnavailable, "unknown_kind"
		case !sameKinds(kinds) || (kinds[0] == KindOwner && id != owner.ID):
			p.Status, p.Reason = StatusUnavailable, "conflicting_kind"
		default:
			p.Kind = kinds[0]
			p.Status, p.access, p.Reason = readiness(p.Kind, inHome, h, shared[id])
		}
		if p.Kind != "" {
			subject := plex.Account{ID: id}
			if hist, err := plex.ResolveHistoryIdentity(owner, resource, subject, inventory); err == nil {
				p.HistoryAccountID = int64(hist.LocalID)
			}
		}
		out = append(out, p)
	}
	sort.Slice(out, func(i, j int) bool {
		if (out[i].Kind == KindOwner) != (out[j].Kind == KindOwner) {
			return out[i].Kind == KindOwner
		}
		return out[i].PlexUserID < out[j].PlexUserID
	})
	return out
}

// readiness says whether WatchMyMedia can read a person's own watch state,
// and how. The owner reads with the server credential; an invited account
// with its exact shared credential; a Home member through a Home switch,
// unless a PIN protects it.
func readiness(kind string, inHome bool, h plex.HomeUser, shared bool) (status, access, reason string) {
	switch {
	case kind == KindOwner:
		return StatusReady, accessOwner, ""
	case kind == KindAccount && shared:
		return StatusReady, accessShared, ""
	case inHome && h.Protected:
		return StatusNeedsPIN, "", "pin_protected"
	case inHome:
		return StatusReady, accessHome, ""
	case kind == KindManaged:
		return StatusUnavailable, "", "not_in_home"
	}
	return StatusNeedsProfileAuthorization, "", "no_server_credential"
}

func sameKinds(kinds []string) bool {
	for _, k := range kinds[1:] {
		if k != kinds[0] {
			return false
		}
	}
	return true
}
