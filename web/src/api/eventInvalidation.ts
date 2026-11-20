import type { QueryClient } from '@tanstack/react-query'
import { watchEventInvalidator } from './watch'
import { invalidatePlexState, plexHistoryEventInvalidator, resetPlexHistoryPages, type PlexHistoryChanged } from './plex'
import { authScope } from '../lib/authScope'
import { invalidatePersonalLiveTV, invalidateRecordings, invalidateRowsAndLists } from './queries'
import { invalidateFollows } from './follows'

/** A server event's name and what it refetches, given the event's parsed data. */
export type EventInvalidations = Readonly<Record<string, (data: unknown) => void>>

/** What a library.changed refetches: everything that shows what the library holds. */
export function invalidateLibrary(qc: QueryClient, libraryId: number) {
  void qc.invalidateQueries({ queryKey: ['libraries'] })
  void qc.invalidateQueries({ queryKey: ['files', libraryId] })
  void qc.invalidateQueries({ queryKey: ['items', libraryId] })
  void qc.invalidateQueries({ queryKey: ['rows', libraryId] })
  void qc.invalidateQueries({ queryKey: ['home'] })
  // Lists match the library when read: what they have, and which rows show, moved.
  void qc.invalidateQueries({ queryKey: ['lists'] })
  void qc.invalidateQueries({ queryKey: ['row-layout'] })
  // An open movie or show page: files came or went (an import, a delete).
  void qc.invalidateQueries({ queryKey: ['movie'] })
  void qc.invalidateQueries({ queryKey: ['show'] })
  // Its related rows: a title came, or one they list went.
  void qc.invalidateQueries({ queryKey: ['related'] })
}

/**
 * Every server event (GET /api/events) that changes what the query cache
 * holds, and the queries each refetches. The web app's EventSource
 * (`events.ts`) and the Samsung TV app's fetch reader (`tizen/session/events.ts`)
 * both dispatch through this one table, so the two can't drift apart. Events
 * that only move a status line (scan and match progress) are not here.
 */
export function eventInvalidations(qc: QueryClient): EventInvalidations {
  const libraries = () => void qc.invalidateQueries({ queryKey: ['libraries'] })
  const livePlays = () => void qc.invalidateQueries({ queryKey: ['activity', 'live'] })
  const requests = () => void qc.invalidateQueries({ queryKey: ['requests'] })
  const plexHistory = plexHistoryEventInvalidator(qc)
  return {
    // Finishing a scan or a match updates the status line and the library's
    // counts; what the library holds is refetched on library.changed, which
    // the server sends once per change, after matching when there is any.
    'scan.finished': libraries,
    'scan.failed': libraries,
    'match.finished': libraries,
    'library.changed': (e) => invalidateLibrary(qc, (e as { libraryId: number }).libraryId),
    // Plays starting, changing and ending: Now playing refetches; history and
    // statistics change only when a play ends.
    'activity.started': livePlays,
    'activity.updated': livePlays,
    'activity.stopped': () => void qc.invalidateQueries({ queryKey: ['activity'] }),
    // The user's progress or watched marks changed, on this screen or another:
    // sent once saved, so Continue watching refetches what was just stored.
    'watch.changed': watchEventInvalidator(qc),
    // A request was made, decided or arrived; a problem was reported or
    // resolved: lists, counts and the pages showing request state refetch.
    'requests.changed': () => {
      requests()
      void qc.invalidateQueries({ queryKey: ['show'] })
    },
    'problems.changed': requests,
    'plex.changed': () => invalidatePlexState(qc),
    'plex.history.changed': (e) => plexHistory(e as PlexHistoryChanged),
    // This person starred a channel or set a reminder, here or on another
    // device; reminders also change when a listings refresh moves or loses
    // their programme.
    'live.favourites.changed': () => invalidatePersonalLiveTV(qc),
    'live.reminders.changed': () => invalidatePersonalLiveTV(qc),
    'live.recordings.changed': () => invalidateRecordings(qc),
    // This person's rows were arranged on another device, or an admin changed
    // the household's default; a list was made, changed or deleted, or a fetch
    // changed what is on it.
    'rows.changed': () => invalidateRowsAndLists(qc),
    'lists.changed': () => invalidateRowsAndLists(qc),
    // This person followed or stopped following a show, here or on another
    // device: the bell, Settings › Notifications and Home's row redraw.
    'follows.changed': () => invalidateFollows(qc),
    // A file's seek thumbnails are made (sent to admins): Settings › Transcoding's count moves on.
    'trickplay.ready': () => void qc.invalidateQueries({ queryKey: ['trickplay'] }),
  }
}

/**
 * The stream came back after a drop. Events sent while it was down (the
 * computer slept, the server restarted) are lost: refetch what is shown, and
 * discard the manual Plex history snapshots without replaying older pages.
 */
export function invalidateAfterReconnect(qc: QueryClient) {
  void resetPlexHistoryPages(qc, undefined, authScope())
  void qc.invalidateQueries()
}
