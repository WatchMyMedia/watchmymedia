import type { components } from '../src/api/schema'
import { CueTracks, cueBlocks, cueTime } from '../src/lib/play/cues'
import type { Cue } from '../src/lib/play/vtt'
import { cueFontPx, pictureBox, subtitleSize, type SubtitleSize } from '../src/lib/subtitleSize'

type SubtitleTrack = components['schemas']['SubtitleTrack']

/**
 * The receiver draws a play's text subtitles itself, as the web player and
 * the TV app do, rather than letting CAF show them: from the chosen track's
 * WebVTT, at the player's time less the subtitle timing, at the person's
 * size against the picture's height. A sender's timing moves them at once.
 * CAF and Shaka still play the stream as before.
 */

/** The WebVTT address of a track the receiver draws: text it can read (ASS converted), never one burned into the picture. */
export function cueSource(track: SubtitleTrack | undefined, burnedId: string | undefined): string | undefined {
  if (!track?.url || track.format === 'image' || track.burnInOnly || track.id === burnedId) return undefined
  return track.url.replace(/\.ass(?=\?|$)/, '.vtt')
}

/**
 * A track's timing on this TV: the one a sender just moved it to, else the
 * decision's (the person's own, or everyone's), and whether that is
 * everyone's.
 */
export function trackTiming(track: SubtitleTrack, moved: ReadonlyMap<string, number>): { timingMs: number; timingShared: boolean } {
  const own = moved.get(track.id)
  if (own !== undefined) return { timingMs: own, timingShared: false }
  return { timingMs: track.timingMs ?? 0, timingShared: !!track.timingShared }
}

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] as string)

/** The cues as markup: a block at the top of the picture for those placed there, the rest at its foot. */
export function cueMarkup(cues: readonly Cue[], place: { left: number; right: number; top: number; bottom: number; fontPx: number }): string {
  if (!cues.length) return ''
  const { top, bottom } = cueBlocks(cues)
  const block = (list: readonly Cue[], edge: string) =>
    `<div class="cues" style="left:${place.left.toFixed(1)}px;right:${place.right.toFixed(1)}px;${edge};font-size:${place.fontPx.toFixed(1)}px">${
      list.map((c) => `<p class="cue"><span>${c.lines.map(esc).join('<br>')}</span></p>`).join('')}</div>`
  return (top.length ? block(top, `top:${place.top.toFixed(1)}px`) : '') + (bottom.length ? block(bottom, `bottom:${place.bottom.toFixed(1)}px`) : '')
}

export type CueSettings = { url: string; sign: (url: string) => string; timingMs: number; size: SubtitleSize }

const MEDIA_EVENTS = ['timeupdate', 'seeking', 'seeked', 'play', 'pause', 'ratechange', 'loadeddata', 'emptied'] as const

/** Draws the cues into el over the video, waking at each change while it plays. */
export class ReceiverCues {
  private readonly tracks: CueTracks
  private settings: CueSettings | null = null
  private video: HTMLMediaElement | null = null
  private timer: number | undefined
  private html = ''

  private readonly wake = () => this.draw()

  constructor(private readonly el: HTMLElement, fetchText: (url: string) => Promise<string>, private readonly findVideo: () => HTMLMediaElement | null) {
    this.tracks = new CueTracks(fetchText, this.wake, this.wake)
    window.addEventListener('resize', this.wake)
  }

  /** The video's own events redraw the cues; CAF may swap its element, so they follow it. */
  private follow(video: HTMLMediaElement | null) {
    if (video === this.video) return
    for (const e of MEDIA_EVENTS) this.video?.removeEventListener(e, this.wake)
    this.video = video
    for (const e of MEDIA_EVENTS) video?.addEventListener(e, this.wake)
  }

  /** What to draw now; null draws nothing. */
  set(settings: CueSettings | null) {
    const same = settings && this.settings && settings.url === this.settings.url && settings.timingMs === this.settings.timingMs && settings.size === this.settings.size
    this.settings = settings
    // CAF makes its video once a stream loads: the next call finds it.
    if (!same || this.findVideo() !== this.video) this.draw()
  }

  draw() {
    window.clearTimeout(this.timer)
    const s = this.settings
    const video = this.findVideo()
    this.follow(video)
    if (!s || !video) return this.paint('')
    const at = cueTime(video.currentTime * 1000, s.timingMs)
    const { cues, next } = this.tracks.at(s.url, s.sign, at)
    const w = window.innerWidth, h = window.innerHeight
    const vw = video instanceof HTMLVideoElement ? video.videoWidth : 0
    const vh = video instanceof HTMLVideoElement ? video.videoHeight : 0
    const box = pictureBox(w, h, vw, vh)
    const margin = box.height * 0.07
    this.paint(cueMarkup(cues, {
      left: box.left, right: w - box.left - box.width,
      top: box.top + margin, bottom: h - box.top - box.height + margin,
      fontPx: cueFontPx(box.height, s.size),
    }))
    if (!video.paused && Number.isFinite(next)) this.timer = window.setTimeout(() => this.draw(), Math.max(10, (next - at) / (video.playbackRate || 1)))
  }

  private paint(html: string) {
    if (html === this.html) return
    this.html = html
    this.el.innerHTML = html
  }
}

/** The size a sender passed, read safely. */
export const loadSize = (size: string | undefined) => subtitleSize(size)
