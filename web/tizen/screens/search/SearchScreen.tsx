import { useEffect, useRef, useState, type CSSProperties, type FocusEvent } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { liveTVCapabilitiesQuery, liveTVRemindersQuery, liveTVSearchQuery, searchQuery, useLiveReminder } from '../../../src/api/queries'
import { requestableSearchQuery, requestOptionsQuery, type Requestable } from '../../../src/api/requests'
import type { components } from '../../../src/api/schema'
import { searchTerm } from '../../../src/lib/liveReminders'
import { isOn, progress } from '../../../src/lib/liveGuide'
import { SUGGEST_DELAY_MS, SUGGEST_MIN_CHARS } from '../../../src/lib/searchSuggestions'
import { useEntry, useFocus, useHold, useMediaUrls, useNav, useSession, useBack } from '../../app/hooks'
import { KeyHints } from '../../ui/Button'
import { Card } from '../../ui/Card'
import { Icon } from '../../ui/Icon'
import { VScroll } from '../../ui/VScroll'
import { TextField } from '../../ui/TextField'
import { problemWords } from '../../ui/problemWords'
import { placeholderTone } from '../../ui/tones'
import { RequestDialog, type RequestTarget } from '../requests/RequestDialog'
import { keyRows, typeKey, type KeyboardMode, type KeyPress } from './keyboard'
import { ResultsRow } from './ResultsRow'
import { episodeSub, libraryBand, liveAction, liveLine, liveWhen, movieSub, notInLibrary, requestableAction, requestableSub, requestBadge, showSub, suggestionChips } from './searchResults'
import './search.css'

type LiveHit = components['schemas']['LiveTVSearchHit']
type Movie = components['schemas']['Movie']
type Show = components['schemas']['Show']

/** What was typed, kept for this Search while pages open on top of it, so coming back finds the same results. */
let kept: { scope: string; text: string } | undefined

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
 * Search (Search on the Samsung TV canvas): the on-screen keyboard, with
 * Use the TV's keyboard for accents, other alphabets and the voice key, and
 * titles suggested as you type; on the right the library's shows, movies
 * and episodes (OK opens, Hold OK plays an episode), On Live TV (OK watches
 * what's on now, else opens it in the guide; Hold OK sets a reminder) and
 * Not in your library, whose Request opens the request dialog. Return from
 * the results goes back to the keyboard.
 */
export function SearchScreen() {
  const session = useSession()
  const entry = useEntry()
  const focus = useFocus()
  const nav = useNav()
  const scope = `${session.serial}/${entry?.key}`
  const [text, setTextState] = useState(() => kept?.scope === scope ? kept.text : '')
  const setText = (t: string) => { kept = { scope, text: t }; setTextState(t) }
  const q = useDebounced(text).trim()
  const searching = q.length >= SUGGEST_MIN_CHARS
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

  const results = searching ? library.data : undefined
  const shows = results?.shows ?? []
  const movies = results?.movies ?? []
  const episodes = results?.episodes ?? []
  const nil = searching ? notInLibrary(requestable.data?.items, results) : []
  const chips = !inResults && text.trim().length >= SUGGEST_MIN_CHARS ? suggestionChips(library.data, text) : []
  const nothing = searching && library.isSuccess && !shows.length && !movies.length && !episodes.length && !hits.length && !nil.length && !requestable.isPending

  // Return from the results goes back to the keyboard, on the key left last.
  useBack(() => {
    const active = document.activeElement
    if (!active?.closest('.search-results')) return false
    const last = entry?.memory.groups['search/keys']
    if (!(last && focus.focusId(last))) focus.focusId('key/1-0')
    return true
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
  const showCard = (s: Show, id: string) => <Card id={id} variant="poster" title={s.title} subtitle={showSub(s)} image={media.imageUrl(s.posterPath, 'w342')} onPress={() => nav.push({ name: 'show', id: s.id })}/>
  const movieCard = (m: Movie, id: string) => <Card id={id} variant="poster" title={m.title} subtitle={movieSub(m)} image={media.imageUrl(m.posterPath, 'w342')} onPress={() => nav.push({ name: 'movie', id: m.id })}/>

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

    <section className="search-results" aria-label="Results">
      <VScroll pad={36}>
        {!searching && <div className="search-hint" data-vitem><h2>Find anything in your library</h2><p className="tv-note">Type part of a title. Shows, movies and episodes appear here as you type{liveTV ? ', with what’s on Live TV' : ''}.</p></div>}
        {searching && library.isPending && <div className="search-hint" data-vitem><p className="tv-note"><span className="tv-spinner" aria-hidden="true"/> Searching…</p></div>}
        {searching && library.isError && <div className="search-hint" data-vitem role="alert"><h2>Search didn’t work</h2><p className="tv-note">{problemWords(library.error).body}</p></div>}
        {nothing && <div className="search-hint" data-vitem><h2>Nothing matches</h2><p className="tv-note">No titles in your library match “{q}”.</p></div>}

        {(shows.length > 0 || movies.length > 0) && (libraryBand(shows.length, movies.length) === 'band'
          ? <div className="search-band" data-row data-group="search/library" data-vitem>
            {shows.length > 0 && <div className="search-band__group"><h2 className="tv-h2">TV Shows <small>{shows.length}</small></h2>
              <div className="search-band__cards">{shows.map((s) => <div key={s.id}>{showCard(s, `search/library/show-${s.id}`)}</div>)}</div></div>}
            {movies.length > 0 && <div className="search-band__group"><h2 className="tv-h2">Movies <small>{movies.length}</small></h2>
              <div className="search-band__cards">{movies.map((m) => <div key={m.id}>{movieCard(m, `search/library/movie-${m.id}`)}</div>)}</div></div>}
          </div>
          : <>
            {shows.length > 0 && <ResultsRow id="search/shows" title="TV Shows" count={shows.length} items={shows} itemKey={(s) => String(s.id)} cell="var(--poster-w)" visible={4} renderItem={showCard}/>}
            {movies.length > 0 && <ResultsRow id="search/movies" title="Movies" count={movies.length} items={movies} itemKey={(m) => String(m.id)} cell="var(--poster-w)" visible={4} renderItem={movieCard}/>}
          </>)}

        {episodes.length > 0 && <ResultsRow id="search/episodes" title="Episodes" count={episodes.length} items={episodes} itemKey={(e) => String(e.id)} cell="var(--search-wide-w)" visible={3}
          renderItem={(e, id) => <Card id={id} variant="episode" title={e.showTitle} subtitle={episodeSub(e)} image={media.imageUrl(e.stillPath ?? e.showPosterPath, 'w780')}
            label={`${e.showTitle}, season ${e.seasonNumber} episode ${e.number}, ${e.title}`}
            onPress={() => nav.push({ name: 'show', id: e.showId, season: e.seasonNumber, episodeId: e.id })}
            onHold={() => nav.play({ kind: 'file', fileId: e.fileId, title: e.showTitle, subtitle: episodeSub(e) })}/>}/>}

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
