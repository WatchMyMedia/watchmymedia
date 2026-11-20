import { useCallback, useEffect, useRef, useState, type FocusEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { fileQuery, seasonQuery, showQuery, showRelatedQuery } from '../../../src/api/queries'
import { requestOptionsQuery, useMonitorEpisodes, useReportProblem, useSearchMissing } from '../../../src/api/requests'
import { useMarkSeasonWatched, useMarkShowWatched, useMarkWatched } from '../../../src/api/watch'
import { useFollowShow } from '../../../src/api/follows'
import type { components } from '../../../src/api/schema'
import { pageEpisodes } from '../../../src/lib/episode'
import { formatBytes } from '../../../src/lib/format'
import { afterEpisodeDelete, arrivalSeason, askableSeasons, deletedLine, seasonTabs } from '../../../src/lib/showPage'
import { useShowMissing } from '../../../src/lib/showMissing'
import { useEntry, useFocus, useKeys, useNav, useSession } from '../../app/hooks'
import type { ScreenProps } from '../../app/routes'
import { ProblemScreen, Skeleton, SkeletonRow, SkeletonText } from '../../ui/States'
import { problemWords, statusOf } from '../../ui/problemWords'
import { RequestDialog, type RequestTarget } from '../requests/RequestDialog'
import { Backdrop, DetailsPanel, Hint, MorePanel, RelatedSection, ReportPanel, NoticeBanner, type MenuItem } from './DetailParts'
import { markedMessage, reportSent, useNotice } from './detailWords'
import { Glyph } from './Glyph'
import { EpisodeRow } from './EpisodeRow'
import { SeasonBar } from './SeasonBar'
import { ShowAbout } from './ShowAbout'
import { DeleteEpisodeDialog, ReplaceDialog } from './ShowDialogs'
import { ShowTop } from './ShowTop'
import { relatedPosterRows } from './relatedCards'
import {
  FOLLOW_LIMIT, abilities, arrivalOf, searchAccepted, episodeKicker, episodeMore, episodePlay, followRefused, missingOf, onTopFor, replacedMessage, seasonActions, seasonLine, seasonMark,
  showMark, topActions, zoneOffset, type Zone,
} from './showLabels'
import './details.css'

type ShowDetail = components['schemas']['ShowDetail']
type SeasonDetail = components['schemas']['SeasonDetail']
type OnTopReason = NonNullable<ReturnType<typeof onTopFor>['why']>

/** The episode on top, once the page has resolved it: kept through refetches, so a mark or an event never moves it. */
type Pin = { season: number; episodeId: number; why?: OnTopReason; gone?: boolean }

/** What each show page in the stack had chosen, so coming back from a title it opened shows the same episode. */
const kept = new Map<number, { tab: number; pin?: Pin }>()

/**
 * One page for a show, its seasons and its episodes (ShowPage and
 * ShowRelated on the Samsung TV canvas; PLAN-SHOW-PAGE.md; the Android TV
 * app's ShowPageScreen). The episode on top, Play focused first; ▼ the
 * season tabs, where focus changes the season at once; ▼ the episode row,
 * where the episode on top follows focus and OK goes back up to Play; ▼
 * About the show; ▼ More like this. Return leaves the show in one step.
 */
export function ShowScreen({ route }: ScreenProps<'show'>) {
  const show = useQuery(showQuery(route.id))
  const nav = useNav()
  if (show.isPending) return <ShowLoading/>
  if (show.isError) return <LoadProblem error={show.error} onRetry={() => void show.refetch()} onBack={() => nav.back()}/>
  return <ShowPage show={show.data} route={route}/>
}

function ShowLoading() {
  return <div className="dt-page" aria-busy="true"><div className="dt-show">
    <div className="dt-show__top"><div className="dt-hero"><SkeletonText width="22rem"/><Skeleton width="44rem" height="4.5rem"/><SkeletonText width="30rem"/><SkeletonText width="56rem"/></div></div>
    <div className="dt-seasons"><SkeletonText width="40rem"/><SkeletonRow variant="episode" title={false}/></div>
  </div></div>
}

function LoadProblem({ error, onRetry, onBack }: { error: unknown; onRetry(): void; onBack(): void }) {
  const words = problemWords(error)
  return <div className="dt-page"><ProblemScreen icon="alert" title="Couldn’t load this show" actions={[
    { id: 'retry', label: 'Try again', onPress: onRetry }, { id: 'back', label: 'Back', onPress: onBack },
  ]}>{words.title}. {words.body}</ProblemScreen></div>
}

const ZONES: Zone[] = ['top', 'seasons', 'episodes', 'about', 'related']
const zoneOf = (el: Element | null): Zone | undefined => {
  const z = el?.closest<HTMLElement>('[data-zone]')?.dataset.zone
  return z?.startsWith('related') ? 'related' : ZONES.find((x) => x === z)
}

function ShowPage({ show, route }: { show: ShowDetail; route: ScreenProps<'show'>['route'] }) {
  const entry = useEntry()
  const session = useSession()
  const focus = useFocus()
  const nav = useNav()
  const qc = useQueryClient()
  const [today] = useState(() => new Date())
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

  const options = useQuery(requestOptionsQuery)
  const can = abilities(session.isAdmin, options.data)
  const [showMissing, setShowMissing] = useShowMissing(show.id)
  const missingOn = showMissing && (can.requests || can.admin)
  const chosen = useQuery(seasonQuery(show.id, tab))
  const shown = useQuery({ ...seasonQuery(show.id, pin?.season ?? tab), enabled: !!pin })
  const related = useQuery(showRelatedQuery(show.id))
  const rows = relatedPosterRows(related.data)

  // The chosen season's episode on top, once its episodes are in; then it stays put (`Pin`).
  const loaded = chosen.data
  if (loaded && loaded.number === tab && (!pin || pin.season !== tab || !loaded.episodes.some((e) => e.id === pin.episodeId))) {
    const sel = pin ? { season: tab, episodeId: pin.season === tab ? pin.episodeId : undefined, arrived: false } : { season: tab, episodeId: route.episodeId, arrived: true }
    const top = onTopFor(sel, loaded, show, arrival)
    if (top.episode && (pin?.episodeId !== top.episode.id || pin.season !== tab)) setState({ tab, pin: { season: tab, episodeId: top.episode.id, why: top.why, gone: top.gone } })
  }
  // The season on screen is the pinned episode's: a tab just chosen shows once its episodes are in.
  const season: SeasonDetail | undefined = pin ? shown.data : undefined
  const e = season?.episodes.find((x) => x.id === pin?.episodeId)
  const here = show.seasons.find((s) => s.number === season?.number) ?? season

  // Only what the page shows: a season's episodes on disk and to come, every one with Show missing, always the one on top.
  const listed = season ? pageEpisodes([...season.episodes].sort((a, b) => a.number - b.number), pin?.episodeId ?? -1, missingOn) : []
  const tabs = seasonTabs(show.seasons, missingOn, tab)

  // The file's tracks for its subtitles line, once the episode has stayed on top a moment.
  const fileId = e?.hasFile ? e.files[0]?.id : undefined
  const [settled, setSettled] = useState<number>()
  useEffect(() => {
    const t = setTimeout(() => setSettled(fileId), 400)
    return () => clearTimeout(t)
  }, [fileId])
  const file = useQuery({ ...fileQuery(fileId ?? 0), enabled: !!fileId && settled === fileId })

  const { notice, say, fail } = useNotice()
  const mark = useMarkWatched()
  const markSeason = useMarkSeasonWatched()
  const markShow = useMarkShowWatched()
  const follow = useFollowShow()
  const report = useReportProblem()
  const search = useSearchMissing(show.id)
  const monitor = useMonitorEpisodes(show.id)
  const [searched, setSearched] = useState<string>()
  const [dialog, setDialog] = useState<'more' | 'report' | 'replace' | 'delete' | 'details' | { request: number[] }>()

  // Scrolling: the part with focus decides how far up the page is (zoneOffset).
  const [zone, setZone] = useState<Zone>('top')
  const [offset, setOffset] = useState(0)
  const page = useRef<HTMLDivElement>(null)
  const track = useRef<HTMLDivElement>(null)
  const seasonsRef = useRef<HTMLDivElement>(null)
  const onFocus = (ev: FocusEvent) => {
    const z = zoneOf(ev.target as HTMLElement)
    if (!z) return
    setZone(z)
    const rem = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16
    setOffset(zoneOffset(z, { seasonsTop: seasonsRef.current?.offsetTop ?? 0, trackHeight: track.current?.scrollHeight ?? 0, viewHeight: page.current?.clientHeight ?? 0, topPad: 4.5 * rem }))
  }

  const pick = useCallback((id: number) => setState((s) => (s.pin && s.pin.episodeId !== id ? { ...s, pin: { season: s.pin.season, episodeId: id } } : s)), [setState])
  const pickTab = useCallback((n: number) => setState((s) => (s.tab === n ? s : { ...s, tab: n })), [setState])
  // The chosen tab's neighbours load meanwhile, so the next move along the tabs is at once.
  useEffect(() => {
    const numbers = seasonTabs(show.seasons, missingOn, tab).map((t) => t.number)
    const i = numbers.indexOf(tab)
    for (const n of [numbers[i - 1], numbers[i + 1]]) if (n !== undefined) void qc.prefetchQuery(seasonQuery(show.id, n))
  }, [show.id, show.seasons, missingOn, tab, qc])
  const toTop = useCallback(() => { focus.focusFirst(page.current?.querySelector('[data-zone="top"]') ?? null) }, [focus])

  // ▲ ▼ between the parts, to the episode on top and the chosen tab rather than whatever is nearest.
  useKeys((i) => {
    if (dialog || (i.action !== 'up' && i.action !== 'down')) return false
    const z = zoneOf(document.activeElement)
    const root = page.current
    const toEpisode = () => focus.focusId(`ep/${pin?.episodeId}`, root) || focus.focusFirst(root?.querySelector('.dt-eps') ?? null)
    const toTabs = () => focus.focusId(`tab/${tab}`, root) || focus.focusFirst(root?.querySelector('[data-zone="seasons"]') ?? null)
    if (i.action === 'down') {
      if (z === 'top') return toTabs()
      if (z === 'seasons') return toEpisode()
      if (z === 'episodes') return focus.focusFirst(root?.querySelector('[data-zone="about"]') ?? null)
      // More like this: the card it had, else its first, as the row is entered from above.
      if (z === 'about' && rows[0]) return focus.focusId(entry?.memory.groups[`related/${rows[0].id}`] ?? '', root) || focus.focusFirst(root?.querySelector('.dt-related') ?? null)
    } else {
      if (z === 'seasons') return focus.focusFirst(root?.querySelector('[data-zone="top"]') ?? null) || false
      if (z === 'episodes') return toTabs()
      if (z === 'about') return toEpisode()
    }
    return false
  })

  if (!season || !e || !here) {
    if (chosen.isError) return <LoadProblem error={chosen.error} onRetry={() => void chosen.refetch()} onBack={() => nav.back()}/>
    if (loaded && loaded.episodes.length === 0) return <div className="dt-page"><ProblemScreen icon="alert" title={show.title} actions={[{ id: 'back', label: 'Back', onPress: () => nav.back() }]}>No episodes of this show are here yet.</ProblemScreen></div>
    return <ShowLoading/>
  }

  const kicker = episodeKicker(show.title, season.number, e.number)
  const topZone = zone === 'top' || zone === 'seasons' || zone === 'episodes'
  const todayIso = today.toISOString().slice(0, 10)
  const top = topActions(e, here, show, can, todayIso)
  const more = episodeMore(e, show, can)
  const seasonOwn = missingOn ? seasonActions(here, season.episodes, show, can, todayIso) : undefined
  const askable = can.requests ? askableSeasons(show.seasons, todayIso) : []
  const sMark = seasonMark(here)
  const target = (seasons: number[]): RequestTarget => ({ kind: 'tv', tmdbId: show.tmdbId, title: show.title, seasons })
  const searchKey = (episode?: number) => `${season.number}:${episode ?? 'all'}`

  const doMark = () => mark.mutate({ type: 'episode', id: e.id, watched: !e.watch?.completed }, {
    onSuccess: () => say({ title: markedMessage(!e.watch?.completed) }), onError: (err) => fail('Couldn’t change the watched mark', err),
  })
  const doSearch = (episode?: number) => {
    const searching = () => { setSearched(searchKey(episode)); say({ title: `${show.managedBy ?? 'Sonarr'} is searching for ${episode === undefined ? season.name.toLowerCase() || `season ${season.number}` : `episode ${episode}`}` }) }
    search.mutate({ season: season.number, episodes: episode === undefined ? undefined : [episode] }, {
      onSuccess: searching,
      onError: (err) => (searchAccepted(err) ? searching() : fail('Couldn’t start a search', err)),
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
  const moreItems: MenuItem[] = [
    ...(more.report ? [{ id: 'more/report', label: 'Report a problem', icon: 'flag' as const, onPress: () => setDialog('report') }] : []),
    ...(more.replace ? [{ id: 'more/replace', label: 'Replace file', icon: 'replace' as const, onPress: () => setDialog('replace') }] : []),
    ...(more.delete ? [{ id: 'more/delete', label: 'Delete episode…', icon: 'trash' as const, danger: true, onPress: () => setDialog('delete') }] : []),
  ]
  const closeDialog = () => setDialog(undefined)

  // data-vscroll: the page moves itself (zoneOffset), so focus never scrolls it as the browser would.
  return <div ref={page} className="dt-page" data-vscroll onFocus={onFocus}>
    <Backdrop path={show.backdropPath} seed={show.title} kind="show"/>
    <div ref={track} className="dt-track" style={{ transform: `translateY(${-offset}px)` }}>
      <div className="dt-show">
        <div className="dt-show__top">
          <ShowTop show={show} season={season.number} e={e} why={pin?.why} gone={pin?.gone} probe={file.data?.probe} missing={missingOf(e, options.data)} actions={top}
            hasMore={moreItems.length > 0} marking={mark.isPending} searching={searched === searchKey(e.number)} today={today}
            onPlay={(fromStart) => { const r = episodePlay(show, season.number, e, fromStart ? 0 : undefined); if (r) nav.play(r) }}
            onMark={doMark} onMore={() => setDialog('more')} onRequest={() => setDialog({ request: [season.number] })} onSearch={() => doSearch(e.number)}
            onMonitor={doMonitor} onInbox={() => nav.push({ name: 'requests', inbox: true })}/>
        </div>
        <div ref={seasonsRef} className="dt-seasons">
          <SeasonBar tabs={tabs} chosen={tab} showMissing={missingOn} line={seasonLine(here, season.episodes, missingOn, today)} mark={sMark} marking={markSeason.isPending}
            actions={seasonOwn} searching={searched === searchKey()} start={!top.request && !top.search && !top.monitor && !top.inbox && !e.hasFile}
            onTab={pickTab} onShowMissing={setShowMissing} onRequest={() => setDialog({ request: [season.number] })} onSearch={() => doSearch()} onMonitor={doMonitor}
            onMark={(watched) => markSeason.mutate({ showId: show.id, number: season.number, watched }, { onSuccess: () => say({ title: markedMessage(watched) }), onError: (err) => fail('Couldn’t change the watched marks', err) })}/>
          <div data-zone="episodes">
            <EpisodeRow show={show.title} season={season.number} episodes={listed} onTopId={e.id} options={options.data} today={today} onPick={pick} onOk={toTop}/>
          </div>
          {zone === 'episodes' && <Hint items={[{ key: '◀ ▶', label: 'The episode on top follows focus' }, { key: 'OK', label: 'back up to its buttons, never plays' }, { key: '▲', label: 'the seasons' }, { key: 'Return', label: `leaves ${show.title}` }]}/>}
          {zone === 'seasons' && tabs.length > 1 && <Hint items={[{ key: '◀ ▶', label: 'changes season at once, its first unwatched episode on top' }, { key: '▼', label: 'its episodes' }, { key: '▲', label: 'the episode’s buttons' }]}/>}
        </div>
        <ShowAbout show={show} today={today} following={show.following} mark={showMark(show.seasons)} marking={markShow.isPending}
          canShowMissing={can.requests || can.admin} showMissing={missingOn} askable={askable} hasRelated={rows.length > 0} focused={zone === 'about'} away={topZone}
          onFollow={doFollow} onShowMissing={setShowMissing} onRequest={() => setDialog({ request: askable })} onDetails={() => setDialog('details')}
          onMark={(watched) => markShow.mutate({ showId: show.id, watched }, { onSuccess: () => say({ title: markedMessage(watched) }), onError: (err) => fail('Couldn’t change the watched marks', err) })}/>
        {rows.map((row) => <RelatedSection key={row.id} row={row} hint={zone === 'related' && <Hint items={[{ key: '◀ ▶', label: 'more like this' }, { key: 'OK', label: 'opens the show' }, { key: '▲', label: `About ${show.title}` }, { key: 'Return', label: `leaves ${show.title}` }]}/>}/>)}
      </div>
    </div>
    {topZone && <p className="dt-about-cue" aria-hidden="true"><Glyph name="down"/>About {show.title}</p>}
    <NoticeBanner notice={notice}/>

    {dialog === 'more' && <MorePanel kicker={kicker} title={e.title || `Episode ${e.number}`} items={moreItems} onClose={closeDialog}
      note={`Only this episode’s actions. The season’s sit by the seasons; the show’s are under About ${show.title}. Return closes this panel.`}/>}
    {dialog === 'report' && <ReportPanel onClose={closeDialog} onPick={(kind) => {
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
    {dialog === 'details' && <DetailsPanel title={show.title} overview={show.overview} cast={show.credits} onClose={closeDialog} facts={[
      ...(show.status ? [['Status', show.status] as [string, string]] : []),
      ...(show.genres.length ? [['Genres', show.genres.join(', ')] as [string, string]] : []),
      ['Episodes', `${show.episodeCount} on disk`], ['Size', formatBytes(show.totalSize)],
    ]}/>}
    {typeof dialog === 'object' && <RequestDialog target={target(dialog.request)} onClose={(requested) => { closeDialog(); if (requested) void qc.invalidateQueries({ queryKey: ['show', show.id] }) }}/>}
  </div>
}
