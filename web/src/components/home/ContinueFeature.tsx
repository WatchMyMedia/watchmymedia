import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { movieQuery, seasonQuery } from '../../api/queries'
import type { components } from '../../api/schema'
import { continueBack, continueWords } from '../../lib/cardFacts'
import { leftAndEnds, percentWatched } from '../../lib/endTime'
import { img } from '../../lib/images'
import { Poster } from '../Poster'
import { PlayIcon } from '../icons'
import { useNow } from '../useNow'

type ContinueItem = components['schemas']['ContinueItem']

const playSearch = (item: ContinueItem) => ({ title: item.subtitle ? `${item.title} · ${item.subtitle}` : item.title, back: continueBack(item) })

/**
 * Home's lead: what this person is most likely to resume, large, with what is
 * left and when it would end, beside the rest of what they have in progress.
 */
export function ContinueFeature({ items }: { items: readonly ContinueItem[] }) {
  const [first, ...rest] = items
  if (!first) return null
  return (
    <section aria-labelledby="h-continue">
      <div className="rowh">
        <h2 id="h-continue">Continue watching</h2>
        <span className="note">{items.length} in progress</span>
      </div>
      <div className="mt-3.5 grid grid-cols-[repeat(auto-fit,minmax(min(520px,100%),1fr))] gap-x-7 gap-y-5">
        <Feature item={first} />
        {rest.length > 0 && (
          <ul className="flex flex-col justify-between gap-3">
            {rest.slice(0, 4).map((item) => (
              <li key={`${item.kind}-${item.fileId}`}>
                <Listed item={item} />
              </li>
            ))}
          </ul>
        )}
      </div>
      {rest.length > 4 && (
        <div className="scrollbar-hide -mx-1 mt-4 flex gap-4 overflow-x-auto px-1 pb-1.5 pt-1">
          {rest.slice(4).map((item) => (
            <div key={`${item.kind}-${item.fileId}`} className="w-[320px] shrink-0">
              <Listed item={item} />
            </div>
          ))}
        </div>
      )}
    </section>
  )
}

function Feature({ item }: { item: ContinueItem }) {
  const words = continueWords(item)
  const overview = useOverview(item)
  const pct = percentWatched(item.positionMs, item.durationMs)
  const now = useNow()
  const left = leftAndEnds(item.positionMs, item.durationMs, now)
  const picture = img(item.stillPath ?? item.backdropPath, 'w1280')
  const details =
    item.kind === 'movie' && item.movieId != null
      ? { to: '/movies/$movieId' as const, params: { movieId: String(item.movieId) }, label: 'Movie details' }
      : null
  return (
    <article aria-label={words.label} className="relative isolate overflow-hidden rounded-[14px] bg-surface-2 ring-1 ring-inset ring-white/[.07]">
      {picture && <img src={picture} alt="" className="absolute inset-0 -z-10 size-full object-cover" />}
      <span aria-hidden="true" className="absolute inset-0 -z-10 bg-[linear-gradient(90deg,rgba(15,15,16,.9)_0%,rgba(15,15,16,.6)_46%,rgba(15,15,16,.05)_80%)] max-md:bg-[linear-gradient(0deg,rgba(15,15,16,.95)_0%,rgba(15,15,16,.6)_55%,rgba(15,15,16,.1)_100%)]" />
      <div className="flex min-h-[300px] max-w-[540px] flex-col justify-end gap-2.5 p-5 md:min-h-[356px] md:p-7">
        <p className="eyebrow">{words.eyebrow}</p>
        <h3 className="text-[26px] font-bold leading-[1.1] tracking-[-.02em] md:text-[34px]">{words.heading}</h3>
        {overview && <p className="line-clamp-2 max-w-[46ch] text-sm text-neutral-300">{overview}</p>}
        {left && (
          <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
            <span className="meter min-w-[120px] flex-[0_1_200px] bg-white/20" role="img" aria-label={`${pct}% watched`}>
              <i style={{ width: `${pct}%` }} />
            </span>
            <span className="text-[13.5px] text-neutral-100">{left}</span>
          </div>
        )}
        <div className="mt-1.5 flex flex-wrap gap-2.5">
          <Link to="/play/$fileId" params={{ fileId: String(item.fileId) }} search={playSearch(item)} className="btn btn-primary btn-lg">
            <PlayIcon />
            Resume
          </Link>
          {details ? (
            <Link to={details.to} params={details.params} className="btn btn-clear btn-lg">
              {details.label}
            </Link>
          ) : (
            item.showId != null && (
              <Link to={continueBack(item)} className="btn btn-clear btn-lg">
                Episode details
              </Link>
            )
          )}
        </div>
      </div>
    </article>
  )
}

function Listed({ item }: { item: ContinueItem }) {
  const words = continueWords(item)
  const pct = percentWatched(item.positionMs, item.durationMs)
  return (
    <Link
      to="/play/$fileId"
      params={{ fileId: String(item.fileId) }}
      search={playSearch(item)}
      aria-label={`Resume ${words.label}${words.left ? `, ${words.left}` : ''}`}
      className="group flex min-h-20 items-center gap-3.5 rounded-lg"
    >
      <span className="relative block w-[142px] shrink-0">
        <Poster path={item.stillPath ?? item.backdropPath ?? item.posterPath} size="w300" alt="" ratio="aspect-video" className="rounded-lg ring-1 ring-inset ring-white/[.07] group-hover:ring-2 group-hover:ring-accent" />
        <span aria-hidden="true" className="absolute inset-x-0 bottom-0 h-1 overflow-hidden rounded-b-lg bg-black/60">
          <span className="block h-full bg-accent" style={{ width: `${pct}%` }} />
        </span>
      </span>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-[15px] font-medium group-hover:text-accent">{words.title}</span>
        <span className="block truncate text-[12.5px] text-neutral-400">{words.line}</span>
        {words.left && <span className="block truncate text-[12.5px] text-neutral-300">{words.left}</span>}
      </span>
    </Link>
  )
}

/** The featured item's synopsis, from its movie or its season, which the row's reply doesn't carry. */
function useOverview(item: ContinueItem): string | undefined {
  const movie = useQuery({ ...movieQuery(item.movieId ?? 0), enabled: item.kind === 'movie' && item.movieId != null })
  const season = useQuery({ ...seasonQuery(item.showId ?? 0, item.seasonNumber ?? 0), enabled: item.kind === 'episode' && item.showId != null && item.seasonNumber != null })
  if (item.kind === 'movie') return movie.data?.overview || undefined
  return season.data?.episodes?.find((e) => e.id === item.episodeId)?.overview || undefined
}
