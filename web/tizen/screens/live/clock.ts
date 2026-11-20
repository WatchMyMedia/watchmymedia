import { useSyncExternalStore } from 'react'

// One ticking clock per step for all of Live TV, so the guide, the banner
// and the cards share a timer and redraw on the minute, not on every frame.

type Tick = { value: number; listeners: Set<() => void>; timer?: ReturnType<typeof setTimeout> }
const ticks = new Map<number, Tick>()

/** The next boundary of `step` after `now`: a minute clock ticks on the minute. */
export function nextTick(now: number, step: number): number {
  return Math.floor(now / step) * step + step
}

function tickFor(step: number): Tick {
  let t = ticks.get(step)
  if (!t) ticks.set(step, t = { value: Date.now(), listeners: new Set() })
  return t
}

function schedule(step: number, t: Tick) {
  const now = Date.now()
  t.timer = setTimeout(() => {
    t.value = Date.now()
    for (const l of t.listeners) l()
    if (t.listeners.size) schedule(step, t)
    else t.timer = undefined
  }, Math.max(250, nextTick(now, step) - now + 20))
}

function subscribe(step: number, listener: () => void) {
  const t = tickFor(step)
  t.listeners.add(listener)
  if (t.timer === undefined) {
    // The clock was stopped: catch up now, and redraw whoever read the old step.
    const before = t.value
    t.value = Date.now()
    if (Math.floor(before / step) !== Math.floor(t.value / step)) queueMicrotask(listener)
    schedule(step, t)
  }
  return () => {
    t.listeners.delete(listener)
    if (!t.listeners.size && t.timer !== undefined) { clearTimeout(t.timer); t.timer = undefined }
  }
}

const subscribers = new Map<number, (l: () => void) => () => void>()
const snapshots = new Map<number, () => number>()

/** The time now, redrawn every `step` ms on its boundary (a minute by default). */
export function useNow(step = 60_000): number {
  let sub = subscribers.get(step)
  if (!sub) subscribers.set(step, sub = (l) => subscribe(step, l))
  let snap = snapshots.get(step)
  if (!snap) snapshots.set(step, snap = () => tickFor(step).value)
  return useSyncExternalStore(sub, snap)
}
