// The Libraries tab's rules: which step of
// Add a library an address opens, each library's state in words, what each
// refusal says, and the unmatched queue's order and outcome.

import { ApiError } from '../api/client'
import { resolveStep, type FlowStep } from './flowSteps'
import { libraryLabel, savedScanError, type LibraryType } from './setupRules'
import type { components } from '../api/schema'

type Library = components['schemas']['Library']
type PathCheck = components['schemas']['PathCheck']
type Group = components['schemas']['UnmatchedGroup']

// ── Add a library ────────────────────────────────────────────────────────

export const ADD_STEPS: readonly FlowStep[] = [
  { key: 'type', label: 'Type' },
  { key: 'folder', label: 'Folder' },
  { key: 'name', label: 'Name and add', guarded: true },
  { key: 'added', label: 'Added' },
]

export type AddDraft = {
  type: LibraryType | null
  path: string
  /** The last check, for the path it was run on. */
  check: PathCheck | null
  name: string
  /** Set once the library is added: the flow can't be reopened after. */
  added: { id: number; name: string; path: string } | null
}

export function emptyAddDraft(): AddDraft {
  return { type: null, path: '', check: null, name: '', added: null }
}

/** The check belongs to the folder typed now, and the folder can be read. */
export function folderReady(draft: Pick<AddDraft, 'path' | 'check'>) {
  const c = draft.check
  return !!c && samePath(c.path, draft.path) && c.exists && c.readable
}

function samePath(a: string, b: string) {
  const clean = (p: string) => p.trim().replace(/[/\\]+$/, '') || p.trim()
  return clean(a) === clean(b)
}

export function addStepDone(draft: AddDraft, key: string) {
  if (key === 'type') return draft.type !== null
  if (key === 'folder') return draft.type !== null && folderReady(draft)
  if (key === 'name') return draft.added !== null
  return false
}

/**
 * The step an Add a library address opens. Once the library is added it
 * can't be reopened here: its steps open the Added screen, and the flow's
 * own address (or Type) starts a new library (`reset`).
 */
export function addFlowStep(draft: AddDraft, requested: string | undefined): { step: FlowStep; reset: boolean } {
  if (draft.added) {
    if (requested === undefined || requested === 'type') return { step: ADD_STEPS[0], reset: true }
    return { step: ADD_STEPS[3], reset: false }
  }
  return { step: resolveStep(ADD_STEPS, requested, (k) => addStepDone(draft, k)), reset: false }
}

/** Choosing a type names the library after it, unless a name was typed. */
export function chooseType(draft: AddDraft, type: LibraryType): AddDraft {
  const named = draft.name.trim() !== '' && draft.name !== libraryLabel(draft.type ?? type)
  return { ...draft, type, name: named ? draft.name : libraryLabel(type) }
}

/** The name a new library gets: what was typed, else its type's name. */
export function addBody(draft: AddDraft) {
  const type = draft.type ?? 'movie'
  return { type, name: draft.name.trim() || libraryLabel(type), path: draft.path.trim() }
}

// ── The folder check ─────────────────────────────────────────────────────

export type FolderResult =
  | { kind: 'missing' }
  | { kind: 'unreadable' }
  | { kind: 'empty' }
  | { kind: 'oneShow'; parent: string | null }
  | { kind: 'looksTV' }
  | { kind: 'ok'; count: number; truncated: boolean }

/** One result of a check at a time, the most useful first. */
export function folderResult(type: LibraryType, check: PathCheck): FolderResult {
  if (!check.exists) return { kind: 'missing' }
  if (!check.readable) return { kind: 'unreadable' }
  if (check.videoFiles === 0) return { kind: 'empty' }
  const seasons = check.sample.filter((name) => /^(?:season[ ._-]*\d+|specials)$/i.test(name.normalize('NFC'))).length
  if (seasons > 0) return type === 'tv' ? { kind: 'oneShow', parent: parentFolder(check.path) } : { kind: 'looksTV' }
  return { kind: 'ok', count: check.videoFiles, truncated: !!check.truncated }
}

