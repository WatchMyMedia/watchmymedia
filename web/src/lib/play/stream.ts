/** A growing recording has no fixed VOD timeline until it is finalized. */
export function requestedPlaylist(recordingId?: string): 'event' | 'vod' {
  return recordingId ? 'event' : 'vod'
}

type Timeline = { protocol: string; playlist?: string; startMs: number }

/** Event media time starts at the session's seek point; VOD time starts at zero. */
export function timelineOffset(decision: Timeline): number {
  return decision.protocol === 'file' || decision.playlist === 'vod' ? 0 : decision.startMs / 1000
}

/**
 * Where the player starts in its own time to show the file at
 * requestedSeconds. A VOD timeline is the file's. An event session of copied
 * video starts on the keyframe before the time asked for,
 * so the player starts that far into it rather than at its start.
 */
export function hlsStartPosition(decision: Timeline, requestedSeconds: number): number {
  return Math.max(0, requestedSeconds - timelineOffset(decision))
}

/** Players resume only past this. */
export const RESUME_MIN_MS = 5_000

/** How far before the start asked for an event decision may begin, on a keyframe. */
const EVENT_START_TOLERANCE_MS = 15_000

/**
 * Where a play that asked for no start picks up, and whether an event session
 * has to be asked for again there. The server starts an event session where
 * the play resumes, on the keyframe before it; an older
 * one started it at 0, and the player asked again. undefined: play from the
 * start.
 */
export function resumePlan(decision: Timeline & { resumeMs: number }): { fromMs: number; again: boolean } | undefined {
  if (decision.resumeMs <= RESUME_MIN_MS) return undefined
  const whole = decision.protocol === 'file' || decision.playlist === 'vod'
  const ahead = decision.resumeMs - decision.startMs
  return { fromMs: decision.resumeMs, again: !whole && !(ahead >= 0 && ahead <= EVENT_START_TOLERANCE_MS) }
}

// hls.js treats maxBufferSize as a minimum amount to fetch, not a hard byte
// ceiling. Cap forward time separately and trim old media after each fragment.
// One in-flight fragment and the browser's decoder can exceed these hints.
export const fileHlsBuffer = {
  maxBufferLength: 30,
  maxMaxBufferLength: 45,
  maxBufferSize: 32_000_000,
  backBufferLength: 30,
  frontBufferFlushThreshold: 60,
} as const
