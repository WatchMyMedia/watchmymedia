import { useSyncExternalStore } from 'react'
import { saveQuality, savedQuality, type Quality } from '../../../src/lib/quality'

// Settings › Playback › Default quality, kept on this TV by the web's own
// savedQuality and saveQuality (the player reads the same), with a listener
// so the group's line and the row change together.

const listeners = new Set<() => void>()
let version = 0

function subscribe(l: () => void) {
  listeners.add(l)
  return () => { listeners.delete(l) }
}

export function setDefaultQuality(q: Quality) {
  saveQuality(q)
  version++
  listeners.forEach((l) => l())
}

/** The Default quality for a server at `apiLevel`: Automatic unless one was chosen on this TV. */
export function useDefaultQuality(apiLevel: number): Quality {
  useSyncExternalStore(subscribe, () => version)
  return savedQuality(apiLevel)
}
