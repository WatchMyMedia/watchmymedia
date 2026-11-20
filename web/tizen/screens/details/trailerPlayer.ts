import { formatDuration } from '../../../src/lib/format'
import { afterPlayerError, PLAYER_ENDED, readPlayerMessage, YOUTUBE_EMBED_ORIGIN, type PlayerMessage } from '../../../src/lib/trailers'
import type { Action } from '../../app/keys'

// The YouTube trailer screen's rules (TrailerPlayer on the Samsung TV canvas;
// the Android TV app's TvTrailerScreen, TrailerViewModel and TrailerRemote).
// YouTube's own player plays in an iframe, driven by the IFrame Player API's
// postMessage commands, never by its keys. While it loads, and while it is
// paused, our own screen takes its place; nothing of ours is drawn over it
// while it plays.

/** YouTube's player states (YT.PlayerState). */
export const PLAYING = 1
export const PAUSED = 2
export const BUFFERING = 3

/** How long YouTube's player has to answer before the screen says it couldn't be reached. */
export const READY_TIMEOUT_MS = 20_000

/** How far ◀ ▶ (and ⏪ ⏩) jump. */
export const SEEK_MS = 10_000

export type TrailerPhase = 'loading' | 'playing' | 'paused' | 'refused' | 'unreachable'

export type TrailerState = {
  /** The trailer being tried, in the movie's YouTube trailers. */
  index: number
  /** Bumped by every new load (the next trailer, Try again), so word from an older one is ignored. */
  attempt: number
  phase: TrailerPhase
  /** The player answered for this load. */
  ready: boolean
  /** It played at some point, so its end goes back to the movie. */
  played: boolean
  timeMs: number
  /** 0 while YouTube doesn't know it. */
  durationMs: number
  /** A trailer that played has ended: back to the movie. */
  leave?: boolean
}

export function initialTrailer(index: number, count: number): TrailerState {
  return { index: Math.max(0, Math.min(index, count - 1)), attempt: 0, phase: count > 0 ? 'loading' : 'refused', ready: false, played: false, timeMs: 0, durationMs: 0 }
}

export type TrailerEvent =
  | { type: 'message'; message: PlayerMessage }
  | { type: 'time'; timeMs: number; durationMs?: number }
  /** The ready wait for this attempt ran out. */
  | { type: 'timeout'; attempt: number }
  /** Try again, after it couldn't be reached. */
  | { type: 'retry' }
  /** OK or Play on the pause screen: the player shows again, then is asked to play. */
  | { type: 'resume' }

/** The state after an event; `leave` when a trailer that played has ended, which goes back to the movie. */
export function trailerStep(s: TrailerState, e: TrailerEvent, count: number): TrailerState {
  const fresh = (index: number): TrailerState => ({ index, attempt: s.attempt + 1, phase: 'loading', ready: false, played: false, timeMs: 0, durationMs: 0 })
  switch (e.type) {
    case 'retry':
      return s.phase === 'unreachable' ? fresh(s.index) : s
    case 'timeout':
      return e.attempt === s.attempt && !s.ready && s.phase === 'loading' ? { ...s, phase: 'unreachable' } : s
    case 'resume':
      return s.phase === 'paused' ? { ...s, phase: 'playing' } : s
    case 'time':
      return { ...s, timeMs: Math.max(0, e.timeMs), durationMs: e.durationMs && e.durationMs > 0 ? e.durationMs : s.durationMs }
    case 'message': {
      if (s.phase === 'refused' || s.phase === 'unreachable') return s
      const m = e.message
      if (m.type === 'ready') return s.ready ? s : { ...s, ready: true, phase: 'playing' }
      if (m.type === 'error') {
        const step = afterPlayerError(m.code, s.index, count)
        if (step === 'refused') return { ...s, phase: 'refused' }
        if (step !== 'stay') return fresh(step.next)
        // YouTube's own message shows in its player: let it be seen.
        return { ...s, ready: true, phase: s.phase === 'paused' ? 'paused' : 'playing' }
      }
      if (m.type === 'state') {
        if (m.state === PLAYING) return { ...s, ready: true, played: true, phase: 'playing' }
        if (m.state === PAUSED && s.ready) return { ...s, phase: 'paused' }
        if (m.state === PLAYER_ENDED && s.played) return { ...s, leave: true }
      }
      return s
    }
  }
}

