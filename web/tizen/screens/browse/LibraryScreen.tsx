import { useCallback, useLayoutEffect, useMemo, useRef, useState, type FocusEvent } from 'react'
import { flushSync } from 'react-dom'
import { useQueries, useQuery, type UseQueryResult } from '@tanstack/react-query'
import type { components } from '../../../src/api/schema'
import { itemsQuery, librariesQuery, rowsQuery, useLibraryByType } from '../../../src/api/queries'
import { filterOptions, sortOptions, type LibraryType } from '../../../src/lib/browseOptions'
import { drawable, isListRow, libraryRows, type ScreenRow } from '../../../src/lib/screenRows'
import { useBack, useFocus, useKeys, useMediaUrls, useNav } from '../../app/hooks'
import type { ScreenProps } from '../../app/routes'
import { Button } from '../../ui/Button'
import { Card } from '../../ui/Card'
import { Grid } from '../../ui/Grid'
import { Icon } from '../../ui/Icon'
import { Option, SidePanel } from '../../ui/Overlays'
import { ProblemScreen, SkeletonRow, SkeletonText } from '../../ui/States'
import { VScroll } from '../../ui/VScroll'
import { problemWords } from '../../ui/problemWords'
import { CardActions } from './CardActions'
import { useCardActions, useFocusHint, useFocusOnArrival, useRowCards } from './browseHooks'
import { useEntryState } from './entryState'
import { ALPHABET, letterCounts, letterLabel, nextLetter, PAGE_SIZE, pagesAround, slots, stepRows, stripEntry, toStrip, type LetterOffset } from './gridPages'
import { HintPill, HScroll, KeyRow, type Hint } from './Hints'
import {
  choicesFrom, countsLine, decadeLabel, DEFAULT_CHOICES, genreChips, gridGroup, gridLine, libraryNoun, narrowed, sortLabel, type GridChoices,
} from './libraryView'
import { itemActions, seeAllRoute, type CardAction, type RowContext } from './rowCards'
import { Glyph, RowView } from './RowView'
import { useWatchlistsOn } from '../watchlists/useWatchlistsOn'
import './browse.css'

type Library = components['schemas']['Library']
type BrowseItem = components['schemas']['BrowseItem']
type ItemList = components['schemas']['ItemList']

const query = (c: GridChoices) => ({ sort: c.sort, filter: c.filter, genre: c.genre || undefined, decade: c.decade || undefined })
const pageData = (results: UseQueryResult<ItemList>[]) => results.map((r) => r.data)

/** One of the library's rows, its See all sorting the grid below (or opening a list's page). */
function LibraryRow({ row, base }: { row: ScreenRow; base: Omit<RowContext, 'now'> }) {
  const { cards } = useRowCards(row, base)
  return <RowView row={row} cards={cards} listings={false}/>
}

/** Sort, decade: the choices a side panel offers. */
type Picker = 'sort' | 'decade'

