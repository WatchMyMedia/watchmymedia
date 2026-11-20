import type { components } from '../api/schema'

// Settings › Server › Inbox:
// Approve, Retry and Mark resolved report in their row, failures included.

type MediaRequest = components['schemas']['MediaRequest']
type Inbox = components['schemas']['Inbox']

/** The app a request goes to: Radarr for movies, Sonarr for shows. */
export function requestApp(r: Pick<MediaRequest, 'mediaType'>): 'Radarr' | 'Sonarr' {
  return r.mediaType === 'movie' ? 'Radarr' : 'Sonarr'
}

/** Requests that wait for an admin, and open problems: the page's pill and the tab's badge. */
export function inboxWaiting(inbox: Pick<Inbox, 'requests' | 'problems'> | undefined): number {
  if (!inbox) return 0
  return inbox.requests.filter((r) => r.status === 'pending' || r.status === 'failed').length + inbox.problems.length
}

export type RowNote = { tone: 'ok' | 'err'; text: string }

/** The app's own words for why it refused a request. */
export function appSaid(r: Pick<MediaRequest, 'mediaType' | 'note'>): string {
  const app = requestApp(r)
  return r.note ? `${app} said: “${r.note.trim().replace(/[.\s]+$/, '')}”.` : `${app} didn’t take it.`
}

/** What a failed request's row says: the app's own words and what to do. */
export function failedLine(r: Pick<MediaRequest, 'mediaType' | 'note'>): string {
  const app = requestApp(r)
  return `${appSaid(r)} Fix it in ${app}, or change where requests go, then retry.`
}

/**
 * Approve's or Retry's result in its row. The server answers with the
 * request: approved (the app took it and is searching) or failed again (the
 * app refused, with its words as the note).
 */
export function decisionNote(reply: Pick<MediaRequest, 'status' | 'mediaType' | 'note' | 'requestedBy'>): RowNote {
  if (reply.status === 'failed') return { tone: 'err', text: `It didn’t go through. ${failedLine(reply)}` }
  if (reply.status === 'declined') return { tone: 'ok', text: `✓ Declined. ${reply.requestedBy} has been told.` }
  return { tone: 'ok', text: `✓ Approved just now. ${requestApp(reply)} is searching. ${reply.requestedBy} has been told.` }
}

/** A decision or Mark resolved the server refused or never answered: nothing changed. */
export function actionFailed(action: 'approve' | 'resolve', error: unknown, words: (e: unknown) => string): { title: string; body: string } {
  const still = action === 'approve' ? 'it’s still waiting' : 'it’s still open'
  const title = action === 'approve' ? 'Couldn’t approve it' : 'Couldn’t mark it resolved'
  if (error instanceof TypeError) return { title, body: `The server didn’t answer, so ${still}. Try again.` }
  const said = words(error).replace(/\.$/, '')
  return { title, body: `${said}, so ${still}. Try again.` }
}
