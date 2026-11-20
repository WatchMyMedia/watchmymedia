import { useCallback, useState } from 'react'
import { useEntry } from '../../app/hooks'

// What a browsing screen keeps while it is under another (Home's page, a
// grid's sort and filters): only the top screen is drawn, so its state is
// kept with its stack entry, the way the shell keeps its focus. It goes
// with the entry: a section opened afresh from the rail starts over.

const kept = new WeakMap<object, Map<string, unknown>>()

/** useState that comes back with the screen: the value is kept with this screen's stack entry. */
export function useEntryState<T>(name: string, initial: T | (() => T)): [T, (value: T) => void] {
  const owner = useEntry()?.memory
  const [value, setValue] = useState<T>(() => {
    const saved = owner ? kept.get(owner) : undefined
    if (saved?.has(name)) return saved.get(name) as T
    return typeof initial === 'function' ? (initial as () => T)() : initial
  })
  const set = useCallback((next: T) => {
    setValue(next)
    if (!owner) return
    let saved = kept.get(owner)
    if (!saved) kept.set(owner, saved = new Map())
    saved.set(name, next)
  }, [owner, name])
  return [value, set]
}
