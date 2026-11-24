import { useCallback, useLayoutEffect, useMemo, useRef, useState, type FocusEvent } from 'react'
import { flushSync } from 'react-dom'
import { useQueries, useQuery, type UseQueryResult } from '@tanstack/react-query'
import type { components } from '../../../src/api/schema'
import { librariesQuery, rowsQuery, useLibraryByType } from '../../../src/api/queries'
import { libraryItemsQuery } from '../../../src/api/libraryItems'
import { sortOptions, type LibraryType } from '../../../src/lib/browseOptions'
import { drawable, isListRow, libraryRows, type ScreenRow } from '../../../src/lib/screenRows'
import { useBack, useFocus, useKeys, useLevel34, useMediaFor, useMediaUrls, useNav, useSession } from '../../app/hooks'
import { bareName } from '../search/crossSearch'
import { useMergedGrid, type MergedGrid } from './useMergedGrid'
import type { TaggedItem } from './oneCardHome'
import type { ScreenProps } from '../../app/routes'
import { Button } from '../../ui/Button'
import { Card } from '../../ui/Card'
import { Grid } from '../../ui/Grid'
import { Icon } from '../../ui/Icon'
import { Option, PanelLine, SidePanel } from '../../ui/Overlays'
import { Clock } from '../../ui/Clock'
import { ProblemScreen, SkeletonRow, SkeletonText } from '../../ui/States'
import { VScroll } from '../../ui/VScroll'
import { problemWords } from '../../ui/problemWords'
import { CardActions } from './CardActions'
import { useCardActions, useFocusOnArrival, useRowCards } from './browseHooks'
import { useEntryState } from './entryState'
import { ALPHABET, letterCounts, letterLabel, nextLetter, PAGE_SIZE, pagesAround, slots, stepRows, stripEntry, toStrip, type LetterOffset } from './gridPages'
import {
  chooseFilter, choicesFrom, countsLine, decadeLabel, DEFAULT_CHOICES, FILTER_ROW_LABEL, filterChoices, filterRows, filterRowValue, filterSummary, genreChips, gridGroup, gridLine,
  gridQuery, libraryNoun, narrowed, narrowedLine, nothingMatches, ratingLabel, rowFilter, sortLabel, type FilterRow, type GridChoices,
} from './libraryView'
import { keepChoices, keptChoices } from './keptChoices'
import { itemActions, seeAllRoute, type CardAction, type RowContext } from './rowCards'
import { Glyph, RowView } from './RowView'
import { useWatchlistsOn } from '../watchlists/useWatchlistsOn'
import './browse.css'

type Library = components['schemas']['Library']
type BrowseItem = components['schemas']['BrowseItem']
type ItemList = components['schemas']['ItemList']

/** Posters across the grid. */
const COLUMNS = 6

/**
 * A page of the grid for these choices. From API level 34 the filters
 * combine and Rated applies; below it the server is asked as it always was,
 * with one filter.
 */
const gridItems = (libraryId: number, c: GridChoices, level34: boolean, offset: number) =>
  libraryItemsQuery(libraryId, { ...gridQuery(c, level34), limit: PAGE_SIZE, offset })
const pageData = (results: UseQueryResult<ItemList>[]) => results.map((r) => r.data)

/** One of the library's rows, its See all sorting the grid below (or opening a list's page). */
function LibraryRow({ row, base }: { row: ScreenRow; base: Omit<RowContext, 'now'> }) {
  const { cards } = useRowCards(row, base)
  return <RowView row={row} cards={cards} listings={false}/>
}

/** The panel open over the grid. */
type Picker = 'filter' | 'sort'