function ChipRow({ type, choices, facets, onChange, onPick }: {
  type: LibraryType; choices: GridChoices; facets?: Pick<ItemList, 'facets' | 'genreCounts'>; onChange(c: GridChoices): void; onPick(p: Picker): void
}) {
  const genres = genreChips(facets?.facets.genres ?? [], facets?.genreCounts, choices.genre)
  const decades = facets?.facets.decades ?? []
  return <HScroll className="library-chips" trackClassName="library-chips__track" trackProps={{ role: 'group', 'aria-label': 'Show', 'data-row': true, 'data-group': 'library/chips' }}>
      {filterOptions(type).map((f) => <button key={f.value || 'all'} type="button" data-focus={`chips/f-${f.value || 'all'}`} className="tv-chip" aria-pressed={choices.filter === f.value}
        onClick={() => onChange({ ...choices, filter: f.value })}>{f.label}</button>)}
      {(genres.length > 0 || decades.length > 0) && <span className="library-chips__divider" aria-hidden="true"/>}
      {genres.map((g) => <button key={g} type="button" data-focus={`chips/g-${g}`} className="tv-chip" aria-pressed={choices.genre === g}
        onClick={() => onChange({ ...choices, genre: choices.genre === g ? '' : g })}>{g}</button>)}
      {decades.length > 0 && <button type="button" data-focus="chips/decade" className="tv-chip library-chip-more" aria-pressed={!!choices.decade} onClick={() => onPick('decade')}>
        {choices.decade ? decadeLabel(choices.decade) : 'Decade'}<Icon name="down"/>
      </button>}
  </HScroll>
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
  const others = useQueries({ queries: wanted.map((p) => ({ ...itemsQuery(library.id, { ...query(choices), limit: PAGE_SIZE, offset: p * PAGE_SIZE }), placeholderData: undefined })), combine: pageData })
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
  const hints: Hint[] = strip
    ? [{ keys: ['▲', '▼'], label: 'Letter, skipping empty ones' }, { keys: ['OK', '◀'], label: `First ${noun} under ${strip.letter}` }, { keys: ['Return'], label: `Back to the ${noun} you were on` }]
    : [
      ...(byTitle ? [{ keys: ['▶'], label: 'From the last column: A–Z' }] : []),
      { keys: ['CH ▲', 'CH ▼'], label: 'A screen up or down' },
      ...(menu ? [{ keys: ['Hold OK'], label: 'Its menu' }] : type === 'movie' ? [{ keys: ['Hold OK'], label: 'Mark watched' }] : []),
      ...(byTitle || choices.sort === 'title' ? [] : [{ note: 'A–Z appears when the list is sorted by title' }]),
    ]
  return <div className="library-grid__body" onFocus={onFocus} data-autofocus={autoFocus || undefined}>
    <Grid key={group} id={group} items={items} itemKey={(_, i) => String(i)} variant="poster" reveal={reveal}
      renderItem={(item, id, i) => {
        const actions = item ? itemActions(item, menu) : {}
        return <div className={`browse-cell${item ? '' : ' browse-slot'}${landing === i ? ' browse-land' : ''}`}>
          <Card id={id} variant="poster" title={item?.title ?? ''} subtitle={item ? gridLine(item) : undefined} image={item && media.imageUrl(item.posterPath, 'w342')}
            progress={item?.progress} badges={item?.isNew ? [{ label: 'New', tone: 'gold' }] : item?.watched ? [{ label: '✓', tone: 'gold' }] : undefined}
            label={item ? [item.title, gridLine(item), item.watched && 'watched'].filter(Boolean).join(', ') : 'Loading'}
            onPress={actions.press && (() => run(actions.press))} onHold={actions.hold && (() => run(actions.hold))}/>
        </div>
      }}/>
    {byTitle && <nav className={`library-strip${strip ? ' library-strip--on' : ''}`} aria-label="Jump to a letter" data-az>
      {ALPHABET.map((l) => {
        const n = counts.get(l) ?? 0
        const current = (strip?.letter ?? stripEntry(letters, view)) === l
        return <button key={l} type="button" data-focus={`az/${l}`} className={`library-az${current ? ' library-az--current' : ''}`} aria-disabled={n === 0 || undefined}
          aria-label={letterLabel(l, n, noun)} onClick={() => { const at = offsetOf(l); if (n && at !== undefined) land(at) }}>{l}</button>
      })}
    </nav>}
    {strip && <div className="library-bubble" aria-hidden="true" style={{ top: `calc(${ALPHABET.indexOf(strip.letter)} * 1.6875rem + 1.3125rem - 3.5rem)` }}>
      <b>{strip.letter}</b><span>{`${counts.get(strip.letter) ?? 0} ${(counts.get(strip.letter) ?? 0) === 1 ? noun : `${noun}s`}`}</span>
    </div>}
    <GridHints hints={hints}/>
  </div>
}

/** The grid's keys along the foot, while focus is in the grid. */
function GridHints({ hints }: { hints: Hint[] }) {
  return <KeyRow hints={hints} className="browse-hints library-hints"/>
}

/**
 * All movies or All shows: the count and Sort, the filter, genre and decade
 * chips, and the grid. Changing a choice leaves focus on its chip, and the
 * grid starts again at its first title.
 */
