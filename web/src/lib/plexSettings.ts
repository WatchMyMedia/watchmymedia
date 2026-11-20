// The rules of the Plex settings tab: which
// state the hub is in, what its setup guide and Next step say, which step of
// each flow an address and the server's state open, and the words for every
// refusal the Plex routes give.

import { guideProgress, resolveStep, type FlowStep } from './flowSteps'
import { plexReason, type PlexConnection, type PlexCounts, type PlexMappingDraft, type PlexPerson, type PlexSection } from './plex'

// ---------------------------------------------------------------------------
// The flows' steps. Each key is the address's last segment.

export const CONNECT_STEPS: readonly FlowStep[] = [
  { key: 'sign-in', label: 'Sign in', guarded: true },
  { key: 'server', label: 'Choose server', guarded: true },
  { key: 'done', label: 'Connected' },
]

export const LINK_STEPS: readonly FlowStep[] = [
  { key: 'people', label: 'People' },
  { key: 'libraries', label: 'Libraries' },
  { key: 'review', label: 'Review' },
  { key: 'saved', label: 'Saved' },
]

export const IMPORT_STEPS: readonly FlowStep[] = [
  { key: 'check', label: 'Check' },
  { key: 'changes', label: 'What will change' },
  { key: 'how', label: 'Choose how' },
  { key: 'import', label: 'Import' },
  { key: 'done', label: 'Imported' },
]

export const UNDO_STEPS: readonly FlowStep[] = [
  { key: 'check', label: 'Check undo' },
  { key: 'confirm', label: 'Confirm' },
  { key: 'done', label: 'Undone' },
]

export const RESTORE_STEPS: readonly FlowStep[] = [
  { key: 'what', label: 'What this does' },
  { key: 'ready', label: 'Ready to check again' },
]

// ---------------------------------------------------------------------------
// The hub.

type HubConnection = Pick<PlexConnection, 'status' | 'linkedUsers' | 'linkedLibraries'>

export type PlexHubState = 'new' | 'mid' | 'set'

export function plexLinked(c?: Pick<PlexConnection, 'linkedUsers' | 'linkedLibraries'>) {
  return !!c && c.linkedUsers > 0 && c.linkedLibraries > 0
}

/** Not connected; connected but not yet linked and imported; set up. */
export function plexHubState(c: HubConnection | undefined, approved: boolean): PlexHubState {
  if (!c) return 'new'
  return plexLinked(c) && approved ? 'set' : 'mid'
}

export type PlexGuideKey = 'connect' | 'link' | 'import'

/** The setup guide: Connect, then Link, then Check and import, each waiting
 * on the one before. Changing the links sets the import back to Now. */
export function plexGuide(c: HubConnection | undefined, approved: boolean) {
  const connected = !!c
  const linked = plexLinked(c)
  const tasks = [
    { key: 'connect', done: connected },
    { key: 'link', done: linked, waitsFor: connected ? undefined : 'connect' },
    { key: 'import', done: linked && approved, waitsFor: linked ? undefined : 'link' },
  ]
  const progress = guideProgress(tasks)
  const state = (key: PlexGuideKey): 'done' | 'now' | 'todo' => {
    const t = tasks.find((x) => x.key === key)!
    return t.done ? 'done' : progress.now === key ? 'now' : 'todo'
  }
  return { done: progress.done, total: progress.total, state }
}

export type PlexNextStep = 'connect' | 'reconnect' | 'running' | 'link' | 'check' | null

/** The hub's one Next step, or null once set up (the status takes its place). */
export function plexNextStep(c: HubConnection | undefined, approved: boolean, importing: boolean): PlexNextStep {
  if (!c) return 'connect'
  if (c.status !== 'connected') return 'reconnect'
  if (importing) return 'running'
  if (!plexLinked(c)) return 'link'
  if (!approved) return 'check'
  return null
}

/** Pause works at once unless an import is running, which it would cancel. */
export function pauseNeedsDialog(syncEnabled: boolean, importing: boolean) {
  return syncEnabled && importing
}

// ---------------------------------------------------------------------------
// Connect a Plex server.

/** The sign-in moves on by itself once Plex says the admin is in, and the
 * finished screen shows only after this flow connected. */