/** The folder above a path, or null at the top (/, D:\, \\server\share). */
export function parentFolder(path: string): string | null {
  const p = path.trim().replace(/[/\\]+$/, '')
  const sep = p.includes('\\') && !p.includes('/') ? '\\' : '/'
  const at = p.lastIndexOf(sep)
  if (at < 0) return null
  const parent = p.slice(0, at)
  if (sep === '/') return parent === '' ? (p.length > 1 ? '/' : null) : parent
  // Windows: keep "D:\" and never climb above a share's root.
  if (/^[A-Za-z]:$/.test(parent)) return `${parent}\\`
  if (/^\\\\[^\\]+$/.test(parent) || parent === '\\' || parent === '') return null
  return parent
}

export function videoCount(count: number, truncated = false) {
  return `${truncated ? 'at least ' : ''}${count.toLocaleString()} video file${count === 1 ? '' : 's'}`
}

// ── Refusals ─────────────────────────────────────────────────────────────

/** `fix` names the step that fixes it; `gone` says there is nothing left to do. */
export type Refusal = { title: string; body: string; fix?: 'folder'; gone?: boolean }

/** "TV Shows’ folder", "Anime’s folder". */
export function possessive(name: string) {
  return /s$/i.test(name) ? `${name}’` : `${name}’s`
}

const OVERLAP = /that folder (is inside|holds) the "(.+)" library \((.+)\)/

/**
 * What a refused add or change says, from the
 * server's status and detail. Null when there is nothing to say: the
 * password dialog was cancelled.
 */
export function libraryRefusal(error: unknown, path: string, action: 'add' | 'save'): Refusal | null {
  if (error instanceof Error && error.name === 'ConfirmationCancelled') return null
  const nothing = action === 'add' ? 'Nothing was added.' : 'Nothing was changed.'
  if (error instanceof TypeError) return { title: action === 'add' ? 'Couldn’t add the library' : 'Couldn’t save', body: `The server didn’t answer. ${nothing} Check the connection and try again.` }
  if (error instanceof ApiError) {
    const detail = error.message
    if (error.status === 409) {
      const m = OVERLAP.exec(detail)
      if (m && m[1] === 'is inside') {
        const tail = action === 'add' ? `Choose a folder that no library holds, or change ${possessive(m[2])} folder first.` : 'Choose a folder no library holds.'
        return { title: `${path} is inside another library`, body: `${m[2]} already covers ${m[3]}. ${tail}`, fix: 'folder' }
      }
      if (m) return { title: `${path} holds another library`, body: `${m[2]} is inside it, at ${m[3]}. Choose a folder no library holds, or change ${possessive(m[2])} folder first.`, fix: 'folder' }
      return { title: `${path} is already a library`, body: 'Choose a folder that no library holds.', fix: 'folder' }
    }
    if (error.status === 422) {
      if (/must be absolute/.test(detail)) return { title: `${path} isn’t a full path`, body: 'Start it from the top of the server’s folders, such as /media/movies or D:\\Movies.', fix: 'folder' }
      if (/not found|not a folder/.test(detail)) {
        return action === 'add'
          ? { title: `${path} is no longer there`, body: 'It was found a moment ago. Check the drive is connected, then go back to Folder.', fix: 'folder' }
          : { title: `${path} wasn’t found on the server`, body: 'Check the spelling, or use Browse to pick it. Nothing was changed.' }
      }
    }
    if (error.status === 404) return { title: 'This library isn’t here any more', body: 'Someone may have removed it. Go back to Libraries.' }
    if (error.status === 429) return { title: 'Too many tries', body: `Wait ${error.retryAfter} seconds and try again. ${nothing}` }
    const said = detail && !detail.startsWith('Request failed with status') ? `${detail[0].toUpperCase()}${detail.slice(1).replace(/\.?$/, '.')} ` : ''
    return { title: action === 'add' ? 'Couldn’t add the library' : 'Couldn’t save', body: `${said}${nothing} Try again.` }
  }
  return { title: action === 'add' ? 'Couldn’t add the library' : 'Couldn’t save', body: `${nothing} Try again.` }
}

