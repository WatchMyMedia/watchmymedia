import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import { useQueries, useQuery } from '@tanstack/react-query'
import { itemsQuery, type ItemSort } from '../api/queries'
import { sortOptions } from '../lib/browseOptions'
import {
  DEFAULT_SIZE,
  NO_FILTERS,
  filtersActive,
  pictureChoices,
  posterMin,
  ratedChoices,
  serverFilters,
  showChips,
  startingChoices,
  type BrowseChoices,
  type BrowsePrefs,
  type ChipFilter,
  type PictureFilter,
} from '../lib/browsePrefs'
import type { ItemFilter } from '../lib/browseOptions'
import { BrowseAZRail, BrowseChips, BrowsePosterSize, BrowseSelect, BrowseViewToggle } from './BrowseControls'
import { BrowseList, LibraryCard } from './LibraryCard'
import { ChevronRightIcon } from './icons'
import type { BrowseItem } from './PosterCard'

const PAGE = 120

type Props = {
  libraryId: number
  type: 'movie' | 'tv'
  q?: string
  /** A row's See all: the sort and filters it opens the grid with, ahead of what was remembered. */
  initial?: { sort?: ItemSort; filter?: ItemFilter; genre?: string; decade?: number }
  prefs: BrowsePrefs
  save: (change: Partial<BrowsePrefs>) => void
  /** Counts for the Show chips, from the library's totals. */
  counts?: Partial<Record<ChipFilter, number>>
}

/**
 * A library's titles: the Show chips, Genre, Decade, Rated and More
 * (picture), Clear filters, Sort, Grid or List, the poster size, and the A–Z
 * rail at the side. The chip, the picture, the rating, the genre and the
 * decade combine. Every choice is remembered on this browser for the
 * library. Titles come a page at a time, Show more adding the next.
 */