export function connectStep(state: { signedIn: boolean; connected: boolean }): string {
  if (state.connected) return 'done'
  return state.signedIn ? 'server' : 'sign-in'
}

// ---------------------------------------------------------------------------
// Link people and libraries.

export type LinkProgress = { people: boolean; libraries: boolean; saved: boolean }

export function linkStep(requested: string | undefined, progress: LinkProgress): string {
  return resolveStep(LINK_STEPS, requested, (key) =>
    key === 'people' ? progress.people : key === 'libraries' ? progress.libraries : key === 'review' ? progress.saved : false,
  ).key
}

/** Today's links as the draft, with people who can't be read left out: the
 * server refuses to link them, so they never hold a link the admin can't see. */
export function plexLinkDraft(people: PlexPerson[], sections: PlexSection[]): PlexMappingDraft {
  return {
    users: Object.fromEntries(people.map((p) => [p.plexUserId, p.status === 'ready' ? (p.linkedUserId ?? 0) : 0])),
    libraries: Object.fromEntries(sections.map((s) => [s.sectionId, s.linkedLibraryId ?? 0])),
  }
}

/** Two Plex people sent to one account, worded with their names. */
export function plexPeopleConflict(draft: PlexMappingDraft, people: Pick<PlexPerson, 'plexUserId' | 'title'>[], users: { id: number; name: string }[]) {
  const seen = new Map<number, number>()
  for (const p of people) {
    const local = draft.users[p.plexUserId] ?? 0
    if (!local) continue
    const first = seen.get(local)
    if (first !== undefined) {
      const a = people.find((x) => x.plexUserId === first)!.title
      const account = users.find((u) => u.id === local)?.name ?? 'one account'
      return {
        title: `${a} and ${p.title} can’t both go to the account ${account}`,
        body: 'Each account here takes one Plex person. Change one of them, or leave one out.',
      }
    }
    seen.set(local, p.plexUserId)
  }
  return null
}

export function plexPersonKind(kind?: string) {
  return kind === 'owner' ? 'Owner' : kind === 'managed' ? 'Plex Home profile' : kind === 'account' ? 'Plex account' : 'Plex person'
}

export function plexPersonReady(status: string): { tone: 'ok' | 'warn'; label: string } {
  if (status === 'ready') return { tone: 'ok', label: 'Ready' }
  if (status === 'needs_pin') return { tone: 'warn', label: 'Needs a PIN' }
  if (status === 'needs_profile_authorization') return { tone: 'warn', label: 'Can’t be read yet' }
  return { tone: 'warn', label: 'Unavailable' }
}

/** Why a person isn't offered an account, beside them. */
export function plexPersonLeftOut(person: Pick<PlexPerson, 'title' | 'status'>) {
  return person.status === 'needs_pin'
    ? 'Profiles with a PIN can’t be imported yet. Left out.'
    : `Check ${person.title}’s access in Plex, then refresh. Left out for now.`
}

/** "Robin → Robin · Alex → Alex · Casey and Jordan left out". */
export function linkSummary(pairs: [string, string][], leftOut: string[]) {
  const parts = pairs.map(([from, to]) => `${from} → ${to}`)
  if (leftOut.length) parts.push(`${nameList(leftOut)} left out`)
  return parts.join(' · ') || 'Nothing linked'
}

// ---------------------------------------------------------------------------
// Check and import.

export type ImportState = {
  /** The check this flow made, if any. */
  preview?: { status: string }
  /** Choose how to import was pressed on What will change. */
  confirmed: boolean
  /** The import this flow follows. */
  run?: { status: string }
}

/**
 * Which step of Check and import to show. Once an import exists there is no
 * Back: it shows on Import while it runs or stopped part-way, and on Imported
 * once it finished. Before that, a finished check moves on to What will
 * change by itself, and Choose how needs What will change to be confirmed.
 */
export function importStep(requested: string | undefined, s: ImportState): string {
  if (s.run) return s.run.status === 'complete' ? 'done' : 'import'
  const checked = s.preview?.status === 'complete' || s.preview?.status === 'stale'
  if (!checked) return 'check'
  return requested === 'how' && s.confirmed ? 'how' : 'changes'
}

