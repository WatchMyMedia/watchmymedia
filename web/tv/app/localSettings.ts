import { useSyncExternalStore } from 'react'
import type { SubtitleSize } from '../player/subtitles'

// The TV's own settings, kept on this TV (Settings › Playback › On this
// TV). Default quality is kept by web/src/lib/quality.ts (savedQuality and
// saveQuality), as on the web; this store holds the rest.

export type LocalSettings = { subtitleSize: SubtitleSize }

const KEY = 'wmm.tv.settings'
const DEFAULTS: LocalSettings = { subtitleSize: 'normal' }
const SIZES: readonly SubtitleSize[] = ['smaller', 'normal', 'larger', 'largest']
const listeners = new Set<() => void>()
let current: LocalSettings | undefined

function load(): LocalSettings {
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) ?? '{}') as Partial<LocalSettings>
    return { subtitleSize: SIZES.includes(raw.subtitleSize as SubtitleSize) ? raw.subtitleSize as SubtitleSize : DEFAULTS.subtitleSize }
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
