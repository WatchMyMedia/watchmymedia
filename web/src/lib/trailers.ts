// Movie trailers (PLAN-TRAILERS.md §6.1): which one the Trailer button
// plays, YouTube's player in a plain iframe, and what its messages mean.

import type { components } from '../api/schema'

export type MovieTrailer = components['schemas']['MovieTrailer']

/** YouTube's privacy-enhanced player, the one origin the server's CSP lets the page frame. */
export const YOUTUBE_EMBED_ORIGIN = 'https://www.youtube-nocookie.com'

/** YouTube's terms, linked from the trailer page. */
export const YOUTUBE_TERMS_URL = 'https://www.youtube.com/t/terms'

// YouTube ids are 11 characters today; allow some room, but nothing that
// could change the URL the key is put in.
const keyPattern = /^[A-Za-z0-9_-]{6,32}$/

/** A trailer this app can play: a file in the library, or a YouTube video. Other sources are skipped. */
export type PlayableTrailer = { kind: 'file'; fileId: number; name: string } | { kind: 'youtube'; key: string; name: string }

function playable(t: MovieTrailer): PlayableTrailer | null {
  if (t.source === 'file' && t.fileId && t.fileId > 0) return { kind: 'file', fileId: t.fileId, name: t.name }
  if (t.source === 'youtube' && t.key && keyPattern.test(t.key)) return { kind: 'youtube', key: t.key, name: t.name }
  return null
}

/** The trailer the movie page's Trailer button plays: the first one the server lists that this app can play. */
export function firstTrailer(trailers: MovieTrailer[] | undefined): PlayableTrailer | null {
  for (const t of trailers ?? []) {
    const p = playable(t)
    if (p) return p
  }
  return null
}

/** The YouTube trailers the trailer page tries, in the server's order. */
export function youTubeTrailers(trailers: MovieTrailer[] | undefined): Extract<PlayableTrailer, { kind: 'youtube' }>[] {
  return (trailers ?? []).flatMap((t) => {
    const p = playable(t)
    return p?.kind === 'youtube' ? [p] : []
  })
}

/**
 * The player's address. enablejsapi and origin make it post its events to
 * this page; origin must be the page's own, or the player sends them nowhere.
 */
export function embedUrl(key: string, origin: string): string {
  const q = new URLSearchParams({ autoplay: '1', rel: '0', playsinline: '1', enablejsapi: '1', origin })
  return `${YOUTUBE_EMBED_ORIGIN}/embed/${encodeURIComponent(key)}?${q}`
}

/** The video on YouTube itself, for "Watch on YouTube". */
export function watchUrl(key: string): string {
  return `https://www.youtube.com/watch?v=${encodeURIComponent(key)}`
}

/**
 * What the player said, from a message event. Only messages from YouTube's
 * player origin count. The format is the one YouTube's own IFrame API script
 * speaks (a JSON string with an event name and info); it isn't documented,
 * so anything unexpected is ignored.
 */
export type PlayerMessage = { type: 'ready' } | { type: 'error'; code: number } | { type: 'state'; state: number } | { type: 'other' }

export function readPlayerMessage(origin: string, data: unknown): PlayerMessage | null {
  if (origin !== YOUTUBE_EMBED_ORIGIN) return null
  let msg: unknown = data
  if (typeof data === 'string') {
    try {
      msg = JSON.parse(data)
    } catch {
      return null
    }
  }
  if (!msg || typeof msg !== 'object') return null
  const { event, info } = msg as { event?: unknown; info?: unknown }
  if (typeof event !== 'string') return null
  if (event === 'onReady') return { type: 'ready' }
  if (event === 'onError') return typeof info === 'number' ? { type: 'error', code: info } : { type: 'other' }
  if (event === 'onStateChange' && typeof info === 'number') return { type: 'state', state: info }
  if (event === 'infoDelivery' && info && typeof info === 'object' && typeof (info as { playerState?: unknown }).playerState === 'number') {
    return { type: 'state', state: (info as { playerState: number }).playerState }
  }
  return { type: 'other' }
}

/** The player's ended state (YT.PlayerState.ENDED). */
export const PLAYER_ENDED = 0

/**
 * What the page does after the player reports an error:
 * - 101 and 150: the owner allows the video only on YouTube. Try the next
 *   trailer; after the last, say this one can't play here.
 * - 100: the video is gone or private. Try the next; after the last, leave
 *   YouTube's own message, since sending people to YouTube wouldn't help.
 * - Anything else (a bad id, the browser's video failing): YouTube's own
 *   message shows in the player.
 */
export type ErrorStep = { next: number } | 'refused' | 'stay'

export function afterPlayerError(code: number, index: number, count: number): ErrorStep {
  const hasNext = index + 1 < count
  if (code === 101 || code === 150) return hasNext ? { next: index + 1 } : 'refused'
  if (code === 100) return hasNext ? { next: index + 1 } : 'stay'
  return 'stay'
}

/**
 * Messages the page posts to the player. "listening" asks it to start
 * sending events (the API script repeats it until the player answers);
 * addEventListener subscribes to one event; command runs a player method.
 */
export function listeningMessage(id: number): string {
  return JSON.stringify({ event: 'listening', id, channel: 'widget' })
}

export function commandMessage(id: number, func: string, args: unknown[] = []): string {
  return JSON.stringify({ event: 'command', func, args, id, channel: 'widget' })
}
