import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { flushSync } from 'react-dom'
import { useQueries, useQuery } from '@tanstack/react-query'
import { unwrap } from '../../../src/api/client'
import { askAt34 } from '../../session/peerRegistry'
import { chosenCopies, titleKey } from '../../session/chosenCopies'
import { serverHeading } from '../details/copies'
import { COPY_MS, useSlow, within } from '../details/useCopies'
import { MERGED_ROWS, mergeHomeRows, notOnHome, oneCardWords, rowOrder, stillAskingHome, type PeerHome, type TaggedContinue } from './oneCardHome'
import { bareName } from '../search/crossSearch'
import type { components } from '../../../src/api/schema'
import { homeQuery, librariesQuery, liveTVCapabilitiesQuery, liveTVChannelsQuery } from '../../../src/api/queries'
import type { LiveChannel } from '../../../src/api/liveTV'
import { showListings } from '../../../src/lib/liveGuide'
import type { ScreenRow } from '../../../src/lib/screenRows'
import { scopeName } from '../../app/focus'
import { useBack, useEntry, useFocus, useKeys, useLevel34, useMediaFor, useMediaUrls, useNav, usePeers, useSession } from '../../app/hooks'
import { Button } from '../../ui/Button'
import { Icon } from '../../ui/Icon'
import { ProblemScreen, Skeleton, SkeletonRow, SkeletonText } from '../../ui/States'
import { problemWords } from '../../ui/problemWords'
import { CardActions } from './CardActions'
import { cardPanel } from './cardPanel'
import { homeEmpty } from './emptyHome'
import { useEntryState } from './entryState'
import {
  END_EDIT, END_TOP, HERO_OPEN, HERO_PLAY, HERO_RESTART, endKicker, homeIsEmpty, homePages, homeScreenRows, pageOfFocus, pageStep, peekLine, resumeHero, resumeLine, rowCounter, rowGroup, totalsLine,
  type HomeData, type HomePage,
} from './homePages'
import { episodeCode, seeAllRoute, type RowCard, type RowContext } from './rowCards'
import { toneStyle, useCardActions, useFocusOnArrival, useRowCards } from './browseHooks'
import { Glyph, RowView } from './RowView'
import { useWatchlistsOn } from '../watchlists/useWatchlistsOn'
import { useApp } from '../../app/hooks'
import { Clock } from '../../ui/Clock'
import { endsAtWords, leftWords } from '../../ui/timeWords'
import { useNow } from '../live/clock'
import './browse.css'

type Hero = components['schemas']['HomeHero']
type ContinueItem = components['schemas']['ContinueItem']

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

