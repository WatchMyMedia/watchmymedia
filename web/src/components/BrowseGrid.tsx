import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { itemsQuery, type ItemFilter, type ItemSort } from '../api/queries'
import { filterOptions, sortOptions } from '../lib/browseOptions'
import { AZBar, Chips, Select } from './Controls'
import { Pager } from './Pager'
import { PosterCard, type BrowseItem } from './PosterCard'

const PAGE = 200

type Props = {
  libraryId: number
  type: 'movie' | 'tv'
  q?: string
  groupByGenre?: boolean
  /** Where the grid starts: a row's See all (PLAN-ROWS.md §6.1). */
  initial?: { sort?: ItemSort; filter?: ItemFilter; genre?: string; decade?: number }
}

/** "All shows" / "All movies" grid with filter chips, sort select and A–Z jump bar. */
export function BrowseGrid({ libraryId, type, q, groupByGenre = false, initial }: Props) {
  const [sort, setSort] = useState<ItemSort>(initial?.sort ?? 'title')
  const [filter, setFilter] = useState<ItemFilter>(initial?.filter ?? '')
  const [genre, setGenre] = useState(initial?.genre ?? '')
  const [decade, setDecade] = useState(initial?.decade ?? 0)
  const [offset, setOffset] = useState(0)
  const items = useQuery(itemsQuery(libraryId, { sort, filter, genre, decade, q, limit: PAGE, offset }))
  const gridRef = useRef<HTMLUListElement>(null)
  // Offset of the item an A–Z jump scrolls to once its page has loaded.
  const jumpTo = useRef<number | null>(null)

  // The server lists every letter in the whole filtered list, not just this page.
  const letters = useMemo(() => new Map((items.data?.letters ?? []).map((l) => [l.letter, l.offset])), [items.data])
  const available = useMemo(() => new Set(letters.keys()), [letters])

  const filters = filterOptions(type)
  const sorts = sortOptions(type)

  const scrollTo = (at: number, page: number) => {
    gridRef.current?.children[at - page]?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  const jump = (letter: string) => {
    const at = letters.get(letter)
    if (at === undefined) return
    const page = Math.floor(at / PAGE) * PAGE
    if (page === offset && !items.isPlaceholderData) {
      scrollTo(at, page)
    } else {
      jumpTo.current = at
      setOffset(page)
    }
  }

  useEffect(() => {
    const at = jumpTo.current
    if (at === null || !items.data || items.isPlaceholderData) return
    jumpTo.current = null
    const page = items.data.offset
    if (at >= page && at < page + PAGE) scrollTo(at, page)
  }, [items.data, items.isPlaceholderData])

  const facets = items.data?.facets
  const list = items.data?.items ?? []

  return (
    <section className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <Chips
          options={filters}
          value={filter}
          onChange={(v) => {
            setFilter(v)
            setOffset(0)
          }}
        />
        {facets && facets.genres.length > 0 && (
          <Select
            label="Genre"
            options={[{ value: '', label: 'Any' }, ...facets.genres.map((g) => ({ value: g, label: g }))]}
            value={genre}
            onChange={(v) => {
              setGenre(v)
              setOffset(0)
            }}
          />
        )}
        {type === 'movie' && facets && facets.decades.length > 0 && (
          <Select
            label="Decade"
            options={[{ value: '0', label: 'Any' }, ...facets.decades.map((d) => ({ value: String(d), label: `${d}s` }))]}
            value={String(decade)}
            onChange={(v) => {
              setDecade(Number(v))
              setOffset(0)
            }}
          />
        )}
        <div className="ml-auto">
          <Select label="Sort" options={sorts} value={sort} onChange={setSort} />
        </div>
      </div>
      {sort === 'title' && list.length > 12 && <AZBar available={available} onJump={jump} />}

      {items.isPending && <p className="text-neutral-400">Loading…</p>}
      {items.isError && <p className="text-red-400">{items.error.message}</p>}
      {items.data && list.length === 0 && (
        <p className="text-neutral-400">{q ? `Nothing matches “${q}”.` : 'Nothing here yet. Matched titles appear once metadata has been fetched.'}</p>
      )}
      {groupByGenre ? (
        <GenreGroups items={list} />
      ) : (
        <ul ref={gridRef} className="grid grid-cols-3 gap-4 md:grid-cols-5 xl:grid-cols-8">
          {list.map((it) => (
            <li key={`${it.kind}-${it.id}`} className="scroll-mt-24">
              <PosterCard item={it} showCount={type === 'tv'} />
            </li>
          ))}
        </ul>
      )}
      {items.data && <Pager offset={offset} limit={PAGE} total={items.data.total} onChange={setOffset} />}
    </section>
  )
}

function GenreGroups({ items }: { items: BrowseItem[] }) {
  const groups = useMemo(() => {
    const m = new Map<string, BrowseItem[]>()
    for (const it of items) {
      if (it.kind === 'unmatched') continue // they have the Unmatched view
      for (const g of it.genres.length ? it.genres : ['Other']) {
        m.set(g, [...(m.get(g) ?? []), it])
      }
    }
    return [...m.entries()].sort(([a], [b]) => a.localeCompare(b))
  }, [items])
  return (
    <div className="space-y-8">
      {groups.map(([genre, list]) => (
        <section key={genre} className="space-y-3">
          <h3 className="text-base font-semibold">
            {genre} <span className="text-sm font-normal text-neutral-500">{list.length}</span>
          </h3>
          <ul className="grid grid-cols-3 gap-4 md:grid-cols-5 xl:grid-cols-8">
            {list.map((it) => (
              <li key={`${it.kind}-${it.id}`}>
                <PosterCard item={it} />
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  )
}