/** The board's names for a check's counts. */
export const IMPORT_COUNT_LABELS: [keyof PlexCounts, string][] = [
  ['willMark', 'Will be marked watched'],
  ['alreadyWatched', 'Already watched here'],
  ['localKept', 'Your choices kept'],
  ['unmatched', 'Not found here'],
  ['ambiguous', 'Need a closer match'],
  ['unavailable', 'No longer available'],
]

/** Whole minutes a check is still good for, 0 once it ran out. */
export function minutesLeft(expiresAt: string | undefined, now: number) {
  const end = expiresAt ? Date.parse(expiresAt) : NaN
  if (!Number.isFinite(end) || end <= now) return 0
  return Math.max(1, Math.ceil((end - now) / 60_000))
}

/** Choose how's button names what it will do. */
export function importLabel(willMark: number) {
  return willMark > 0 ? `Import ${count(willMark, 'watched mark')}` : 'Import now'
}

/** A stopped import can be retried for 7 days after it started. */
export const RETRY_DAYS = 7

export function retryUntil(startedAt: string): Date {
  return new Date(Date.parse(startedAt) + RETRY_DAYS * 86_400_000)
}

export function canRetry(
  run: { status: string; generation: number; startedAt: string },
  c: Pick<PlexConnection, 'status' | 'generation'>,
  now: number,
): { ok: boolean; why?: string } {
  if (!['failed', 'partial', 'cancelled'].includes(run.status)) return { ok: false }
  if (retryUntil(run.startedAt).getTime() <= now) return { ok: false, why: 'Retry is no longer offered. Check and import again.' }
  if (c.status !== 'connected') return { ok: false, why: 'Plex needs you to sign in again first.' }
  if (run.generation !== c.generation) return { ok: false, why: 'The links changed since this import. Check and import again.' }
  return { ok: true }
}

export function skipped(counts: { unmatched: number; ambiguous: number; unavailable: number }) {
  return counts.unmatched + counts.ambiguous + counts.unavailable
}

// ---------------------------------------------------------------------------
// Undo an import, and a restored server.

/** Confirm only after the check found something; once undone, only Undone. */
export function undoStep(requested: string | undefined, s: { checked: boolean; undone: boolean }): string {
  if (s.undone) return 'done'
  return resolveStep(UNDO_STEPS, requested, (key) => (key === 'check' ? s.checked : false)).key
}

export function restoreStep(s: { restarted: boolean }): string {
  return s.restarted ? 'ready' : 'what'
}

// ---------------------------------------------------------------------------
// Words.

export function count(n: number, one: string, many = `${one}s`) {
  return `${n.toLocaleString()} ${n === 1 ? one : many}`
}

/** "Robin", "Robin and Alex", "Robin, Alex and Casey". */
export function nameList(names: string[]) {
  if (names.length <= 1) return names[0] ?? ''
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
}

/** "a moment ago", "6 minutes ago", "3 hours ago"; null past a day. */
export function ago(iso: string | undefined, now: number) {
  const at = iso ? Date.parse(iso) : NaN
  if (!Number.isFinite(at)) return null
  const minutes = Math.floor((now - at) / 60_000)
  if (minutes < 1) return 'a moment ago'
  if (minutes < 60) return `${count(minutes, 'minute')} ago`
  const hours = Math.floor(minutes / 60)
  return hours < 24 ? `${count(hours, 'hour')} ago` : null
}

/** "Plex was last checked 6 minutes ago. Next check in about 9 minutes." */
export function syncLine(lastFinishedAt: string | undefined, now: number) {
  const last = ago(lastFinishedAt, now)
  if (!last || !lastFinishedAt) return 'Plex is checked about every 15 minutes.'
  const next = Math.ceil((Date.parse(lastFinishedAt) + 15 * 60_000 - now) / 60_000)
  return `Plex was last checked ${last}. ${next > 0 ? `Next check in about ${count(next, 'minute')}.` : 'The next check is due now.'}`
}

/** The host of a server's address, for "Maple House at 192.0.2.10". */
export function originHost(origin: string) {
  try {
    return new URL(origin).hostname || origin
  } catch {
    return origin
  }
}

