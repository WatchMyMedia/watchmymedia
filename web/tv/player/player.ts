import { PlayerError, type Decision } from './api'

/**
 * The engine's one media backend: the TV's own player sits behind it
 * (Samsung's AVPlay), and in the preview a <video>.
 * Times are the player's own: for an event playlist that is the time since
 * the session's start, and the controllers map it to the file.
 */
export type PlayerState = 'idle' | 'buffering' | 'playing' | 'paused' | 'ended' | 'error'

/** A failure as a code (never the native message, which can hold the media URL) and what kind it is. */
export type PlayerFailure = {
  code: string
  /** network and auth can recover with a new request or token; format can't, on this stream. */
  kind: 'network' | 'auth' | 'format' | 'other'
  httpStatus?: number
}

export type PlayerTrack = {
  kind: 'video' | 'audio' | 'text'
  /** The player's own index, for selectTrack. */
  index: number
  language?: string
  codec?: string
  channels?: number
  width?: number
  height?: number
}

export type PlayerUpdate = {
  state?: PlayerState
  positionMs?: number
  durationMs?: number
  /** Buffered ahead of the position, when the player knows it (AVPlay doesn't). */
  bufferedMs?: number
  /** The video as decoded, once known. */
  video?: { width?: number; height?: number; codec?: string }
  failure?: PlayerFailure
}

export type PlayerListener = (update: PlayerUpdate) => void

/** A player operation that failed, with what kind of failure it was. */
export class PlaybackFailure extends PlayerError {
  constructor(message: string, readonly failure: PlayerFailure) { super(message) }
}

export type OpenOptions = {
  /** Where to start, in the player's time; ignored for live streams, which start at the live edge. */
  startMs?: number
  /** A stream larger than 1080p (uhd()): AVPlay decodes it only in 4K mode. */
  uhd?: boolean
  /** A rolling live playlist (Live TV): starts at the live edge, and Go live seeks there. */
  live?: boolean
  /** Prepare and stay paused at startMs. */
  paused?: boolean
  /**
   * The latest a file's start may land, in the player's time: by default a
   * second past startMs. A resume the server started 5 s back passes where
   * the viewer was, so a keyframe between the two needs no second seek.
   */
  latestMs?: number
}

export interface Player {
  readonly name: string
  /** Updates go to every listener; the controllers drop those of plays they don't own. */
  listen(listener: PlayerListener): () => void
  open(url: string, options?: OpenOptions): Promise<void>
  pause(): Promise<void>
  resume(): Promise<void>
  seek(positionMs: number): Promise<void>
  seekToLiveEdge(): Promise<void>
  stop(): Promise<void>
  /** The position now, in the player's time. */
  position(): number
  /** A live stream's seekable window, in the player's time. */
  liveWindow(): { startMs: number; endMs: number } | undefined
  tracks(): PlayerTrack[]
  /** Switches the stream's own audio or text track; false when it can't, and the server must send it. */
  selectTrack(kind: 'audio' | 'text', index: number): Promise<boolean>
}

/** Whether the stream the server sends is larger than 1080p, which some TVs' players decode only in a 4K mode. */
export function uhd(decision: Pick<Decision, 'video'>): boolean {
  return (decision.video.width ?? 0) > 1920 || (decision.video.height ?? 0) > 1080
}
