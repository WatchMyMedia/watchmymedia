import { useEffect, useRef, useState } from 'react'
import { Poster } from './Poster'
import { seCode } from '../lib/format'
import type { components } from '../api/schema'

type UpNext = components['schemas']['UpNext']

/**
 * The player's "Next episode" card, shown from the credits on. It counts
 * down from `seconds` (holding while `hold` is set, such as when paused),
 * the play button filling as it goes, and calls onPlay at zero. With
 * `seconds` 0 (autoplay off) it waits for a click, which calls onPlayNow.
 */
export function NextEpisodeCard({
  next,
  seconds,
  hold,
  onPlay,
  onPlayNow,
  onDismiss,
}: {
  next: UpNext
  seconds: number
  hold: boolean
  onPlay: () => void
  onPlayNow: () => void
  onDismiss: () => void
}) {
  const [remaining, setRemaining] = useState(seconds)
  const holdRef = useRef(hold)
  useEffect(() => {
    holdRef.current = hold
  }, [hold])
  useEffect(() => {
    if (seconds <= 0) return
    const id = window.setInterval(() => {
      if (!holdRef.current) setRemaining((r) => Math.max(0, r - 1))
    }, 1000)
    return () => window.clearInterval(id)
  }, [seconds])
  useEffect(() => {
    if (seconds > 0 && remaining === 0) onPlay()
  }, [seconds, remaining, onPlay])
  const filled = seconds > 0 ? Math.min(100, ((seconds - remaining) / seconds) * 100) : 0
  return (
    <div
      className="absolute bottom-28 right-4 z-10 w-80 max-w-[calc(100%-2rem)] overflow-hidden rounded-lg border border-white/15 bg-neutral-950/90 text-white shadow-2xl backdrop-blur"
      onClick={(e) => e.stopPropagation()}
      role="dialog"
      aria-label="Next episode"
    >
      <div className="relative">
        <Poster path={next.stillPath} size="w300" alt={next.title} ratio="aspect-video" className="rounded-none" />
        <span className="absolute left-2 top-2 rounded bg-black/70 px-1.5 py-0.5 text-[11px] font-medium uppercase tracking-wide text-white/80">Next episode</span>
      </div>
      <div className="p-3">
        <div className="truncate text-sm font-semibold">
          {seCode(next.seasonNumber, next.episodeNumber)} · {next.title}
        </div>
        <div className="truncate text-xs text-white/60">{next.showTitle}</div>
        <div className="mt-3 flex gap-2">
          <button type="button" onClick={onPlayNow} className="relative min-h-[40px] flex-1 overflow-hidden rounded-md bg-white/15 px-3 text-sm font-semibold hover:bg-white/25">
            <span className="absolute inset-y-0 left-0 bg-brand transition-[width] duration-1000 ease-linear" style={{ width: `${filled}%` }} aria-hidden="true" />
            <span className="relative">▶ Next episode{seconds > 0 && remaining > 0 ? ` in ${remaining}` : ''}</span>
          </button>
          <button type="button" onClick={onDismiss} className="min-h-[40px] rounded-md border border-white/20 px-3 text-sm text-white/80 hover:bg-white/10">
            Watch credits
          </button>
        </div>
      </div>
    </div>
  )
}
