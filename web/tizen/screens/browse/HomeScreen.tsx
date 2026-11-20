import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { flushSync } from 'react-dom'
import { useQuery } from '@tanstack/react-query'
import type { components } from '../../../src/api/schema'
import { homeQuery, librariesQuery, liveTVCapabilitiesQuery, liveTVChannelsQuery } from '../../../src/api/queries'
import type { LiveChannel } from '../../../src/api/liveTV'
import { showListings } from '../../../src/lib/liveGuide'
import type { ScreenRow } from '../../../src/lib/screenRows'
import { scopeName } from '../../app/focus'
import { useBack, useEntry, useFocus, useKeys, useMediaUrls, useNav, useSession } from '../../app/hooks'
import { Button } from '../../ui/Button'
import { Icon } from '../../ui/Icon'
import { ProblemScreen, Skeleton, SkeletonRow, SkeletonText } from '../../ui/States'
import { problemWords } from '../../ui/problemWords'
import { CardActions } from './CardActions'
import { cardPanel, panelHint } from './cardPanel'
import { homeEmpty } from './emptyHome'
import { useEntryState } from './entryState'
import {
  END_EDIT, END_TOP, HERO_OPEN, HERO_PLAY, homeIsEmpty, homePages, homeScreenRows, pageOfFocus, pageStep, peekLine, rowCounter, rowGroup, totalsLine,
  type HomeData, type HomePage,
} from './homePages'
import { episodeCode, seeAllRoute, type RowCard, type RowContext } from './rowCards'
import { toneStyle, useFocusOnArrival, useRowCards } from './browseHooks'
import { Glyph, RowView } from './RowView'
import './browse.css'

type Hero = components['schemas']['HomeHero']

/** Live TV for Home's row: the channels that can be watched, and whether the server has listings. */
function useLive() {
  const session = useSession()
  const capabilities = useQuery(liveTVCapabilitiesQuery)
  const on = !!capabilities.data?.enabled && !!capabilities.data.allowed && !!capabilities.data.playback
  const channels = useQuery({ ...liveTVChannelsQuery, enabled: on })
  const items = channels.data?.items
  const available = useMemo(() => (on && items ? items.filter((c) => c.available) : []), [on, items])
  return {
    channels: available as readonly LiveChannel[],
    listings: showListings(session.server.apiLevel, capabilities.data),
    pending: capabilities.isPending || (on && channels.isPending),
  }
}

/** The focused card's picture over the top of the screen, faded into the ground; its tone while it loads or without one. */
function Backdrop({ url, seed, short }: { url?: string; seed: string; short?: boolean }) {
  return <div className={`home-backdrop${short ? ' home-backdrop--short' : ''}`} style={toneStyle(seed)} aria-hidden="true">
    {url && <img key={url} className="home-backdrop__img" src={url} alt="" decoding="async" draggable={false}/>}
    <span className="home-backdrop__fade"/>
  </div>
}

/** "S03 E07 · The Long Dark · 52 min · [4K] [HDR] · TV-14". */
function Facts({ facts, tags, rating, big }: { facts: string[]; tags: string[]; rating?: string; big?: boolean }) {
  const parts: ReactNode[] = []
  if (facts.length) parts.push(<span key="f" className="home-facts__text">{facts.join(' · ')}</span>)
  if (tags.length) parts.push(<span key="t" className="home-facts__tags">{tags.map((t) => <span key={t} className="home-tag">{t}</span>)}</span>)
  if (rating) parts.push(<span key="r">{rating}</span>)
  return <p className={`home-facts${big ? ' home-facts--big' : ''}`}>
    {parts.flatMap((p, i) => (i ? [<span key={`s${i}`} className="home-facts__sep">·</span>, p] : [p]))}
  </p>
}

/** The hero's play, from Home's episode cards when it is one of them (for the player's line under the title). */
function heroPlay(hero: Hero, rows: readonly ScreenRow[]) {
  if (!hero.fileId) return undefined
  const episode = hero.episodeId ? rows.flatMap((r) => r.episodes ?? []).find((e) => e.episodeId === hero.episodeId) : undefined
  const subtitle = episode ? `${episodeCode(episode.seasonNumber, episode.episodeNumber)} · ${episode.title}` : hero.kind === 'episode' ? hero.facts : undefined
  return { kind: 'file' as const, fileId: hero.fileId, title: hero.title, subtitle, backdropPath: hero.backdropPath }
}

