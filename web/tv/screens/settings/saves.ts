import { useState } from 'react'
import type { components } from '../../../src/api/schema'
import { useUpdatePreferences } from '../../../src/api/queries'

// A setting that saves at once: its row
// says Saving… while the server answers, and when it refuses, what the
// setting is still, with OK to try again. A row that confirms (the sound
// settings, Subtitle size) says Saved once it is.

type Patch = components['schemas']['UpdatePreferencesInputBody']

export type RowSave = { state: 'saving' } | { state: 'saved' } | { state: 'failed'; still: string; retry: Patch }
export type Saves = Readonly<Record<string, RowSave>>

export function begin(saves: Saves, key: string): Saves {
  return { ...saves, [key]: { state: 'saving' } }
}

export function settle(saves: Saves, key: string, confirm = false): Saves {
  if (confirm) return { ...saves, [key]: { state: 'saved' } }
  return Object.fromEntries(Object.entries(saves).filter(([k]) => k !== key))
}

export function fail(saves: Saves, key: string, still: string, retry: Patch): Saves {
  return { ...saves, [key]: { state: 'failed', still, retry } }
}

/**
 * The account's settings, saved a row at a time: `save(key, patch, still)`,
 * and OK on a failed row tries it again. `confirm` keeps "Saved" in the row
 * once it is.
 */
export function usePreferenceSaves() {
  const update = useUpdatePreferences()
  const [saves, setSaves] = useState<Saves>({})
  const save = (key: string, patch: Patch, still: string, confirm = false) => {
    setSaves((s) => begin(s, key))
    update.mutate(patch, {
      onSuccess: () => setSaves((s) => settle(s, key, confirm)),
      onError: () => setSaves((s) => fail(s, key, still, patch)),
    })
  }
  /** OK on a row: tries a failed change again (with confirm, as it was saved), else does `otherwise`. */
  const press = (key: string, otherwise: () => void, confirm = false) => () => {
    const s = saves[key]
    if (s?.state === 'failed') save(key, s.retry, s.still, confirm)
    else if (s?.state !== 'saving') otherwise()
  }
  return { status: (key: string): RowSave | undefined => saves[key], save, press }
}
