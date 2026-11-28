import { useEffect, useState, useSyncExternalStore, type RefObject } from 'react'
import { CueTracks, cueTime } from '../lib/play/cues'
import type { Cue } from '../lib/play/vtt'
import { pictureBox } from '../lib/subtitleSize'
import { SubtitleTiming, type TimingSaver, type TimingTarget, type TimingView } from '../lib/play/subtitleTiming'

const NONE: readonly Cue[] = Object.freeze([])

async function fetchCues(url: string): Promise<string> {
  const response = await fetch(url, { credentials: 'same-origin' })
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return response.text()
}

/**
 * The cues of the text track at url showing now, read at the video's time
 * less the timing: positive timing shows each cue later. The track's WebVTT
 * is fetched once; the cues change on the video's own events and, while it
 * plays, at the moment the next one starts or ends.
 */
export function useCues(videoRef: RefObject<HTMLVideoElement | null>, url: string | undefined, timingMs: number): readonly Cue[] {
  const [loaded, setLoaded] = useState(0)
  // A cache of the tracks' cues for the play; a load or a failure looks again.
  const [t] = useState(() => new CueTracks(fetchCues, () => setLoaded((n) => n + 1), () => setLoaded((n) => n + 1)))
  const [cues, setCues] = useState<readonly Cue[]>(NONE)
  useEffect(() => {
    const video = videoRef.current
    if (!video || !url) {
      setCues(NONE)
      return
    }
    let timer: number | undefined
    const update = () => {
      window.clearTimeout(timer)
      const at = cueTime(video.currentTime * 1000, timingMs)
      const shown = t.at(url, (u) => u, at)
      setCues(shown.cues)
      if (!video.paused && Number.isFinite(shown.next)) timer = window.setTimeout(update, Math.max(10, (shown.next - at) / (video.playbackRate || 1)))
    }
    update()
    const events = ['timeupdate', 'seeking', 'seeked', 'play', 'pause', 'ratechange', 'loadeddata', 'emptied'] as const
    for (const e of events) video.addEventListener(e, update)
    return () => {
      window.clearTimeout(timer)
      for (const e of events) video.removeEventListener(e, update)
    }
  }, [videoRef, url, timingMs, loaded, t])
  return url ? cues : NONE
}

export type PictureBox = { left: number; top: number; width: number; height: number; outerWidth: number; outerHeight: number }

/** Where the video draws its picture inside the player, kept current as either changes size. */
export function usePictureBox(videoRef: RefObject<HTMLVideoElement | null>, rootRef: RefObject<HTMLElement | null>): PictureBox {
  const [box, setBox] = useState<PictureBox>({ left: 0, top: 0, width: 0, height: 0, outerWidth: 0, outerHeight: 0 })
  useEffect(() => {
    const video = videoRef.current
    const root = rootRef.current
    if (!video || !root) return
    const measure = () => {
      const p = pictureBox(video.clientWidth, video.clientHeight, video.videoWidth, video.videoHeight)
      const next = { left: video.offsetLeft + p.left, top: video.offsetTop + p.top, width: p.width, height: p.height, outerWidth: root.clientWidth, outerHeight: root.clientHeight }
      setBox((b) => (Object.keys(next) as (keyof PictureBox)[]).every((k) => Math.abs(b[k] - next[k]) < 0.5) ? b : next)
    }
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(video)
    observer.observe(root)
    video.addEventListener('loadedmetadata', measure)
    video.addEventListener('resize', measure)
    return () => {
      observer.disconnect()
      video.removeEventListener('loadedmetadata', measure)
      video.removeEventListener('resize', measure)
    }
  }, [videoRef, rootRef])
  return box
}

type TimingStore = {
  control: SubtitleTiming
  subscribe: (listener: () => void) => () => void
  /** What a burned-in track's saved timing does: the player's latest. */
  onBurned: (fn: (target: TimingTarget) => void) => void
}

function timingStore(saver: TimingSaver, admin: boolean): TimingStore {
  const listeners = new Set<() => void>()
  let burned: (target: TimingTarget) => void = () => undefined
  const control = new SubtitleTiming({
    saver,
    admin,
    onChange: () => { for (const l of listeners) l() },
    onBurnedSaved: (target) => burned(target),
  })
  return { control, onBurned: (fn) => { burned = fn }, subscribe: (l) => { listeners.add(l); return () => { listeners.delete(l) } } }
}

/** A play's subtitle timing as React state: the timing store and the view it publishes. */
export function useSubtitleTiming(saver: TimingSaver, admin: boolean, onBurnedSaved: (target: TimingTarget) => void): { control: SubtitleTiming; view: TimingView } {
  const [store] = useState(() => timingStore(saver, admin))
  useEffect(() => store.control.setAdmin(admin), [store, admin])
  useEffect(() => store.onBurned(onBurnedSaved), [store, onBurnedSaved])
  // Leaving the player saves a change still waiting for its second.
  useEffect(() => () => store.control.flush(), [store])
  const view = useSyncExternalStore(store.subscribe, () => store.control.view())
  return { control: store.control, view }
}