/** Filter and Sort, each a button that opens its panel, with what each is set to; Clear filters while the grid is narrowed. */
function GridButtons({ type, choices, onPick, onClear }: { type: LibraryType; choices: GridChoices; onPick(p: Picker): void; onClear(): void }) {
  // ▶ from the last button stops there, rather than dropping into the grid below.
  // ▲ goes to Try again beside the count when a server didn't answer, before the rows above.
  const focus = useFocus()
  useKeys((input) => {
    const active = document.activeElement as HTMLElement | null
    if (input.action === 'up' && active?.closest('.library-buttons')) return focus.focusId('library/retry-servers')
    return input.action === 'right' && !!active?.closest('.library-buttons') && !active.nextElementSibling
  })
  return <div className="library-buttons">
    <button type="button" data-focus="library/filter" className="tv-btn library-btn" onClick={() => onPick('filter')}>
      <Icon name="filter"/>Filter<span className="library-btn__value">{filterSummary(choices)}</span>
    </button>
    <button type="button" data-focus="library/sort" className="tv-btn library-btn" onClick={() => onPick('sort')}>
      <Icon name="sort"/>Sort<span className="library-btn__value">{sortLabel(type, choices.sort)}</span>
    </button>
    {narrowed(choices) && <Button id="library/clear" onPress={onClear}>Clear filters</Button>}
  </div>
}

/**
 * Filter: a row for each way to narrow the grid, with what it is set to;
 * OK opens its choices in the same panel, and choosing one changes the grid
 * at once and comes back to the rows. Return goes up a level, then closes.
 */
function FilterPanel({ type, choices, facets, line, kept, onChange, onClose }: {
  type: LibraryType; choices: GridChoices; facets?: Pick<ItemList, 'facets' | 'genreCounts' | 'contentRatingCounts'>; line?: string; kept: boolean; onChange(c: GridChoices): void; onClose(): void
}) {
  const level34 = useLevel34()
  const [page, setPage] = useState<FilterRow>()
  const [from, setFrom] = useState<FilterRow>()
  const genres = genreChips(facets?.facets.genres ?? [], facets?.genreCounts, choices.genre)
  const decades = facets?.facets.decades ?? []
  // The library's ratings, those the other choices leave titles for (the chosen one stays, so it can be cleared).
  const ratings = genreChips(facets?.facets.contentRatings ?? [], facets?.contentRatingCounts, choices.rating)
  const rows = filterRows(type, { genres: genres.length > 0, decades: decades.length > 0, ratings: ratings.length > 0, level34 })
  const back = (row: FilterRow) => { setFrom(row); setPage(undefined) }
  if (page) {
    const pick = (c: GridChoices) => { onChange(c); back(page) }
    const options = page === 'genre' ? [{ value: '', label: 'Any' }, ...genres.map((g) => ({ value: g, label: g }))]
      : page === 'decade' ? [{ value: '0', label: 'Any' }, ...decades.map((d) => ({ value: String(d), label: decadeLabel(d) }))]
        : page === 'rating' ? [{ value: '', label: 'Any' }, ...ratings.map((r) => ({ value: r, label: ratingLabel(r) }))]
          : filterChoices(type, page, level34)
    const chosen = page === 'genre' ? choices.genre : page === 'decade' ? String(choices.decade) : page === 'rating' ? choices.rating : rowFilter(page, choices) ?? ''
    const choose = (value: string): GridChoices => {
      switch (page) {
        case 'genre': return { ...choices, genre: value }
        case 'decade': return { ...choices, decade: Number(value) }
        case 'rating': return { ...choices, rating: value }
        default: return chooseFilter(choices, page, filterChoices(type, page, level34).find((o) => o.value === value)?.value ?? '', level34)
      }
    }
    return <SidePanel key={page} title={FILTER_ROW_LABEL[page]} sub={line} onClose={() => back(page)}>
      <VScroll>
        {options.map((o) => <Option key={o.value || 'any'} id={`filter/${page}/${o.value || 'any'}`} label={o.label} checked={o.value === chosen} onPress={() => pick(choose(o.value))}/>)}
      </VScroll>
    </SidePanel>
  }
  const noun = type === 'tv' ? 'TV Shows' : 'Movies'
  return <SidePanel key="rows" title="Filter" sub={line} onClose={onClose} foot={kept ? <p className="tv-faint">{noun} opens this way next time, on this TV.</p> : undefined}>
    {rows.map((r) => <Option key={r} id={`filter/${r}`} label={FILTER_ROW_LABEL[r]} detail={r === 'picture' ? (type === 'tv' ? '4K' : '4K, HDR') : undefined} autoFocus={r === (from ?? rows[0])}
      end={<span className={filterRowValue(r, choices) === 'Any' ? undefined : 'library-chosen'}>{filterRowValue(r, choices)}</span>} next onPress={() => setPage(r)}/>)}
    {narrowed(choices) && <><PanelLine/><div className="library-panel-clear"><Button id="filter/clear" onPress={() => onChange({ ...DEFAULT_CHOICES, sort: choices.sort })}>Clear filters</Button></div></>}
  </SidePanel>
}