/** Why Scan or Match didn't start. */
export function startRefusal(error: unknown, what: 'scan' | 'match', name: string): Refusal | null {
  if (error instanceof Error && error.name === 'ConfirmationCancelled') return null
  if (what === 'match' && error instanceof ApiError && error.status === 503) return { title: 'Match is off', body: 'There’s no TMDB key.' }
  if (what === 'scan' && error instanceof ApiError && error.status === 503) return { title: `Couldn’t scan ${name}`, body: 'The scanner isn’t running on the server. Restart the server, then scan again.' }
  if (error instanceof ApiError && error.status === 404) return { title: 'This library isn’t here any more', body: 'Someone may have removed it.' }
  return what === 'scan'
    ? { title: `Couldn’t scan ${name}`, body: 'The server didn’t start the scan. Check the connection, then scan again.' }
    : { title: 'Couldn’t match: the server didn’t answer', body: 'Nothing was changed. Try again in a few minutes.' }
}

/** Why Remove failed. */
export function removeRefusal(error: unknown, name: string): Refusal | null {
  if (error instanceof Error && error.name === 'ConfirmationCancelled') return null
  if (error instanceof ApiError && error.status === 404) return { title: `${name} is already gone`, body: 'Someone else removed it. Nothing more to do.', gone: true }
  if (error instanceof ApiError && error.status === 429) return { title: `Couldn’t remove ${name}`, body: `Too many tries. Wait ${error.retryAfter} seconds and try again. Nothing was removed.` }
  if (error instanceof ApiError && error.status >= 400 && error.status < 500 && error.message && !error.message.startsWith('Request failed')) {
    return { title: `Couldn’t remove ${name}`, body: `${error.message.replace(/\.?$/, '.')} Nothing was removed.` }
  }
  return { title: `Couldn’t remove ${name}`, body: 'The server didn’t answer and nothing was removed. Try again.' }
}

// ── A library's state, in words ──────────────────────────────────────────

export type LiveActivity = {
  scan?: { scanned: number; total: number }
  match?: { done: number; total: number }
  lastScan?: { finished?: { partial?: boolean; failed: number }; failed?: { error: string }; at: number }
}

export type LibraryState = { tone: 'ok' | 'warn' | 'err' | 'now' | 'off'; label: string }

/** The pill on a library's row and page. */
export function libraryState(lib: Library, live: LiveActivity = {}): LibraryState {
  if (live.scan || lib.scan) return { tone: 'now', label: 'Scanning' }
  if (live.match || lib.match) return { tone: 'now', label: 'Matching' }
  if (live.lastScan?.failed?.error || savedScanError(lib, false, live.lastScan)) return { tone: 'err', label: 'Scan failed' }
  if (lib.unmatchedCount > 0) return { tone: 'warn', label: `${lib.unmatchedCount.toLocaleString()} unmatched` }
  if (lib.pendingCount > 0) return { tone: 'off', label: `${lib.pendingCount.toLocaleString()} waiting to match` }
  if (!lib.lastScannedAt) return { tone: 'off', label: 'Not scanned yet' }
  return { tone: 'ok', label: 'Up to date' }
}

export function fileCount(n: number) {
  return `${n.toLocaleString()} file${n === 1 ? '' : 's'}`
}

/** The library with the most unmatched files, for the hub's Needs you. */
export function mostUnmatched(libraries: readonly Library[]): Library | null {
  return libraries.reduce<Library | null>((best, l) => (l.unmatchedCount > 0 && (!best || l.unmatchedCount > best.unmatchedCount) ? l : best), null)
}

/** "9 need a show chosen, 5 have seasons TMDB numbers differently". */
export function unmatchedBreakdown(type: LibraryType, groups: readonly Group[]) {
  const files = (kind: Group['kind']) => groups.filter((g) => g.kind === kind).reduce((n, g) => n + g.files.length, 0)
  const parts = [
    [files('show'), `need${files('show') === 1 ? 's' : ''} a ${type === 'tv' ? 'show' : 'movie'} chosen`],
    [files('episodes'), `${files('episodes') === 1 ? 'has' : 'have'} seasons TMDB numbers differently`],
    [files('lookup'), `${files('lookup') === 1 ? 'hasn’t' : 'haven’t'} been looked up yet`],
  ] as const
  const said = parts.filter(([n]) => n > 0).map(([n, words]) => `${n.toLocaleString()} ${words}`)
  return said.length ? `${said.join(', ')}.` : ''
}

