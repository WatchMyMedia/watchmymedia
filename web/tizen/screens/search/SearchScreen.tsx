import { useEffect, useRef, useState, useSyncExternalStore, type CSSProperties, type FocusEvent } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { liveTVCapabilitiesQuery, liveTVRemindersQuery, liveTVSearchQuery, searchQuery, useLiveReminder } from '../../../src/api/queries'
import { requestableSearchQuery, requestOptionsQuery, type Requestable } from '../../../src/api/requests'
import type { components } from '../../../src/api/schema'
import { searchTerm } from '../../../src/lib/liveReminders'
import { isOn, progress } from '../../../src/lib/liveGuide'
import { SUGGEST_DELAY_MS, SUGGEST_MIN_CHARS } from '../../../src/lib/searchSuggestions'
import { useApp, useAppState, useEntry, useFocus, useHold, useKeys, useMediaUrls, useNav, useSession, useBack } from '../../app/hooks'
import { otherServers, type PeerServer } from '../../session/peers'
import type { OpenTarget } from '../../session/session'
import { KeyHints } from '../../ui/Button'
import { Card } from '../../ui/Card'
import { Icon } from '../../ui/Icon'
import { VScroll } from '../../ui/VScroll'
import { TextField } from '../../ui/TextField'
import { problemWords } from '../../ui/problemWords'
import { placeholderTone } from '../../ui/tones'
import { RequestDialog, type RequestTarget } from '../requests/RequestDialog'
import { CrossSearch, mergeResults, resultKey, type Tagged } from './crossSearch'
import { keepSearch, keptSearch, searchScope } from './keptSearch'
import { keyRows, typeKey, type KeyboardMode, type KeyPress } from './keyboard'
import { ResultsRow } from './ResultsRow'
import { askingAgain, didNotAnswer, emptyWords, episodeSub, libraryBand, liveAction, liveLine, liveWhen, movieSub, noMatchWords, notInLibrary, requestableAction, requestableSub, requestBadge, showSub, stillAsking, suggestionChips } from './searchResults'
import './search.css'

type LiveHit = components['schemas']['LiveTVSearchHit']
type Movie = components['schemas']['Movie']
type Show = components['schemas']['Show']
type SearchEpisode = components['schemas']['SearchEpisode']

/**
 * The other servers this TV is signed in to, asked for this visit to
 * Search: each through a client of its own, never the session's. Their
 * pictures come with their own media tokens, drawn as those arrive.
 */