// ---------------------------------------------------------------------------
// Refusals. Each says what happened, whether anything was saved, and what to
// do, with the fix as a button where there is one. Null means say
// nothing: the password dialog was cancelled.

export type PlexFix =
  | 'start-again'
  | 'sign-in-again'
  | 'try-again'
  | 'exit'
  | 'check-again'
  | 'see-import'
  | 'link'
  | 'reconnect'
  | 'refresh'
  | 'check-undo'
  | 'restore'

export type PlexMessage = {
  tone: 'err' | 'warn'
  title: string
  body: string
  fix?: { do: PlexFix; label: string }
  /** The mistake belongs under the address field. */
  field?: boolean
}

/** The reason code a Plex refusal carries, or null. */
export function plexCode(error: unknown): string | null {
  if (typeof error === 'string') return /^[a-z_]+$/.test(error) ? error : null
  return error instanceof Error && /^[a-z_]+$/.test(error.message) ? error.message : null
}

function cancelled(error: unknown) {
  return error instanceof Error && error.name === 'ConfirmationCancelled'
}

/** fetch rejects with a TypeError when this server can't be reached at all. */
function offline(error: unknown) {
  return error instanceof TypeError
}

const UNREACHABLE = new Set(['transport', 'timeout', 'network', 'unreachable', 'response_read', 'unavailable', 'http'])

/** Plex couldn't be reached at its address. */
export function plexUnreachable(code: string | null) {
  return !!code && UNREACHABLE.has(code)
}

const OFFLINE: PlexMessage = {
  tone: 'err',
  title: 'The server didn’t answer',
  body: 'Nothing was changed. Check the connection and try again.',
  fix: { do: 'try-again', label: 'Try again' },
}

/** The reason's words, if they are known. */
function reason(code: string | null) {
  return code ? plexReason(code, '') : ''
}

export function signInMessage(error: unknown): PlexMessage | null {
  if (!error || cancelled(error)) return null
  if (offline(error)) return { ...OFFLINE, fix: { do: 'start-again', label: 'Start again' } }
  const code = plexCode(error)
  switch (code) {
    case 'too_many_sign_ins':
      return {
        tone: 'err',
        title: 'Too many sign-ins are waiting',
        body: 'Eight sign-ins were started and not finished. They clear by themselves in a few minutes. Wait, then start again.',
      }
    case 'sign_in_not_found':
      return {
        tone: 'err',
        title: 'The sign-in ran out of time',
        body: 'Plex only waits a few minutes. Nothing was connected.',
        fix: { do: 'start-again', label: 'Start again' },
      }
    case 'restricted_account':
      return {
        tone: 'err',
        title: 'That Plex account doesn’t own a server',
        body: 'It’s a shared or managed account. Sign in with the account that owns the Plex server. In Plex, the owner is the account under Settings › Account on the server itself.',
        fix: { do: 'start-again', label: 'Sign in with another account' },
      }
    case 'bad_sign_in_link':
      return {
        tone: 'err',
        title: 'Plex’s sign-in link could not be checked',
        body: 'Nothing was connected. Start again for a new link.',
        fix: { do: 'start-again', label: 'Start again' },
      }
  }
  return {
    tone: 'err',
    title: 'Couldn’t sign in to Plex',
    body: reason(code) || 'Nothing was connected. Check this server’s connection, then start again.',
    fix: { do: 'start-again', label: 'Start again' },
  }
}

