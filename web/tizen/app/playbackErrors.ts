import { useSyncExternalStore } from 'react'

// The last playback errors on this TV, for Settings › About (SettingsAccount
// on the canvas, Tweak `pane`: `about`). Kept on this TV and never sent: the
// player records each failure it gives up on, and About words them as codes
// someone helping can read off the screen. Facts only, so nothing here can
// carry a title, an address, a token or the server's own text.

export type PlaybackMedia = 'movie' | 'episode' | 'live' | 'recording' | 'trailer'
/** As the engine's PlayerFailure.kind. */
export type PlaybackFailureKind = 'network' | 'auth' | 'format' | 'other'

export type PlaybackErrorRecord = {
  /** When it happened, in ms since the epoch. */
  at: number
  media: PlaybackMedia
  kind: PlaybackFailureKind
  /** The HTTP status, when the server refused. */
  status?: number
  /** For Live TV, the channel's number ("7.1"). */
  channel?: string
}

/** How many About keeps: the last five, newest first. */
export const MAX_PLAYBACK_ERRORS = 5

const KEY = 'wmm.tv.playback-errors'
const MEDIA: readonly PlaybackMedia[] = ['movie', 'episode', 'live', 'recording', 'trailer']
const KINDS: readonly PlaybackFailureKind[] = ['network', 'auth', 'format', 'other']
const listeners = new Set<() => void>()
let current: readonly PlaybackErrorRecord[] | undefined

function valid(v: unknown): PlaybackErrorRecord | undefined {
  if (!v || typeof v !== 'object') return undefined
  const r = v as Record<string, unknown>
  if (typeof r.at !== 'number' || !MEDIA.includes(r.media as PlaybackMedia) || !KINDS.includes(r.kind as PlaybackFailureKind)) return undefined
  const out: PlaybackErrorRecord = { at: r.at, media: r.media as PlaybackMedia, kind: r.kind as PlaybackFailureKind }
  if (typeof r.status === 'number' && r.status >= 100 && r.status < 600) out.status = Math.round(r.status)
  if (typeof r.channel === 'string' && /^[\w.-]{1,12}$/.test(r.channel)) out.channel = r.channel
  return out
}

function load(): readonly PlaybackErrorRecord[] {
  try {
    const raw: unknown = JSON.parse(localStorage.getItem(KEY) ?? '[]')
    return Array.isArray(raw) ? raw.map(valid).filter((r): r is PlaybackErrorRecord => !!r).slice(0, MAX_PLAYBACK_ERRORS) : []
  } catch {
    return []
  }
}

function save(list: readonly PlaybackErrorRecord[]) {
  current = list
  try { localStorage.setItem(KEY, JSON.stringify(list)) } catch { /* kept for this run only */ }
  listeners.forEach((l) => l())
}

/** The errors kept, newest first. */
export function playbackErrors(): readonly PlaybackErrorRecord[] {
  return current ??= load()
}

/** Notes a playback the player gave up on; `at` defaults to now. Only the last five are kept. */
export function recordPlaybackError(error: Omit<PlaybackErrorRecord, 'at'> & { at?: number }) {
  const record = valid({ ...error, at: error.at ?? Date.now() })
  if (record) save([record, ...playbackErrors()].slice(0, MAX_PLAYBACK_ERRORS))
}

/** Forgets them all (tests, and a TV handed on). */
export function clearPlaybackErrors() { save([]) }

function subscribe(l: () => void) {
  listeners.add(l)
  return () => { listeners.delete(l) }
}

export function usePlaybackErrors(): readonly PlaybackErrorRecord[] {
  return useSyncExternalStore(subscribe, playbackErrors)
}