/** "2026 · 1 season · TV-14 · Drama", with a row screen's tags in words ("4K", "HDR"). */
function Facts({ facts, tags, rating }: { facts: string[]; tags: string[]; rating?: string }) {
  const parts: ReactNode[] = []
  if (facts.length) parts.push(<span key="f" className="home-facts__text">{facts.join(' · ')}</span>)
  if (tags.length) parts.push(<span key="t" className="home-facts__tags">{tags.map((t) => <span key={t} className="tv-tag">{t}</span>)}</span>)
  if (rating) parts.push(<span key="r">{rating}</span>)
  return <p className="home-facts">
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

/** The server's pick, when nothing is in progress: a kicker, the title, its facts, two lines and Play. */
function PickHero({ hero, rows }: { hero: Hero; rows: readonly ScreenRow[] }) {
  const nav = useNav()
  const media = useMediaUrls()
  const play = heroPlay(hero, rows)
  const open = hero.kind === 'episode' && hero.showId && hero.episodeId ? { label: 'Episodes', route: { name: 'show' as const, id: hero.showId, episodeId: hero.episodeId } }
    : hero.showId ? { label: 'Episodes', route: { name: 'show' as const, id: hero.showId } }
      : hero.movieId ? { label: 'More info', route: { name: 'movie' as const, id: hero.movieId } } : undefined
  return <>
    <Backdrop url={media.imageUrl(hero.backdropPath, 'w1280')} seed={hero.title}/>
    <section className="home-hero" aria-label={hero.kicker || hero.title}>
      {hero.kicker && <p className="tv-kicker">{hero.kicker}</p>}
      <h1 className="home-hero__title">{hero.title}</h1>
      <Facts facts={[hero.facts, hero.contentRating ?? ''].filter(Boolean)} tags={[]}/>
      {hero.overview && <p className="home-hero__overview">{hero.overview}</p>}
      <div className="tv-actions home-hero__actions" data-group="home/hero">
        {play && <Button id={HERO_PLAY} variant="primary" icon="play" autoFocus onPress={() => nav.play(play)}>Play</Button>}
        {open && <Button id={HERO_OPEN} variant={play ? 'glass' : 'primary'} autoFocus={!play} onPress={() => nav.push(open.route)}>{open.label}</Button>}
      </div>
    </section>
  </>
}

/**
 * What you were watching, when something is in progress: where you are,
 * how much is left and when it would end, with Resume focused, Start over,
 * and the show's episodes or the movie's page.
 */
function ResumeHero({ item }: { item: TaggedContinue }) {
  const nav = useNav()
  const media = useMediaFor()(item.oneCard?.server.id)
  const { run } = useCardActions()
  // Hold OK on Resume opens the card's actions, as it does on the row's cards (API level 34).
  const menu = useLevel34() ? () => run({ kind: 'continueMenu', item }) : undefined
  const now = useNow()
  // "2026 · from Cabin": the hero says when Resume plays another server's copy, or a title only it has.
  const line = [resumeLine(item), oneCardWords(item.oneCard, bareName) ?? ''].filter(Boolean).join(' · ')
  const left = Math.max(0, item.durationMs - item.positionMs)
  const fraction = item.durationMs ? Math.min(1, item.positionMs / item.durationMs) : 0
  const picture = item.backdropPath ?? item.stillPath
  // From another server: Resume plays from there, and a title only it has opens in place.
  const from = item.oneCard ? { server: item.oneCard.server.id } : {}
  const only = item.oneCard?.where === 'only' ? from : {}
  const request = { kind: 'file' as const, fileId: item.fileId, title: item.title, subtitle: resumeLine(item) || undefined, backdropPath: picture, ...from }
  const open = item.showId ? { label: 'Episodes', route: { name: 'show' as const, id: item.showId, episodeId: item.episodeId, ...only } }
    : item.movieId ? { label: 'More info', route: { name: 'movie' as const, id: item.movieId, ...only } } : undefined
  return <>
    <Backdrop url={media.imageUrl(picture, 'w1280')} seed={item.title}/>
    <section className="home-hero" aria-label="Pick up where you left off">
      <p className="tv-kicker">Continue watching</p>
      <h1 className="home-hero__title">{item.title}</h1>
      {line && <p className="home-hero__line">{line}</p>}
      {item.durationMs > 0 && <div className="home-hero__progress">
        <span className="tv-bar home-hero__bar" role="img" aria-label={`${Math.round(item.positionMs / 60_000)} of ${Math.round(item.durationMs / 60_000)} minutes watched`}><i style={{ width: `${Math.round(fraction * 100)}%` }}/></span>
        <b>{leftWords(left)}</b><span className="tv-muted">{endsAtWords(now, left)}</span>
      </div>}
      <div className="tv-actions home-hero__actions" data-group="home/hero">
        <Button id={HERO_PLAY} variant="primary" icon="play" autoFocus onPress={() => nav.play({ ...request, startMs: item.positionMs })} onHold={menu}>Resume</Button>
        <Button id={HERO_RESTART} variant="glass" onPress={() => nav.play({ ...request, startMs: 0 })}>Start over</Button>
        {open && <Button id={HERO_OPEN} variant="glass" onPress={() => nav.push(open.route)}>{open.label}</Button>}
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
    </section>}
    <div className="home-page-row" data-autofocus={autoFocus || undefined}>
      <RowView row={row} cards={cards} counter={rowCounter(rows, row)} listings={base.listings} onFocusIndex={setFocused} dimPast/>
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

/** The last screen: Back to the top focused, Edit rows, and what the library holds in titles. */
function EndPage({ home, rows, onTop }: { home: HomeData; rows: readonly ScreenRow[]; onTop(): void }) {
  const nav = useNav()
  const totals = totalsLine(home)
  return <section className="home-end">
    {rows.length > 0 && <p className="tv-kicker">{endKicker(rows)}</p>}
    <h1 className="home-end__title">That’s everything on Home</h1>
    {totals && <p className="home-end__body">{totals}</p>}
    <div className="tv-actions">
      <Button id={END_TOP} variant="primary" autoFocus onPress={onTop}><Glyph name="up"/>Back to the top</Button>
      <Button id={END_EDIT} onPress={() => nav.push({ name: 'settings', group: 'homeRows', rowsScreen: 'home' })}>Edit rows</Button>
    </div>
  </section>
}

/** How long a page takes to slide; a jump of more than one page doesn't slide. */
const PAGE_MS = 320

/**
 * Home one row a screen: ▲ ▼ page, focus moving first
 * and the screen sliding after, so a key pressed during the slide acts on
 * the row arriving. A held key's repeats are swallowed, so they can't skip
 * screens. Only the page showing and the ones either side are drawn, and
 * only the one showing takes focus. Return from a row page goes to the
 * first page, and from there to the shell's Leave WatchMyMedia?.
 */
function HomePager({ home, resume, rows, base, onShown }: { home: HomeData; resume?: ContinueItem; rows: readonly ScreenRow[]; base: Omit<RowContext, 'now'>; onShown?: (row: ScreenRow | undefined) => void }) {
  const entry = useEntry()
  const focus = useFocus()
  const root = useRef<HTMLDivElement>(null)
  const pages = useMemo(() => homePages(!!(resume || home.hero), rows), [resume, home.hero, rows])
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
    // ▼ from the hero: the row's card it last had, else its first (not the card nearest the button).
    if (page?.kind === 'start' && input.action === 'down' && !inRow && page.row) {
      const row = active?.closest('[data-home-page]')?.querySelector<HTMLElement>('[data-home-row]')
      const remembered = entry?.memory.groups[rowGroup(page.row)]
      if (row && ((remembered && focus.focusId(remembered, row)) || focus.focusFirst(row))) return true
      return false
    }
    if (page?.kind === 'start' && input.action === 'up' && inRow) return false
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
  // The row on screen keeps its order from now on: another server's late cards join its end.
  const shown = pages[index]
  const shownRow = shown && shown.kind !== 'end' ? shown.row : undefined
  useEffect(() => { onShown?.(shownRow) }, [onShown, shownRow])

  const listings = base.listings
  const drawn = pages.map((page, i) => ({ page, i })).filter(({ i }) => Math.abs(i - index) <= 1)
  return <div className="home" ref={root}>
    <div className={`home__track${jump ? ' home__track--jump' : ''}`} style={{ transform: `translateY(calc(${-index} * 67.5rem))` }}>
      {drawn.map(({ page, i }) => {
        const peek = peekLine(pages, i, listings)
        return <section key={page.key} className="home__page" data-home-page={i} inert={i !== index} style={{ top: `calc(${i} * 67.5rem)` }}>
          <Page page={page} home={home} resume={resume} rows={rows} base={base} first={i === 0} onTop={() => goTo(0)}/>
          <Peek text={peek.text} up={peek.up}/>
        </section>
      })}
    </div>
  </div>
}

function Page({ page, home, resume, rows, base, first, onTop }: { page: HomePage; home: HomeData; resume?: ContinueItem; rows: readonly ScreenRow[]; base: Omit<RowContext, 'now'>; first: boolean; onTop(): void }) {
  switch (page.kind) {
    case 'start': return <>
      {resume ? <ResumeHero item={resume}/> : home.hero && <PickHero hero={home.hero} rows={rows}/>}
      {page.row && <StartRow row={page.row} rows={rows} base={base}/>}
    </>
    case 'row': return <RowPage row={page.row} rows={rows} base={base} autoFocus={first}/>
    case 'end': return <EndPage home={home} rows={rows} onTop={onTop}/>
  }
}

/** Loading: the hero's lines and buttons, a row of wide cards, and who it is waiting for. Focus waits on the menu meanwhile. */
function HomeSkeleton() {
  const session = useSession()
  return <div className="home home-skeleton" aria-busy="true">
    <div className="home-hero">
      <SkeletonText width="16.25rem"/>
      <Skeleton width="40rem" height="4.5rem"/>
      <Skeleton width="32.5rem" height="2rem" radius="1rem"/>
      <div className="tv-actions home-hero__actions" aria-hidden="true"><Skeleton width="13.125rem" height="var(--button-h)" radius="2.375rem"/><Skeleton width="11.875rem" height="var(--button-h)" radius="2.375rem"/></div>
      <p className="home-skeleton__wait" role="status"><span className="tv-spinner tv-spinner--big" aria-hidden="true"/>Getting Home from {session.server.name}…</p>
    </div>
    <div className="home-skeleton__rows" aria-hidden="true"><SkeletonRow variant="continue"/></div>
  </div>
}

/** Nothing to watch yet: why, Check again focused, Live TV when there is some, and Choose another server. */
function EmptyHome({ libraries, liveTV, onRetry }: { libraries: components['schemas']['Library'][] | undefined; liveTV: boolean; onRetry(): void }) {
  const session = useSession()
  const app = useApp()
  const nav = useNav()
  const words = homeEmpty(libraries, session.isAdmin)
  const address = session.isAdmin && words.setup ? session.baseUrl : undefined
  return <ProblemScreen title={words.title} extra={address && <p className="home-address">{address}</p>} actions={[
    { id: 'home/retry', label: 'Check again', onPress: onRetry },
    ...(liveTV ? [{ id: 'home/live', label: 'Live TV', onPress: () => nav.openSection({ name: 'live' }) }] : []),
    { id: 'home/servers', label: 'Choose another server', onPress: () => (app.store.list().length > 1 ? nav.servers() : app.addServer()) },
  ]}>
    {words.body}
  </ProblemScreen>
}

/**
 * Home (route `home`): the server's rows in the person's order
 * (`GET /api/home?arranged=true`), one a screen, with the hero first and
 * Edit rows last; Live TV's row where the person put it. Rows refresh as
 * the server's events invalidate Home.
 */
/**
 * Every other signed-in server's Home, for one card across servers (API
 * level 34): asked at once, each through its own client after the identity
 * check, with search's 4 s; a server below level 34 is left out without a
 * word. Its states end the merged rows: still asking, or Try again.
 */
function usePeerHomes(on: boolean) {
  const peers = usePeers()
  const asked = on ? peers : []
  const results = useQueries({
    queries: asked.map((peer) => ({
      queryKey: ['peer', peer.id, 'home', 'arranged'],
      queryFn: async ({ signal }: { signal: AbortSignal }) =>
        (await within(COPY_MS, signal, (s) => askAt34(peer, s, async (api, s2) => unwrap(await api.GET('/api/home', { params: { query: { arranged: true } }, signal: s2 }))))) ?? null,
      retry: false,
      staleTime: 30_000,
    })),
  })
  const homes: PeerHome[] = asked.flatMap((peer, i) => (results[i]?.data ? [{ server: { id: peer.id, name: peer.name }, home: results[i].data! }] : []))
  const slow = useSlow(asked.filter((_, i) => results[i]?.isPending).map((p) => serverHeading(p.name)))
  const failed = asked.flatMap((peer, i) => (results[i]?.isError ? [{ id: peer.id, name: serverHeading(peer.name) }] : []))
  const retry = (id: string) => {
    const i = asked.findIndex((p) => p.id === id)
    if (i < 0) return
    asked[i].forget()
    void results[i].refetch()
  }
  return { homes, slow, failed, retry }
}

export function HomeScreen() {
  const home = useQuery(homeQuery)
  const libraries = useQuery(librariesQuery)
  const live = useLive()
  const session = useSession()
  const libs = libraries.data?.items
  const menu = useWatchlistsOn()
  const continueMenu = useLevel34()
  const others = usePeerHomes(continueMenu)
  const { slow, failed } = others
  const base = useMemo<Omit<RowContext, 'now'>>(() => {
    const refs = libs ?? []
    return {
      libraries: refs, channels: live.channels, listings: live.listings, menu, continueMenu,
      seeAll: (target, row) => { const route = seeAllRoute(target, row, refs); return route && { kind: 'open', route } },
      // Every row filled from every server ends with who is still being asked, or who didn't answer.
      tail: (row) => !MERGED_ROWS.has(row.id) ? [] : [
        ...(slow.length ? [{ key: 'wait', variant: row.style === 'poster' ? 'poster' as const : 'wide' as const, title: '', tile: { kind: 'wait' as const, text: stillAskingHome(slow) } }] : []),
        ...failed.map((f) => ({ key: `retry-${f.id}`, variant: row.style === 'poster' ? 'poster' as const : 'wide' as const, title: '', label: notOnHome(f.name),
          tile: { kind: 'retry' as const, text: notOnHome(f.name) }, press: { kind: 'retryServer' as const, id: f.id } })),
      ],
    }
  }, [libs, live.channels, live.listings, menu, continueMenu, slow, failed])
  const own = useMemo(() => (home.data ? homeScreenRows(home.data, live.channels.length > 0) : []), [home.data, live.channels.length])
  // Rows on screen keep their order (`frozen`), so a server that answers late adds to their ends.
  const [frozen, setFrozen] = useState<ReadonlyMap<string, string[]>>(() => new Map())
  const currentId = session.server.id
  const rows = useMemo(() => mergeHomeRows(own, currentId, others.homes, frozen, (c) => {
    if (!c.tmdbId) return undefined
    return chosenCopies().get(c.kind === 'movie' ? titleKey({ kind: 'movie', tmdbId: c.tmdbId }) : titleKey({ kind: 'episode', showTmdbId: c.tmdbId, season: c.seasonNumber ?? 0, episode: c.episodeNumber ?? 0 }))
  }), [own, currentId, others.homes, frozen])
  const onShown = useCallback((row: ScreenRow | undefined) => {
    const continued = rows.find((r) => r.style === 'continue')
    const add = [row, continued].flatMap((r) => {
      if (!r || frozen.has(r.id)) return []
      const order = rowOrder(rows.find((x) => x.id === r.id) ?? r, currentId)
      return order ? [[r.id, order] as const] : []
    })
    if (add.length) setFrozen((was) => new Map([...was, ...add]))
  }, [rows, currentId, frozen])

  const resume = useMemo(() => resumeHero(rows), [rows])
  const body = (() => {
    if (home.isPending || libraries.isPending || live.pending) return <HomeSkeleton/>
    if (home.isError) {
      const words = problemWords(home.error)
      return <ProblemScreen title={`Couldn’t load Home. ${words.title}`} actions={[{ id: 'home/retry', label: 'Try again', onPress: () => void home.refetch() }]}>{words.body}</ProblemScreen>
    }
    if (homeIsEmpty(home.data, rows)) return <EmptyHome libraries={libs} liveTV={live.channels.length > 0} onRetry={() => { void home.refetch(); void libraries.refetch() }}/>
    return <CardActions onRetry={others.retry}><HomePager home={home.data} resume={resume?.item} rows={resume?.rows ?? rows} base={base} onShown={onShown}/></CardActions>
  })()
  return <>{body}<Clock/></>
}