function HeroSection({ hero, rows }: { hero: Hero; rows: readonly ScreenRow[] }) {
  const nav = useNav()
  const media = useMediaUrls()
  const play = heroPlay(hero, rows)
  const open = hero.kind === 'episode' && hero.showId && hero.episodeId ? { label: 'Go to episode', route: { name: 'show' as const, id: hero.showId, episodeId: hero.episodeId } }
    : hero.showId ? { label: 'Go to show', route: { name: 'show' as const, id: hero.showId } }
      : hero.movieId ? { label: 'More info', route: { name: 'movie' as const, id: hero.movieId } } : undefined
  return <>
    <Backdrop url={media.imageUrl(hero.backdropPath, 'w1280')} seed={hero.title}/>
    <section className="home-hero" aria-label={hero.kicker || hero.title}>
      {hero.kicker && <p className="tv-kicker home-kicker">{hero.kicker}</p>}
      <h1 className="home-hero__title">{hero.title}</h1>
      <Facts facts={[hero.facts].filter(Boolean)} tags={[hero.quality || '', hero.hdr ? 'HDR' : ''].filter(Boolean)} rating={hero.contentRating} big/>
      {hero.overview && <p className="home-hero__overview">{hero.overview}</p>}
      <div className="tv-actions home-hero__actions">
        {play && <Button id={HERO_PLAY} variant="primary" icon="play" autoFocus onPress={() => nav.play(play)}>Play</Button>}
        {open && <Button id={HERO_OPEN} variant={play ? 'ghost' : 'primary'} autoFocus={!play} onPress={() => nav.push(open.route)}>{open.label}</Button>}
      </div>
    </section>
  </>
}

function Peek({ text, up }: { text: string; up: boolean }) {
  return <div className={`home-peek${up ? ' home-peek--up' : ''}`} aria-label={up ? 'Row before' : 'Next row'}>
    <Icon name="down"/><span>{text}</span>
  </div>
}

/** A row screen: the focused card's picture, kicker, title, facts and overview over the row, and what OK does. */
function RowPage({ row, rows, base, autoFocus }: { row: ScreenRow; rows: readonly ScreenRow[]; base: Omit<RowContext, 'now'>; autoFocus: boolean }) {
  const media = useMediaUrls()
  const { cards, now } = useRowCards(row, base)
  const remembered = useEntry()?.memory.groups[rowGroup(row)]
  const [focused, setFocused] = useState(() => Math.max(0, cards.findIndex((c) => `${rowGroup(row)}/${c.key}` === remembered)))
  const at = Math.min(focused, cards.length - 1)
  const panel = cardPanel(row, cards, at, { channels: base.channels, listings: base.listings, now })
  // The pictures either side, so moving along the row doesn't show the tone first.
  useEffect(() => {
    for (const i of [at - 1, at + 1]) {
      const path = cardPicture(row, cards, i)
      const url = path && media.imageUrl(path, 'w1280')
      if (url) new Image().src = url
    }
  }, [row, cards, at, media])
  return <>
    <Backdrop url={panel?.picture ? media.imageUrl(panel.picture, 'w1280') : undefined} seed={panel?.seed ?? row.title}/>
    {panel && <section className="home-panel" aria-live="polite">
      <p className="tv-kicker home-kicker">{panel.kicker}</p>
      <h1 className="home-panel__title">{panel.title}</h1>
      {(panel.facts.length > 0 || panel.tags.length > 0 || panel.rating) && <Facts facts={panel.facts} tags={panel.tags} rating={panel.rating}/>}
      {panel.overview && <p className="home-panel__overview">{panel.overview}</p>}
      <p className="home-panel__hint"><span className="home-okkey">OK</span><span>{panelHint(panel.target, cards[at], at === 0)}</span></p>
    </section>}
    <div className="home-page-row" data-autofocus={autoFocus || undefined}>
      <RowView row={row} cards={cards} counter={rowCounter(rows, row)} listings={base.listings} onFocusIndex={setFocused}/>
    </div>
  </>
}

function cardPicture(row: ScreenRow, cards: readonly RowCard[], index: number): string | undefined {
  if (!cards[index] || cards[index].more) return undefined
  switch (row.style) {
    case 'continue': { const c = row.continue?.[index]; return c?.stillPath ?? c?.backdropPath }
    case 'episode': { const e = row.episodes?.[index]; return e?.stillPath ?? e?.showBackdropPath }
    case 'live': return undefined
    default: return row.items?.[index]?.backdropPath
  }
}

