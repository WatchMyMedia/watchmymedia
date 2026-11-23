import { useEffect, useRef, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { homeQuery, liveTVCapabilitiesQuery, liveTVRemindersQuery, liveTVSearchQuery, searchQuery, useLiveReminder } from '../../../src/api/queries'
import { requestableSearchQuery, requestOptionsQuery, type Requestable } from '../../../src/api/requests'
import type { components } from '../../../src/api/schema'
import { searchTerm } from '../../../src/lib/liveReminders'
import { isOn, progress } from '../../../src/lib/liveGuide'
import { SUGGEST_DELAY_MS, SUGGEST_MIN_CHARS } from '../../../src/lib/searchSuggestions'
import { nearest } from '../../app/geometry'
import { useApp, useAppState, useBack, useEntry, useFocus, useHold, useKeys, useMediaUrls, useNav, useSession } from '../../app/hooks'
import { leftOutReason, otherServers, serversLeftOut, type LeftOut, type PeerServer } from '../../session/peers'
import type { OpenTarget } from '../../session/session'
import { Button } from '../../ui/Button'
import { Card } from '../../ui/Card'
import { Icon } from '../../ui/Icon'
import { VScroll } from '../../ui/VScroll'
import { TextField } from '../../ui/TextField'
import { problemWords } from '../../ui/problemWords'
import { placeholderTone } from '../../ui/tones'
import { RequestDialog, type RequestTarget } from '../requests/RequestDialog'
import { CrossSearch, CURRENT, mergeResults, noneAnswered, resultKey, searchLines, SERVER_MS, type Tagged } from './crossSearch'
import { keepSearch, keptSearch, searchScope } from './keptSearch'
import { Keyboard } from './OnScreenKeyboard'
import { readRecent, recentScope, rememberSearch } from './recentSearches'
import { ResultsRow } from './ResultsRow'
import {
  askButton, askingAgain, ASK_LINE, bestMatch, continueItems, didNotAnswer, emptyWords, episodeSub, kindLine, leftOutLine, liveAction, liveLine, liveWhen, noMatchWords,
  notInLibrary, placeIn, requestableAction, requestableSub, requestBadge, stillAsking,
} from './searchResults'
import './search.css'

type LiveHit = components['schemas']['LiveTVSearchHit']
type Movie = components['schemas']['Movie']
type Show = components['schemas']['Show']
type SearchEpisode = components['schemas']['SearchEpisode']
/** A library title in the results, a show or a movie, with the server it is on. */
type Title = { kind: 'show'; tagged: Tagged<Show> } | { kind: 'movie'; tagged: Tagged<Movie> }

/** The keyboard's bottom row: ▼ stops there. */
const BOTTOM_KEYS = new Set(['key/space', 'key/delete', 'key/clear', 'key/tv'])

/**
 * The other servers this TV is signed in to, asked for this visit to
 * Search: each through a client of its own, never the session's. Their
 * pictures come with their own media tokens, drawn as those arrive.
 */
function useOtherServers(): { peers: PeerServer[]; cross: CrossSearch; leftOut: LeftOut[] } {
  const app = useApp()
  const [made] = useState(() => {
    const peers = otherServers(app)
    return {
      peers, cross: new CrossSearch(peers, leftOutReason),
      // The servers a search doesn't ask get a line each, but only beside servers that are asked.
      leftOut: peers.length ? serversLeftOut(app) : [],
      subscribe: (listener: () => void) => { const offs = peers.map((p) => p.tokens.subscribe(listener)); return () => offs.forEach((off) => off()) },
      version: () => peers.reduce((n, p) => n + p.tokens.snapshot(), 0),
    }
  })
  useSyncExternalStore(made.subscribe, made.version)
  useEffect(() => () => {
    made.cross.stop()
    for (const p of made.peers) p.stop()
  }, [made])
  return made
}

/** The typed text, the query asked a moment after typing stops. */
function useDebounced(text: string): string {
  const [q, setQ] = useState(text)
  useEffect(() => {
    const timer = setTimeout(() => setQ(text), SUGGEST_DELAY_MS)
    return () => clearTimeout(timer)
  }, [text])
  return q
}

function tone(title: string): CSSProperties {
  const [a, b] = placeholderTone(title)
  return { '--ph-a': a, '--ph-b': b } as CSSProperties
}

function LiveHitCard({ hit, focusId, now, onPress, onHold }: { hit: LiveHit; focusId: string; now: number; onPress(): void; onHold(): void }) {
  const ref = useRef<HTMLButtonElement>(null)
  const media = useMediaUrls()
  useHold(ref, onHold)
  const p = hit.programme
  const on = isOn(p, now)
  const picture = media.mediaUrl(p.stillUrl ?? p.pictureUrl)
  return <button ref={ref} type="button" data-focus={focusId} className="search-live" onClick={onPress}
    aria-label={`${on ? `Watch ${hit.channel.number} ${hit.channel.name}, ` : ''}${p.title}, ${liveLine(hit)}, ${liveWhen(hit, now)}${on ? '' : '. Opens it in the guide'}`}>
    <span className="search-live__art" style={tone(p.title)}>
      {picture ? <img src={picture} alt="" decoding="async" loading="lazy"/> : <span className="search-live__title">{p.title}</span>}
      <span className="search-live__chan">{hit.channel.number}</span>
      {on ? <span className="tv-badge tv-badge--live search-live__mark">Live</span> : p.reminder ? <span className="search-live__bell search-live__mark"><Icon name="bell"/></span> : null}
      {on && <span className="tv-card__progress"><span style={{ width: `${Math.round(progress(p, now))}%` }}/></span>}
    </span>
    <span className="tv-card__title">{p.title}</span>
    <span className={`tv-card__sub${p.reminder && !on ? ' search-live__when--set' : ''}`}>{liveWhen(hit, now)}</span>
  </button>
}

function NotInLibraryCard({ item, focusId, onPress }: { item: Requestable; focusId: string; onPress(): void }) {
  const media = useMediaUrls()
  const badge = requestBadge(item.state)
  const image = media.imageUrl(item.posterPath, 'w342')
  return <button type="button" data-focus={focusId} className="tv-card tv-card--poster" onClick={onPress} aria-label={`${item.title}, ${requestableSub(item)}, ${badge.label}`}>
    <span className="tv-card__art" style={tone(item.title)}>
      <span className="tv-card__placeholder" aria-hidden="true"/>
      {image && <img className="tv-card__img" src={image} alt="" decoding="async" loading="lazy"/>}
      <span className={`search-nil__badge${badge.gold ? ' search-nil__badge--gold' : ''}`}>{badge.icon && <Icon name={badge.icon}/>}{badge.label}</span>
    </span>
    <span className="tv-card__title">{item.title}</span>
    <span className="tv-card__sub">{requestableSub(item)}</span>
  </button>
}

/** The library's best match: its picture with your place in it, what it is, and Resume (when you are partway) and Open. */
function BestMatch({ title, line, place, progressOf, image, onResume, onOpen }: {
  title: string; line: string; place?: string; progressOf?: number; image?: string; onResume?: () => void; onOpen(): void
}) {
  return <section className="search-best" data-vitem aria-label="Best match">
    <h2 className="search-label">Best match</h2>
    <div className="search-best__body">
      <span className="search-best__pic" style={tone(title)} aria-hidden="true">
        <span className="tv-card__placeholder"/>
        {image && <img className="tv-card__img" src={image} alt="" decoding="async"/>}
        {progressOf !== undefined && <span className="tv-card__progress"><span style={{ width: `${Math.round(progressOf * 100)}%` }}/></span>}
      </span>
      <div className="search-best__text">
        <h3 className="tv-head tv-cut">{title}</h3>
        <p className="search-best__line">{line}</p>
        {place && <p className="search-best__place">{place}</p>}
        <div className="tv-actions search-best__actions" data-edge>
          {onResume && <Button id="search/best/resume" variant="primary" icon="play" onPress={onResume}>Resume</Button>}
          <Button id="search/best/open" variant={onResume ? 'ghost' : 'primary'} onPress={onOpen}>Open</Button>
        </div>
      </div>
    </div>
  </section>
}

/** Not in your library's first title, when the library had nothing: its poster, what it is, why asking helps and Ask for it. */
function AskFeature({ item, onPress }: { item: Requestable; onPress(): void }) {
  const media = useMediaUrls()
  const image = media.imageUrl(item.posterPath, 'w342')
  const button = askButton(item)
  return <div className="search-ask">
    <span className="search-ask__pic" style={tone(item.title)} aria-hidden="true">
      <span className="tv-card__placeholder"/>
      {image && <img className="tv-card__img" src={image} alt="" decoding="async"/>}
    </span>
    <div className="search-ask__text">
      <h3 className="tv-head">{item.title}</h3>
      <p className="search-best__line">{requestableSub(item)}</p>
      {item.state === 'requestable' && <p className="search-best__line search-ask__why">{ASK_LINE}</p>}
      <div className="tv-actions search-best__actions">
        <Button id={`search/ask/${item.mediaType}-${item.tmdbId}`} variant="primary" disabled={!!button.why} why={button.why} onPress={onPress}>{button.label}</Button>
      </div>
    </div>
  </div>
}

/**
 * Search: the box, the last three searches (Search again) and an
 * on-screen keyboard on the left, with the TV's own keyboard (accents,
 * other alphabets and the voice key) one key away; on the right, the best
 * match with your place in it and Resume, then In your library, Episodes,
 * On live TV (OK watches what is on now, else opens it in the guide; Hold
 * OK sets a reminder, as a shortcut) and, set apart at the end, Not in your
 * library with Ask for it. Titles on this TV's other servers come after
 * this one's, each with its server's name. ◀ from a result's first card
 * and Return go back to the keyboard, on the key used last.
 */
export function SearchScreen() {
  const app = useApp()
  const appState = useAppState()
  const session = useSession()
  const entry = useEntry()
  const focus = useFocus()
  const nav = useNav()
  const scope = searchScope(session.serial, entry?.key)
  const [text, setTextState] = useState(() => keptSearch(scope))
  const setText = (t: string) => { keepSearch(scope, t); setTextState(t) }
  const q = useDebounced(text).trim()
  const searching = q.length >= SUGGEST_MIN_CHARS
  const { peers, cross, leftOut } = useOtherServers()
  const parts = useSyncExternalStore(cross.subscribe, cross.snapshot)
  const several = peers.length > 0
  const resultsRef = useRef<HTMLElement>(null)
  const [again, setAgain] = useState<string[]>([])
  const [late, setLate] = useState<string>()
  const [retrying, setRetrying] = useState(false)
  const recentKey = recentScope(session.server.id, session.account.userId)
  const [recent, setRecent] = useState(() => readRecent(storage(), recentKey))
  // A server asked again that answers takes its line away, and focus with it: focus goes to a card.
  const answered = again.filter((id) => parts.find((p) => p.id === id)?.state !== 'asking')
  if (answered.length) setAgain(again.filter((id) => !answered.includes(id)))
  useEffect(() => {
    if (document.activeElement !== document.body) return
    const card = resultsRef.current?.querySelector<HTMLElement>('[data-focus]')
    if (card) focus.focus(card)
  }, [again, retrying, focus])
  // A keystroke cancels every call still out; the other servers are asked a moment after typing stops, as the current one is.
  useEffect(() => {
    cross.cancel()
    const words = text.trim()
    const timer = setTimeout(() => cross.search(words.length >= SUGGEST_MIN_CHARS ? words : ''), SUGGEST_DELAY_MS)
    return () => clearTimeout(timer)
  }, [cross, text])
  const [asking, setAsking] = useState<RequestTarget>()
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 30_000); return () => clearInterval(timer) }, [])

  const capabilities = useQuery(liveTVCapabilitiesQuery)
  const options = useQuery(requestOptionsQuery)
  const home = useQuery(homeQuery)
  const liveTV = !!capabilities.data?.enabled && !!capabilities.data.allowed && !!capabilities.data.guide
  const canRequest = !!options.data && (options.data.movies || options.data.shows)
  const library = useQuery({ ...searchQuery(q), enabled: searching, placeholderData: keepPreviousData })
  const term = searchTerm(q)
  const live = useQuery({ ...liveTVSearchQuery(term ?? ''), enabled: liveTV && !!term, placeholderData: keepPreviousData })
  const requestable = useQuery({ ...requestableSearchQuery(q), enabled: canRequest && searching })
  const hits = searching ? live.data?.items ?? [] : []
  const reminders = useQuery({ ...liveTVRemindersQuery, enabled: hits.some((h) => h.programme.reminder) })
  const remind = useLiveReminder()
  const media = useMediaUrls()

  // With other servers to list, the current one gets the same few seconds they do: past that, or when it fails,
  // its titles aren't listed for these words (its late answer is dropped, so nothing moves) and the others' are.
  const currentIn = searching && library.isSuccess && !library.isPlaceholderData
  const fetching = library.isFetching
  const queryClient = session.queryClient
  useEffect(() => {
    if (!several || !searching || currentIn || !fetching) return
    const timer = setTimeout(() => {
      setLate(q)
      void queryClient.cancelQueries({ queryKey: searchQuery(q).queryKey })
    }, SERVER_MS)
    return () => clearTimeout(timer)
  }, [several, searching, q, currentIn, fetching, queryClient])
  const currentFailed = several && searching && !currentIn && (library.isError || late === q)
  const allFailed = noneAnswered(currentFailed, parts)

  // The current server's results first; each other server's after them, as mergeResults orders them.
  const results = searching && !currentFailed ? library.data : undefined
  const merged = mergeResults(results, searching && (currentIn || currentFailed || library.isError), searching ? parts : [])
  const { shows, movies, episodes } = merged
  const best = searching ? bestMatch(shows, movies, q) : undefined
  const place = placeIn(best, continueItems(home.data))
  // In your library: each server's shows and movies together, servers in the order they answered, so a card on screen never moves.
  const titles: Title[] = []
  for (const server of [undefined, ...new Set([...shows, ...movies].map((t) => t.server?.id).filter(Boolean))]) {
    for (const t of shows) if (t.server?.id === server && !(best?.kind === 'show' && !t.server && best.item.id === t.item.id)) titles.push({ kind: 'show', tagged: t })
    for (const t of movies) if (t.server?.id === server && !(best?.kind === 'movie' && !t.server && best.item.id === t.item.id)) titles.push({ kind: 'movie', tagged: t })
  }
  const nil = searching ? notInLibrary(requestable.data?.items, results) : []
  // With one server it says so as it always has; with several, only once each has answered for these words.
  const nothing = searching && (several ? merged.settled && !currentFailed : library.isSuccess) && !library.isError && !shows.length && !movies.length && !episodes.length && !hits.length && !requestable.isPending
  const switching = appState.screen === 'signedIn' && appState.switching?.state === 'switching' ? appState.switching : undefined

  /** Using a result keeps the words for Search again. */
  const used = () => { if (q) setRecent(rememberSearch(storage(), recentKey, q)) }
  const toKeyboard = () => {
    const last = entry?.memory.groups['search/keys']
    if (!(last && focus.focusId(last))) focus.focusId('key/1-0')
  }
  // Return from the results goes back to the keyboard, on the key left last.
  useBack(() => {
    const active = document.activeElement
    if (!resultsRef.current?.contains(active)) return false
    toKeyboard()
    return true
  })

  useKeys((i) => {
    const active = document.activeElement as HTMLElement | null
    if (!active) return false
    // ▼ from the keyboard's bottom row stops there.
    if (i.action === 'down' && BOTTOM_KEYS.has(active.dataset.focus ?? '')) return true
    const results = resultsRef.current
    // ▶ from the box goes to the results, when there are any.
    if (i.action === 'right' && active.dataset.focus === 'search/field') return focus.focusFirst(results) || true
    if (!results?.contains(active)) return false
    // ◀ from a result with nothing before it goes back to the keyboard, on the key used last.
    if (i.action === 'left') {
      const row = active.closest<HTMLElement>('[data-row]')
      const cards = row ? [...row.querySelectorAll<HTMLElement>('[data-focus]')] : []
      const edge = active.closest<HTMLElement>('[data-edge]')
      if ((row && cards[0] === active) || (edge && (edge === active || edge.querySelector('[data-focus]') === active))) { toKeyboard(); return true }
      return false
    }
    if (i.action !== 'up' && i.action !== 'down') return false
    // ▲ ▼ go group by group through the results: within a group first (Not in your library has a title and a row),
    // then to the group above or below, on the card it had last; past the first group or the last, they stop.
    // By the groups' order rather than by where things sit, so a line as wide as the column is reached like a row.
    const groups = [...results.querySelectorAll<HTMLElement>('[data-vitem]')]
      .filter((el) => !el.parentElement?.closest('[data-vitem]') && el.querySelector('[data-focus]'))
    const at = groups.findIndex((el) => el.contains(active))
    if (at < 0) return false
    const here = [...groups[at].querySelectorAll<HTMLElement>('[data-focus]')].filter((el) => el !== active && el.getClientRects().length > 0)
    const inside = nearest(active.getBoundingClientRect(), here.map((el) => el.getBoundingClientRect()), i.action)
    if (inside >= 0) { focus.focus(here[inside]); return true }
    const next = groups[at + (i.action === 'down' ? 1 : -1)]
    if (!next) return true
    const group = next.matches('[data-group]') ? next : next.querySelector<HTMLElement>('[data-group]')
    const remembered = group?.dataset.group ? entry?.memory.groups[group.dataset.group] : undefined
    if (remembered && focus.focusId(remembered, next)) return true
    return focus.focusFirst(next)
  })

  const openTvKeyboard = () => focus.find('search/field')?.click()
  const toggleReminder = (hit: LiveHit) => {
    if (Date.parse(hit.programme.start) <= now) return
    const set = reminders.data?.items.find((r) => r.programmeId === hit.programme.id)
    remind.mutate(set ? { reminderId: set.id } : { programmeId: hit.programme.id })
  }
  const openNil = (r: Requestable) => {
    const act = requestableAction(r)
    used()
    if (act?.kind === 'request') setAsking({ kind: r.mediaType, tmdbId: r.tmdbId, title: r.title })
    else if (act) nav.push({ name: act.kind, id: act.id })
  }
  // A result on another server: its pictures come from that server, and OK switches the app to it, then opens the title there.
  const mediaOf = (t: Tagged<unknown>) => t.server ? peers.find((p) => p.id === t.server?.id)?.media : media
  const openOn = (server: { id: string }, target: OpenTarget) => {
    const info = peers.find((p) => p.id === server.id)?.info
    used()
    void app.switchTo(server.id, { ...(info ? { info } : {}), open: { text: text.trim(), target } })
  }
  const openTitle = (t: Title) => {
    const target: OpenTarget = t.kind === 'show' ? { kind: 'show', id: t.tagged.item.id } : { kind: 'movie', id: t.tagged.item.id }
    if (t.tagged.server) { openOn(t.tagged.server, target); return }
    used()
    nav.push(t.kind === 'show' ? { name: 'show', id: t.tagged.item.id } : { name: 'movie', id: t.tagged.item.id })
  }
  const titleCard = (t: Title, id: string) => {
    const item = t.tagged.item
    return <Card id={id} variant="poster" title={item.title} server={t.tagged.server?.name} subtitle={kindLine(t.kind, t.kind === 'show' ? { year: item.year, episodeCount: (item as Show).episodeCount } : { year: item.year })}
      image={mediaOf(t.tagged)?.imageUrl(item.posterPath, 'w342')} onPress={() => openTitle(t)}/>
  }
  const episodeCard = (t: Tagged<SearchEpisode>, id: string) => {
    const e = t.item
    const open = () => {
      if (t.server) { openOn(t.server, { kind: 'show', id: e.showId, season: e.seasonNumber, episodeId: e.id }); return }
      used()
      nav.push({ name: 'show', id: e.showId, season: e.seasonNumber, episodeId: e.id })
    }
    // Hold OK plays an episode on this server, as a shortcut; on another it opens there first, as OK does.
    return <Card id={id} variant="episode" title={e.showTitle} server={t.server?.name} subtitle={episodeSub(e)} image={mediaOf(t)?.imageUrl(e.stillPath ?? e.showPosterPath, 'w780')}
      label={`${e.showTitle}, season ${e.seasonNumber} episode ${e.number}, ${e.title}${t.server ? `, on ${t.server.name}` : ''}`}
      onPress={open} onHold={t.server ? open : () => { used(); nav.play({ kind: 'file', fileId: e.fileId, title: e.showTitle, subtitle: episodeSub(e) }) }}/>
  }
  // Try again keeps its line, and focus on it, while the server is asked: it stays if the server fails again.
  const askAgain = (id: string) => {
    if (id !== CURRENT) {
      setAgain((ids) => ids.includes(id) ? ids : [...ids, id])
      cross.retry(id)
      return
    }
    setRetrying(true)
    void library.refetch().then((r) => { if (r.isSuccess) setLate(undefined) }).finally(() => setRetrying(false))
  }
  const lines = searching && several
    ? searchLines({ current: { name: session.server.name, failed: currentFailed, again: retrying }, parts, again, leftOut })
    : []
  const waiting = merged.asking.filter((name) => !lines.some((l) => l.name === name))
  const empty = emptyWords(session.server.name, 1 + peers.length, liveTV)
  const none = noMatchWords(q, merged.answered, liveTV)
  // With several servers the rows keep room for a card's server line, so one that grows moves nothing.
  const room = (card: string) => several ? `calc(${card} + 4.5rem)` : undefined
  const bestItem = best?.item
  const feature = nothing && nil.length > 0 ? nil[0] : undefined
  const moreNil = feature ? nil.slice(1) : nil

  return <div className="search">
    <section className="search-left" aria-label="Type a search">
      <div className="search-field">
        <Icon name="search"/>
        <TextField id="search/field" label={liveTV ? 'Search movies, shows and live TV' : 'Search movies and shows'} value={text} onChange={setText}
          placeholder={liveTV ? 'Search movies, shows and live TV' : 'Search movies and shows'}/>
      </div>
      {!text.trim() && recent.length > 0 && <div className="search-again" role="group" aria-label="Search again">
        <p className="search-label">Search again</p>
        <div className="search-again__row" data-group="search/again">
          {recent.map((r, i) => <button key={r} type="button" data-focus={`again/${i}`} className="tv-btn tv-btn--ghost search-again__chip" onClick={() => setText(r)}>{r}</button>)}
        </div>
      </div>}
      <Keyboard group="search/keys" layout="search" text={text} setText={setText} onTvKeyboard={openTvKeyboard}
        note="TV keyboard opens the TV’s own, which also takes your voice."/>
    </section>

    <section ref={resultsRef} className="search-results" aria-label="Results">
      <VScroll pad={36}>
        {!searching && <div className="search-intro" data-vitem><h1 className="tv-title">{empty.title}</h1><p>{empty.body}</p><p>Results appear here as you type.</p></div>}
        {searching && library.isPending && !currentFailed && <div className="search-intro" data-vitem><p className="search-status"><span className="tv-spinner" aria-hidden="true"/>Searching…</p></div>}
        {searching && (several ? allFailed : library.isError) && <div className="search-intro" data-vitem role="alert"><h2 className="tv-head">Search didn’t work</h2>
          <p>{problemWords(library.error ?? new DOMException('The server took too long', 'TimeoutError')).body}</p></div>}
        {nothing && <div className="search-none" data-vitem role="status"><h2 className="tv-head">{none.title}</h2>{none.body && <p>{none.body}</p>}</div>}

        {best && bestItem && <BestMatch title={bestItem.title}
          line={kindLine(best.kind, best.kind === 'show' ? { year: bestItem.year, episodeCount: (bestItem as Show).episodeCount } : { year: bestItem.year })}
          place={place?.line} progressOf={place && place.item.durationMs ? place.item.positionMs / place.item.durationMs : undefined}
          image={media.imageUrl(place?.item.stillPath ?? bestItem.backdropPath, 'w780')}
          onResume={place ? () => { used(); nav.play({ kind: 'file', fileId: place.item.fileId, startMs: place.item.positionMs, title: place.item.title, subtitle: place.item.subtitle, backdropPath: place.item.backdropPath ?? place.item.stillPath }) } : undefined}
          onOpen={() => openTitle(best.kind === 'show' ? { kind: 'show', tagged: { item: best.item as Show } } : { kind: 'movie', tagged: { item: best.item as Movie } })}/>}

        {titles.length > 0 && <ResultsRow id="search/library" title="In your library" items={titles} itemKey={(t) => `${t.kind}-${resultKey(t.tagged)}`} cell="var(--poster-w)" visible={4}
          renderItem={titleCard} room={room('var(--poster-h)')}/>}

        {episodes.length > 0 && <ResultsRow id="search/episodes" title="Episodes" items={episodes} itemKey={resultKey} cell="var(--wide-w)" visible={2}
          renderItem={episodeCard} room={room('var(--wide-h)')}/>}

        {waiting.length > 0 && <p role="status" className="search-tail" data-vitem><span className="tv-spinner" aria-hidden="true"/>{stillAsking(waiting)}</p>}
        {/* The whole line takes focus, so ▼ from any card reaches it; the ring is drawn on Try again. */}
        {lines.map((f) => <div key={f.id} role="status" data-vitem>
          {f.kind === 'failed'
            ? <button type="button" data-focus={`search/again/${f.id}`} data-edge className="search-tail search-tail--again" onClick={() => askAgain(f.id)}>
              <Icon name="alert"/>
              <span>{didNotAnswer(f.name)}</span>
              <span className="search-tail__again">Try again</span>
            </button>
            : f.kind === 'asking'
              ? <button type="button" data-focus={`search/again/${f.id}`} data-edge className="search-tail search-tail--again" aria-busy="true">
                <span className="tv-spinner" aria-hidden="true"/>
                <span>{askingAgain(f.name)}</span>
              </button>
              : <p className="search-tail"><Icon name="alert"/><span>{leftOutLine(f.name, f.kind)}</span></p>}
        </div>)}
        {switching && <p role="status" className="search-tail" data-vitem><span className="tv-spinner" aria-hidden="true"/>Switching to {switching.name}…</p>}

        {liveTV && hits.length > 0 && <ResultsRow id="search/live" title="On live TV" items={hits} itemKey={(h) => h.programme.id} cell="var(--wide-w)" visible={2}
          renderItem={(h, id) => <LiveHitCard hit={h} focusId={id} now={now} onHold={() => toggleReminder(h)} onPress={() => {
            const act = liveAction(h, now)
            used()
            if (act.kind === 'watch') nav.play({ kind: 'live', channelId: act.channelId })
            else nav.push({ name: 'liveGuide', channelId: act.channelId, at: act.at })
          }}/>}/>}

        {nil.length > 0 && <section className="search-nil" data-vitem aria-label="Not in your library">
          {feature && <>
            <h2 className="search-label">Not in your library</h2>
            <div data-edge><AskFeature item={feature} onPress={() => openNil(feature)}/></div>
          </>}
          {moreNil.length > 0 && <ResultsRow id="search/nil" title={feature ? 'More you can ask for' : 'Not in your library'} count={options.data?.backend === 'seerr' ? 'From Seerr' : undefined}
            items={moreNil} itemKey={(r) => `${r.mediaType}-${r.tmdbId}`} cell="var(--poster-w)" visible={4}
            renderItem={(r, id) => <NotInLibraryCard item={r} focusId={id} onPress={() => openNil(r)}/>}/>}
        </section>}
      </VScroll>
    </section>

    {asking && <RequestDialog target={asking} onClose={() => setAsking(undefined)}/>}
  </div>
}

/** The TV's storage, where Search again is kept; none where it can't be read. */
function storage(): Storage | undefined {
  try { return window.localStorage } catch { return undefined }
}
