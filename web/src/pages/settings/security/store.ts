import { useSyncExternalStore } from 'react'

// Turn on sign-in protection's progress in this visit, and the hub's banner
// after protection is turned off. In memory only: saving the keys reloads
// the page (so its Content-Security-Policy lets Cloudflare's widget in), and
// the saved keys then say how far the flow got.

type FlowFlags = { gotKeys: boolean; tried: boolean }

let flags: FlowFlags = { gotKeys: false, tried: false }
let turnedOff = false
let leaving = false
const listeners = new Set<() => void>()

function emit() {
  for (const l of listeners) l()
}

function subscribe(l: () => void) {
  listeners.add(l)
  return () => { listeners.delete(l) }
}

export function useFlowFlags() {
  return useSyncExternalStore(subscribe, () => flags)
}

export function setFlowFlags(change: Partial<FlowFlags>) {
  flags = { ...flags, ...change }
  emit()
}

export function useTurnedOffNotice() {
  return useSyncExternalStore(subscribe, () => turnedOff)
}

export function noteTurnedOff(on: boolean) {
  turnedOff = on
  emit()
}

/** Set while the flow sends the admin back to the hub, so its own address
 * rule doesn't move them to another step on the way out. */
export function leavingFlow() {
  return leaving
}

export function setLeavingFlow(on: boolean) {
  leaving = on
}