/** The A–Z strip's state: the letter focused on it, and the title focus came from (Return goes back to it). */
type Strip = { letter: string; from: number }

/**
 * The grid: the whole result as slots, the pages near focus asked for
 * (gridPages.ts), so a long library costs no more than a short one. Sorted
 * by title, the A–Z strip sits at its right edge: ▶ from the last column
 * enters it at the focused title's letter, ▲ ▼ move between letters that
 * have titles (the grid follows), OK or ◀ lands on the letter's first title
 * and Return goes back to the card. CH ▲ ▼ move two rows.
 */
function GridBody({ library, type, choices, first, focusFirst, onFocused, autoFocus }: {
  library: Library; type: LibraryType; choices: GridChoices; first: ItemList; focusFirst: boolean; onFocused(): void; autoFocus: boolean
}) {
  const media = useMediaUrls()
  const focus = useFocus()
  const { run } = useCardActions()
  const menu = useWatchlistsOn()
  const group = gridGroup(library.id, choices)
  const total = first.total
  const letters = useMemo<readonly LetterOffset[]>(() => (choices.sort === 'title' ? first.letters ?? [] : []), [choices.sort, first.letters])
  const counts = useMemo(() => letterCounts(letters, total), [letters, total])
  const byTitle = letters.length > 0
  const [view, setView] = useState(0)
  const [reveal, setReveal] = useState<{ index: number }>()
  const [strip, setStrip] = useState<Strip>()

  const wanted = pagesAround(view, total).filter((p) => p > 0)
  const level34 = useLevel34()
  const others = useQueries({ queries: wanted.map((p) => ({ ...gridItems(library.id, choices, level34, p * PAGE_SIZE), placeholderData: undefined })), combine: pageData })
  const wantedKey = wanted.join(',')
  const items = useMemo(() => {
    const pages = new Map<number, readonly BrowseItem[]>([[0, first.items]])
    wantedKey.split(',').filter(Boolean).forEach((p, k) => { const data = others[k]; if (data) pages.set(Number(p), data.items) })
    return slots(total, pages)
  }, [first.items, others, total, wantedKey])

  /** Draws the grid around `index`, then puts focus there. */
  const land = useCallback((index: number) => {
    flushSync(() => { setReveal({ index }); setView(index); setStrip(undefined) })
    focus.focusId(`${group}/${index}`)
  }, [focus, group])

  // A row's See all above: the grid's first card, once its first page is in.
  useLayoutEffect(() => {
    if (!focusFirst) return
    focus.focusId(`${group}/0`)
    onFocused()
  }, [focusFirst, focus, group, onFocused])

  const offsetOf = (letter: string) => letters.find((l) => l.letter === letter)?.offset
  const indexOf = (el: Element | null) => {
    const cell = el?.closest('[data-index]')
    return cell && el?.closest(`[data-group="${CSS.escape(group)}"]`) ? Number(cell.getAttribute('data-index')) : -1
  }
  useKeys((input) => {
    const active = document.activeElement
    if (strip && active?.closest('[data-az]')) {
      switch (input.action) {
        case 'up': case 'down': {
          const next = nextLetter(strip.letter, counts, input.action === 'up' ? -1 : 1)
          const at = next ? offsetOf(next) : undefined
          if (next && at !== undefined) {
            flushSync(() => { setStrip({ ...strip, letter: next }); setReveal({ index: at }); setView(at) })
            focus.focusId(`az/${next}`)
          }
          return true
        }
        case 'left': case 'ok': {
          const at = offsetOf(strip.letter)
          if (at !== undefined) land(at)
          return true
        }
        case 'right': return true
        default: return false
      }
    }
    const i = indexOf(active)
    if (i < 0) return false
    if (input.action === 'channelUp' || input.action === 'channelDown') {
      land(stepRows(i, total, input.action === 'channelUp' ? -2 : 2))
      return true
    }
    if (input.action === 'right' && byTitle && toStrip(i, total)) {
      const letter = stripEntry(letters, i)
      if (!letter) return false
      flushSync(() => setStrip({ letter, from: i }))
      focus.focusId(`az/${letter}`)
      return true
    }
    return false
  })
  useBack(() => {
    if (!strip) return false
    land(strip.from)
    return true
  }, !!strip)

  const onFocus = (e: FocusEvent) => {
    const target = e.target as HTMLElement
    const i = indexOf(target)
    if (i >= 0 && i !== view) setView(i)
    // Onto the strip some other way (a pointer, a move from Sort): it remembers the title focus was on.
    const letter = target.closest('[data-az]') && target.dataset.focus?.slice(3)
    if (letter && !strip) setStrip({ letter, from: view })
  }
  const landing = strip ? offsetOf(strip.letter) : undefined
  const noun = libraryNoun(type)
  const rowCount = Math.max(1, Math.ceil(total / COLUMNS))
  const atRow = Math.floor(Math.max(0, view) / COLUMNS)
  return <div className="library-grid__body" onFocus={onFocus} data-autofocus={autoFocus || undefined}>
    <Grid key={group} id={group} items={items} itemKey={(_, i) => String(i)} variant="poster" reveal={reveal}
      renderItem={(item, id, i) => {
        const actions = item ? itemActions(item, menu) : {}
        return <div className={`browse-cell${item ? '' : ' browse-slot'}${landing === i ? ' browse-land' : ''}`}>
          <Card id={id} variant="poster" title={item?.title ?? ''} subtitle={item ? gridLine(item) : undefined} image={item && media.imageUrl(item.posterPath, 'w342')}
            progress={item?.progress} badges={item?.isNew ? [{ label: 'New', tone: 'gold' }] : undefined} done={!!item?.watched && !item.progress}
            label={item ? [item.title, gridLine(item), item.watched && 'watched'].filter(Boolean).join(', ') : 'Loading'}
            onPress={actions.press && (() => run(actions.press))} onHold={actions.hold && (() => run(actions.hold))}/>
        </div>
      }}/>
    {byTitle && <nav className={`library-strip${strip ? ' library-strip--on' : ''}`} aria-label="Jump to a letter" data-az>
      {ALPHABET.map((l) => {
        const n = counts.get(l) ?? 0
        const current = (strip?.letter ?? stripEntry(letters, view)) === l
        return <button key={l} type="button" data-focus={`az/${l}`} className={`library-az${current ? ' library-az--current' : ''}`} aria-disabled={n === 0 || undefined} data-skip={n === 0 || undefined}
          aria-label={letterLabel(l, n, noun)} onClick={() => { const at = offsetOf(l); if (n && at !== undefined) land(at) }}>{l}</button>
      })}
    </nav>}
    {strip && <div className="library-bubble" aria-hidden="true" style={{ top: `calc(${ALPHABET.indexOf(strip.letter)} * 1.65rem + .825rem - 3.5rem)` }}>
      <b>{strip.letter}</b><span>{`${counts.get(strip.letter) ?? 0} ${(counts.get(strip.letter) ?? 0) === 1 ? noun : `${noun}s`}`}</span>
    </div>}
    {!byTitle && rowCount > 2 && <div className="library-pos" role="img" aria-label={`Row ${atRow + 1} of ${rowCount}`}>
      <i style={{ height: `${Math.max(6, 200 / rowCount)}%`, top: `${(Math.min(atRow, rowCount - 2) / rowCount) * 100}%` }}/>
    </div>}
  </div>
}

