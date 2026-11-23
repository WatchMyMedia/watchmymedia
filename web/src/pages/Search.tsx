import { useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { movieQuery, searchQuery, seasonQuery, showQuery } from '../api/queries'
import type { components } from '../api/schema'
import { type Requestable } from '../api/requests'
import { Poster } from '../components/Poster'
import { RequestDialog } from '../components/RequestDialog'
import { useLiveSearch, useNotInLibrary } from '../components/SearchHooks'
import { CheckIcon, PlayIcon, PlusIcon } from '../components/icons'
import { OnLiveTV } from '../components/liveTV/OnLiveTV'
import { useNow } from '../components/useNow'
import { qualityWords, scoreWords, showStatusWords } from '../lib/cardFacts'
import { leftWords, percentWatched } from '../lib/endTime'
import { formatRuntime, plural } from '../lib/format'
import { requestSource } from '../lib/requests'
import {
  bestMatch, episodeRowWords, requestableLine, requestableState, resultsWords, searchChips, searchMovieLine, searchShowLine, sectionShown,
  type BestMatch, type SearchSection,
} from '../lib/searchPage'
import { episodePath } from '../lib/showPage'

type SearchResults = components['schemas']['SearchResults']
type Movie = components['schemas']['Movie']
type Show = components['schemas']['Show']
type SearchEpisode = components['schemas']['SearchEpisode']

/**
 * Search: what the library has first, the best match on top with the
 * person's place in it, episodes that play from the list and what's on live
 * TV; then, set apart, what the library doesn't have, with Request. The
 * words are typed in the app bar's box.
 */
export function Search({ q }: { q: string }) {
  const results = useQuery({ ...searchQuery(q), enabled: q.length > 0 })
  const live = useLiveSearch(q)
  const missing = useNotInLibrary(q)
  const [chosen, setChosen] = useState<SearchSection | undefined>(undefined)
  if (!q)
    return (
      <section className="flex max-w-[640px] flex-col gap-2.5 pt-9">
        <h1 className="text-[28px] font-bold leading-tight tracking-[-.02em]">Search</h1>
        <p className="text-[15px] text-neutral-300">Type in the box at the top to search the titles of movies, shows and episodes{live.on ? ', and the live TV guide' : ''}.</p>
      </section>
    )
  if (results.isPending) return <p className="pt-7 text-neutral-400">Searching…</p>
  if (results.isError) return <p className="pt-7 text-red-400">{results.error.message}</p>
  const r = results.data
  const counts: Record<SearchSection, number> = { movies: r.movies.length, shows: r.shows.length, episodes: r.episodes.length, live: live.hits.length, requests: missing.items.length }
  const inLibrary = counts.movies + counts.shows + counts.episodes + counts.live
  const best = bestMatch(r, q)
  const show = (s: SearchSection) => sectionShown(chosen, s) && counts[s] > 0
  return (
    <div className="flex flex-col pb-6 pt-2">
      {inLibrary === 0 && !live.pending ? (
        <Nothing q={q} live={live.on} />
      ) : (
        <>
          <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1.5">
            <h1 className="text-[28px] font-bold leading-tight tracking-[-.02em]">Results for “{q}”</h1>
            <span className="text-neutral-400">{resultsWords(inLibrary + counts.requests)}</span>
          </div>
          <div role="group" aria-label="Narrow the results" className="mt-4 flex flex-wrap items-center gap-2">
            {searchChips(counts).map((c) => (
              <button key={c.label} type="button" aria-pressed={chosen === c.value} onClick={() => setChosen(c.value)} className="chip">
                {c.label}
                <span className="n">{c.count}</span>
              </button>
            ))}
          </div>
          <Library r={r} best={chosen === undefined ? best : undefined} show={show} />
          {show('live') && (
            <div className="mt-8 md:mt-10">
              <OnLiveTV hits={live.hits} />
            </div>
          )}
        </>
      )}
      {sectionShown(chosen, 'requests') && <NotInLibrary missing={missing} />}
    </div>
  )
}

/** The library's results: the best match beside the shows, then movies and episodes. */
function Library({ r, best, show }: { r: SearchResults; best?: BestMatch; show: (s: SearchSection) => boolean }) {
  const shows = best?.kind === 'show' ? r.shows.filter((s) => s.id !== best.item.id) : r.shows
  const movies = best?.kind === 'movie' ? r.movies.filter((m) => m.id !== best.item.id) : r.movies
  return (
    <>
      {(best || (show('shows') && shows.length > 0)) && (
        <div className="mt-7 flex flex-wrap gap-x-10 gap-y-8">
          {best && (
            <section aria-labelledby="h-best" className="min-w-0 max-w-[840px] flex-[1_1_560px]">
              <div className="rowh">
                <h2 id="h-best">Best match</h2>
              </div>
              {best.kind === 'movie' ? <BestMovie movie={best.item} /> : <BestShow show={best.item} />}
            </section>
          )}
          {show('shows') && shows.length > 0 && (
            <Titles id="h-shows" heading={best ? 'More shows' : 'Shows'} count={shows.length} className="min-w-0 flex-[1_1_360px]">
              {shows.map((s) => (
                <TitleCard key={s.id} to={{ to: '/shows/$showId', params: { showId: String(s.id) } }} title={s.title} line={searchShowLine(s)} posterPath={s.posterPath} />
              ))}
            </Titles>
          )}
        </div>
      )}
      {show('movies') && movies.length > 0 && (
        <Titles id="h-movies" heading={best ? 'More movies' : 'Movies'} count={movies.length} className="mt-8 md:mt-10">
          {movies.map((m) => (
            <TitleCard key={m.id} to={{ to: '/movies/$movieId', params: { movieId: String(m.id) } }} title={m.title} line={searchMovieLine(m)} posterPath={m.posterPath} />
          ))}
        </Titles>
      )}
      {show('episodes') && r.episodes.length > 0 && <Episodes episodes={r.episodes} />}
    </>
  )
}

function Titles({ id, heading, count, className = '', children }: { id: string; heading: string; count: number; className?: string; children: ReactNode }) {
  return (
    <section aria-labelledby={id} className={className}>
      <div className="rowh">
        <h2 id={id}>{heading}</h2>
        <span className="note">{count}</span>
      </div>
      <ul className="mt-3.5 grid grid-cols-[repeat(auto-fill,minmax(min(150px,42%),1fr))] gap-x-4 gap-y-5 md:grid-cols-[repeat(auto-fill,168px)]">
        {children}
      </ul>
    </section>
  )
}

type TitleTarget = { to: '/movies/$movieId'; params: { movieId: string } } | { to: '/shows/$showId'; params: { showId: string } }

function TitleCard({ to, title, line, posterPath }: { to: TitleTarget; title: string; line: string; posterPath?: string }) {
  return (
    <li className="min-w-0">
      <Link {...to} className="card group block">
        <Poster path={posterPath} alt={title} className="ring-1 ring-inset ring-white/[.07]" />
        <span className="mt-2 block truncate text-sm font-medium group-hover:text-accent">{title}</span>
        {line && <span className="mt-px block truncate text-[12.5px] text-neutral-400">{line}</span>}
      </Link>
    </li>
  )
}

/** A best match's frame: its poster, then the words and buttons. */
function BestFrame({ to, posterPath, children }: { to: TitleTarget; posterPath?: string; children: ReactNode }) {
  return (
    <article className="panel mt-3.5 flex items-start gap-4 p-3.5 md:gap-5 md:p-[18px]">
      {/* The poster is a bigger pointer target for Open; the buttons are the tab stops. */}
      <Link {...to} tabIndex={-1} aria-hidden="true" className="card block w-[88px] shrink-0 md:w-[150px]">
        <Poster path={posterPath} alt="" size="w342" />
      </Link>
      <div className="flex min-w-0 flex-1 flex-col gap-2.5 self-stretch">{children}</div>
    </article>
  )
}

function BestMovie({ movie: m }: { movie: Movie }) {
  const detail = useQuery(movieQuery(m.id))
  const d = detail.data
  const file = d?.files?.[0]
  const watch = d?.watch
  const resume = !!watch && watch.positionMs > 0 && !watch.completed
  const duration = watch?.durationMs || file?.durationMs
  const left = resume ? leftWords(watch.positionMs, duration) : undefined
  const pct = resume ? percentWatched(watch.positionMs, duration) : 0
  const facts = [m.year ? String(m.year) : '', m.runtimeMin ? formatRuntime(m.runtimeMin) : '', m.contentRating ?? '', m.genres.slice(0, 2).join(', '), scoreWords(m.rating)].filter(Boolean)
  const to: TitleTarget = { to: '/movies/$movieId', params: { movieId: String(m.id) } }
  return (
    <BestFrame to={to} posterPath={m.posterPath}>
      <p className="eyebrow">Movie</p>
      <h3 className="text-[22px] font-bold leading-[1.15] tracking-[-.02em] md:text-[26px]">{m.title}</h3>
      {facts.length > 0 && (
        <p className="meta">
          {facts.map((f) => (
            <span key={f}>{f}</span>
          ))}
        </p>
      )}
      {d && (watch?.completed || qualityWords(d.quality, d.hdr)) && (
        <p className="flex flex-wrap gap-2">
          {watch?.completed && (
            <span className="pill pill-gold">
              <CheckIcon size={13} />
              Watched
            </span>
          )}
          {qualityWords(d.quality, d.hdr) && <span className="pill">{qualityWords(d.quality, d.hdr)}</span>}
        </p>
      )}
      {left && (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2 pt-1.5">
          <span className="meter min-w-[120px] flex-[0_1_200px]" role="img" aria-label={`${pct}% watched`}>
            <i style={{ width: `${pct}%` }} />
          </span>
          <span className="text-[13.5px] text-neutral-300">{left}</span>
        </div>
      )}
      <div className="mt-auto flex flex-wrap gap-2.5 pt-1">
        {file && (
          <Link to="/play/$fileId" params={{ fileId: String(file.id) }} search={{ title: m.title, back: `/movies/${m.id}` }} className="btn btn-primary">
            <PlayIcon size={15} />
            {resume ? 'Resume' : 'Play'}
          </Link>
        )}
        <Link {...to} className="btn">
          Open movie
        </Link>
      </div>
    </BestFrame>
  )
}

function BestShow({ show: s }: { show: Show }) {
  const detail = useQuery(showQuery(s.id))
  const now = useNow()
  const d = detail.data
  const seasons = d?.seasons?.filter((x) => x.number > 0).length ?? 0
  const years = s.year ? (d?.status === 'Ended' && d.lastAirDate ? `${s.year}–${d.lastAirDate.slice(0, 4)}` : `${s.year}–`) : ''
  const facts = [years, seasons ? plural(seasons, 'season') : s.episodeCount ? plural(s.episodeCount, 'episode') : '', s.contentRating ?? '', s.genres.slice(0, 2).join(', '), scoreWords(s.rating)].filter(Boolean)
  const status = showStatusWords(s, now)
  const next = d?.nextEpisode
  // The next episode's place, from its season: a show's reply names the episode, not how far in it is.
  const season = useQuery({ ...seasonQuery(s.id, next?.seasonNumber ?? 0), enabled: !!next })
  const watch = season.data?.episodes?.find((e) => e.id === next?.episodeId)?.watch
  const resume = !!watch && watch.positionMs > 0 && !watch.completed
  const left = resume ? leftWords(watch.positionMs, watch.durationMs) : undefined
  const pct = resume ? percentWatched(watch.positionMs, watch.durationMs) : 0
  const to: TitleTarget = { to: '/shows/$showId', params: { showId: String(s.id) } }
  return (
    <BestFrame to={to} posterPath={s.posterPath}>
      <p className="eyebrow">TV show</p>
      <h3 className="text-[22px] font-bold leading-[1.15] tracking-[-.02em] md:text-[26px]">{s.title}</h3>
      {facts.length > 0 && (
        <p className="meta">
          {facts.map((f) => (
            <span key={f}>{f}</span>
          ))}
        </p>
      )}
      {status && (
        <p>
          <span className="pill pill-gold">{status}</span>
        </p>
      )}
      {next && (
        <div className="flex flex-col gap-2 pt-1.5">
          <p className="text-sm">
            <span className="text-neutral-400">Up next</span> · S{next.seasonNumber} E{next.episodeNumber}
            {next.title ? ` · ${next.title}` : ''}
          </p>
          {left && (
            <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
              <span className="meter min-w-[120px] flex-[0_1_200px]" role="img" aria-label={`${pct}% watched`}>
                <i style={{ width: `${pct}%` }} />
              </span>
              <span className="text-[13.5px] text-neutral-300">{left}</span>
            </div>
          )}
        </div>
      )}
      <div className="mt-auto flex flex-wrap gap-2.5 pt-1">
        {next?.fileId && (
          <Link
            to="/play/$fileId"
            params={{ fileId: String(next.fileId) }}
            search={{ title: `${s.title} · S${next.seasonNumber}E${next.episodeNumber}${next.title ? ` ${next.title}` : ''}`, back: episodePath(s.id, next.seasonNumber, next.episodeId) }}
            className="btn btn-primary"
          >
            <PlayIcon size={15} />
            {resume ? 'Resume' : 'Play'} S{next.seasonNumber} E{next.episodeNumber}
          </Link>
        )}
        <Link {...to} className="btn">
          Open show
        </Link>
      </div>
    </BestFrame>
  )
}

/** Episodes, each playing from its row. */
function Episodes({ episodes }: { episodes: readonly SearchEpisode[] }) {
  return (
    <section aria-labelledby="h-eps" className="mt-8 md:mt-10">
      <div className="rowh">
        <h2 id="h-eps">Episodes</h2>
        <span className="note">{episodes.length} · matched by episode title</span>
      </div>
      <ul className="panel mt-3.5">
        {episodes.map((e) => {
          const w = episodeRowWords(e)
          const at = episodePath(e.showId, e.seasonNumber, e.id)
          return (
            <li key={e.id} className="flex items-center gap-3.5 border-t border-line px-3.5 py-3 first:border-t-0">
              <Link to={at} aria-label={w.label} className="group flex min-w-0 flex-1 items-center gap-3.5">
                <span className="w-24 shrink-0 sm:w-32">
                  <Poster path={e.stillPath ?? e.showPosterPath} size="w300" alt="" ratio="aspect-video" className="ring-1 ring-inset ring-white/[.07]" />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="line-clamp-2 text-[15px] font-medium group-hover:text-accent md:line-clamp-1">{w.title}</span>
                  {w.line && <span className="block truncate text-[12.5px] text-neutral-400">{w.line}</span>}
                </span>
              </Link>
              {e.watched && (
                <span role="img" aria-label="Watched" className="hidden size-[22px] shrink-0 place-items-center rounded-full text-accent ring-1 ring-inset ring-accent/70 sm:grid">
                  <CheckIcon size={13} />
                </span>
              )}
              {e.fileId ? (
                <Link
                  to="/play/$fileId"
                  params={{ fileId: String(e.fileId) }}
                  search={{ title: `${e.showTitle} · S${e.seasonNumber}E${e.number}${e.title ? ` ${e.title}` : ''}`, back: at }}
                  aria-label={`Play ${w.label}`}
                  className="ib rounded-full"
                >
                  <PlayIcon size={16} />
                </Link>
              ) : (
                <span className="shrink-0 text-xs text-neutral-400">No file</span>
              )}
            </li>
          )
        })}
      </ul>
    </section>
  )
}

/** Nothing in the library: say what Search looks at, and where to browse instead. */
function Nothing({ q, live }: { q: string; live: boolean }) {
  return (
    <section aria-labelledby="h-none" className="flex max-w-[640px] flex-col gap-2.5 pb-2 pt-9">
      <h1 id="h-none" className="text-[28px] font-bold leading-tight tracking-[-.02em]">
        Nothing in your library matches “{q}”
      </h1>
      <p className="text-[15px] text-neutral-300">Check the spelling, or try fewer words. Search looks at the titles of movies, shows and episodes{live ? ', and at the live TV guide' : ''}.</p>
      <div className="mt-2 flex flex-wrap gap-2.5">
        <Link to="/movies" className="btn">
          Browse movies
        </Link>
        <Link to="/tv" className="btn">
          Browse TV shows
        </Link>
      </div>
    </section>
  )
}

/**
 * What TMDB has that the library doesn't, set apart under a line, each
 * with Request or where its request stands. Fetched beside the library
 * search, never holding it up.
 */
function NotInLibrary({ missing }: { missing: ReturnType<typeof useNotInLibrary> }) {
  const [open, setOpen] = useState<Requestable | null>(null)
  if (!missing.on) return null
  const source = requestSource(missing.backend)
  return (
    <section aria-labelledby="h-req" className="mt-10 border-t border-line pt-7">
      <div className="rowh">
        <h2 id="h-req">Not in your library</h2>
        <span className="note">From {source} · these aren’t on the server yet</span>
      </div>
      {missing.pending && <p className="mt-3.5 text-sm text-neutral-400">Looking on {source}…</p>}
      {missing.error && <p className="mt-3.5 text-sm text-warn">{missing.error.message}</p>}
      {!missing.pending && !missing.error && missing.items.length === 0 && <p className="mt-3.5 text-sm text-neutral-400">Nothing else found.</p>}
      {missing.items.length > 0 && (
        <ul className="mt-3.5 grid grid-cols-[repeat(auto-fill,minmax(min(150px,42%),1fr))] gap-x-4 gap-y-5 md:grid-cols-[repeat(auto-fill,168px)]">
          {missing.items.map((r) => {
            const state = requestableState(r)
            return (
              <li key={`${r.mediaType}-${r.tmdbId}`} className="min-w-0">
                <span aria-hidden="true" className="block opacity-45">
                  <Poster path={r.posterPath} alt="" />
                </span>
                <span className="mt-2 block truncate text-sm font-medium text-neutral-300">{r.title}</span>
                <span className="block truncate text-[12.5px] text-neutral-400">{requestableLine(r)}</span>
                {state ? (
                  <span className={`pill mt-2.5 ${state.gold ? 'pill-gold' : ''}`}>{state.label}</span>
                ) : (
                  <button type="button" onClick={() => setOpen(r)} aria-label={`Request ${r.title}`} className="btn btn-sm mt-2.5 w-full max-md:min-h-11">
                    <PlusIcon size={14} />
                    Request
                  </button>
                )}
              </li>
            )
          })}
        </ul>
      )}
      <p className="mt-[18px] max-w-[68ch] text-[13px] text-neutral-400">
        A request goes to {source}. The title appears on Home when it arrives, and whoever asked is told.
      </p>
      {open && <RequestDialog mediaType={open.mediaType} tmdbId={open.tmdbId} onClose={() => setOpen(null)} />}
    </section>
  )
}
