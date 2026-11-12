import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { searchQuery } from '../api/queries'
import { requestOptionsQuery, requestableSearchQuery, type Requestable } from '../api/requests'
import { Poster } from '../components/Poster'
import { RequestDialog } from '../components/RequestDialog'
import { OnLiveTV } from '../components/liveTV/OnLiveTV'
import { seCode } from '../lib/format'
import { requestSource } from '../lib/requests'

export function Search({ q }: { q: string }) {
  return (
    <div className="space-y-6">
      <PhoneSearchBox key={q} q={q} />
      <Results q={q} />
      <OnLiveTV q={q} />
      <NotInLibrary q={q} />
    </div>
  )
}

/** The header's search box is hidden on phones, so the page has its own there. */
function PhoneSearchBox({ q }: { q: string }) {
  const navigate = useNavigate()
  const [text, setText] = useState(q)
  return (
    <form
      role="search"
      className="sm:hidden"
      onSubmit={(e) => {
        e.preventDefault()
        if (text.trim()) void navigate({ to: '/search', search: { q: text.trim() } })
      }}
    >
      <input
        type="search"
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder="Search"
        aria-label="Search titles"
        autoFocus={!q}
        enterKeyHint="search"
        className="min-h-[44px] w-full rounded-md border border-line-2 bg-surface px-3 text-base placeholder:text-neutral-500"
      />
    </form>
  )
}

function Results({ q }: { q: string }) {
  const results = useQuery(searchQuery(q))
  if (!q) return <p className="text-neutral-400">Type something to search.</p>
  if (results.isPending) return <p className="text-neutral-400">Searching…</p>
  if (results.isError) return <p className="text-red-400">{results.error.message}</p>
  const r = results.data
  const total = r.movies.length + r.shows.length + r.episodes.length
  return (
    <div className="space-y-8">
      <h1 className="text-2xl font-bold">
        Results for “{q}” <span className="text-base font-normal text-neutral-500">{total}</span>
      </h1>
      {total === 0 && <p className="text-neutral-400">Nothing matched.</p>}
      {r.movies.length > 0 && (
        <section className="space-y-3">
          <h2 className="text-lg font-semibold">Movies</h2>
          <ul className="grid grid-cols-3 gap-4 md:grid-cols-5 xl:grid-cols-8">
            {r.movies.map((m) => (
              <li key={m.id}>
                <Link to="/movies/$movieId" params={{ movieId: String(m.id) }} className="card block">
                  <Poster path={m.posterPath} alt={m.title} />
                  <div className="mt-1.5 truncate text-sm font-medium">{m.title}</div>
                  <div className="text-xs text-neutral-500">{m.year || ''}</div>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}
      {r.shows.length > 0 && (
        <section className="space-y-3">
          <h2 className="text-lg font-semibold">Shows</h2>
          <ul className="grid grid-cols-3 gap-4 md:grid-cols-5 xl:grid-cols-8">
            {r.shows.map((s) => (
              <li key={s.id}>
                <Link to="/shows/$showId" params={{ showId: String(s.id) }} className="card block">
                  <Poster path={s.posterPath} alt={s.title} />
                  <div className="mt-1.5 truncate text-sm font-medium">{s.title}</div>
                  <div className="text-xs text-neutral-500">{s.year || ''}</div>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}
      {r.episodes.length > 0 && (
        <section className="space-y-3">
          <h2 className="text-lg font-semibold">Episodes</h2>
          <ul className="space-y-2">
            {r.episodes.map((e) => (
              <li key={e.id}>
                <Link
                  to="/shows/$showId/seasons/$number/episodes/$episodeId"
                  params={{ showId: String(e.showId), number: String(e.seasonNumber), episodeId: String(e.id) }}
                  className="flex items-center gap-3 rounded-lg border border-line bg-surface p-2 hover:border-line-2"
                >
                  <div className="w-28 shrink-0">
                    <Poster path={e.stillPath ?? e.showPosterPath} size="w300" alt={e.title} ratio="aspect-video" />
                  </div>
                  <div className="min-w-0">
                    <div className="truncate text-sm font-medium">{e.title}</div>
                    <div className="text-xs text-neutral-500">
                      {e.showTitle} · {seCode(e.seasonNumber, e.number)}
                      {e.airDate ? ` · ${e.airDate}` : ''}
                    </div>
                  </div>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  )
}

const stateLabel: Record<Requestable['state'], string> = {
  requestable: 'Request',
  requested: 'Requested',
  downloading: 'Downloading',
  available: 'In your library',
}

/**
 * TMDB's results the library may not have, each with Request or where it
 * stands (PLAN-ARR.md §7.1). Fetched beside the library search, never
 * holding it up.
 */
function NotInLibrary({ q }: { q: string }) {
  const options = useQuery(requestOptionsQuery)
  const on = !!(options.data?.movies || options.data?.shows) && q.length > 0
  const results = useQuery({ ...requestableSearchQuery(q), enabled: on })
  const [open, setOpen] = useState<Requestable | null>(null)
  if (!on) return null
  const items = (results.data?.items ?? []).filter((r) => (r.mediaType === 'movie' ? options.data?.movies : options.data?.shows))
  return (
    <section className="space-y-3">
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <h2 className="text-lg font-semibold">Not in your library</h2>
        <span className="text-[13px] text-neutral-400">From {requestSource(options.data?.backend)}</span>
      </div>
      {results.isPending && <p className="text-sm text-neutral-400">Looking on {requestSource(options.data?.backend)}…</p>}
      {results.isError && <p className="text-sm text-warn">{results.error.message}</p>}
      {results.data && items.length === 0 && <p className="text-sm text-neutral-400">Nothing else found.</p>}
      <ul className="grid grid-cols-3 gap-4 md:grid-cols-5 xl:grid-cols-8">
        {items.map((r) => (
          <li key={`${r.mediaType}-${r.tmdbId}`}>
            {r.movieId ? (
              <Link to="/movies/$movieId" params={{ movieId: String(r.movieId) }} className="card block">
                <RequestableCard r={r} />
              </Link>
            ) : r.showId && r.state === 'available' ? (
              <Link to="/shows/$showId" params={{ showId: String(r.showId) }} className="card block">
                <RequestableCard r={r} />
              </Link>
            ) : (
              <button type="button" onClick={() => setOpen(r)} className="card block w-full text-left">
                <RequestableCard r={r} />
              </button>
            )}
          </li>
        ))}
      </ul>
      {open && <RequestDialog mediaType={open.mediaType} tmdbId={open.tmdbId} onClose={() => setOpen(null)} />}
    </section>
  )
}

function RequestableCard({ r }: { r: Requestable }) {
  return (
    <>
      <Poster path={r.posterPath} alt={r.title} />
      <div className="mt-1.5 truncate text-sm font-medium">{r.title}</div>
      <div className="flex items-center justify-between gap-2 text-xs">
        <span className="text-neutral-500">
          {r.year || ''} · {r.mediaType === 'movie' ? 'Movie' : 'Show'}
        </span>
        <span className={r.state === 'requestable' ? 'font-semibold text-accent' : 'text-neutral-400'}>{stateLabel[r.state]}</span>
      </div>
    </>
  )
}