/**
 * The grid from every server (one card across servers): the titles merged
 * a page at a time, each card's picture and page from its own server, a
 * title only another server has saying "On Cabin" and opening in place.
 * There is no A–Z strip: the servers' letters can't be told apart in one
 * order without every page; the position bar stays.
 */
function MergedGridBody({ library, choices, grid, focusFirst, onFocused, autoFocus }: {
  library: Library; choices: GridChoices; grid: MergedGrid; focusFirst: boolean; onFocused(): void; autoFocus: boolean
}) {
  const mediaFor = useMediaFor()
  const focus = useFocus()
  const { run } = useCardActions()
  const menu = useWatchlistsOn()
  const group = `${gridGroup(library.id, choices)}/all-servers`
  const [view, setView] = useState(0)
  useLayoutEffect(() => {
    if (!focusFirst) return
    focus.focusId(`${group}/${grid.items[0] ? cellKey(grid.items[0]) : ''}`)
    onFocused()
  }, [focusFirst, focus, group, grid.items, onFocused])
  const onFocus = (e: FocusEvent) => {
    const i = Number((e.target as HTMLElement).closest?.('[data-index]')?.getAttribute('data-index') ?? -1)
    if (i >= 0 && i !== view) setView(i)
  }
  const rowCount = Math.max(1, Math.ceil(grid.items.length / COLUMNS))
  const atRow = Math.floor(Math.max(0, view) / COLUMNS)
  return <div className="library-grid__body" onFocus={onFocus} data-autofocus={autoFocus || undefined}>
    <Grid id={group} items={grid.items} itemKey={cellKey} variant="poster" hasMore={grid.hasMore} onLoadMore={grid.loadMore}
      renderItem={(item, id) => {
        const tag = item.oneCard
        const own = itemActions(item, !tag && menu)
        const press = own.press
        const open = press?.kind === 'open' && tag ? { kind: 'open' as const, route: { ...press.route, server: tag.server.id } as typeof press.route } : undefined
        const actions = tag ? { press: open ?? own.press, hold: open ?? own.press } : own
        const line = tag ? [`On ${bareName(tag.server.name)}`, item.year ? String(item.year) : ''].filter(Boolean).join(' · ') : gridLine(item)
        return <div className="browse-cell">
          <Card id={id} variant="poster" title={item.title} subtitle={line} image={mediaFor(tag?.server.id).imageUrl(item.posterPath, 'w342')}
            progress={item.progress} badges={item.isNew ? [{ label: 'New', tone: 'gold' }] : undefined} done={!!item.watched && !item.progress}
            label={[item.title, line, item.watched && 'watched'].filter(Boolean).join(', ')}
            onPress={actions.press && (() => run(actions.press))} onHold={actions.hold && (() => run(actions.hold))}/>
        </div>
      }}/>
    {rowCount > 2 && <div className="library-pos" role="img" aria-label={`Row ${atRow + 1} of ${rowCount}${grid.hasMore ? ' so far' : ''}`}>
      <i style={{ height: `${Math.max(6, 200 / rowCount)}%`, top: `${(Math.min(atRow, rowCount - 2) / rowCount) * 100}%` }}/>
    </div>}
  </div>
}

