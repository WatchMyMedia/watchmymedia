import { useCallback, useEffect, useMemo, useRef, useState, type FocusEvent } from 'react'
import { flushSync } from 'react-dom'
import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { fileOn, plansOn, seasonOn, showOn, showRelatedOn } from '../../session/sourceQueries'
import type { Source } from '../../session/peerRegistry'
import { chosenCopies, titleKey, type ChosenCopy } from '../../session/chosenCopies'
import { endsAtWords } from '../../ui/timeWords'
import { copiesView, orderCopies, type CopyRef, type ServerCopy } from './copies'
import { CopiesPanel } from './CopiesPanel'
import { mergeSeason, type MergedEpisode, type ServerSeason } from './showCopies'
import { copyServer, useMarkEverywhere, useShowCopies } from './useCopies'
import { SourceGone } from './MovieScreen'
import { requestOptionsQuery, useMonitorEpisodes, useReportProblem, useSearchMissing } from '../../../src/api/requests'
import { useMarkSeasonWatched, useMarkShowWatched } from '../../../src/api/watch'
import { useFollowShow } from '../../../src/api/follows'
import { watchlistTitle } from '../../../src/lib/watchlists'
import type { components } from '../../../src/api/schema'
import { pageEpisodes } from '../../../src/lib/episode'
import { afterEpisodeDelete, arrivalSeason, askableSeasons, deletedLine, seasonTabs } from '../../../src/lib/showPage'
import { useShowMissing } from '../../../src/lib/showMissing'
import { playOn, SourceContext, useBack, useEntry, useFocus, useKeys, useNav, usePeers, useSession, useSource } from '../../app/hooks'
import type { ScreenProps } from '../../app/routes'
import { Clock } from '../../ui/Clock'
import { ProblemScreen, Skeleton, SkeletonRow, SkeletonText } from '../../ui/States'
import { problemWords, statusOf } from '../../ui/problemWords'
import { RequestDialog, type RequestTarget } from '../requests/RequestDialog'
import { useNow } from '../live/clock'
import { Backdrop, RelatedSection, NoticeBanner } from './DetailParts'
import { markedMessage, reportSent, useNotice } from './detailWords'
import { Icon } from '../../ui/Icon'
import { trackLine } from './fileWords'
import { EpisodeRow } from './EpisodeRow'
import { SeasonBar } from './SeasonBar'
import { ShowAbout } from './ShowAbout'
import { DeleteEpisodeDialog, ReplaceDialog } from './ShowDialogs'
import { ShowTop } from './ShowTop'
import { AudioPanel, FileDetailsPanel, MorePanelRows, ReportPanel, type MoreRow } from './TitlePanels'
import { planProfile } from './planProfile'
import { alongButtons } from './pageKeys'
import { nextEpisodeLine } from './titleWords'
import { relatedPosterRows } from './relatedCards'
import {
  FOLLOW_LIMIT, abilities, arrivalOf, episodeKicker, episodeMore, episodePlay, followRefused, missingOf, onTopFor, replacedMessage, seasonActions, seasonLine, seasonMark,
  showMark, topActions, zoneOffset, type Zone,
} from './showLabels'
import { useWatchlistsOn } from '../watchlists/useWatchlistsOn'
import './details.css'

type ShowDetail = components['schemas']['ShowDetail']
type SeasonDetail = components['schemas']['SeasonDetail']
type OnTopReason = NonNullable<ReturnType<typeof onTopFor>['why']>

/** The episode on top, once the page has resolved it: kept through refetches, so a mark or an event never moves it. */
type Pin = { season: number; episodeId: number; why?: OnTopReason; gone?: boolean }

/** What each show page in the stack had chosen, so coming back from a title it opened shows the same episode. */
const kept = new Map<number, { tab: number; pin?: Pin }>()

/**
 * One page for a show, its seasons and its episodes, without the menu.
 * The episode on top with its picture filling the screen, Play focused
 * first; ▼ the season tabs, where focus changes the season at once; ▼ the
 * episode row, where the episode on top follows focus and OK goes back up
 * to Play; ▼ About the show and its cast; ▼ More like this. Return from
 * below the top comes back to it first, then leaves.
 */