function StartRow({ row, rows, base }: { row: ScreenRow; rows: readonly ScreenRow[]; base: Omit<RowContext, 'now'> }) {
  const { cards } = useRowCards(row, base)
  return <div className="home-start-row"><RowView row={row} cards={cards} counter={rowCounter(rows, row)} listings={base.listings}/></div>
}

function EndPage({ home, onTop }: { home: HomeData; onTop(): void }) {
  const nav = useNav()
  return <section className="home-end">
    <h1 className="home-end__title">That’s everything on Home</h1>
    <p className="home-end__body">Move, hide and add rows. Your order follows you to every device.</p>
    <div className="tv-actions">
      <Button id={END_EDIT} onPress={() => nav.push({ name: 'settings', group: 'homeRows', rowsScreen: 'home' })}><Glyph name="sliders"/>Edit rows</Button>
      <Button id={END_TOP} onPress={onTop}><Glyph name="up"/>Back to the top</Button>
    </div>
    <p className="home-end__totals">{totalsLine(home)}</p>
  </section>
}

/** How long a page takes to slide; a jump of more than one page doesn't slide. */
const PAGE_MS = 320

/**
 * Home one row a screen (HomeStart, HomeRow): ▲ ▼ page, focus moving first
 * and the screen sliding after, so a key pressed during the slide acts on
 * the row arriving. A held key's repeats are swallowed, so they can't skip
 * screens. Only the page showing and the ones either side are drawn, and
 * only the one showing takes focus. Return from a row page goes to the
 * first page, and from there to the shell's Leave WatchMyMedia?.
 */
function HomePager({ home, rows, base }: { home: HomeData; rows: readonly ScreenRow[]; base: Omit<RowContext, 'now'> }) {
  const entry = useEntry()
  const focus = useFocus()
  const root = useRef<HTMLDivElement>(null)
  const pages = useMemo(() => homePages(!!home.hero, rows), [home.hero, rows])
  const [pageKey, setPageKey] = useEntryState<string>('home-page', () => pageOfFocus(pages, entry?.memory.last) ?? pages[0].key)
  const index = Math.max(0, pages.findIndex((p) => p.key === pageKey))
  const [jump, setJump] = useState(false)
  useFocusOnArrival()

  /** Puts focus on a page: the card its row last had, else its row's first, else its first button. */
  const focusPage = useCallback((i: number) => {
    const el = root.current?.querySelector<HTMLElement>(`[data-home-page="${i}"]`)
    if (!el) return
    const page = pages[i]
    const remembered = page.kind !== 'end' && page.row ? entry?.memory.groups[rowGroup(page.row)] : undefined
    const row = el.querySelector<HTMLElement>('[data-home-row]')
    if (!(remembered && focus.focusId(remembered, el)) && !(row && focus.focusFirst(row))) focus.focusFirst(el)
  }, [pages, entry, focus])

  const goTo = useCallback((i: number) => {
    if (i < 0 || i >= pages.length) return
    flushSync(() => {
      setJump(Math.abs(i - index) > 1)
      setPageKey(pages[i].key)
    })
    focusPage(i)
  }, [pages, index, setPageKey, focusPage])

  useKeys((input) => {
    const active = document.activeElement as HTMLElement | null
    if (scopeName(active) !== 'content') return false
    // ▶ past the hero's last button stays there, rather than dropping into the row below.
    if (input.action === 'right' && active?.closest('.home-hero') && !active.nextElementSibling) return true
    if (input.action !== 'up' && input.action !== 'down') return false
    const at = Number(active?.closest<HTMLElement>('[data-home-page]')?.dataset.homePage ?? index)
    const page = pages[at]
    const inRow = !!active?.closest('[data-home-row]')
    // The first page's hero and row are one screen: ▲ from the row and ▼ from the hero move within it.
    if (page?.kind === 'start' && ((input.action === 'up' && inRow) || (input.action === 'down' && !inRow && page.row))) return false
    if (page?.kind === 'start' && input.action === 'up') return true
    const next = pageStep(pages.length, at, input.action)
    if (next !== undefined && !input.repeat) goTo(next)
    return true
  })
  useBack(() => {
    if (index === 0) return false
    goTo(0)
    return true
  })

  useEffect(() => {
    if (!jump) return
    const timer = setTimeout(() => setJump(false), PAGE_MS)
    return () => clearTimeout(timer)
  }, [jump])

  const listings = base.listings
  const drawn = pages.map((page, i) => ({ page, i })).filter(({ i }) => Math.abs(i - index) <= 1)
  return <div className="home" ref={root}>
    <div className={`home__track${jump ? ' home__track--jump' : ''}`} style={{ transform: `translateY(calc(${-index} * 67.5rem))` }}>
      {drawn.map(({ page, i }) => {
        const peek = peekLine(pages, i, listings)
        return <section key={page.key} className="home__page" data-home-page={i} inert={i !== index} style={{ top: `calc(${i} * 67.5rem)` }}>
          <Page page={page} home={home} rows={rows} base={base} first={i === 0} onTop={() => goTo(0)}/>
          <Peek text={peek.text} up={peek.up}/>
        </section>
      })}
    </div>
  </div>
}