/** A merged card's key: its server's id with its own, as ids are each server's own. */
const cellKey = (item: TaggedItem) => (item.oneCard ? `${item.oneCard.server.id}:${item.kind}${item.id}` : `${item.kind}${item.id}`)

/**
 * All movies or All shows: the count, Filter and Sort, and the grid.
 * Changing a choice leaves focus on its button, and the grid starts again
 * at its first title. Alone (no rows above), the library's name heads it.
 */
function GridSection({ library, type, choices, onChange, alone, lists, autoFocus, focusFirst, onFocused, kept }: {
  library: Library; type: LibraryType; choices: GridChoices; onChange(c: GridChoices): void; alone: boolean; lists: number; autoFocus: boolean; focusFirst: boolean; onFocused(): void; kept: boolean
}) {
  const level34 = useLevel34()
  const page = useQuery(gridItems(library.id, choices, level34, 0))
  const [picker, setPicker] = useState<Picker>()
  const title = type === 'tv' ? 'TV Shows' : 'Movies'
  const noun = type === 'tv' ? 'shows' : 'movies'
  const data = page.data
  // With other servers at API level 34, the grid is every server's: merged a page at a time, the counts "about" until known.
  const merged = useMergedGrid(library, type, choices, data && !page.isPlaceholderData ? data : undefined)
  const all = data?.totals?.items ?? data?.total
  const line = merged ? merged.counts.line : data && narrowed(choices) && all !== undefined ? narrowedLine(type, data.total, all) : countsLine(type, { totals: data?.totals, total: data?.total, lists })
  // Try again goes while the server is asked again, so focus moves to Filter rather than nowhere.
  const focus = useFocus()
  const retry = merged && merged.failed.length > 0 && <Button id="library/retry-servers" onPress={() => { merged.retry(); focus.focusId('library/filter') }}>Try again</Button>
  const clear = () => onChange({ ...DEFAULT_CHOICES, sort: choices.sort })
  return <section className={`library-grid${alone ? ' library-grid--alone' : ''}${choices.sort === 'title' && data?.letters?.length ? ' library-grid--az' : ''}`}
    data-vitem aria-label={`All ${noun}`}>
    {alone
      ? <header className="library-head"><h1 className="library-title">{title}</h1><div className="library-counts-row">{merged?.counts.about && <span className="tv-spinner" aria-hidden="true"/>}<p className="library-counts" role={merged ? 'status' : undefined}>{line || ' '}</p>{retry}</div></header>
      : <div className="library-grid__head"><h2 className="library-grid__title">All {noun}{data && !page.isPlaceholderData && <small>{line}</small>}</h2>{retry}</div>}
    <GridButtons type={type} choices={choices} onPick={setPicker} onClear={clear}/>
    {page.isPending || page.isPlaceholderData ? <div className="library-grid__body"><SkeletonRow variant="poster" title={false}/></div>
      : page.isError ? <GridProblem error={page.error} onRetry={() => void page.refetch()}/>
        : merged && merged.items.length > 0 ? <MergedGridBody key={`${gridGroup(library.id, choices)}/all`} library={library} choices={choices} grid={merged} focusFirst={focusFirst} onFocused={onFocused} autoFocus={autoFocus}/>
        : data && data.total === 0 && !merged?.hasMore ? <div className="library-empty">
          <h2 className="library-empty__title">{narrowed(choices) ? 'Nothing matches' : `No ${noun} yet`}</h2>
          <p className="library-empty__body">{narrowed(choices) ? nothingMatches(type, choices) : 'Add them to the library on the server’s web page and scan it; they appear here.'}</p>
          {narrowed(choices) && <div className="tv-actions">
            <Button id="library/clear-all" variant="primary" autoFocus onPress={clear}>Clear filters</Button>
            <Button id="library/change" onPress={() => setPicker('filter')}>Change filters</Button>
          </div>}
        </div>
          : data && <GridBody key={gridGroup(library.id, choices)} library={library} type={type} choices={choices} first={data} focusFirst={focusFirst} onFocused={onFocused} autoFocus={autoFocus}/>}
    {picker === 'filter' && <FilterPanel type={type} choices={choices} facets={data} line={line} kept={kept} onChange={onChange} onClose={() => setPicker(undefined)}/>}
    {picker === 'sort' && <SidePanel title="Sort" sub={kept ? `${title} opens this way next time, on this TV.` : undefined} onClose={() => setPicker(undefined)}>
      {sortOptions(type).map((o) => <Option key={o.value} id={`sort/${o.value}`} label={o.label} checked={o.value === choices.sort} onPress={() => { setPicker(undefined); onChange({ ...choices, sort: o.value }) }}/>)}
    </SidePanel>}
  </section>
}