export function connectMessage(error: unknown, server: string): PlexMessage | null {
  if (!error || cancelled(error)) return null
  if (offline(error)) return OFFLINE
  const code = plexCode(error)
  if (code === 'server_not_owned' || code === 'no_owned_server')
    return {
      tone: 'err',
      title: 'This account doesn’t own a Plex server',
      body: 'Sign in with the account that owns it, or check the server is signed in to Plex.',
      fix: { do: 'sign-in-again', label: 'Sign in again' },
    }
  if (code === 'server_identity_mismatch' || code === 'identity_mismatch')
    return {
      tone: 'err',
      title: 'That address answered as a different Plex server',
      body: `It isn’t ${server}, or this account doesn’t own it. Choose another address from the list, or type the one ${server} is on.`,
    }
  if (code === 'one_server_only' || code === 'different_owner' || code === 'already_connected')
    return {
      tone: 'err',
      title: 'Another Plex server is already connected',
      body: 'Only one can be connected at a time, and only by the admin who connected it. Disconnect it on the Plex page first.',
      fix: { do: 'exit', label: 'Exit to Plex' },
    }
  if (code === 'origin_not_allowed' || code === 'invalid_origin')
    return { tone: 'err', title: 'Check the address', body: 'Enter the server’s full HTTP or HTTPS address, with its port and no extra path.', field: true }
  if (code === 'sign_in_not_found' || code === 'sign_in_pending') return signInMessage('sign_in_not_found')
  if (plexUnreachable(code))
    return {
      tone: 'err',
      title: `Couldn’t reach ${server} at that address`,
      body: 'Check Plex is running, then try again or pick another address.',
      fix: { do: 'try-again', label: 'Try again' },
    }
  return {
    tone: 'err',
    title: `Couldn’t connect ${server}`,
    body: reason(code) || 'Nothing was connected. Check that Plex is running, try another address, then choose Connect server again.',
    fix: { do: 'try-again', label: 'Try again' },
  }
}

/** A check that couldn't start, or that ended without counts. */
export function checkMessage(error: unknown, server: string): PlexMessage | null {
  if (!error || cancelled(error)) return null
  if (offline(error)) return { ...OFFLINE, fix: { do: 'check-again', label: 'Check again' } }
  const code = plexCode(error)
  switch (code) {
    case 'run_active':
      return {
        tone: 'err',
        title: 'An import is still running',
        body: 'A check can’t start until it ends. Wait for it, or cancel it. What it has already done is kept.',
        fix: { do: 'see-import', label: 'See the import' },
      }
    case 'nothing_linked':
      return { tone: 'err', title: 'Nobody is linked yet', body: 'Link at least one person and one library first.', fix: { do: 'link', label: 'Link people and libraries' } }
    case 'needs_reconnect':
    case 'needs_authorization':
      return reconnectBanner(server)
    case 'stale':
    case 'stale_generation':
    case 'preview_stale':
    case 'stale_preview':
      return {
        tone: 'warn',
        title: 'This check is out of date',
        body: 'The links or the items left out changed since it started. Check again to see the new numbers.',
        fix: { do: 'check-again', label: 'Check again' },
      }
    case 'preview_running':
      return {
        tone: 'warn',
        title: 'A check is already running',
        body: 'It was started a moment ago, here or in another tab. Wait a minute, then check again.',
        fix: { do: 'check-again', label: 'Check again' },
      }
    case 'preview_not_found':
    case 'preview_not_ready':
      return { tone: 'warn', title: 'This check ran out', body: 'Checks are kept for 15 minutes. Nothing was imported. Check again.', fix: { do: 'check-again', label: 'Check again' } }
  }
  if (plexUnreachable(code))
    return {
      tone: 'err',
      title: `Couldn’t reach ${server}`,
      body: 'Check Plex is running. If its address changed, reconnect on the Plex page.',
      fix: { do: 'check-again', label: 'Check again' },
    }
  return {
    tone: 'err',
    title: 'The check couldn’t finish',
    body: reason(code) || 'Nothing was changed here or on Plex. Check again.',
    fix: { do: 'check-again', label: 'Check again' },
  }
}

/** Starting the import from Choose how. */
export function startMessage(error: unknown, server: string): PlexMessage | null {
  if (!error || cancelled(error)) return null
  if (offline(error)) return OFFLINE
  const code = plexCode(error)
  if (code === 'run_active')
    return { tone: 'err', title: 'An import is still running', body: 'Wait for it, or cancel it. Nothing new was imported.', fix: { do: 'see-import', label: 'See the import' } }
  if (code === 'needs_reconnect' || code === 'needs_authorization') return reconnectBanner(server)
  if (code && ['preview_not_ready', 'preview_not_found', 'preview_stale', 'stale_preview', 'stale_generation', 'preview_session_changed', 'generation_changed'].includes(code))
    return {
      tone: 'err',
      title: 'Something changed since the check',
      body: 'The links or left-out items changed, or the check is more than 15 minutes old, so nothing was imported.',
      fix: { do: 'check-again', label: 'Check again' },
    }
  return {
    tone: 'err',
    title: 'Couldn’t start the import',
    body: reason(code) || 'Nothing was imported. Check again, then import.',
    fix: { do: 'check-again', label: 'Check again' },
  }
}

