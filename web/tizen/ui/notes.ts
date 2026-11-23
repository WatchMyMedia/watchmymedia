import type { IconName } from './Icon'

// A short note over the screen that takes no focus: why a dimmed button
// can't be pressed, or what a press just did. One at a time, gone on its own.
// `Notes` (ui/Toast.tsx) draws it, once for the app.

export type Note = { text: string; icon?: IconName; n: number; until: number }

let current: Note | undefined
let count = 0
const listeners = new Set<() => void>()

/** How long a note stays. */
export const NOTE_MS = 4000

function emit() { for (const l of listeners) l() }

/** Shows a note top right; a second one replaces the first. */
export function showNote(text: string, icon?: IconName, ms = NOTE_MS) {
  current = { text, icon, n: ++count, until: Date.now() + ms }
  emit()
}

/** Takes down the note now (a screen leaving). */
export function clearNote() {
  if (!current) return
  current = undefined
  emit()
}

export function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

/** The note showing, if any. */
export function currentNote(): Note | undefined { return current }
