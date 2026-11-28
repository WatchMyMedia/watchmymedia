import { useSyncExternalStore } from 'react'
import type { SubtitleSize } from '../player/subtitles'

// The TV's own settings, kept on this TV (Settings › Playback › On this
// TV). Default quality is kept by web/src/lib/quality.ts (savedQuality and
// saveQuality), as on the web; this store holds the rest. Subtitle size is
// the person's on a server from API level 35; this one is what the TV uses
// below it, and what it moved up to each person (sizeMoved,
// session/subtitleSizeMove.ts).

export type LocalSettings = { subtitleSize: SubtitleSize; sizeMoved: readonly string[] }

const KEY = 'wmm.tv.settings'
const DEFAULTS: LocalSettings = { subtitleSize: 'normal', sizeMoved: [] }
const SIZES: readonly SubtitleSize[] = ['smaller', 'normal', 'larger', 'largest']
const listeners = new Set<() => void>()
let current: LocalSettings | undefined

function load(): LocalSettings {
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) ?? '{}') as Partial<LocalSettings>
    return {
      subtitleSize: SIZES.includes(raw.subtitleSize as SubtitleSize) ? raw.subtitleSize as SubtitleSize : DEFAULTS.subtitleSize,
      sizeMoved: Array.isArray(raw.sizeMoved) ? raw.sizeMoved.filter((k): k is string => typeof k === 'string') : [],
    }
  } catch {
    return DEFAULTS
  }
}

export function localSettings(): LocalSettings {
  return current ??= load()
}

export function setLocalSettings(patch: Partial<LocalSettings>) {
  current = { ...localSettings(), ...patch }
  try { localStorage.setItem(KEY, JSON.stringify(current)) } catch { /* kept for this run only */ }
  listeners.forEach((l) => l())
}

function subscribe(l: () => void) {
  listeners.add(l)
  return () => { listeners.delete(l) }
}

export function useLocalSettings(): LocalSettings {
  return useSyncExternalStore(subscribe, localSettings)
}