function GridProblem({ error, onRetry }: { error: unknown; onRetry(): void }) {
  const words = problemWords(error)
  return <div className="library-empty">
    <h2 className="library-empty__title">{`Couldn’t load the grid. ${words.title}`}</h2>
    <p className="library-empty__body">{words.body}</p>
    <Button id="library/retry" variant="primary" onPress={onRetry}>Try again</Button>
  </div>
}

/** Focus for a library that drew after a slow load (useFocusOnArrival), from inside what it draws. */
function FocusOnArrival() {
  useFocusOnArrival()
  return null
}

function LibrarySkeleton() {
  return <div className="library" aria-busy="true">
    <span className="tv-sr" role="status">Loading</span>
    <div className="library-head" aria-hidden="true"><div className="library-head__text"><SkeletonText width="16rem"/><SkeletonText width="22rem"/></div></div>
    <div className="library-rows" aria-hidden="true"><SkeletonRow variant="poster"/><SkeletonRow variant="wide"/></div>
  </div>
}

/**
 * The library's rows (its Overview, arranged), Edit rows, then its grid,
 * in one vertical scroll. The grid's
 * choices come back with the screen; `initial` is a See all's.
 */
function LibraryView({ library, type, initial, toGrid }: { library: Library; type: LibraryType; initial?: GridChoices; toGrid: boolean }) {
  const nav = useNav()
  const session = useSession()
  const rows = useQuery(rowsQuery(library.id))
  const libraries = useQuery(librariesQuery)
  // The library opens the way it was left on this TV; a See all opens the row's way and keeps nothing.
  const kept = !initial
  const [choices, setStoredChoices] = useEntryState<GridChoices>('library-choices', () => initial ?? keptChoices(session.server.id, library.id))
  const setChoices = useCallback((c: GridChoices) => { setStoredChoices(c); if (kept) keepChoices(session.server.id, library.id, c) }, [setStoredChoices, kept, session.server.id, library.id])
  // The grid's first page, so first focus has somewhere to go; a new choice keeps the last page until its own is in.
  const level34 = useLevel34()
  const first = useQuery(gridItems(library.id, choices, level34, 0))
  const [gridFocus, setGridFocus] = useState(false)
  const root = useRef<HTMLDivElement>(null)
  const refs = libraries.data?.items
  const menu = useWatchlistsOn()
  const base = useMemo<Omit<RowContext, 'now'>>(() => ({
    libraries: refs ?? [], channels: [], listings: false, menu, continueMenu: level34, screenType: type,
    // A list's page, or the grid below sorted the row's way; a link to another library has none here.
    seeAll: (target, row): CardAction | undefined => {
      const route = seeAllRoute(target, row, refs ?? [])
      if (route?.name === 'list') return { kind: 'open', route }
      if (target.kind !== 'library' || route?.name !== 'seeAll' || route.libraryId !== library.id) return undefined
      const { sort, filter, genre, decade } = target.search
      return { kind: 'grid', sort, filter, genre, decade }
    },
  }), [refs, library.id, menu, level34, type])
  const drawn = useMemo(() => (rows.data ? libraryRows(rows.data, type).filter(drawable) : []), [rows.data, type])
  const onGrid = useCallback((a: Extract<CardAction, { kind: 'grid' }>) => { setChoices(choicesFrom(a)); setGridFocus(true) }, [setChoices])
  const onFocused = useCallback(() => setGridFocus(false), [])

  if (rows.isPending || libraries.isPending || first.isPending) return <LibrarySkeleton/>
  const lists = drawn.filter(isListRow).length
  const alone = drawn.length === 0
  const title = type === 'tv' ? 'TV Shows' : 'Movies'
  return <CardActions onGrid={onGrid}>
    <div className="library" ref={root}>
      <VScroll pad={48}>
        {!alone && <header className="library-head" data-vitem>
          <div className="library-head__text"><h1 className="library-title">{title}</h1>
            <p className="library-counts">{countsLine(type, { totals: first.data?.totals, total: first.data?.total, lists }) || ' '}</p></div>
        </header>}
        {!alone && <div className="library-rows">
          {drawn.map((row, i) => <div key={row.id} data-vitem data-autofocus={(i === 0 && !toGrid) || undefined}><LibraryRow row={row} base={base}/></div>)}
        </div>}
        {!alone && <div className="library-edit" data-vitem>
          <span className="browse-card" data-hint="Edit rows">
            <Button id="library/edit" onPress={() => nav.push({ name: 'settings', group: 'homeRows', rowsScreen: type })}><Glyph name="sliders"/>Edit rows</Button>
          </span>
        </div>}
        <GridSection library={library} type={type} choices={choices} onChange={setChoices} alone={alone} lists={lists} autoFocus={alone || toGrid} focusFirst={gridFocus} onFocused={onFocused} kept={kept}/>
      </VScroll>
      <FocusOnArrival/>
    </div>
    <Clock/>
  </CardActions>
}

