/** A growing recording has no fixed VOD timeline until it is finalized. */
export function requestedPlaylist(recordingId?: string): 'event' | 'vod' {
  return recordingId ? 'event' : 'vod'
}

type Timeline = { protocol: string; playlist?: string; startMs: number }

/** Event media time starts at the session's seek point; VOD time starts at zero. */
export function timelineOffset(decision: Timeline): number {
  return decision.protocol === 'file' || decision.playlist === 'vod' ? 0 : decision.startMs / 1000
}

/** hls.js must seek within a full VOD timeline after resume or a reload. */
export function hlsStartPosition(decision: Timeline, requestedSeconds: number): number {
  return decision.playlist === 'vod' ? Math.max(0, requestedSeconds) : 0
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