export const SCAN_INTERVALS: readonly (readonly [number, string])[] = [
  [5, 'Every 5 minutes'],
  [15, 'Every 15 minutes'],
  [30, 'Every 30 minutes'],
  [60, 'Every hour'],
  [360, 'Every 6 hours'],
  [1440, 'Once a day'],
]

/** The schedule's choices; an interval set in config.yaml may be none of these. */
export function scanIntervalOptions(current: number | undefined) {
  return current && !SCAN_INTERVALS.some(([m]) => m === current) ? [...SCAN_INTERVALS, [current, `Every ${current} minutes`] as const] : SCAN_INTERVALS
}

export function nextScanIn(minutes: number) {
  if (minutes % 1440 === 0) return minutes === 1440 ? 'a day' : `${minutes / 1440} days`
  if (minutes % 60 === 0) return minutes === 60 ? 'an hour' : `${minutes / 60} hours`
  return `${minutes} minutes`
}

// ── Remove ───────────────────────────────────────────────────────────────

type Removal = components['schemas']['LibraryRemoval']

function n(count: number, one: string, many = `${one}s`) {
  return `${count.toLocaleString()} ${count === 1 ? one : many}`
}

/**
 * What Remove says stays and goes, with the server's
 * numbers. Removing a library deletes no plays: Activity history keeps
 * them. What goes is the titles and everyone's watched marks and resume
 * points on them.
 */
export function removalWords(lib: Pick<Library, 'type' | 'path'>, r: Removal | undefined) {
  const stays = [`Every file in ${lib.path}. Nothing on disk is touched.`]
  if (!r) {
    return {
      stays: [...stays, 'Plays in Activity history, with their titles and pictures.'],
      deleted: [
        lib.type === 'tv' ? 'Its shows’ matches and details.' : 'Its movies’ matches and details.',
        'Everyone’s watched marks and resume points on them.',
        'The rows people arranged for this library. Lists made from it move to Home.',
      ],
    }
  }
  const count = lib.type === 'tv' ? r.shows : r.movies
  const titles = count === 0
    ? `Its list of files. None of them is matched to a ${lib.type === 'tv' ? 'show' : 'movie'} yet.`
    : lib.type === 'tv'
      ? `Matches and details for its ${n(r.shows, 'show')} and ${n(r.episodes, 'episode')}.`
      : `Matches and details for its ${n(r.movies, 'movie')}.`
  const marks = r.watched + r.inProgress === 0
    ? 'Nobody has watched marks or resume points on them.'
    : `${n(r.watched, 'watched mark')} and ${n(r.inProgress, 'resume point')}, across ${n(r.people, 'person', 'people')}.`
  return {
    stays: [...stays, r.playsKept > 0 ? `${n(r.playsKept, 'play')} in Activity history, with their titles and pictures.` : 'Activity history. It has no plays from this library.'],
    deleted: [titles.replace(/^The 1 (show|movie)’/, 'The 1 $1’s'), marks, 'The rows people arranged for this library. Lists made from it move to Home.'],
  }
}

// ── Review unmatched: a queue ────────────────────────────────────────────

export type EntryState = 'open' | 'skipped' | 'matched' | 'seasons' | 'ignored'

export type QueueEntry = {
  key: string
  title: string
  files: number
  kind: Group['kind']
  state: EntryState
  /** The group to show now: the server's current one holding these files. */
  live: Group | null
}

/** A file sorted on this screen, kept until the server's list catches up. */
export type Recorded = { state: 'matched' | 'seasons' | 'ignored'; at: number; folder?: string }

/**
 * The queue's entries, in the order the review started with. A group is
 * sorted once none of its files are waiting any more: ignored when they are
 * all in the ignored list, else matched (or seasons, when lined up here).
 * Until the server's list is newer than a sort made here (`liveAt`), the
 * sort made here stands. Files that still wait after it (episodes to pick)
 * show the group the server now holds them in.
 */