export function linkSaveMessage(error: unknown, server: string): PlexMessage | null {
  if (!error || cancelled(error)) return null
  if (offline(error)) return OFFLINE
  const code = plexCode(error)
  if (code && ['stale_generation', 'generation_changed', 'unknown_plex_user', 'unknown_section', 'plex_user_not_ready'].includes(code))
    return {
      tone: 'err',
      title: 'Couldn’t save the links',
      body: `${server}’s people or libraries changed while you were here. Refresh people and libraries, look over your choices, then save again.`,
      fix: { do: 'refresh', label: 'Refresh people and libraries' },
    }
  if (code === 'unknown_user' || code === 'unknown_library')
    return { tone: 'err', title: 'Couldn’t save the links', body: `${reason(code)} Nothing was saved.`, fix: { do: 'refresh', label: 'Refresh people and libraries' } }
  if (code === 'run_active')
    return { tone: 'err', title: 'An import is running', body: 'Wait for it, or cancel it, before changing the links. Nothing was saved.', fix: { do: 'see-import', label: 'See the import' } }
  return { tone: 'err', title: 'Couldn’t save the links', body: reason(code) || 'Nothing was saved. Try again.', fix: { do: 'try-again', label: 'Try again' } }
}

export function excludeMessage(error: unknown): PlexMessage | null {
  if (!error || cancelled(error)) return null
  if (offline(error)) return OFFLINE
  const code = plexCode(error)
  if (code === 'run_active') return { tone: 'err', title: 'An import is running', body: 'Items can’t be left out or let back in while it runs. Nothing changed.' }
  return { tone: 'err', title: 'Couldn’t change the items left out', body: reason(code) || 'Nothing changed. Try again.' }
}

export function retryMessage(error: unknown): PlexMessage | null {
  if (!error || cancelled(error)) return null
  if (offline(error)) return OFFLINE
  const code = plexCode(error)
  if (code === 'run_scope_expired')
    return { tone: 'err', title: 'This import can’t be retried any more', body: 'It’s more than 7 days old, or what it covered changed. Check and import again.', fix: { do: 'check-again', label: 'Check again' } }
  if (code === 'stale_generation' || code === 'run_not_retryable')
    return { tone: 'err', title: 'This import can’t be retried', body: 'The links or the connection changed since it ran. Check and import again.', fix: { do: 'check-again', label: 'Check again' } }
  if (code === 'run_active') return { tone: 'err', title: 'Another import is running', body: 'Wait for it to finish, or cancel it, then retry.' }
  return { tone: 'err', title: 'Couldn’t retry the import', body: reason(code) || 'Nothing changed. Try again.', fix: { do: 'try-again', label: 'Try again' } }
}

export function cancelRunMessage(error: unknown): PlexMessage | null {
  if (!error || cancelled(error)) return null
  if (offline(error)) return OFFLINE
  return { tone: 'err', title: 'Couldn’t cancel the import', body: reason(plexCode(error)) || 'It may still be running. Try again.', fix: { do: 'try-again', label: 'Try again' } }
}

