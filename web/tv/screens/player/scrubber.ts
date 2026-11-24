import type { FilePlaybackState, Segment } from '../../player'

// The scrubber's geometry, in percent of the track:
// what has played and is buffered, a seek's target and
// the gap to it, the segment bands, and how much of a growing recording is
// written. The track is the timeline: a growing recording's planned length.

/** ms as a share of total, 0–100. */
export function percent(ms: number, total: number): number {
  return total > 0 ? Math.min(100, Math.max(0, (ms / total) * 100)) : 0
}

export type Band = { key: string; left: number; width: number; opening: boolean }

/**
 * A band per segment that lies partly inside the timeline (intro and recap
 * in gold, credits and ad breaks light); one without an end runs to the end
 * of the file.
 */
export function bands(segments: readonly Segment[] | undefined, durationMs: number, timelineMs: number): Band[] {
  if (!segments?.length || timelineMs <= 0) return []
  const out: Band[] = []
  for (const s of segments) {
    const left = percent(s.startMs, timelineMs)
    const right = percent(s.endMs ?? durationMs, timelineMs)
    if (right > left) out.push({ key: `${s.kind}-${s.id}`, left, width: right - left, opening: s.kind === 'intro' || s.kind === 'recap' })
  }
  return out
}

export type ScrubberView = {
  /** Gold: what has played, to the position. */
  played: number
  /** Light: buffered ahead of the position. */
  buffered: number
  /** The thumb: the seek's target while seeking, else the position. */
  thumb: number
  /** While seeking: the part between the position and the target. */
  gap?: { left: number; width: number }
  /** A recording still recording: where the written part ends. */
  edge?: number
  seeking: boolean
}

type Progress = Pick<FilePlaybackState, 'positionMs' | 'durationMs' | 'timelineMs' | 'bufferedMs' | 'scrub'> & { recording?: { now: boolean } }

export function scrubberView(p: Progress): ScrubberView {
  const total = p.timelineMs || p.durationMs
  const played = percent(p.positionMs, total)
  const target = p.scrub?.targetMs
  const thumb = target === undefined ? played : percent(target, total)
  return {
    played,
    buffered: percent(p.positionMs + (p.bufferedMs ?? 0), total),
    thumb,
    gap: target === undefined ? undefined : { left: Math.min(played, thumb), width: Math.abs(thumb - played) },
    edge: p.recording?.now ? percent(p.durationMs, total) : undefined,
    seeking: target !== undefined,
  }
}

/** Forward 10 s and ⏩ can't go past what is written: the play is at a growing recording's edge. */
export function atRecordingEdge(recording: FilePlaybackState['recording']): boolean {
  return !!recording?.now && !recording.goToNow
}