function GridSection({ library, type, choices, onChange, alone, lists, autoFocus, focusFirst, onFocused }: {
  library: Library; type: LibraryType; choices: GridChoices; onChange(c: GridChoices): void; alone: boolean; lists: number; autoFocus: boolean; focusFirst: boolean; onFocused(): void
}) {
  // While a new choice loads, the chips keep the last reply's genres (the focused chip stays), and the grid shows its loading.
  const page = useQuery(itemsQuery(library.id, { ...query(choices), limit: PAGE_SIZE, offset: 0 }))
  const [picker, setPicker] = useState<Picker>()
  const [inGrid, setInGrid] = useState(false)
  const title = type === 'tv' ? 'TV Shows' : 'Movies'
  const noun = type === 'tv' ? 'shows' : 'movies'
  const data = page.data
  const counts = countsLine(type, { totals: data?.totals, total: data?.total, lists, sort: alone ? choices.sort : undefined })
  const sort = <div className="library-sort">
    <span className="library-sort__label">Sort</span>
    <button type="button" data-focus="library/sort" className="tv-chip library-chip-more" onClick={() => setPicker('sort')}>{sortLabel(type, choices.sort)}<Icon name="down"/></button>
  </div>
  const onFocusIn = (e: FocusEvent) => setInGrid(!!(e.target as HTMLElement).closest('.library-grid__body'))
  const onFocusOut = (e: FocusEvent) => { if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setInGrid(false) }
  return <section className={`library-grid${alone ? ' library-grid--alone' : ''}${choices.sort === 'title' && data?.letters?.length ? ' library-grid--az' : ''}${inGrid ? ' library-grid--in' : ''}`}
    data-vitem aria-label={`All ${noun}`} onFocus={onFocusIn} onBlur={onFocusOut}>
    {alone
      ? <header className="library-head">
        <div className="library-head__text"><h1 className="library-title">{title}</h1><p className="library-counts">{counts || ' '}</p></div>{sort}
      </header>
      : <div className="library-grid__head">
        <h2 className="library-grid__title">All {noun}{data && !page.isPlaceholderData && <small>{data.total.toLocaleString()}</small>}</h2>{sort}
      </div>}
    <ChipRow type={type} choices={choices} facets={data} onChange={onChange} onPick={setPicker}/>
    {page.isPending || page.isPlaceholderData ? <div className="library-grid__body"><SkeletonRow variant="poster" title={false}/></div>
      : page.isError ? <GridProblem error={page.error} onRetry={() => void page.refetch()}/>
        : data && data.total === 0 ? <div className="library-empty">
          <h2 className="library-empty__title">{narrowed(choices) ? 'Nothing matches' : `No ${noun} yet`}</h2>
          <p className="library-empty__body">{narrowed(choices) ? `No ${noun} match these filters.` : 'Add them to the library on the server’s web page and scan it; they appear here.'}</p>
          {narrowed(choices) && <Button id="library/clear" variant="primary" onPress={() => onChange({ ...DEFAULT_CHOICES, sort: choices.sort })}>Clear filters</Button>}
        </div>
          : data && <GridBody key={gridGroup(library.id, choices)} library={library} type={type} choices={choices} first={data} focusFirst={focusFirst} onFocused={onFocused} autoFocus={autoFocus}/>}
    {picker === 'sort' && <SidePanel title="Sort by" onClose={() => setPicker(undefined)}>
      {sortOptions(type).map((o) => <Option key={o.value} id={`sort/${o.value}`} label={o.label} checked={o.value === choices.sort} onPress={() => { setPicker(undefined); onChange({ ...choices, sort: o.value }) }}/>)}
    </SidePanel>}
    {picker === 'decade' && <SidePanel title="Decade" onClose={() => setPicker(undefined)}>
      {[0, ...(data?.facets.decades ?? [])].map((d) => <Option key={d} id={`decade/${d}`} label={d ? decadeLabel(d) : 'Any decade'} checked={d === choices.decade}
        onPress={() => { setPicker(undefined); onChange({ ...choices, decade: d }) }}/>)}
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
function LibraryView({ library, type, initial, toGrid }: { library: Library; type: LibraryType; initial: GridChoices; toGrid: boolean }) {
  const nav = useNav()
  const rows = useQuery(rowsQuery(library.id))
  const libraries = useQuery(librariesQuery)
  const [choices, setChoices] = useEntryState<GridChoices>('library-choices', initial)
  // The grid's first page, so first focus has somewhere to go; a new choice keeps the last page until its own is in.
  const first = useQuery(itemsQuery(library.id, { ...query(choices), limit: PAGE_SIZE, offset: 0 }))
  const [gridFocus, setGridFocus] = useState(false)
  const root = useRef<HTMLDivElement>(null)
  const hint = useFocusHint(root)
  const refs = libraries.data?.items
  const menu = useWatchlistsOn()
  const base = useMemo<Omit<RowContext, 'now'>>(() => ({
    libraries: refs ?? [], channels: [], listings: false, menu,
    // A list's page, or the grid below sorted the row's way; a link to another library has none here.
    seeAll: (target, row): CardAction | undefined => {
      const route = seeAllRoute(target, row, refs ?? [])
      // A watchlist's page opens with this screen's type chosen.
      if (route?.name === 'list') return { kind: 'open', route: { ...route, type } }
      if (target.kind !== 'library' || route?.name !== 'seeAll' || route.libraryId !== library.id) return undefined
      const { sort, filter, genre, decade } = target.search
      return { kind: 'grid', sort, filter, genre, decade }
    },
  }), [refs, library.id, menu, type])
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
        <GridSection library={library} type={type} choices={choices} onChange={setChoices} alone={alone} lists={lists} autoFocus={alone || toGrid} focusFirst={gridFocus} onFocused={onFocused}/>
      </VScroll>
      <HintPill words={hint}/>
      <FocusOnArrival/>
    </div>
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
  return <LibraryView key={library.id} library={library} type={route.type} initial={DEFAULT_CHOICES} toGrid={false}/>
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

