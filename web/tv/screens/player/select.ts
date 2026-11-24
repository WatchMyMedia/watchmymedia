import { useState, useSyncExternalStore } from 'react'

// The engine publishes a new state several times a second while the
// position moves. Each part of the screen reads only what it draws through
// a selector that hands back the same value until that part changes, so a
// tick redraws the scrubber and not the whole player.

export type Store<S> = { subscribe(listener: () => void): () => void; snapshot(): S }

/** Equal when every field is the same, or an object whose fields are all the same (two levels, as the engine compares). */
export function shallow2(a: unknown, b: unknown): boolean {
  if (Object.is(a, b)) return true
  if (!isRecord(a) || !isRecord(b)) return false
  const keys = new Set([...Object.keys(a), ...Object.keys(b)])
  for (const k of keys) {
    const x = a[k], y = b[k]
    if (Object.is(x, y)) continue
    if (!isRecord(x) || !isRecord(y)) return false
    const inner = new Set([...Object.keys(x), ...Object.keys(y)])
    for (const j of inner) if (!Object.is(x[j], y[j])) return false
  }
  return true
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return !!v && typeof v === 'object' && !Array.isArray(v)
}

/** A snapshot of select(store's state) that keeps its identity while the selection stays equal. */
export function selector<S, T>(store: Store<S>, select: (state: S) => T, equal: (a: T, b: T) => boolean = shallow2): () => T {
  let last: { state: S; value: T } | undefined
  return () => {
    const state = store.snapshot()
    if (last && last.state === state) return last.value
    const value = select(state)
    if (last && equal(last.value, value)) { last = { state, value: last.value }; return last.value }
    last = { state, value }
    return value
  }
}

/** Reads part of a store: the component redraws only when that part changes. `select` is read once, so pass a module-level function. */
export function useSelect<S, T>(store: Store<S>, select: (state: S) => T, equal?: (a: T, b: T) => boolean): T {
  const [snapshot] = useState(() => selector(store, select, equal))
  return useSyncExternalStore(store.subscribe, snapshot)
}
