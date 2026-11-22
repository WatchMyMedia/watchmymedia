// The short message at the foot of the window ("Saved to Watchlist", with
// Undo). One at a time: a new one takes the place of the last, which is then
// done, so a removal it held back is sent. Timers live here, not in a
// component, so a message ends on time whichever page is showing.

/** How long a message shows, and so how long a removal waits for Undo. */
export const TOAST_MS = 5000

export type ToastIcon = 'saved' | 'removed'

export type Toast = {
  id: number
  text: string
  icon?: ToastIcon
  /** Undo's press. Without one the message has no Undo. */
  undo?: () => void
  /** Called once when the message goes without Undo: timed out, replaced or closed. */
  done?: () => void
}

let current: Toast | undefined
let timer: ReturnType<typeof setTimeout> | undefined
let seq = 0
const listeners = new Set<() => void>()

function set(next: Toast | undefined) {
  if (timer !== undefined) clearTimeout(timer)
  timer = undefined
  current = next
  for (const f of listeners) f()
}

/** Shows a message, ending the one before it. */
export function showToast(t: Omit<Toast, 'id'>, ms = TOAST_MS): number {
  const before = current
  const id = ++seq
  set({ ...t, id })
  before?.done?.()
  timer = setTimeout(() => dismissToast(id), ms)
  return id
}

/** Undo pressed: the message goes and its undo runs instead of done. */
export function undoToast(id: number) {
  if (current?.id !== id) return
  const t = current
  set(undefined)
  t.undo?.()
}

/** The message goes without Undo. */
export function dismissToast(id: number) {
  if (current?.id !== id) return
  const t = current
  set(undefined)
  t.done?.()
}

/** Ends whatever shows now, as leaving the page does. */
export function flushToast() {
  if (current) dismissToast(current.id)
}

export function currentToast(): Toast | undefined {
  return current
}

export function subscribeToast(f: () => void): () => void {
  listeners.add(f)
  return () => void listeners.delete(f)
}