export function ShowScreen({ route }: ScreenProps<'show'>) {
  const source = useSource(route.server)
  if (source === 'gone') return <SourceGone/>
  return <SourceContext.Provider value={source}><ShowFrom route={route} source={source}/></SourceContext.Provider>
}

/** The show from `source`: the current server, or another opened in place. */
function ShowFrom({ route, source }: { route: ScreenProps<'show'>['route']; source: Source }) {
  const show = useQuery(showOn(source, route.id))
  const nav = useNav()
  if (show.isPending) return <ShowLoading/>
  if (show.isError) return <LoadProblem error={show.error} onRetry={() => void show.refetch()} onBack={() => nav.back()}/>
  return <ShowPage show={show.data} route={route} source={source}/>
}

function ShowLoading() {
  return <div className="dt-page" aria-busy="true">
    <span className="tv-sr" role="status">Loading the show</span>
    <div className="dt-head dt-head--show" aria-hidden="true"><SkeletonText width="28rem"/><Skeleton width="44rem" height="4.5rem"/><SkeletonText width="30rem"/><SkeletonText width="56rem"/>
      <div className="dt-actions"><Skeleton width="13rem" height="var(--button-h)" radius="2.375rem"/><Skeleton width="15rem" height="var(--button-h)" radius="2.375rem"/></div></div>
    <div className="dt-seasons dt-seasons--loading" aria-hidden="true"><SkeletonText width="40rem"/><SkeletonRow variant="episode" title={false}/></div>
  </div>
}

function LoadProblem({ error, onRetry, onBack }: { error: unknown; onRetry(): void; onBack(): void }) {
  const words = problemWords(error)
  return <div className="dt-page"><ProblemScreen title="Couldn’t load this show" actions={[
    { id: 'retry', label: 'Try again', onPress: onRetry }, { id: 'back', label: 'Back', onPress: onBack },
  ]}>{words.title}. {words.body}</ProblemScreen></div>
}

const ZONES: Zone[] = ['top', 'seasons', 'episodes', 'about', 'cast', 'related']
const zoneOf = (el: Element | null): Zone | undefined => {
  const z = el?.closest<HTMLElement>('[data-zone]')?.dataset.zone
  return z?.startsWith('related') ? 'related' : ZONES.find((x) => x === z)
}

