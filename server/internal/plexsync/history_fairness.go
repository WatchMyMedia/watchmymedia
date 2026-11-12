package plexsync

import "watchmymedia/server/internal/db"

type historyWork struct {
	link      db.PlexUserLink
	libraries []db.ListPlexLibraryLinksRow
}

// historyWorkOrder rotates the stable (Plex person, section) order after the
// last committed page. A long backfill may spend this run's remaining budget,
// but the following run starts with the next scope instead of rereading the
// same completed scopes first. A generation change resets both cursor fields.
// SQL returns links by Plex ID and libraries by section ID.
func historyWorkOrder(links []db.PlexUserLink, libraries []db.ListPlexLibraryLinksRow, person, section int64) []historyWork {
	if len(links) == 0 || len(libraries) == 0 {
		return nil
	}
	firstLink, firstLibrary := 0, 0
	found := false
	for i, l := range links {
		for j, library := range libraries {
			if l.PlexUserID > person || l.PlexUserID == person && library.SectionID > section {
				firstLink, firstLibrary = i, j
				found = true
				break
			}
		}
		if found {
			break
		}
	}
	work := make([]historyWork, 0, len(links)+1)
	work = append(work, historyWork{link: links[firstLink], libraries: libraries[firstLibrary:]})
	for _, l := range links[firstLink+1:] {
		work = append(work, historyWork{link: l, libraries: libraries})
	}
	for _, l := range links[:firstLink] {
		work = append(work, historyWork{link: l, libraries: libraries})
	}
	if firstLibrary > 0 {
		work = append(work, historyWork{link: links[firstLink], libraries: libraries[:firstLibrary]})
	}
	return work
}
