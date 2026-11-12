package plexsync

import (
	"context"

	"watchmymedia/server/internal/events"
)

// PlexChanged asks an administrator to refetch persisted connection/run state.
type PlexChanged struct {
	ConnectionID int64 `json:"connectionId"`
}

// PlexHistoryChanged asks only the record owner's clients to refetch history.
type PlexHistoryChanged struct {
	DeletedIDs []int64 `json:"deletedIds,omitempty" required:"false" doc:"Imported viewing IDs removed for this private event's owner."`
	Cleared    bool    `json:"cleared,omitempty" required:"false" doc:"All earlier imported viewings were cleared for this private event's owner."`
}

// Notifications carry no provider payload or identity. Persisted polling is
// authoritative; a dropped event only delays a refresh until the next poll.
func (s *Service) publishHistory(user int64) {
	s.publishHistoryChange(user, PlexHistoryChanged{})
}

func (s *Service) publishHistoryChange(user int64, change PlexHistoryChanged) {
	if s.bus != nil {
		s.bus.Publish(events.Event{Type: "plex.history.changed", Data: change, To: &events.Audience{UserID: user, Private: true}})
	}
}
func (s *Service) publishAdmins(ctx context.Context, connection int64) {
	if s.bus == nil {
		return
	}
	users, err := s.q.ListUsers(ctx)
	if err != nil {
		return
	}
	for _, user := range users {
		if user.IsAdmin == 1 {
			s.bus.Publish(events.Event{Type: "plex.changed", Data: PlexChanged{ConnectionID: connection}, To: &events.Audience{UserID: user.ID, Private: true}})
		}
	}
}