/** Checking or confirming an undo. `nothing` is a check that found nothing to take back. */
export function undoMessage(error: unknown, stage: 'check' | 'undo'): PlexMessage | null {
  if (!error || cancelled(error)) return null
  if (offline(error)) return OFFLINE
  const code = plexCode(error)
  if (code === 'nothing' || code === 'run_not_found' || (stage === 'check' && code === 'stale_generation'))
    return {
      tone: 'err',
      title: 'This import can’t be undone any more',
      body: 'A later import or a change to the links replaced it. To clear a watched mark, use Mark unwatched on the movie or episode.',
    }
  if (code === 'undo_preview_stale' || (stage === 'undo' && code === 'stale_generation'))
    return { tone: 'err', title: 'The undo check ran out', body: 'It expired, or the connection changed. Nothing was undone. Check undo again.', fix: { do: 'check-undo', label: 'Check undo again' } }
  if (code === 'run_active')
    return { tone: 'err', title: 'An import is running', body: 'Wait for it to finish, or cancel it, then check undo again.', fix: { do: 'check-undo', label: 'Check undo again' } }
  return stage === 'check'
    ? { tone: 'err', title: 'Couldn’t check the undo', body: reason(code) || 'Nothing was undone. Try again.', fix: { do: 'check-undo', label: 'Check undo again' } }
    : { tone: 'err', title: 'Couldn’t undo the import', body: reason(code) || 'Nothing was undone. Try again.', fix: { do: 'try-again', label: 'Try again' } }
}

/** Pause and Resume on the hub. A refusal says the switch is back as it was. */
export function syncMessage(error: unknown, pausing: boolean, server: string): PlexMessage | null {
  if (!error || cancelled(error)) return null
  const code = plexCode(error)
  const stays = pausing ? 'Updates are still on.' : 'Updates are still paused.'
  const title = pausing ? 'Couldn’t pause updates' : 'Couldn’t resume updates'
  if (code === 'needs_reconnect' || code === 'needs_authorization') return reconnectBanner(server)
  if (code === 'import_first') return { tone: 'err', title, body: `Check what will change and import once before keeping up to date. ${stays}`, fix: { do: 'check-again', label: 'Check what will change' } }
  if (code === 'stale_generation') return { tone: 'err', title, body: `The connection changed while this page was open. ${stays} Try again.`, fix: { do: 'try-again', label: 'Try again' } }
  if (!offline(error) && reason(code)) return { tone: 'err', title, body: `${reason(code)} ${stays}`, fix: { do: 'try-again', label: 'Try again' } }
  return { tone: 'err', title, body: `The server didn’t answer. ${stays} Try again.`, fix: { do: 'try-again', label: 'Try again' } }
}

export function restoreMessage(error: unknown): PlexMessage | null {
  if (!error || cancelled(error)) return null
  if (offline(error)) return OFFLINE
  const code = plexCode(error)
  if (code === 'stale_generation') return { tone: 'err', title: 'The connection changed while you were here', body: 'Nothing was paused. Try again.', fix: { do: 'try-again', label: 'Try again' } }
  if (code === 'run_active') return { tone: 'err', title: 'An import is running', body: 'Wait for it, or cancel it, first. Nothing was paused.', fix: { do: 'see-import', label: 'See the import' } }
  return { tone: 'err', title: 'Couldn’t pause and start fresh', body: reason(code) || 'Nothing changed. Try again.', fix: { do: 'try-again', label: 'Try again' } }
}

export function disconnectMessage(error: unknown, server: string): PlexMessage | null {
  if (!error || cancelled(error)) return null
  if (offline(error)) return { ...OFFLINE, fix: undefined }
  const code = plexCode(error)
  if (code === 'connection_not_found') return { tone: 'err', title: `${server} was already disconnected`, body: 'Close this and the Plex page shows where things stand.' }
  return { tone: 'err', title: `Couldn’t disconnect ${server}`, body: reason(code) || 'Nothing changed. Try again.' }
}

/** Plex stopped accepting this server: the hub's banner and the flows' message. */
export function reconnectBanner(server: string): PlexMessage {
  return {
    tone: 'warn',
    title: 'Plex needs you to sign in again',
    body: `${server} stopped accepting this server, and updates are on hold. Reconnecting keeps your links.`,
    fix: { do: 'reconnect', label: 'Reconnect' },
  }
}

/** A problem the connection reports, as a banner on the hub. */
export function problemBanner(problem: string | undefined): PlexMessage | null {
  if (!problem) return null
  if (problem === 'source_identity_changed')
    return { tone: 'warn', title: 'Updates from Plex are on hold', body: plexReason(problem), fix: { do: 'restore', label: 'Check a restored server' } }
  return { tone: 'warn', title: 'Updates from Plex are on hold', body: plexReason(problem, 'Plex answered in a way this server can’t use. Check Plex, then check what will change again.') }
}
