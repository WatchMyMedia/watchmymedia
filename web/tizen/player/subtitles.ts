import { CueSchedule } from './cues'
import { parseVtt, type Cue } from './vtt'
import type { SubtitleTrack } from './api'

/** The TV's subtitle sizes, as Android TV's (SettingsPlayback: 22, 28, 34 and 42 px at 1080p). */
export type SubtitleSize = 'smaller' | 'normal' | 'larger' | 'largest'
export const SUBTITLE_PX: Record<SubtitleSize, number> = { smaller: 22, normal: 28, larger: 34, largest: 42 }

/** A track the server burns into the video: an image format, or one it says can't be drawn here. */
export const burnsIn = (track: SubtitleTrack | undefined) => !!track && (track.format === 'image' || track.burnInOnly)

/** The cue file's address without its token or query, so a renewed token doesn't fetch it again. */
export function trackKey(url: string): string {
  return url.split('#')[0]
}

const NONE: readonly Cue[] = Object.freeze([])

/**
 * The text tracks' cues, fetched once per address. The server sends WebVTT
 * for every text track (ASS converted), in the stream's time: an event
 * playlist's cues are already shifted to its start (?shift=), so a track is
 * read at the player's time, not the file's.
 */
export class CueTracks {
  private tracks = new Map<string, CueSchedule | 'loading' | 'failed'>()
  private closed = false

  constructor(
    private fetchText: (url: string) => Promise<string>,
    private onLoad: () => void,
    private onFail: (key: string) => void,
  ) {}

  /** The cues showing at the stream's time t for the track at url, fetching it the first time; and when they next change. */
  at(url: string, sign: (url: string) => string, t: number): { cues: readonly Cue[]; next: number } {
    const key = trackKey(url)
    const schedule = this.tracks.get(key)
    if (schedule instanceof CueSchedule) return schedule.at(t)
    if (!schedule) this.fetch(key, sign(url))
    return { cues: NONE, next: Infinity }
  }

  private fetch(key: string, signed: string) {
    this.tracks.set(key, 'loading')
    this.fetchText(signed).then((text) => {
      if (this.closed) return
      this.tracks.set(key, new CueSchedule(parseVtt(text)))
      this.onLoad()
    }, () => {
      if (this.closed) return
      this.tracks.set(key, 'failed')
      this.onFail(key)
    })
  }

  close() {
    this.closed = true
    this.tracks.clear()
  }
}

/** Fetches a cue file; any failure, a refused status included, is a rejection. */
export async function fetchCueText(url: string): Promise<string> {
  const response = await fetch(url, { credentials: 'omit', redirect: 'error' })
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return response.text()
}