function ShowPage({ show, route, source }: { show: ShowDetail; route: ScreenProps<'show'>['route']; source: Source }) {
  const entry = useEntry()
  const session = useSession()
  const peers = usePeers()
  // Requests, Sonarr's actions, follows and watchlists are the current server's: a show opened from another server offers none of them.
  const own = !source.peer
  const focus = useFocus()
  const nav = useNav()
  const qc = useQueryClient()
  const [today] = useState(() => new Date())
  const now = useNow()
  const arrival = arrivalOf(route)
  const [state, setState] = useState(() => (entry && kept.get(entry.key)) || { tab: arrivalSeason(show, route.season) ?? 1, pin: undefined as Pin | undefined })
  const { tab, pin } = state
  useEffect(() => {
    if (!entry) return
    kept.delete(entry.key)
    kept.set(entry.key, state)
    // A few pages back is all a stack holds in practice.
    for (const key of kept.keys()) { if (kept.size <= 40) break; kept.delete(key) }
  }, [entry, state])

  const options = useQuery({ ...requestOptionsQuery, enabled: own })
  const can = abilities(own && session.isAdmin, own ? options.data : undefined)
  const [showMissing, setShowMissing] = useShowMissing(show.id)
  const missingOn = showMissing && (can.requests || can.admin)
  const chosen = useQuery(seasonOn(source, show.id, tab))
  const shown = useQuery({ ...seasonOn(source, show.id, pin?.season ?? tab), enabled: !!pin })
  const related = useQuery(showRelatedOn(source, show.id))
  const rows = relatedPosterRows(related.data)

  // One card across servers: the show's copies on the other servers, and the season on screen from each that has it.
  const others = useShowCopies(show.tmdbId, source)
  const currentId = session.server.id
  const pageServer = useMemo(() => copyServer(source, currentId), [source, currentId])
  const onScreen = pin?.season ?? tab
  const elsewhere = others.found.filter((f) => f.detail.seasons.some((x) => x.number === onScreen))
  const otherSeasons = useQueries({ queries: elsewhere.map((f) => ({ ...seasonOn(f.source, f.detail.id, onScreen), retry: false })) })
  const seasonsElsewhere: ServerSeason[] = elsewhere.flatMap((f, i) => {
    const data = otherSeasons[i]?.data
    return data ? [{ server: copyServer(f.source, currentId), episodes: data.episodes }] : []
  })
  const sourceOf = (id: string): Source => (id === source.id ? source : others.found.find((f) => f.source.id === id)?.source ?? source)
  const order = useMemo(() => peers.map((p) => p.id), [peers])
  const onSeveral = others.found.length > 0

  // The chosen season's episode on top, once its episodes are in; then it stays put (`Pin`).
  const loaded = chosen.data
  if (loaded && loaded.number === tab && (!pin || pin.season !== tab || !loaded.episodes.some((e) => e.id === pin.episodeId))) {
    const sel = pin ? { season: tab, episodeId: pin.season === tab ? pin.episodeId : undefined, arrived: false } : { season: tab, episodeId: route.episodeId, arrived: true }
    const top = onTopFor(sel, loaded, show, arrival)
    if (top.episode && (pin?.episodeId !== top.episode.id || pin.season !== tab)) setState({ tab, pin: { season: tab, episodeId: top.episode.id, why: top.why, gone: top.gone } })
  }
  // The season on screen is the pinned episode's: a tab just chosen shows once its episodes are in.
  const season: SeasonDetail | undefined = pin ? shown.data : undefined
  // The season with every server's copies: an episode only another server has sits in its place, "On Cabin".
  const seasonKey = seasonsElsewhere.map((x) => `${x.server.id}:${x.episodes.length}`).join(',')
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const merged = useMemo(() => (season ? mergeSeason(season.number, { server: pageServer, episodes: season.episodes }, seasonsElsewhere) : undefined), [season, pageServer, seasonKey])
  const e: MergedEpisode | undefined = merged?.episodes.find((x) => x.id === pin?.episodeId)
  const here = show.seasons.find((s) => s.number === season?.number) ?? season

  // Only what the page shows: a season's episodes on disk and to come, every one with Show missing, always the one on top.
  const listed = merged ? pageEpisodes(merged.episodes, pin?.episodeId ?? -1, missingOn) : []
  const tabs = seasonTabs(show.seasons, missingOn, tab)

  // The file's tracks for its subtitles line, once the episode has stayed on top a moment.
  // The episode's own server: another one when only that one has it.
  const eSource = e?.on ? sourceOf(e.on.id) : source
  const fileId = e?.hasFile ? e.files[0]?.id : undefined
  const [settled, setSettled] = useState<number>()
  useEffect(() => {
    const t = setTimeout(() => setSettled(fileId), 400)
    return () => clearTimeout(t)
  }, [fileId])
  const file = useQuery({ ...fileOn(eSource, fileId ?? 0), enabled: !!fileId && settled === fileId })
  const plans = useQuery({ ...plansOn(eSource, fileId ? [fileId] : [], planProfile()), enabled: !!fileId && settled === fileId })

  const { notice, say, fail } = useNotice()
  const watchlistsOn = useWatchlistsOn()
  const mark = useMarkEverywhere()
  const markSeason = useMarkSeasonWatched()
  const markShow = useMarkShowWatched()
  const follow = useFollowShow()
  const report = useReportProblem()
  const search = useSearchMissing(show.id)
  const monitor = useMonitorEpisodes(show.id)
  const [searched, setSearched] = useState<string>()
  const [dialog, setDialog] = useState<'more' | 'copies' | 'report' | 'tracks' | 'file' | 'replace' | 'delete' | { request: number[] }>()
  const [remembered, setRemembered] = useState<Record<string, ChosenCopy>>({})
  const [moreAt, setMoreAt] = useState<string>()

  // Scrolling: the part with focus decides how far up the page is (zoneOffset).
  const [zone, setZone] = useState<Zone>('top')
  const [offset, setOffset] = useState(0)
  const page = useRef<HTMLDivElement>(null)
  const track = useRef<HTMLDivElement>(null)
  const onFocus = (ev: FocusEvent) => {
    const z = zoneOf(ev.target as HTMLElement)
    if (!z) return
    setZone(z)
    const rem = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16
    const about = track.current?.querySelector<HTMLElement>('[data-zone="about"]')
    setOffset(zoneOffset(z, { aboutTop: about?.offsetTop ?? 0, trackHeight: track.current?.scrollHeight ?? 0, viewHeight: page.current?.clientHeight ?? 0, topPad: 3.375 * rem }))
  }

  const pick = useCallback((id: number) => setState((s) => (s.pin && s.pin.episodeId !== id ? { ...s, pin: { season: s.pin.season, episodeId: id } } : s)), [setState])
  const pickTab = useCallback((n: number) => setState((s) => (s.tab === n ? s : { ...s, tab: n })), [setState])
  // The chosen tab's neighbours load meanwhile, so the next move along the tabs is at once.
  useEffect(() => {
    const numbers = seasonTabs(show.seasons, missingOn, tab).map((t) => t.number)
    const i = numbers.indexOf(tab)
    for (const n of [numbers[i - 1], numbers[i + 1]]) if (n !== undefined) void qc.prefetchQuery(seasonOn(source, show.id, n))
  }, [show.id, show.seasons, missingOn, tab, qc, source])
  const toTop = useCallback(() => { focus.focusFirst(page.current?.querySelector('[data-zone="top"]') ?? null) }, [focus])
  // The top's buttons, the one last used: Return from below the top, ▲ from the tabs.
  const toButtons = useCallback(() => focus.focusId(entry?.memory.groups.actions ?? '', page.current) || focus.focusFirst(page.current?.querySelector('[data-zone="top"]') ?? null), [focus, entry])

  // ▲ ▼ between the parts, to the episode on top, the chosen tab and the button last used rather than whatever is nearest.
  useKeys((i) => {
    if (dialog) return false
    if (i.action === 'left' || i.action === 'right') return alongButtons(document.activeElement as HTMLElement | null, i.action, (el) => focus.focus(el))
    if (i.action !== 'up' && i.action !== 'down') return false
    const z = zoneOf(document.activeElement)
    const root = page.current
    const toEpisode = () => focus.focusId(`ep/${pin?.episodeId}`, root) || focus.focusFirst(root?.querySelector('.dt-eps') ?? null)
    const toTabs = () => focus.focusId(`tab/${tab}`, root) || focus.focusFirst(root?.querySelector('[data-zone="seasons"]') ?? null)
    const toAbout = () => focus.focusId(entry?.memory.groups.about ?? '', root) || focus.focusFirst(root?.querySelector('[data-zone="about"]') ?? null)
    const toCast = () => focus.focusId(entry?.memory.groups.cast ?? '', root) || focus.focusId('cast/0', root)
    // More like this: the card it had, else its first, as the row is entered from above.
    const toRelated = () => !!rows[0] && (focus.focusId(entry?.memory.groups[`related/${rows[0].id}`] ?? '', root) || focus.focusFirst(root?.querySelector('.dt-related') ?? null))
    if (i.action === 'down') {
      if (z === 'top') return toTabs() || toEpisode() || true
      if (z === 'seasons') return toEpisode() || true
      if (z === 'episodes') return toAbout() || true
      if (z === 'about') return toCast() || toRelated() || true
      if (z === 'cast') return toRelated() || true
    } else {
      if (z === 'top') return true
      if (z === 'seasons') return toButtons() || true
      if (z === 'episodes') return toTabs() || toButtons() || true
      if (z === 'about') return toEpisode() || true
      if (z === 'cast') return toAbout() || true
      if (z === 'related') return toCast() || toAbout() || true
    }
    return false
  })
  // Return from About, the cast or More like this comes back to the top first.
  useBack(() => {
    if (dialog || zone === 'top' || zone === 'seasons' || zone === 'episodes') return false
    toButtons()
    return true
  })

  if (!season || !e || !here) {
    if (chosen.isError) return <LoadProblem error={chosen.error} onRetry={() => void chosen.refetch()} onBack={() => nav.back()}/>
    if (loaded && loaded.episodes.length === 0) return <div className="dt-page"><ProblemScreen title={show.title} actions={[{ id: 'back', label: 'Back', onPress: () => nav.back() }]}>No episodes of this show are here yet.</ProblemScreen></div>
    return <ShowLoading/>
  }

  const kicker = episodeKicker(show.title, season.number, e.number)
  // The episode's copies on every server, Play's copy and where the person got to on any of them.
  const epCopies: ServerCopy[] = orderCopies(merged?.copies.get(e.id) ?? [], order)
  const epKey = titleKey({ kind: 'episode', showTmdbId: show.tmdbId, season: season.number, episode: e.number })
  const epChosen = remembered[epKey] ?? chosenCopies().get(epKey)
  const epView = copiesView(e.on ?? pageServer, epCopies, epChosen, onSeveral)
  const copyPlay = (ref: CopyRef, fromStart: boolean) => {
    const base = episodePlay(show, season.number, e)
    if (!base || base.kind !== 'file') return undefined
    const start = fromStart ? 0 : epView.play?.startMs
    return { ...base, fileId: ref.file, ...(start !== undefined ? { startMs: start } : { startMs: undefined }), ...(ref.server !== currentId ? { server: ref.server } : {}) }
  }
  const chooseCopy = (ref: CopyRef) => {
    const next = { server: ref.server, file: ref.file }
    chosenCopies().set(epKey, next)
    flushSync(() => { setRemembered((r) => ({ ...r, [epKey]: next })); setDialog(undefined) })
    focus.focusId('play', page.current)
  }
  const epLine = epView.where ? { play: 'Resume' as const, where: epView.where.text, ends: endsAtWords(now, epView.where.leftMs), progress: epView.where.progress } : undefined
  const ownEpisode = own && !e.on
  const topZone = zone === 'top' || zone === 'seasons' || zone === 'episodes'
  const fileDetail = file.data && file.data.id === fileId ? file.data : undefined
  const todayIso = today.toISOString().slice(0, 10)
  const top = topActions(e, here, show, can, todayIso)
  const more = episodeMore(e, show, ownEpisode ? can : abilities(false, undefined))
  const seasonOwn = missingOn ? seasonActions(here, season.episodes, show, can, todayIso) : undefined
  const askable = can.requests ? askableSeasons(show.seasons, todayIso) : []
  // The season's and the show's marks are each server's own: a show opened from another server leaves them there.
  const sMark = own ? seasonMark(here) : undefined
  const target = (seasons: number[]): RequestTarget => ({ kind: 'tv', tmdbId: show.tmdbId, title: show.title, seasons })
  const searchKey = (episode?: number) => `${season.number}:${episode ?? 'all'}`

  // Marks the episode on every server that has it, as the person sees one episode.
  const doMark = () => {
    const watched = !e.watch?.completed
    const targets = epCopies.length ? epCopies.map((c) => ({ source: sourceOf(c.server.id), type: 'episode' as const, id: c.titleId })) : [{ source, type: 'episode' as const, id: e.id }]
    mark.mark(targets, watched).then(() => say({ title: markedMessage(watched) }), (err: unknown) => fail('Couldn’t change the watched mark', err))
  }
  const doSearch = (episode?: number) => {
    const searching = () => { setSearched(searchKey(episode)); say({ title: `${show.managedBy ?? 'Sonarr'} is searching for ${episode === undefined ? season.name.toLowerCase() || `season ${season.number}` : `episode ${episode}`}` }) }
    search.mutate({ season: season.number, episodes: episode === undefined ? undefined : [episode] }, {
      onSuccess: searching,
      onError: (err) => fail('Couldn’t start a search', err),
    })
  }
  const doMonitor = () => monitor.mutate({ season: season.number, monitored: true }, {
    onSuccess: () => say({ title: `${show.managedBy ?? 'Sonarr'} now monitors ${season.number === 0 ? 'Specials' : `season ${season.number}`}` }),
    onError: (err) => fail('Couldn’t start monitoring', err),
  })
  const doFollow = () => {
    const on = !show.following
    follow.mutate({ showId: show.id, tmdbId: show.tmdbId, follow: on }, {
      onError: (err) => say({ title: on && statusOf(err) === 409 ? FOLLOW_LIMIT : followRefused(on, show.title), problem: true }),
    })
  }
  const open = (next: 'report' | 'tracks' | 'file' | 'replace' | 'delete', from: string) => { setMoreAt(from); setDialog(next) }
  const probe = fileDetail?.probe
  const moreItems: MoreRow[] = [
    ...(probe && (probe.audio.length > 0 || probe.subtitles.length > 0) ? [{ id: 'more/tracks', label: 'Audio and subtitles', detail: trackLine(probe), next: true, onPress: () => open('tracks', 'more/tracks') }] : []),
    ...(fileDetail ? [{ id: 'more/file', label: 'File details', detail: 'How it plays on this TV', next: true, onPress: () => open('file', 'more/file') }] : []),
    ...(more.report ? [{ id: 'more/report', label: 'Report a problem', next: true, onPress: () => open('report', 'more/report') }] : []),
    ...(more.replace ? [{ id: 'more/replace', label: 'Replace file', danger: true, onPress: () => open('replace', 'more/replace') }] : []),
    ...(more.delete ? [{ id: 'more/delete', label: 'Delete episode…', danger: true, onPress: () => open('delete', 'more/delete') }] : []),
  ]
  const closeDialog = () => setDialog(undefined)
  const backToMore = () => setDialog('more')
  const watchlistTarget = own && watchlistsOn ? watchlistTitle('show', show.tmdbId) : undefined
  const watchlist = watchlistTarget && { target: watchlistTarget, holding: show.watchlists ?? [] }

  // data-vscroll: the page moves itself (zoneOffset), so focus never scrolls it as the browser would.
  return <div ref={page} className="dt-page" data-vscroll onFocus={onFocus}>
    <Backdrop path={e.stillPath ?? show.backdropPath} size={e.stillPath ? 'w780' : 'w1280'} seed={`${show.title}${e.number}`} kind="show"/>
    <Clock/>
    <div ref={track} className="dt-track" style={{ transform: `translateY(${-offset}px)` }}>
      <div className="dt-show">
        <div className={`dt-show__top${topZone ? '' : ' dt-head--away'}`}>
          <ShowTop show={show} season={season.number} e={e} why={pin?.why} gone={pin?.gone} missing={ownEpisode ? missingOf(e, options.data) : undefined} actions={top}
            hasMore={moreItems.length > 0} marking={mark.busy} searching={searched === searchKey(e.number)} today={today} now={now}
            also={epView.also} playLabel={epView.playLabel} line={epLine}
            pick={epView.button ? { label: epView.button, onPress: () => { setMoreAt(undefined); setDialog('copies') } } : undefined}
            onPlay={(fromStart) => {
              const r = epView.play && (onSeveral || e.on) ? copyPlay(epView.play.ref, fromStart) : playOn(episodePlay(show, season.number, e, fromStart ? 0 : undefined) ?? { kind: 'file', fileId: 0 }, source)
              if (r && r.kind === 'file' && r.fileId) nav.play(r)
            }}
            onMark={doMark} onMore={() => { setMoreAt(undefined); setDialog('more') }} onRequest={() => setDialog({ request: [season.number] })} onSearch={() => doSearch(e.number)}
            onMonitor={doMonitor} onInbox={() => nav.push({ name: 'requests', inbox: true })}/>
        </div>
        <div className={`dt-seasons${topZone ? '' : ' dt-head--away'}`}>
          <SeasonBar tabs={tabs} chosen={tab} showMissing={missingOn} line={missingOn ? seasonLine(here, season.episodes, missingOn, today) : nextEpisodeLine(season.episodes, today)} mark={sMark} marking={markSeason.isPending}
            actions={seasonOwn} searching={searched === searchKey()} start={!top.request && !top.search && !top.monitor && !top.inbox && !e.hasFile}
            // The switch goes with Show missing: focus goes to the chosen tab rather than nowhere.
            onTab={pickTab} onShowMissing={(on) => { setShowMissing(on); if (!on) focus.restore([`tab/${tab}`, 'season/mark']) }} onRequest={() => setDialog({ request: [season.number] })} onSearch={() => doSearch()} onMonitor={doMonitor}
            onMark={(watched) => markSeason.mutate({ showId: show.id, number: season.number, watched }, { onSuccess: () => say({ title: markedMessage(watched) }), onError: (err) => fail('Couldn’t change the watched marks', err) })}/>
          <div data-zone="episodes">
            <EpisodeRow show={show.title} season={season.number} episodes={listed} onTopId={e.id} options={options.data} today={today} onPick={pick} onOk={toTop}/>
          </div>
        </div>
        <ShowAbout show={show} following={own ? show.following : undefined} watchlist={watchlist} onWatchlistProblem={(title) => say({ title, problem: true })} mark={own ? showMark(show.seasons) : undefined} marking={markShow.isPending}
          canShowMissing={own && (can.requests || can.admin)} showMissing={missingOn} askable={own ? askable : []} away={topZone}
          onFollow={doFollow} onShowMissing={setShowMissing} onRequest={() => setDialog({ request: askable })}
          onMark={(watched) => markShow.mutate({ showId: show.id, watched }, { onSuccess: () => say({ title: markedMessage(watched) }), onError: (err) => fail('Couldn’t change the watched marks', err) })}/>
        {rows.map((row) => <RelatedSection key={row.id} row={row}/>)}
      </div>
    </div>
    {topZone && <p className="dt-about-cue" aria-hidden="true"><Icon name="down"/>About {show.title}</p>}
    <NoticeBanner notice={notice}/>

    {dialog === 'more' && <MorePanelRows title={e.title || `Episode ${e.number}`} rows={moreItems} start={moreAt} onClose={closeDialog}/>}
    {dialog === 'copies' && <CopiesPanel sub={`${show.title} · S${season.number} E${e.number}${e.title ? ` · ${e.title}` : ''}`} groups={epView.groups} note={epView.resumeNote}
      asking={others.asking} onChoose={chooseCopy} onClose={closeDialog}/>}
    {dialog === 'tracks' && probe && <AudioPanel probe={probe} onClose={backToMore}/>}
    {dialog === 'file' && fileDetail && <FileDetailsPanel versions={[fileDetail]} chosen={fileDetail} planFor={(id) => plans.data?.items.find((x) => x.fileId === id)} onChoose={() => undefined} onClose={backToMore}/>}
    {dialog === 'report' && <ReportPanel onClose={backToMore} onPick={(kind) => {
      closeDialog()
      report.mutate({ itemType: 'episode', itemId: e.id, fileId: e.files[0]?.id, kind }, { onSuccess: (r) => say({ title: reportSent(!!r.seerrIssueId) }), onError: (err) => fail('Couldn’t send the report', err) })
    }}/>}
    {dialog === 'replace' && e.files[0] && <ReplaceDialog app={show.managedBy ?? 'Sonarr'} kicker={kicker} file={e.files[0]} onClose={closeDialog}
      onReplaced={(r) => { closeDialog(); say({ title: replacedMessage(r) }) }}/>}
    {dialog === 'delete' && <DeleteEpisodeDialog showId={show.id} kicker={kicker} e={e} onClose={closeDialog} onDeleted={() => {
      const next = afterEpisodeDelete(listed, e.id)
      closeDialog()
      if (next) setState((s) => ({ ...s, pin: { season: season.number, episodeId: next.episode.id } }))
      say({ title: deletedLine(e.title || `Episode ${e.number}`, next?.which) })
    }}/>}
    {typeof dialog === 'object' && <RequestDialog target={target(dialog.request)} onClose={(requested) => { closeDialog(); if (requested) void qc.invalidateQueries({ queryKey: ['show', show.id] }) }}/>}
  </div>
}