function Page({ page, home, rows, base, first, onTop }: { page: HomePage; home: HomeData; rows: readonly ScreenRow[]; base: Omit<RowContext, 'now'>; first: boolean; onTop(): void }) {
  switch (page.kind) {
    case 'start': return <>
      {home.hero && <HeroSection hero={home.hero} rows={rows}/>}
      {page.row && <StartRow row={page.row} rows={rows} base={base}/>}
    </>
    case 'row': return <RowPage row={page.row} rows={rows} base={base} autoFocus={first}/>
    case 'end': return <EndPage home={home} onTop={onTop}/>
  }
}

/** The board's loading state: the hero's lines and buttons, a row of wide cards and one of posters. */
function HomeSkeleton() {
  return <div className="home home-skeleton" aria-busy="true">
    <span className="tv-sr" role="status">Loading Home</span>
    <div className="home-hero" aria-hidden="true">
      <SkeletonText width="21.25rem"/>
      <Skeleton width="33.75rem" height="4rem"/>
      <SkeletonText width="29.375rem"/>
      <SkeletonText width="47.5rem"/>
      <SkeletonText width="37.5rem"/>
      <div className="tv-actions home-hero__actions"><Skeleton width="9.375rem" height="4rem" radius="var(--radius-m)"/><Skeleton width="13.125rem" height="4rem" radius="var(--radius-m)"/></div>
    </div>
    <div className="home-skeleton__rows" aria-hidden="true"><SkeletonRow variant="continue"/><SkeletonRow variant="poster"/></div>
  </div>
}

function EmptyHome({ libraries, onRetry }: { libraries: components['schemas']['Library'][] | undefined; onRetry(): void }) {
  const session = useSession()
  const words = homeEmpty(libraries, session.isAdmin)
  const address = session.isAdmin && words.setup ? session.baseUrl : undefined
  return <ProblemScreen icon={words.kind === 'scanning' ? 'clock' : words.kind === 'scanFailed' ? 'alert' : 'setup'} title={words.title}
    actions={[{ id: 'home/retry', label: 'Check again', onPress: onRetry }]} extra={address && <p className="home-address">{address}</p>}>
    {words.body}
  </ProblemScreen>
}

/**
 * Home (route `home`): the server's rows in the person's order
 * (`GET /api/home?arranged=true`), one a screen, with the hero first and
 * Edit rows last; Live TV's row where the person put it. Rows refresh as
 * the server's events invalidate Home.
 */
export function HomeScreen() {
  const home = useQuery(homeQuery)
  const libraries = useQuery(librariesQuery)
  const live = useLive()
  const libs = libraries.data?.items
  const base = useMemo<Omit<RowContext, 'now'>>(() => {
    const refs = libs ?? []
    return {
      libraries: refs, channels: live.channels, listings: live.listings,
      seeAll: (target, row) => { const route = seeAllRoute(target, row, refs); return route && { kind: 'open', route } },
    }
  }, [libs, live.channels, live.listings])
  const rows = useMemo(() => (home.data ? homeScreenRows(home.data, live.channels.length > 0) : []), [home.data, live.channels.length])

  if (home.isPending || libraries.isPending || live.pending) return <HomeSkeleton/>
  if (home.isError) {
    const words = problemWords(home.error)
    return <ProblemScreen icon="offline" title={`Couldn’t load Home. ${words.title}`} actions={[{ id: 'home/retry', label: 'Try again', onPress: () => void home.refetch() }]}>{words.body}</ProblemScreen>
  }
  if (homeIsEmpty(home.data, rows)) return <EmptyHome libraries={libs} onRetry={() => { void home.refetch(); void libraries.refetch() }}/>
  return <CardActions><HomePager home={home.data} rows={rows} base={base}/></CardActions>
}
