import { useEffect, useSyncExternalStore } from 'react'
import { useQueryClient, type QueryClient } from '@tanstack/react-query'
import type { components } from './schema'
import { watchEventInvalidator } from './watch'
import { invalidatePlexState, plexHistoryEventInvalidator, resetPlexHistoryPages, type PlexHistoryChanged } from './plex'
import { authScope } from '../lib/authScope'
import { invalidatePersonalLiveTV, invalidateRecordings, invalidateRowsAndLists } from './queries'

export type ScanProgress = components['schemas']['ScanProgress']
export type ScanFinished = components['schemas']['ScanFinished']
export type ScanFailed = components['schemas']['ScanFailed']
export type MatchProgress = components['schemas']['MatchProgress']
export type MatchFinished = components['schemas']['MatchFinished']
export type ActivityStopped = components['schemas']['ActivityStopped']

type State = {
  connected: boolean
  scans: Record<number, ScanProgress>
  matches: Record<number, MatchProgress>
  lastScan: Record<number, { finished?: ScanFinished; failed?: ScanFailed; at: number }>
  lastMatch: Record<number, { finished: MatchFinished; at: number }>
}

let state: State = { connected: false, scans: {}, matches: {}, lastScan: {}, lastMatch: {} }
const listeners = new Set<() => void>()

function set(patch: Partial<State>) {
  state = { ...state, ...patch }
  listeners.forEach((l) => l())
}

function without<T>(map: Record<number, T>, id: number): Record<number, T> {
  const next = { ...map }
  delete next[id]
  return next
}

function subscribe(l: () => void) {
  listeners.add(l)
  return () => listeners.delete(l)
}

export function useEventState(): State {
  return useSyncExternalStore(subscribe, () => state)
}

let source: EventSource | null = null

const stopListeners = new Set<(e: ActivityStopped) => void>()

/** Calls fn whenever a play ends; returns the unsubscribe function. */
export function onPlayStopped(fn: (e: ActivityStopped) => void): () => void {
  stopListeners.add(fn)
  return () => {
    stopListeners.delete(fn)
  }
}

function invalidateLibrary(qc: QueryClient, libraryId: number) {
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
}

function connect(qc: QueryClient) {
  if (source) return
  const es = new EventSource('/api/events')
  source = es
  // EventSource reconnects on its own, but events sent while it was down
  // (the computer slept, the server restarted) are lost: refetch what is shown.
  let dropped = false
  es.onopen = () => {
    set({ connected: true })
    if (dropped) {
      // Delete/clear notifications may have been missed while disconnected.
      // Discard our manual snapshots without replaying every older page.
      void resetPlexHistoryPages(qc, undefined, authScope())
      void qc.invalidateQueries()
    }
  }
  es.onerror = () => {
    dropped = true
    set({ connected: false })
  }
  const on = <T,>(name: string, handler: (data: T) => void) =>
    es.addEventListener(name, (ev) => handler(JSON.parse((ev as MessageEvent).data) as T))

  on<ScanProgress>('scan.started', (p) => set({ scans: { ...state.scans, [p.libraryId]: { ...p, scanned: 0 } } }))
  on<ScanProgress>('scan.progress', (p) => set({ scans: { ...state.scans, [p.libraryId]: p } }))
  // Finishing a scan or a match updates the status line and the library's
  // counts; what the library holds is refetched on library.changed, which
  // the server sends once per change, after matching when there is any.
  on<ScanFinished>('scan.finished', (r) => {
    set({ scans: without(state.scans, r.libraryId), lastScan: { ...state.lastScan, [r.libraryId]: { finished: r, at: Date.now() } } })
    void qc.invalidateQueries({ queryKey: ['libraries'] })
  })
  on<ScanFailed>('scan.failed', (r) => {
    set({ scans: without(state.scans, r.libraryId), lastScan: { ...state.lastScan, [r.libraryId]: { failed: r, at: Date.now() } } })
    void qc.invalidateQueries({ queryKey: ['libraries'] })
  })
  on<MatchProgress>('match.started', (p) => set({ matches: { ...state.matches, [p.libraryId]: { ...p, done: 0 } } }))
  on<MatchProgress>('match.progress', (p) => set({ matches: { ...state.matches, [p.libraryId]: p } }))
  on<MatchFinished>('match.finished', (r) => {
    set({ matches: without(state.matches, r.libraryId), lastMatch: { ...state.lastMatch, [r.libraryId]: { finished: r, at: Date.now() } } })
    void qc.invalidateQueries({ queryKey: ['libraries'] })
  })
  on<{ libraryId: number }>('library.changed', (e) => invalidateLibrary(qc, e.libraryId))
  // Plays starting, changing and ending: Now playing refetches; history and
  // statistics change only when a play ends.
  on('activity.started', () => void qc.invalidateQueries({ queryKey: ['activity', 'live'] }))
  on('activity.updated', () => void qc.invalidateQueries({ queryKey: ['activity', 'live'] }))
  on<ActivityStopped>('activity.stopped', (e) => {
    stopListeners.forEach((l) => l(e))
    void qc.invalidateQueries({ queryKey: ['activity'] })
  })
  // The user's progress or watched marks changed, on this screen or another:
  // sent once saved, so Continue watching refetches what was just stored.
  on('watch.changed', watchEventInvalidator(qc))
  // A request was made, decided or arrived; a problem was reported or
  // resolved: lists, counts and the pages showing request state refetch.
  on('requests.changed', () => {
    void qc.invalidateQueries({ queryKey: ['requests'] })
    void qc.invalidateQueries({ queryKey: ['show'] })
  })
  on('plex.changed', () => invalidatePlexState(qc))
  on<PlexHistoryChanged>('plex.history.changed', plexHistoryEventInvalidator(qc))
  on('problems.changed', () => void qc.invalidateQueries({ queryKey: ['requests'] }))
  // This person starred a channel or set a reminder, here or on another
  // device; reminders also change when a listings refresh moves or loses
  // their programme (PLAN-LIVE-TV-GUIDE.md §5.7).
  on('live.favourites.changed', () => invalidatePersonalLiveTV(qc))
  on('live.reminders.changed', () => invalidatePersonalLiveTV(qc))
  on('live.recordings.changed', () => invalidateRecordings(qc))
  // This person's rows were arranged on another device, or an admin changed
  // the household's default; a list was made, changed or deleted, or a fetch
  // changed what is on it (PLAN-ROWS.md §6.2, §6.3).
  on('rows.changed', () => invalidateRowsAndLists(qc))
  on('lists.changed', () => invalidateRowsAndLists(qc))
  // A file's seek thumbnails are made (sent to admins): Settings › Transcoding's count moves on.
  on('trickplay.ready', () => void qc.invalidateQueries({ queryKey: ['trickplay'] }))
}

/** Mount once near the root: keeps the event stream open for the app's lifetime. */
export function useServerEvents() {
  const qc = useQueryClient()
  useEffect(() => {
    connect(qc)
  }, [qc])
}