export function queueEntries(
  snapshot: readonly Group[],
  live: readonly Group[],
  liveAt: number,
  skipped: ReadonlySet<string>,
  recorded: ReadonlyMap<string, Recorded>,
): QueueEntry[] {
  const waitingIn = new Map<number, Group>()
  const ignored = new Set<number>()
  for (const g of live) {
    for (const f of g.files) {
      if (g.kind === 'ignored') ignored.add(f.id)
      else waitingIn.set(f.id, g)
    }
  }
  return snapshot.map((g) => {
    const same = live.find((l) => l.key === g.key && l.kind !== 'ignored')
    const holder = same ?? g.files.map((f) => waitingIn.get(f.id)).find(Boolean) ?? null
    const base = { key: g.key, title: g.show?.title || g.title || g.folder || g.files[0]?.relativePath || 'Unnamed files', files: g.files.length, kind: g.kind }
    const here = recorded.get(g.key)
    if (here && here.at > liveAt) return { ...base, live: null, state: here.state }
    if (holder) return { ...base, live: holder, kind: holder.kind, state: skipped.has(g.key) ? 'skipped' : 'open' }
    const state: EntryState = g.files.every((f) => ignored.has(f.id)) ? 'ignored' : here?.state === 'seasons' ? 'seasons' : 'matched'
    return { ...base, live: null, state }
  })
}

export function isSorted(state: EntryState) {
  return state === 'matched' || state === 'seasons' || state === 'ignored'
}

/** The queue's place for an address: a 1-based position, or the end. */
export type QueuePlace = { at: number } | { done: true }

/**
 * Which file an address opens. A position in range opens it; the end
 * opens only when nothing is left to decide here (every file sorted or
 * skipped); anything else opens the first file still open.
 */
export function queuePlace(entries: readonly QueueEntry[], requested: string | undefined): QueuePlace {
  const firstOpen = entries.findIndex((e) => e.state === 'open')
  if (requested && /^\d+$/.test(requested)) {
    const at = Number(requested)
    if (at >= 1 && at <= entries.length) return { at }
  }
  if (firstOpen < 0) return { done: true }
  return { at: firstOpen + 1 }
}

/** Where to go after a file is sorted or skipped: the next open one after
 * it, then any before it, else the end. */
export function nextOpen(entries: readonly QueueEntry[], from: number): QueuePlace {
  const after = entries.findIndex((e, i) => i >= from && e.state === 'open')
  if (after >= 0) return { at: after + 1 }
  const before = entries.findIndex((e) => e.state === 'open')
  return before >= 0 ? { at: before + 1 } : { done: true }
}

/** The list's word for an entry. */
export function entryWord(e: Pick<QueueEntry, 'state' | 'kind'>, type: LibraryType) {
  if (e.state === 'matched') return 'matched'
  if (e.state === 'seasons') return 'seasons lined up'
  if (e.state === 'ignored') return 'ignored'
  if (e.state === 'skipped') return 'skipped'
  if (e.kind === 'episodes') return 'seasons'
  if (e.kind === 'lookup') return 'not looked up'
  return type === 'tv' ? 'show?' : 'movie?'
}

/** The end of the queue. */
export function queueSummary(entries: readonly QueueEntry[], recorded: ReadonlyMap<string, Recorded> = new Map()) {
  const count = (s: EntryState) => entries.filter((e) => e.state === s).length
  const files = (s: EntryState) => entries.filter((e) => e.state === s).reduce((n, e) => n + e.files, 0)
  const sorted = entries.filter((e) => isSorted(e.state)).length
  return {
    total: entries.length,
    sorted,
    skipped: count('skipped'),
    open: count('open'),
    matched: count('matched'),
    seasons: count('seasons'),
    ignored: count('ignored'),
    matchedFiles: files('matched') + files('seasons'),
    waitingFiles: files('skipped') + files('open'),
    /** Folders that now remember their match or season line-up for new files. */
    folders: new Set(entries.filter((e) => e.state === 'matched' || e.state === 'seasons').map((e) => recorded.get(e.key)?.folder).filter(Boolean)).size,
  }
}