function NoLibrary({ type }: { type: LibraryType }) {
  const nav = useNav()
  return <ProblemScreen icon="setup" title={`This server has no ${type === 'tv' ? 'TV' : 'movie'} library yet`} actions={[{ id: 'library/home', label: 'Back to Home', onPress: () => nav.back() }]}>
    Add one on the server’s web page, and scan it; it appears here.
  </ProblemScreen>
}

function LibraryProblem({ type, error, onRetry }: { type: LibraryType; error: unknown; onRetry(): void }) {
  const words = problemWords(error)
  return <ProblemScreen icon="offline" title={`Couldn’t load ${type === 'tv' ? 'TV Shows' : 'Movies'}. ${words.title}`} actions={[{ id: 'library/retry', label: 'Try again', onPress: onRetry }]}>{words.body}</ProblemScreen>
}

/** TV Shows and Movies (route `library`): the first library of the type. */
export function LibraryScreen({ route }: ScreenProps<'library'>) {
  const { library, isPending, isError, error, refetch } = useLibraryByType(route.type)
  if (isPending) return <LibrarySkeleton/>
  if (isError) return <LibraryProblem type={route.type} error={error} onRetry={() => void refetch()}/>
  if (!library) return <NoLibrary type={route.type}/>
  return <LibraryView key={library.id} library={library} type={route.type} toGrid={false}/>
}

/**
 * A row's See all (route `seeAll`): its library's screen
 * sorted and filtered the row's way, focus on the grid's first card.
 */
export function SeeAllScreen({ route }: ScreenProps<'seeAll'>) {
  const libraries = useQuery(librariesQuery)
  const library = libraries.data?.items.find((l) => l.id === route.libraryId)
  const type: LibraryType = library?.type === 'tv' ? 'tv' : 'movie'
  const initial = useMemo(() => choicesFrom(route), [route])
  if (libraries.isPending) return <LibrarySkeleton/>
  if (libraries.isError) return <LibraryProblem type={type} error={libraries.error} onRetry={() => void libraries.refetch()}/>
  if (!library) return <NoLibrary type={type}/>
  return <LibraryView key={library.id} library={library} type={type} initial={initial} toGrid/>
}