function useOtherServers(): { peers: PeerServer[]; cross: CrossSearch } {
  const app = useApp()
  const [made] = useState(() => {
    const peers = otherServers(app)
    return {
      peers, cross: new CrossSearch(peers),
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

function Keyboard({ text, setText, dim, onTvKeyboard }: { text: string; setText(t: string): void; dim: boolean; onTvKeyboard(): void }) {
  const [mode, setMode] = useState<KeyboardMode>('letters')
  const [shift, setShift] = useState(false)
  const press = (p: KeyPress) => setText(typeKey(text, p))
  return <div className="search-keys" aria-label="Keyboard" data-group="search/keys" style={{ opacity: dim ? .4 : 1 }}>
    {keyRows(mode, shift).map((row, r) => <div key={r} className="search-keys__row">
      {row.map((c, k) => <button key={k} type="button" data-focus={`key/${r}-${k}`} data-autofocus={(r === 1 && k === 0) || undefined} className="search-key"
        onClick={() => { press({ kind: 'char', char: c }); if (shift) setShift(false) }}>{c}</button>)}
    </div>)}
    <div className="search-keys__row">
      <button type="button" data-focus="key/shift" className="search-key search-key--fn" style={{ width: '6.5rem' }} aria-pressed={shift} disabled={mode === 'symbols'}
        onClick={() => setShift((s) => !s)}>{shift ? '⇧ ABC' : '⇧ abc'}</button>
      <button type="button" data-focus="key/mode" className="search-key search-key--fn" style={{ width: '6.5rem' }} onClick={() => { setMode((m) => m === 'letters' ? 'symbols' : 'letters'); setShift(false) }}>
        {mode === 'letters' ? '#+=' : 'abc'}</button>
      <button type="button" data-focus="key/space" className="search-key search-key--fn" style={{ width: '12.5rem' }} onClick={() => press({ kind: 'space' })}>Space</button>
      <button type="button" data-focus="key/delete" className="search-key" style={{ width: '6.5rem' }} aria-label="Delete" onClick={() => press({ kind: 'delete' })}>⌫</button>
      <button type="button" data-focus="key/clear" className="search-key search-key--fn search-key--gold" style={{ width: '10.5rem' }} onClick={() => press({ kind: 'clear' })}>Clear</button>
    </div>
    <button type="button" data-focus="key/tv" className="search-key search-key--wide" onClick={onTvKeyboard}>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M2 6h20v12H2zM6 10h.01M10 10h.01M14 10h.01M18 10h.01M7 14h10"/></svg>
      Use the TV’s keyboard
    </button>
    <p className="search-keys__note">Accents, other alphabets and the remote’s voice key come with the TV’s keyboard. Return or its Done comes back here.</p>
  </div>
}

function LiveHitCard({ hit, focusId, now, onPress, onHold }: { hit: LiveHit; focusId: string; now: number; onPress(): void; onHold(): void }) {
  const ref = useRef<HTMLButtonElement>(null)
  const media = useMediaUrls()
  useHold(ref, onHold)
  const p = hit.programme
  const on = isOn(p, now)
  const picture = media.mediaUrl(p.stillUrl ?? p.pictureUrl)
  const logo = media.mediaUrl(hit.channel.logoUrl)
  const tone = placeholderTone(p.title)
  return <button ref={ref} type="button" data-focus={focusId} className="search-live" onClick={onPress}
    aria-label={`${on ? `Watch ${hit.channel.number} ${hit.channel.name}, ` : ''}${p.title}, ${liveLine(hit)}, ${liveWhen(hit, now)}${on ? '' : '. Opens it in the guide'}`}>
    <span className="search-live__art" style={{ '--ph-a': tone[0], '--ph-b': tone[1] } as CSSProperties}>
      {picture ? <img src={picture} alt="" decoding="async" loading="lazy"/> : <span className="search-live__title">{p.title}</span>}
      <span className="search-live__top">
        {logo && <img className="search-live__logo" src={logo} alt="" decoding="async"/>}
        <b>{hit.channel.number}</b>
        {on ? <span className="tv-badge tv-badge--live search-live__mark">Live</span> : p.reminder ? <span className="search-live__bell search-live__mark"><Icon name="bell"/></span> : null}
      </span>
      {on && <span className="tv-card__progress"><span style={{ width: `${Math.round(progress(p, now))}%` }}/></span>}
    </span>
    <span className="tv-card__title">{p.title}</span>
    <span className="tv-card__sub">{liveLine(hit)}</span>
    <span className={`tv-card__sub search-live__when${on ? ' search-live__when--on' : p.reminder ? ' search-live__when--set' : ''}`}>{liveWhen(hit, now)}</span>
  </button>
}

function NotInLibraryCard({ item, focusId, onPress }: { item: Requestable; focusId: string; onPress(): void }) {
  const media = useMediaUrls()
  const badge = requestBadge(item.state)
  const image = media.imageUrl(item.posterPath, 'w342')
  const tone = placeholderTone(item.title)
  return <button type="button" data-focus={focusId} className="tv-card tv-card--poster" onClick={onPress} aria-label={`${item.title}, ${requestableSub(item)}, ${badge.label}`}>
    <span className="tv-card__art" style={{ '--ph-a': tone[0], '--ph-b': tone[1] } as CSSProperties}>
      <span className="tv-card__placeholder" aria-hidden="true"/>
      {image && <img className="tv-card__img" src={image} alt="" decoding="async" loading="lazy"/>}
      <span className={`search-nil__badge${badge.gold ? ' search-nil__badge--gold' : ''}`}>{badge.icon ? <Icon name={badge.icon}/> : <b aria-hidden="true">+</b>}{badge.label}</span>
    </span>
    <span className="tv-card__title">{item.title}</span>
    <span className="tv-card__sub">{requestableSub(item)}</span>
  </button>
}

/**
 * Search: the on-screen keyboard, with
 * Use the TV's keyboard for accents, other alphabets and the voice key, and
 * titles suggested as you type; on the right the library's shows, movies
 * and episodes (OK opens, Hold OK plays an episode), On Live TV (OK watches
 * what's on now, else opens it in the guide; Hold OK sets a reminder) and
 * Not in your library, whose Request opens the request dialog. Return from
 * the results goes back to the keyboard.
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
  const { peers, cross } = useOtherServers()
  const parts = useSyncExternalStore(cross.subscribe, cross.snapshot)
  const several = peers.length > 0
  const resultsRef = useRef<HTMLElement>(null)
  const [again, setAgain] = useState<string[]>([])
  // A server asked again that answers takes its line away: focus goes to a card, or back to the keyboard.
  const answered = again.filter((id) => parts.find((p) => p.id === id)?.state !== 'asking')
  if (answered.length) setAgain(again.filter((id) => !answered.includes(id)))
  useEffect(() => {
    if (document.activeElement !== document.body) return
    const card = resultsRef.current?.querySelector<HTMLElement>('.tv-card[data-focus]')
    if (card) focus.focus(card)
  }, [again, focus])
  // A keystroke cancels every call still out; the other servers are asked a moment after typing stops, as the current one is.
  useEffect(() => {
    cross.cancel()
    const words = text.trim()
    const timer = setTimeout(() => cross.search(words.length >= SUGGEST_MIN_CHARS ? words : ''), SUGGEST_DELAY_MS)
    return () => clearTimeout(timer)
  }, [cross, text])
  const [inResults, setInResults] = useState(false)
  const [asking, setAsking] = useState<RequestTarget>()
  const [focused, setFocused] = useState<string>()
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 30_000); return () => clearInterval(timer) }, [])

  const capabilities = useQuery(liveTVCapabilitiesQuery)
  const options = useQuery(requestOptionsQuery)
  const liveTV = !!capabilities.data?.enabled && !!capabilities.data.allowed && !!capabilities.data.guide
  const canRequest = !!options.data && (options.data.movies || options.data.shows)
  const library = useQuery({ ...searchQuery(q), enabled: searching, placeholderData: keepPreviousData })
  const term = searchTerm(q)
  const live = useQuery({ ...liveTVSearchQuery(term ?? ''), enabled: liveTV && !!term, placeholderData: keepPreviousData })
  const requestable = useQuery({ ...requestableSearchQuery(q), enabled: canRequest && searching })
  const hits = live.data?.items ?? []
  const reminders = useQuery({ ...liveTVRemindersQuery, enabled: hits.some((h) => h.programme.reminder) })
  const remind = useLiveReminder()
  const media = useMediaUrls()

  // The current server's results first; each other server's after them, as mergeResults orders them.
  const results = searching ? library.data : undefined
  const merged = mergeResults(results, searching && (library.isError || (library.isSuccess && !library.isPlaceholderData)), searching ? parts : [])
  const { shows, movies, episodes } = merged
  const nil = searching ? notInLibrary(requestable.data?.items, results) : []
  const chips = !inResults && text.trim().length >= SUGGEST_MIN_CHARS ? suggestionChips(library.data, text) : []
  const nothing = searching && merged.settled && !library.isError && !shows.length && !movies.length && !episodes.length && !hits.length && !nil.length && !requestable.isPending
  const switching = appState.screen === 'signedIn' && appState.switching?.state === 'switching' ? appState.switching : undefined

  const toKeyboard = () => {
    const last = entry?.memory.groups['search/keys']
    if (!(last && focus.focusId(last))) focus.focusId('key/1-0')
  }
  // Return from the results goes back to the keyboard, on the key left last.
  useBack(() => {
    const active = document.activeElement
    if (!active?.closest('.search-results')) return false
    toKeyboard()
    return true
  })

  // A line at the end of the results is as wide as the column, where the cards around it are not, so ▲ ▼
  // reach it (and leave it) by its place in the list rather than by where it sits.
  useKeys((i) => {
    if (i.action !== 'up' && i.action !== 'down') return false
    const active = document.activeElement
    const results = resultsRef.current
    if (!active || !results?.contains(active)) return false
    const items = [...results.querySelectorAll<HTMLElement>('[data-vitem]')].filter((el) => el.querySelector('[data-focus]'))
    const at = items.findIndex((el) => el.contains(active))
    const next = items[at + (i.action === 'down' ? 1 : -1)]
    const line = (el: HTMLElement) => !!el.querySelector('.search-tail--again')
    if (at < 0 || !next || !(line(next) || line(items[at]))) return false
    return focus.focusFirst(next)
  })

  const openTvKeyboard = () => focus.find('search/field')?.click()
  const onFocus = (e: FocusEvent) => {
    const el = e.target as HTMLElement
    setInResults(!!el.closest('.search-results'))
    setFocused(el.dataset.focus)
  }
  const liveHint = hits.find((h) => focused === `search/live/${h.programme.id}`)
  const nilHint = nil.find((r) => focused === `search/nil/${r.mediaType}-${r.tmdbId}`)
  const toggleReminder = (hit: LiveHit) => {
    if (Date.parse(hit.programme.start) <= now) return
    const set = reminders.data?.items.find((r) => r.programmeId === hit.programme.id)
    remind.mutate(set ? { reminderId: set.id } : { programmeId: hit.programme.id })
  }
  const openNil = (r: Requestable) => {
    const act = requestableAction(r)
    if (act?.kind === 'request') setAsking({ kind: r.mediaType, tmdbId: r.tmdbId, title: r.title })
    else if (act) nav.push({ name: act.kind, id: act.id })
  }
  // A result on another server: its pictures come from that server, and OK switches the app to it, then opens the title there.
  const mediaOf = (t: Tagged<unknown>) => t.server ? peers.find((p) => p.id === t.server?.id)?.media : media
  const openOn = (server: { id: string }, target: OpenTarget) => {
    const info = peers.find((p) => p.id === server.id)?.info
    void app.switchTo(server.id, { ...(info ? { info } : {}), open: { text: text.trim(), target } })
  }
  const showCard = (t: Tagged<Show>, id: string) => {
    const s = t.item
    return <Card id={id} variant="poster" title={s.title} server={t.server?.name} subtitle={showSub(s)} image={mediaOf(t)?.imageUrl(s.posterPath, 'w342')}
      onPress={() => t.server ? openOn(t.server, { kind: 'show', id: s.id }) : nav.push({ name: 'show', id: s.id })}/>
  }
  const movieCard = (t: Tagged<Movie>, id: string) => {
    const m = t.item
    return <Card id={id} variant="poster" title={m.title} server={t.server?.name} subtitle={movieSub(m)} image={mediaOf(t)?.imageUrl(m.posterPath, 'w342')}
      onPress={() => t.server ? openOn(t.server, { kind: 'movie', id: m.id }) : nav.push({ name: 'movie', id: m.id })}/>
  }
  const episodeCard = (t: Tagged<SearchEpisode>, id: string) => {
    const e = t.item
    const open = () => t.server ? openOn(t.server, { kind: 'show', id: e.showId, season: e.seasonNumber, episodeId: e.id })
      : nav.push({ name: 'show', id: e.showId, season: e.seasonNumber, episodeId: e.id })
    // Hold OK plays an episode on this server; on another it opens there first, as OK does.
    return <Card id={id} variant="episode" title={e.showTitle} server={t.server?.name} subtitle={episodeSub(e)} image={mediaOf(t)?.imageUrl(e.stillPath ?? e.showPosterPath, 'w780')}
      label={`${e.showTitle}, season ${e.seasonNumber} episode ${e.number}, ${e.title}${t.server ? `, on ${t.server.name}` : ''}`}
      onPress={open} onHold={t.server ? open : () => nav.play({ kind: 'file', fileId: e.fileId, title: e.showTitle, subtitle: episodeSub(e) })}/>
  }
  // Try again keeps its line, and focus on it, while the server is asked: it stays if the server fails again.
  const askAgain = (id: string) => {
    setAgain((ids) => ids.includes(id) ? ids : [...ids, id])
    cross.retry(id)
  }
  const lines = searching ? parts.filter((p) => p.state === 'failed' || (p.state === 'asking' && again.includes(p.id))) : []
  const waiting = merged.asking.filter((name) => !lines.some((l) => l.name === name))
  const empty = emptyWords(1 + peers.length, liveTV)
  const none = noMatchWords(q, merged.answered)
  // With several servers the groups stack and keep room for a card's server line, so one that grows moves nothing.
  const stacked = several || libraryBand(shows.length, movies.length) === 'rows'
  const room = (card: string) => several ? `calc(${card} + 5.375rem)` : undefined

  return <div className="search" onFocus={onFocus}>
    <section className="search-left">
      <h1 className="search-title">Search</h1>
      <div className="search-field">
        <Icon name="search"/>
        <TextField id="search/field" label={liveTV ? 'Titles of shows, movies, episodes or Live TV programmes' : 'Titles of shows, movies or episodes'} value={text} onChange={setText}
          placeholder={liveTV ? 'Shows, movies, episodes or Live TV' : 'Shows, movies or episodes'}/>
      </div>
      {chips.length > 0 && <div className="search-suggest" role="group" aria-label="Suggestions">
        <span className="search-suggest__label">Suggestions</span>
        <div className="search-suggest__chips" data-row data-group="search/chips">
          {chips.map((c) => <button key={c.key} type="button" data-focus={`chip/${c.key}`} className="search-chip" onClick={() => setText(c.title)}>
            {c.parts[0]}<b>{c.parts[1]}</b>{c.parts[2]}
          </button>)}
        </div>
      </div>}
      <Keyboard text={text} setText={setText} dim={inResults} onTvKeyboard={openTvKeyboard}/>
    </section>

    <section ref={resultsRef} className="search-results" aria-label="Results">
      <VScroll pad={36}>
        {!searching && <div className="search-hint" data-vitem><h2>{empty.title}</h2><p className="tv-note">{empty.body}</p></div>}
        {searching && library.isPending && <div className="search-hint" data-vitem><p className="tv-note"><span className="tv-spinner" aria-hidden="true"/> Searching…</p></div>}
        {searching && library.isError && <div className="search-hint" data-vitem role="alert"><h2>Search didn’t work</h2><p className="tv-note">{problemWords(library.error).body}</p></div>}
        {nothing && <div className="search-hint" data-vitem role="status"><h2>{none.title}</h2><p className="tv-note">{none.body}</p></div>}

        {(shows.length > 0 || movies.length > 0) && (!stacked
          ? <div className="search-band" data-row data-group="search/library" data-vitem>
            {shows.length > 0 && <div className="search-band__group"><h2 className="tv-h2">TV Shows <small>{shows.length}</small></h2>
              <div className="search-band__cards">{shows.map((t) => <div key={resultKey(t)}>{showCard(t, `search/library/show-${resultKey(t)}`)}</div>)}</div></div>}
            {movies.length > 0 && <div className="search-band__group"><h2 className="tv-h2">Movies <small>{movies.length}</small></h2>
              <div className="search-band__cards">{movies.map((t) => <div key={resultKey(t)}>{movieCard(t, `search/library/movie-${resultKey(t)}`)}</div>)}</div></div>}
          </div>
          : <>
            {shows.length > 0 && <ResultsRow id="search/shows" title="TV Shows" count={shows.length} items={shows} itemKey={resultKey} cell="var(--poster-w)" visible={4} renderItem={showCard} room={room('var(--poster-h)')}/>}
            {movies.length > 0 && <ResultsRow id="search/movies" title="Movies" count={movies.length} items={movies} itemKey={resultKey} cell="var(--poster-w)" visible={4} renderItem={movieCard} room={room('var(--poster-h)')}/>}
          </>)}

        {episodes.length > 0 && <ResultsRow id="search/episodes" title="Episodes" count={episodes.length} items={episodes} itemKey={resultKey} cell="var(--search-wide-w)" visible={3}
          renderItem={episodeCard} room={room('10.40625rem')}/>}

        {waiting.length > 0 && <p role="status" className="search-tail" data-vitem><span className="tv-spinner" aria-hidden="true"/>{stillAsking(waiting)}</p>}
        {/* The whole line takes focus, so ▼ from any card reaches it; the ring is drawn on Try again. */}
        {lines.map((f) => <div key={f.id} role="status" data-vitem>
          {f.state === 'failed'
            ? <button type="button" data-focus={`search/again/${f.id}`} className="search-tail search-tail--again" onClick={() => askAgain(f.id)}>
              <Icon name="alert"/>
              <span>{didNotAnswer(f.name)}</span>
              <span className="search-tail__again">Try again</span>
            </button>
            : <button type="button" data-focus={`search/again/${f.id}`} className="search-tail search-tail--again" aria-busy="true">
              <span className="tv-spinner" aria-hidden="true"/>
              <span>{askingAgain(f.name)}</span>
            </button>}
        </div>)}
        {switching && <p role="status" className="search-tail" data-vitem><span className="tv-spinner" aria-hidden="true"/>Switching to {switching.name}…</p>}

        {liveTV && hits.length > 0 && <ResultsRow id="search/live" title="On Live TV" count={hits.length} items={hits} itemKey={(h) => h.programme.id} cell="var(--search-wide-w)" visible={3}
          renderItem={(h, id) => <LiveHitCard hit={h} focusId={id} now={now} onHold={() => toggleReminder(h)} onPress={() => {
            const act = liveAction(h, now)
            if (act.kind === 'watch') nav.play({ kind: 'live', channelId: act.channelId })
            else nav.push({ name: 'liveGuide', channelId: act.channelId, at: act.at })
          }}/>}
          foot={liveHint && <KeyLine title={liveHint.programme.title} hints={liveAction(liveHint, now).kind === 'watch' ? [['OK', `Watch ${liveHint.channel.number}`]]
            : [['OK', 'Open it in the guide'], ...(Date.parse(liveHint.programme.start) > now ? [['Hold OK', liveHint.programme.reminder ? 'Remove the reminder' : 'Remind me'] as [string, string]] : [])]}/>}/>}

        {nil.length > 0 && <ResultsRow id="search/nil" title="Not in your library" count={options.data?.backend === 'seerr' ? 'From Seerr' : nil.length} items={nil} itemKey={(r) => `${r.mediaType}-${r.tmdbId}`}
          cell="var(--poster-w)" visible={4} renderItem={(r, id) => <NotInLibraryCard item={r} focusId={id} onPress={() => openNil(r)}/>}
          foot={nilHint && requestableAction(nilHint) && <KeyLine title={nilHint.title} hints={[['OK', requestableAction(nilHint)?.kind === 'request' ? nilHint.mediaType === 'tv' ? 'Choose seasons and request' : 'Request it' : 'Open it']]}/>}/>}
      </VScroll>
    </section>

    {!inResults && <div className="search-foot"><KeyHints hints={[{ key: '▲', label: 'From the top row: suggestions, then the search box' }, { key: '▼', label: 'From the bottom row: the TV’s keyboard' }]}/></div>}
    {asking && <RequestDialog target={asking} onClose={() => setAsking(undefined)}/>}
  </div>
}

/** The focused result's keys, under its row: "Station 9 · OK Open it in the guide · Hold OK Remind me". */
function KeyLine({ title, hints }: { title: string; hints: [string, string][] }) {
  return <p className="search-keyline" aria-hidden="true">
    <b>{title}</b><span className="search-keyline__dot">·</span>
    {hints.map(([key, label]) => <span key={key} className="search-keyline__hint"><span className="tv-kbd">{key}</span>{label}</span>)}
  </p>
}
