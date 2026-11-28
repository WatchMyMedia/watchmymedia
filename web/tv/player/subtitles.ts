export { CueTracks, trackKey } from '../../src/lib/play/cues'
import type { SubtitleTrack } from './api'

/** The TV's subtitle sizes, as Android TV's (22, 28, 34 and 42 px at 1080p). */
export type SubtitleSize = 'smaller' | 'normal' | 'larger' | 'largest'
export const SUBTITLE_PX: Record<SubtitleSize, number> = { smaller: 22, normal: 28, larger: 34, largest: 42 }

/** A track the server burns into the video: an image format, or one it says can't be drawn here. */
export const burnsIn = (track: SubtitleTrack | undefined) => !!track && (track.format === 'image' || track.burnInOnly)

/** Fetches a cue file; any failure, a refused status included, is a rejection. */
export async function fetchCueText(url: string): Promise<string> {
  const response = await fetch(url, { credentials: 'omit', redirect: 'error' })
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return response.text()
}