/** Whether YouTube's player is on screen: once it answered, and not while our pause screen stands in for it. */
export function playerShown(s: TrailerState): boolean {
  return s.ready && s.phase === 'playing'
}

/** What a remote key does on the trailer screen (TrailerRemote.kt's table). */
export type TrailerKey = 'toggle' | 'play' | 'pause' | 'back10' | 'ahead10' | 'swallow' | 'pass'

export function trailerKey(action: Action, phase: TrailerPhase): TrailerKey {
  // The problem screens' buttons take the arrows and OK; Return is always ours.
  if (phase === 'refused' || phase === 'unreachable' || action === 'back') return 'pass'
  if (phase === 'loading') return 'swallow'
  switch (action) {
    case 'ok': case 'playPause': return 'toggle'
    case 'play': return 'play'
    case 'pause': case 'stop': return 'pause'
    case 'left': case 'rewind': return 'back10'
    case 'right': case 'fastForward': return 'ahead10'
    case 'up': case 'down': case 'hold': return 'swallow'
    default: return 'pass'
  }
}

/** Where a jump lands: never before the start, and short of the end so it doesn't finish the trailer. */
export function seekTarget(timeMs: number, durationMs: number, deltaMs: number): number {
  const t = Math.max(0, timeMs + deltaMs)
  return durationMs > 1000 ? Math.min(t, durationMs - 1000) : t
}

/** "Paused · 0:27 / 2:31". */
export function pausedLine(timeMs: number, durationMs: number): string {
  const clock = (ms: number) => (ms >= 1000 ? formatDuration(ms) : '0:00')
  return `Paused · ${clock(timeMs)}${durationMs > 0 ? ` / ${clock(durationMs)}` : ''}`
}

/**
 * Where YouTube's player comes from: its privacy-enhanced embed, or in the
 * browser preview the fakes' stand-in (`srcdoc`), so the preview reaches
 * nothing on the network. The fakes set it; the TV build never does.
 */
export type TrailerEmbed = { origin: string; src?: (key: string) => string; srcdoc?: (key: string) => string }

const youtube: TrailerEmbed = { origin: YOUTUBE_EMBED_ORIGIN }
let embed: TrailerEmbed = youtube

export function trailerEmbed(): TrailerEmbed { return embed }

/** The browser preview's fakes only. */
export function setTrailerEmbed(next: TrailerEmbed | undefined) { embed = next ?? youtube }

/**
 * The player's address. It doesn't play by itself: the screen shows it once
 * it answers, then asks it to play, so nothing plays unseen. `origin` is
 * passed only for an http(s) page: the packaged app loads from file://,
 * whose origin is opaque, and the player then answers any page.
 */
export function embedUrl(key: string, pageOrigin: string): string {
  const q = new URLSearchParams({ autoplay: '0', controls: '1', rel: '0', playsinline: '1', enablejsapi: '1', iv_load_policy: '3', fs: '0' })
  if (/^https?:\/\//.test(pageOrigin)) q.set('origin', pageOrigin)
  return `${YOUTUBE_EMBED_ORIGIN}/embed/${encodeURIComponent(key)}?${q}`
}

/**
 * The player's word, from a message event: only from the player's origin.
 * The time comes in its `infoDelivery` messages (undocumented, as the rest
 * of the protocol is; PLAN-TRAILERS.md's As built says what they were).
 */
export function readTrailerMessage(expectedOrigin: string, origin: string, data: unknown): { message?: PlayerMessage; timeMs?: number; durationMs?: number } | null {
  if (origin !== expectedOrigin) return null
  const message = readPlayerMessage(YOUTUBE_EMBED_ORIGIN, data) ?? undefined
  let parsed: unknown = data
  if (typeof data === 'string') {
    try { parsed = JSON.parse(data) } catch { parsed = undefined }
  }
  const info = (parsed as { event?: unknown; info?: unknown } | undefined)?.info
  const time = info && typeof info === 'object' ? info as { currentTime?: unknown; duration?: unknown } : undefined
  const timeMs = typeof time?.currentTime === 'number' && Number.isFinite(time.currentTime) ? Math.round(time.currentTime * 1000) : undefined
  const durationMs = typeof time?.duration === 'number' && Number.isFinite(time.duration) ? Math.round(time.duration * 1000) : undefined
  if (!message && timeMs === undefined) return null
  return { message, timeMs, durationMs }
}