export function BrowseGrid({ libraryId, type, q, initial = {}, prefs, save, counts = {} }: Props) {
  const [choices, setChoices] = useState<BrowseChoices>(() => startingChoices(initial, prefs))
  const [pages, setPages] = useState<number[]>([0])
  const [current, setCurrent] = useState<string>()
  const view = prefs.view ?? 'grid'
  const size = prefs.size ?? DEFAULT_SIZE
  const columns = prefs.columns ?? {}
  const listRef = useRef<HTMLUListElement | HTMLDivElement>(null)
  // The offset an A–Z jump scrolls to once its page has loaded.
  const jumpTo = useRef<number | null>(null)
  const { sort, genre, decade, rated } = choices
  const filters = serverFilters(choices)
  const results = useQueries({ queries: pages.map((offset) => itemsQuery(libraryId, { sort, filter: '', filters, contentRating: rated, genre, decade, q, limit: PAGE, offset })) })
  const first = results[0]
  const loaded = results.every((r) => r.data && !r.isPlaceholderData)
  const list = results.flatMap((r) => r.data?.items ?? [])
  const total = first.data?.total ?? 0
  const start = pages[0]
  const end = start + list.length

  const choose = (change: Partial<BrowseChoices>) => {
    const next = { ...choices, ...change }
    setChoices(next)
    setPages([0])
    setCurrent(undefined)
    save({ sort: next.sort, filter: next.filter, picture: next.picture, rated: next.rated, genre: next.genre, decade: next.decade })
  }

  // The server lists every letter in the whole filtered list, not just the pages loaded.
  const letters = useMemo(() => new Map((first.data?.letters ?? []).map((l) => [l.letter, l.offset])), [first.data])
  const available = useMemo(() => new Set(letters.keys()), [letters])

  const scrollTo = (at: number) => {
    const el = listRef.current?.querySelector(`[data-row="${at - pages[0]}"]`)
    el?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    ;(el?.querySelector('a') as HTMLElement | null)?.focus({ preventScroll: true })
  }

  const jump = (letter: string) => {
    const at = letters.get(letter)
    if (at === undefined) return
    setCurrent(letter)
    const page = Math.floor(at / PAGE) * PAGE
    if (at >= start && at < end && loaded) scrollTo(at)
    else {
      jumpTo.current = at
      setPages(page === pages[pages.length - 1] + PAGE ? [...pages, page] : [page])
    }
  }

  useEffect(() => {
    const at = jumpTo.current
    if (at === null || !loaded) return
    jumpTo.current = null
    if (at >= start && at < end) scrollTo(at)
    // scrollTo reads the list as drawn; it changes with the pages.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loaded, start, end])

  const facets = first.data?.facets
  const genreCounts = first.data?.genreCounts ?? {}
  const pictures = pictureChoices(type)
  const ratings = ratedChoices(facets?.contentRatings, first.data?.contentRatingCounts)
  const narrowed = filtersActive(choices)
  const sorts = sortOptions(type)
  const gridStyle = { '--pm': `${posterMin(size)}px`, '--pms': `${posterMin(size, true)}px` } as CSSProperties

  return (
    <section aria-label={type === 'tv' ? 'All shows' : 'All movies'}>
      <div className="flex flex-wrap items-center gap-x-2 gap-y-2.5">
        <div className="flex flex-wrap items-center gap-2">
          <BrowseChips
            label="Show"
            options={showChips(type).map((c) => ({ ...c, count: c.value ? counts[c.value] : undefined }))}
            value={choices.filter}
            onChange={(v) => choose({ filter: v })}
          />
          {facets && facets.genres.length > 0 && (
            <BrowseSelect
              label="Genre"
              value={genre}
              options={[{ value: '', label: 'Any' }, ...facets.genres.map((g) => ({ value: g, label: genreCounts[g] !== undefined ? `${g} (${genreCounts[g]})` : g }))]}
              onChange={(v) => choose({ genre: v })}
            />
          )}
          {type === 'movie' && facets && facets.decades.length > 0 && (
            <BrowseSelect
              label="Decade"
              value={String(decade)}
              options={[{ value: '0', label: 'Any' }, ...facets.decades.map((d) => ({ value: String(d), label: `${d}s` }))]}
              onChange={(v) => choose({ decade: Number(v) })}
            />
          )}
          {(ratings.length > 1 || rated) && (
            <BrowseSelect label="Rated" value={rated} options={ratings.some((r) => r.value === rated) ? ratings : [...ratings, { value: rated, label: rated }]} onChange={(v) => choose({ rated: v })} />
          )}
          <BrowseSelect
            label="More"
            name="Picture"
            value={choices.picture}
            options={[{ value: '' as PictureFilter, label: 'Any' }, ...pictures]}
            onChange={(v) => choose({ picture: v })}
          />
          {narrowed && (
            <button type="button" onClick={() => choose(NO_FILTERS)} className="btn btn-ghost btn-sm text-accent hover:text-neutral-100 max-md:min-h-10">
              Clear filters
            </button>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-2 md:ml-auto">
          <BrowseSelect label="Sort" value={sort} options={sorts} onChange={(v: ItemSort) => choose({ sort: v })} />
          <BrowseViewToggle value={view} onChange={(v) => save({ view: v })} />
          {view === 'grid' ? (
            <BrowsePosterSize value={size} onChange={(v) => save({ size: v })} />
          ) : (
            <span role="group" aria-label="Columns" className="inline-flex items-center gap-1.5 max-md:hidden">
              <span aria-hidden="true" className="text-[13px] text-neutral-400">
                Columns
              </span>
              <button type="button" aria-pressed={!!columns.quality} onClick={() => save({ columns: { ...columns, quality: !columns.quality } })} className="chip">
                Quality
              </button>
              <button type="button" aria-pressed={!!columns.size} onClick={() => save({ columns: { ...columns, size: !columns.size } })} className="chip">
                Size
              </button>
            </span>
          )}
        </div>
      </div>
      <p className="mt-3 text-[13px] text-neutral-400">
        Your filters, sort and view are remembered for {type === 'tv' ? 'TV Shows' : 'Movies'} on this browser.
      </p>

      <div className="mt-4 flex items-start gap-4">
        <div className="min-w-0 flex-1">
          {first.isPending && <p className="text-neutral-400">Loading…</p>}
          {first.isError && <p className="text-red-400">{first.error.message}</p>}
          {first.data && total === 0 && (
            <p className="text-neutral-400">
              {q ? `Nothing matches “${q}”.` : narrowed ? 'Nothing here matches these filters.' : 'Nothing here yet. Matched titles appear once metadata has been fetched.'}
              {narrowed && (
                <>
                  {' '}
                  <button type="button" onClick={() => choose(NO_FILTERS)} className="underline hover:text-neutral-100">
                    Clear filters
                  </button>
                </>
              )}
            </p>
          )}
          {start > 0 && (
            <div className="mb-6 flex justify-center">
              <button type="button" onClick={() => setPages([start - PAGE, ...pages])} className="btn">
                Show earlier
              </button>
            </div>
          )}
          {view === 'grid' ? (
            <ul
              ref={listRef as React.RefObject<HTMLUListElement>}
              style={gridStyle}
              className="grid grid-cols-[repeat(auto-fill,minmax(min(var(--pms),42%),1fr))] gap-x-3 gap-y-[18px] md:grid-cols-[repeat(auto-fill,minmax(min(var(--pm),42%),1fr))] md:gap-x-4 md:gap-y-[22px]"
            >
              {list.map((it, i) => (
                <li key={`${it.kind}-${it.id}`} data-row={i} className="min-w-0 scroll-mt-24">
                  <LibraryCard item={it} />
                </li>
              ))}
            </ul>
          ) : (
            <div ref={listRef as React.RefObject<HTMLDivElement>}>{list.length > 0 && <BrowseList items={list} type={type} columns={columns} />}</div>
          )}
          {total > 0 && (
            <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
              <span className="text-[13px] text-neutral-400">
                {start > 0 ? `${(start + 1).toLocaleString()}–${end.toLocaleString()}` : end.toLocaleString()} of {total.toLocaleString()}
              </span>
              {end < total && (
                <button type="button" disabled={!loaded} onClick={() => setPages([...pages, pages[pages.length - 1] + PAGE])} className="btn">
                  Show more
                </button>
              )}
            </div>
          )}
        </div>
        {sort === 'title' && total > 12 && <BrowseAZRail available={available} current={current} onJump={jump} />}
      </div>
    </section>
  )
}

/**
 * The Genres tab: each genre with its count and its titles in a row, and a
 * way to the whole library filtered by it.
 */
export function BrowseGenres({ libraryId, type, onGenre }: { libraryId: number; type: 'movie' | 'tv'; onGenre: (genre: string) => void }) {
  const items = useQuery(itemsQuery(libraryId, { sort: 'title', filter: '', limit: 1000, offset: 0 }))
  const groups = useMemo(() => {
    const m = new Map<string, BrowseItem[]>()
    for (const it of items.data?.items ?? []) {
      if (it.kind === 'unmatched') continue
      for (const g of it.genres.length ? it.genres : ['Other']) m.set(g, [...(m.get(g) ?? []), it])
    }
    return [...m.entries()].sort(([a], [b]) => a.localeCompare(b))
  }, [items.data])
  if (items.isPending) return <p className="text-neutral-400">Loading…</p>
  if (items.isError) return <p className="text-red-400">{items.error.message}</p>
  if (!groups.length) return <p className="text-neutral-400">No genres yet. They come with each title’s details once it is matched.</p>
  return (
    <div className="space-y-9">
      {groups.map(([genre, list]) => (
        <section key={genre} aria-label={genre}>
          <div className="rowh">
            <h2>{genre}</h2>
            <span className="note">
              {list.length.toLocaleString()} {type === 'tv' ? (list.length === 1 ? 'show' : 'shows') : list.length === 1 ? 'movie' : 'movies'}
            </span>
            {genre !== 'Other' && (
              <button type="button" onClick={() => onGenre(genre)} className="more inline-flex items-center gap-0.5">
                See all <ChevronRightIcon size={13} />
              </button>
            )}
          </div>
          <ul className="scrollbar-hide -mx-1 mt-3.5 flex gap-3 overflow-x-auto px-1 pb-1.5 pt-1 md:gap-4">
            {list.slice(0, 24).map((it) => (
              <li key={`${it.kind}-${it.id}`} className="w-[124px] shrink-0 md:w-[152px]">
                <LibraryCard item={it} />
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  )
}
