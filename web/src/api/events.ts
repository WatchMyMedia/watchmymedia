import { useEffect, useSyncExternalStore } from 'react'
import { useQueryClient, type QueryClient } from '@tanstack/react-query'
import type { components } from './schema'
import { eventInvalidations, invalidateAfterReconnect } from './eventInvalidation'

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

const libraryListeners = new Set<(reason: 'changed' | 'reopened') => void>()

/**
 * Calls fn whenever a library's contents change (library.changed), and when
 * the stream comes back after a drop, which may have hidden one; returns the
 * unsubscribe function.
 */
export function onLibraryChanged(fn: (reason: 'changed' | 'reopened') => void): () => void {
  libraryListeners.add(fn)
  return () => {
    libraryListeners.delete(fn)
  }
}

function connect(qc: QueryClient) {
  if (source) return
  const es = new EventSource('/api/events')
  source = es
  const invalidate = eventInvalidations(qc)
  // EventSource reconnects on its own, but events sent while it was down
  // (the computer slept, the server restarted) are lost: refetch what is shown.
  let dropped = false
  es.onopen = () => {
    set({ connected: true })
    if (dropped) {
      invalidateAfterReconnect(qc)
      // A library may have changed while it was down: new episodes check.
      libraryListeners.forEach((l) => l('reopened'))
    }
  }
  es.onerror = () => {
    dropped = true
    set({ connected: false })
  }
  // This page's own state for each event: the status line's scans and
  // matches, and the listeners above.
  const local = {
    'scan.started': (p: ScanProgress) => set({ scans: { ...state.scans, [p.libraryId]: { ...p, scanned: 0 } } }),
    'scan.progress': (p: ScanProgress) => set({ scans: { ...state.scans, [p.libraryId]: p } }),
    'scan.finished': (r: ScanFinished) =>
      set({ scans: without(state.scans, r.libraryId), lastScan: { ...state.lastScan, [r.libraryId]: { finished: r, at: Date.now() } } }),
    'scan.failed': (r: ScanFailed) =>
      set({ scans: without(state.scans, r.libraryId), lastScan: { ...state.lastScan, [r.libraryId]: { failed: r, at: Date.now() } } }),
    'match.started': (p: MatchProgress) => set({ matches: { ...state.matches, [p.libraryId]: { ...p, done: 0 } } }),
    'match.progress': (p: MatchProgress) => set({ matches: { ...state.matches, [p.libraryId]: p } }),
    'match.finished': (r: MatchFinished) =>
      set({ matches: without(state.matches, r.libraryId), lastMatch: { ...state.lastMatch, [r.libraryId]: { finished: r, at: Date.now() } } }),
    // New episodes are checked for a while after the last change.
    'library.changed': () => libraryListeners.forEach((l) => l('changed')),
    'activity.stopped': (e: ActivityStopped) => stopListeners.forEach((l) => l(e)),
  } as Record<string, ((data: never) => void) | undefined>
  // Then what each event refetches, from the table the TV app shares.
  for (const name of new Set([...Object.keys(local), ...Object.keys(invalidate)])) {
    es.addEventListener(name, (ev) => {
      const data: unknown = JSON.parse((ev as MessageEvent).data)
      local[name]?.(data as never)
      invalidate[name]?.(data)
    })
  }
}

/** Mount once near the root: keeps the event stream open for the app's lifetime. */
export function useServerEvents() {
  const qc = useQueryClient()
  useEffect(() => {
    connect(qc)
  }, [qc])
}
