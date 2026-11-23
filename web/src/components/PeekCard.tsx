import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { movieQuery, showQuery } from '../api/queries'
import { useMarkWatched } from '../api/watch'
import type { components } from '../api/schema'
import { peekFacts, qualityWords } from '../lib/cardFacts'
import { endsWords } from '../lib/endTime'
import { img } from '../lib/images'
import { CheckIcon, PlayIcon } from './icons'
import { WatchlistAction } from './watchlists/WatchlistButton'
import { useNow } from './useNow'

type BrowseItem = components['schemas']['BrowseItem']

const OPEN_DELAY_MS = 450
const CLOSE_DELAY_MS = 160
const WIDTH = 340

/**
 * The hover card: while a mouse rests on a poster in a row, a card beside it
 * with the synopsis, the facts, Play, Watchlist and (for a movie) Mark
 * watched, and when it would end. Touch and the keyboard don't open it: the
 * title's page has the same, one step away. PeekHost is the card's own box,
 * whose title and line stay its links.
 */
export function PeekHost({ item, enabled, className, children }: { item: BrowseItem; enabled: boolean; className?: string; children: ReactNode }) {
  const [anchor, setAnchor] = useState<DOMRect | null>(null)
  const timer = useRef<number | undefined>(undefined)
  const el = useRef<HTMLDivElement>(null)
  useEffect(() => () => window.clearTimeout(timer.current), [])
  useEffect(() => {
    if (!anchor) return
    const away = () => setAnchor(null)
    window.addEventListener('scroll', away, true)
    window.addEventListener('resize', away)
    return () => {
      window.removeEventListener('scroll', away, true)
      window.removeEventListener('resize', away)
    }
  }, [anchor])
  const hold = () => window.clearTimeout(timer.current)
  const leave = () => {
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => setAnchor(null), CLOSE_DELAY_MS)
  }
  return (
    <div
      ref={el}
      className={className}
      onPointerEnter={(e) => {
        if (!enabled || e.pointerType !== 'mouse' || !window.matchMedia('(min-width: 768px)').matches) return
        window.clearTimeout(timer.current)
        timer.current = window.setTimeout(() => {
          if (el.current) setAnchor(el.current.getBoundingClientRect())
        }, OPEN_DELAY_MS)
      }}
      onPointerLeave={leave}
      onPointerDown={() => {
        window.clearTimeout(timer.current)
        setAnchor(null)
      }}
    >
      {children}
      {anchor && createPortal(<PeekCard item={item} anchor={anchor} onEnter={hold} onLeave={leave} onClose={() => setAnchor(null)} />, document.body)}
    </div>
  )
}

function PeekCard({ item, anchor, onEnter, onLeave, onClose }: { item: BrowseItem; anchor: DOMRect; onEnter: () => void; onLeave: () => void; onClose: () => void }) {
  const box = useRef<HTMLDivElement>(null)
  const [place, setPlace] = useState<{ left: number; top: number }>(() => ({ left: anchor.left + anchor.width * 0.6, top: anchor.top + 24 }))
  useLayoutEffect(() => {
    const h = box.current?.offsetHeight ?? 360
    const right = anchor.right - 24
    // Beside the card, to the right while it fits, else to the left; never off the window.
    const left = right + WIDTH + 16 <= window.innerWidth ? right : Math.max(12, anchor.left + 24 - WIDTH)
    const top = Math.min(Math.max(72, anchor.top + 24), window.innerHeight - h - 12)
    setPlace({ left, top })
  }, [anchor])
  const movie = useQuery({ ...movieQuery(item.id), enabled: item.kind === 'movie' })
  const show = useQuery({ ...showQuery(item.id), enabled: item.kind === 'show' })
  const mark = useMarkWatched()
  const facts = peekFacts(item)
  const picture = img(item.backdropPath, 'w780') ?? img(item.posterPath, 'w342')
  const now = useNow()
  const ends = item.kind === 'movie' ? endsWords(item.runtimeMin, now) : undefined
  const foot = [item.genres.slice(0, 2).join(', '), qualityWords(item.quality, item.hdr)].filter(Boolean).join(' · ')
  const target = { mediaType: item.kind === 'show' ? ('tv' as const) : ('movie' as const), tmdbId: item.tmdbId ?? 0, title: item.title, year: item.year, posterPath: item.posterPath }
  let play: ReactNode = null
  if (item.kind === 'movie') {
    const file = movie.data?.files?.[0]
    if (file)
      play = (
        <Link to="/play/$fileId" params={{ fileId: String(file.id) }} search={{ title: item.title, back: `/movies/${item.id}` }} onClick={onClose} className="btn btn-primary btn-sm min-h-9">
          <PlayIcon size={15} />
          {movie.data?.watch && movie.data.watch.positionMs > 0 && !movie.data.watch.completed ? 'Resume' : 'Play'}
        </Link>
      )
  } else {
    const next = show.data?.nextEpisode
    if (next?.fileId)
      play = (
        <Link
          to="/play/$fileId"
          params={{ fileId: String(next.fileId) }}
          search={{ title: `${item.title} · S${next.seasonNumber}E${next.episodeNumber}${next.title ? ` ${next.title}` : ''}`, back: `/shows/${item.id}` }}
          onClick={onClose}
          className="btn btn-primary btn-sm min-h-9"
        >
          <PlayIcon size={15} />
          {`S${next.seasonNumber} E${next.episodeNumber}`}
        </Link>
      )
  }
  return (
    <div
      ref={box}
      role="group"
      aria-label={`${item.title}, shown while the pointer rests on its card`}
      onPointerEnter={onEnter}
      onPointerLeave={onLeave}
      style={{ left: place.left, top: place.top, width: WIDTH }}
      className="fixed z-50 overflow-hidden rounded-[14px] border border-line-3 bg-surface-2 shadow-[0_24px_48px_rgba(0,0,0,.6)]"
    >
      <div className="relative h-[150px] bg-surface">
        {picture ? <img src={picture} alt="" className="size-full object-cover" /> : <span className="absolute inset-x-4 bottom-3 text-xl font-bold">{item.title}</span>}
      </div>
      <div className="flex flex-col gap-2.5 px-4 pb-4 pt-3.5">
        <p className="text-[15px] font-semibold">{item.title}</p>
        {facts.length > 0 && (
          <p className="meta -mt-1.5 text-[13px]">
            {facts.map((f) => (
              <span key={f}>{f}</span>
            ))}
          </p>
        )}
        {item.overview && <p className="line-clamp-3 text-[13.5px] text-neutral-300">{item.overview}</p>}
        <div className="flex items-center gap-2">
          {play}
          {!!item.tmdbId && <WatchlistAction target={target} title={item.title} watchlisted={item.watchlisted} className="[&>button]:size-9 [&>button]:min-h-0 [&>button]:rounded-[7px] [&>button]:px-0 [&>button>span]:hidden" />}
          {item.kind === 'movie' && (
            <button
              type="button"
              aria-pressed={!!item.watched}
              aria-label={item.watched ? `Mark ${item.title} unwatched` : `Mark ${item.title} watched`}
              title={item.watched ? 'Watched' : 'Mark watched'}
              onClick={() => mark.mutate({ type: 'movie', id: item.id, watched: !item.watched })}
              className={`ib ib-sm size-9 ${item.watched ? 'text-accent' : ''}`}
            >
              <CheckIcon size={16} />
            </button>
          )}
          {ends && <span className="ml-auto text-xs text-dim">{ends}</span>}
        </div>
        {foot && <p className="text-xs text-dim">{foot}</p>}
      </div>
    </div>
  )
}
