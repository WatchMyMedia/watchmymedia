import { useEffect, useRef, useState } from 'react'
import { Poster } from './Poster'
import { formatRuntime } from '../lib/format'
import { PlayIcon } from './icons'
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
  const facts = [next.runtimeMin ? formatRuntime(next.runtimeMin) : '', next.seasonFinale ? 'season finale' : ''].filter(Boolean).join(' · ')
  return (
    <section
      className="absolute bottom-32 right-4 z-10 flex w-[440px] max-w-[calc(100%-2rem)] gap-3.5 rounded-[14px] border border-line-3 bg-surface/95 p-3 text-neutral-100 shadow-2xl md:right-12"
      onClick={(e) => e.stopPropagation()}
      aria-label="Up next"
    >
      <span className="relative w-[120px] shrink-0 self-start sm:w-40">
        <Poster path={next.stillPath} size="w300" alt="" ratio="aspect-video" />
        {next.runtimeMin ? <span className="absolute bottom-2 right-2 rounded-[5px] bg-ground/80 px-[7px] py-0.5 text-[11px] font-bold ring-1 ring-inset ring-white/15">{formatRuntime(next.runtimeMin)}</span> : null}
      </span>
      <div className="flex min-w-0 flex-col gap-[3px]">
        <p className="eyebrow">{seconds > 0 && remaining > 0 ? `Next episode in ${remaining}` : 'Next episode'}</p>
        <p className="truncate text-base font-[650]">
          S{next.seasonNumber} E{next.episodeNumber}
          {next.title ? ` · ${next.title}` : ''}
        </p>
        <p className="truncate text-[13px] text-neutral-400">{facts || next.showTitle}</p>
        <div className="mt-2 flex flex-wrap gap-2">
          <button type="button" onClick={onPlayNow} className="btn btn-primary btn-sm relative overflow-hidden">
            <span className="absolute inset-y-0 left-0 bg-brand-hover transition-[width] duration-1000 ease-linear" style={{ width: `${filled}%` }} aria-hidden="true" />
            <PlayIcon size={13} className="relative" />
            <span className="relative">Play now</span>
          </button>
          <button type="button" onClick={onDismiss} className="btn btn-sm">
            Watch credits
          </button>
        </div>
      </div>
    </section>
  )
}
