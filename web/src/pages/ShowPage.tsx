import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useRouterState } from '@tanstack/react-router'
import { fileQuery, seasonQuery, showQuery } from '../api/queries'
import { useCanDelete, useCanManage } from '../api/delete'
import { requestOptionsQuery, useMonitorEpisodes, useSearchMissing } from '../api/requests'
import type { components } from '../api/schema'
import { useMarkSeasonWatched, useMarkShowWatched, useMarkWatched } from '../api/watch'
import { useAuth } from '../authContext'
import { HistoryCard } from '../components/activity/HistoryCard'
import { Badge } from '../components/Badge'
import { ChangePosterDialog } from '../components/ChangePosterDialog'
import { DeleteDialog, ReplaceFileDialog } from '../components/DeleteDialog'
import { CastRow, FileTags, SideCard } from '../components/DetailHeader'
import { RecordingProvenance } from '../components/liveTV/RecordingProvenance'
import { MoreMenu, type MoreItem } from '../components/MoreMenu'
import { Poster } from '../components/Poster'
import { RematchDialog } from '../components/RematchDialog'
import { ReportProblemDialog } from '../components/ReportProblemDialog'
import { RequestDialog } from '../components/RequestDialog'
import { SegmentEditorDialog } from '../components/SegmentEditorDialog'
import { episodeActions, episodeFacts, episodeLine, episodeMinutes, isPartlyWatched, pageEpisodes, rowCode, showYears, trackSummary } from '../lib/episode'
import { formatBytes, formatDate, plural, relativeTime, seCode } from '../lib/format'
import { img } from '../lib/images'
import { canRequestSeason, missingStatus, seasonTabCount, toneDot, toneText } from '../lib/requests'
import {
  afterEpisodeDelete,
  afterSeasonDelete,
  arrivalSeason,
  askableSeasons,
  deletedLine,
  episodeOnTop,
  episodePath,
  missingActions,
  onTopChip,
  seasonMark,
  seasonName,
  seasonRequested,
  seasonTabs,
  seasonWatchLine,
  showMark,
  type Arrival,
  type OnTopReason,
} from '../lib/showPage'
import { useShowMissing } from '../lib/showMissing'

type Episode = components['schemas']['Episode']
type SeasonDetail = components['schemas']['SeasonDetail']
type ShowDetail = components['schemas']['ShowDetail']

const btn = 'inline-flex min-h-[44px] items-center justify-center gap-2 rounded-md px-[18px] text-sm'
const primaryBtn = `${btn} btn-primary font-semibold`
const secondaryBtn = `${btn} border border-line-2 bg-surface font-medium hover:bg-surface-2 disabled:opacity-50 disabled:hover:bg-surface`
// From the start and Mark watched share a row at phone width, so they keep to one line there.
const pairBtn = 'inline-flex min-h-[44px] flex-1 items-center justify-center gap-2 whitespace-nowrap rounded-md border border-line-2 bg-surface px-3 text-sm font-medium hover:bg-surface-2 disabled:opacity-50 sm:flex-none sm:px-[18px]'
const smallBtn = 'inline-flex min-h-9 items-center justify-center gap-1.5 whitespace-nowrap rounded-md border border-line-2 bg-surface px-3 text-[13px] font-medium hover:bg-surface-2 disabled:opacity-50'

function Check({ size }: { size: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="m5 12 5 5 9-10" />
    </svg>
  )
}

function Chevron({ left = false }: { left?: boolean }) {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={left ? 'm15 18-6-6 6-6' : 'm9 18 6-6-6-6'} />
    </svg>
  )
}

const watchedPct = (e: Episode) => (e.watch && isPartlyWatched(e) ? Math.round((e.watch.positionMs / Math.max(e.watch.durationMs || e.files[0]?.durationMs || 0, 1)) * 100) : 0)

/**
 * The one show page (PLAN-SHOW-PAGE.md, design/mockups/web/ShowPage): the show's, a season's and
 * an episode's addresses all draw it, and only the episode on top differs (§3). Under the episode,
 * the season tabs and the season's episodes in a row; then About the show. Picking a tab or a card
 * replaces the address with the episode's, so Back leaves the show in one step.
 */
export function ShowPage({ showId, season, episodeId }: { showId: number; season?: number; episodeId?: number }) {
  const show = useQuery(showQuery(showId))
  if (show.isPending) return <p className="text-neutral-400">Loading…</p>
  if (show.isError) return <p className="text-red-400">{show.error.message}</p>
  const arrival: Arrival = episodeId !== undefined ? { kind: 'episode', id: episodeId } : season !== undefined ? { kind: 'season' } : { kind: 'show' }
  return <Loaded show={show.data} number={arrivalSeason(show.data, season)} arrival={arrival} />
}

function Loaded({ show: s, number, arrival }: { show: ShowDetail; number?: number; arrival: Arrival }) {
  const season = useQuery({ ...seasonQuery(s.id, number ?? 0), enabled: number !== undefined })
  const [showMissing, setShowMissing] = useShowMissing(s.id)
  const deleted = useRouterState({ select: (st) => st.location.state.deleted })
  const backdrop = img(s.backdropPath, 'w1280')

  let body: React.ReactNode
  if (number === undefined) body = <p className="text-neutral-400">{s.title} has no seasons yet.</p>
  else if (season.isPending) body = <p className="text-neutral-400">Loading…</p>
  else if (season.isError) body = <p className="text-red-400">{season.error.message}</p>
  else {
    const se = season.data
    const top = episodeOnTop(arrival, se.episodes, s)
    const e = top.episode
    const listed = e ? pageEpisodes(se.episodes, e.id, showMissing) : se.episodes
    body = (
      <>
        {top.gone && (
          <p role="status" className="mb-4 text-neutral-300">
            That episode isn&rsquo;t in {seasonName(se.number)} of {s.title} any more.
          </p>
        )}
        {e ? (
          // Keyed by episode, so moving to another starts without the last one's menu, dialog or error.
          <EpisodeTop key={e.id} show={s} se={se} e={e} why={arrival.kind === 'show' ? top.why : undefined} listed={listed} />
        ) : (
          <p className="text-neutral-400">{seasonName(se.number)} has no episodes yet.</p>
        )}
        <SeasonSection
          show={s}
          se={se}
          listed={listed}
          current={e?.id}
          showMissing={showMissing}
          onShowMissing={setShowMissing}
          deleted={deleted && deleted.season === se.number ? deletedLine(deleted.title, deleted.which) : undefined}
        />
      </>
    )
  }

  return (
    <div>
      <div className="relative -mx-4 h-56 overflow-hidden md:-mx-12 md:h-72">
        {backdrop && <div className="absolute inset-0 bg-cover bg-center opacity-50" style={{ backgroundImage: `url(${backdrop})` }} />}
        <div className="absolute inset-0 bg-gradient-to-b from-black/10 via-black/40 to-ground" />
        <nav aria-label="Breadcrumb" className="relative px-4 pt-4 text-sm text-neutral-300 md:px-12">
          <Link to="/tv" className="hover:text-neutral-100">
            TV Shows
          </Link>
          <span aria-hidden="true" className="mx-1.5 text-neutral-500">
            ›
          </span>
          <span aria-current="page">{s.title}</span>
        </nav>
      </div>
      <div className="relative -mt-24">{body}</div>
      <AboutShow show={s} number={season.data?.number} showMissing={showMissing} />
    </div>
  )
}

/** The episode on top: its still, facts, file and actions; for a missing one, why it is missing and what can be done. */
function EpisodeTop({ show: s, se, e, why, listed }: { show: ShowDetail; se: SeasonDetail; e: Episode; why?: OnTopReason; listed: Episode[] }) {
  const best = e.files[0]
  const file = useQuery({ ...fileQuery(best?.id ?? 0), enabled: !!best })
  const options = useQuery(requestOptionsQuery)
  const mark = useMarkWatched()
  const search = useSearchMissing(s.id)
  const monitor = useMonitorEpisodes(s.id)
  const manage = useCanManage()
  const canDelete = useCanDelete()
  const auth = useAuth()
  const admin = (auth.user?.isAdmin ?? false) && auth.adminAllowed
  const navigate = useNavigate()
  const [acting, setActing] = useState<'report' | 'replace' | 'segments' | 'delete' | 'request' | null>(null)
  const code = seCode(se.number, e.number)
  const title = e.title || `Episode ${e.number}`
  const line = episodeLine(s.title, se.number, e.number)
  const here = episodePath(s.id, se.number, e.id)
  const playTitle = `${s.title} · ${code} ${title}`
  const { primary, fromStart, mark: toggle } = episodeActions(e)
  const minutes = episodeMinutes(e)
  const pct = watchedPct(e)
  const tracks = trackSummary(file.data?.probe)
  const status = !e.hasFile ? missingStatus(e) : null
  const today = new Date().toISOString().slice(0, 10)
  const gap = missingActions(e, {
    admin: !!options.data?.manage,
    canRequest: !!options.data?.shows,
    seasonAskable: se.number > 0 && canRequestSeason(se, today),
    managed: !!s.managedBy,
    seasonUnmonitored: se.monitored === false,
  })

  const items: MoreItem[] = []
  if (e.hasFile) {
    items.push({ label: 'Report a problem', icon: 'flag', onSelect: () => setActing('report') })
    if (manage && s.managedBy && best) items.push({ label: 'Replace file', icon: 'replace', onSelect: () => setActing('replace') })
    if (admin && best) items.push({ label: 'Skip segments…', icon: 'skip', onSelect: () => setActing('segments') })
    if (canDelete) items.push({ label: 'Delete episode…', icon: 'delete', danger: true, divider: true, onSelect: () => setActing('delete') })
  }

  return (
    <section aria-label={`${seasonName(se.number)}, episode ${e.number}: ${title}`} className="relative grid gap-8 xl:grid-cols-[minmax(0,36rem)_minmax(0,1fr)]">
      <div
        className={`relative aspect-video w-full max-w-[36rem] self-start overflow-hidden rounded-lg bg-surface shadow-[0_20px_40px_rgba(0,0,0,.6)] ${status ? 'border border-dashed border-line-2' : 'ring-1 ring-line-2'}`}
      >
        {status ? (
          <div className="grid h-full w-full place-items-center text-[15px] font-semibold text-neutral-400">{status.thumb}</div>
        ) : (
          <Poster path={e.stillPath ?? s.backdropPath} size="w780" alt={`${code} ${title}`} ratio="aspect-video" />
        )}
        {e.isNew && !e.watch?.completed && (
          <Badge tone="accent" className="absolute left-2.5 top-2.5">
            New
          </Badge>
        )}
        {e.watch?.completed && (
          <span className="absolute left-2.5 top-2.5 grid h-6 w-6 place-items-center rounded-full bg-accent text-black" title="Watched">
            <Check size={13} />
          </span>
        )}
        {minutes > 0 && e.hasFile && <span className="absolute bottom-3.5 right-2.5 rounded bg-black/70 px-1.5 py-0.5 text-[11px] font-bold tracking-wide">{minutes} min</span>}
        {(pct > 0 || status?.progress !== undefined) && (
          <div className="absolute inset-x-0 bottom-0 h-[5px] bg-black/60">
            <div className="h-full bg-accent" style={{ width: `${status?.progress !== undefined ? Math.round(status.progress * 100) : pct}%` }} />
          </div>
        )}
      </div>

      <div className="flex min-w-0 flex-col gap-2.5 xl:pt-20">
        <div className="flex flex-wrap items-center gap-2.5">
          <p className="text-xs font-bold uppercase tracking-[.1em] text-accent">{line}</p>
          {why && <span className="inline-flex min-h-6 items-center rounded-full border border-accent px-2.5 text-xs font-semibold text-accent">{onTopChip[why]}</span>}
        </div>
        <h1 className={`text-3xl font-bold leading-tight sm:text-4xl ${status ? 'text-neutral-300' : ''}`}>{title}</h1>
        <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-neutral-300">
          {episodeFacts(e).map((f, i) => (
            <span key={f} className="contents">
              {i > 0 && (
                <span aria-hidden="true" className="text-neutral-500">
                  ·
                </span>
              )}
              <span>{f}</span>
            </span>
          ))}
          {e.watch?.completed && (
            <>
              <span aria-hidden="true" className="text-neutral-500">
                ·
              </span>
              <span className="inline-flex items-center gap-1.5 font-semibold text-neutral-100">
                <span aria-hidden="true" className="grid h-[18px] w-[18px] place-items-center rounded-full bg-accent text-black">
                  <Check size={10} />
                </span>
                Watched
              </span>
            </>
          )}
        </p>
        <RecordingProvenance recording={best?.recording} />
        {e.overview && <p className="mt-1.5 max-w-3xl text-[15px] leading-relaxed text-neutral-200">{e.overview}</p>}
        {best && (
          <>
            <p className="flex flex-wrap items-center gap-1.5 text-xs text-neutral-400">
              <FileTags file={best} />
              {tracks.channels && <span className="rounded border border-line-2 px-1.5 py-0.5 text-[10px] font-medium uppercase tracking-wide text-neutral-300">{tracks.channels}</span>}
              <span>{[tracks.subtitles, formatBytes(best.size)].filter(Boolean).join(' · ')}</span>
            </p>
            <details className="text-xs text-neutral-400">
              <summary className="min-h-6 cursor-pointer">File</summary>
              <p className="mt-1.5 break-all">{best.relativePath}</p>
            </details>
          </>
        )}
        {why === 'again' && <p className="text-sm text-neutral-300">You&rsquo;ve watched every episode. Start again here, or pick any episode below.</p>}
        {status && (
          <>
            <p className={`mt-1 inline-flex items-center gap-2 text-[15px] font-semibold ${toneText[status.tone]}`}>
              <span aria-hidden="true" className={`h-2 w-2 shrink-0 rounded-full ${toneDot[status.tone]}`} />
              {status.label}
            </p>
          </>
        )}

        <div className="mt-2.5 flex flex-wrap items-center gap-2">
          {primary.kind === 'play' && (
            <Link to="/play/$fileId" params={{ fileId: String(primary.fileId) }} search={{ start: primary.start, title: playTitle, back: here }} className={`${primaryBtn} w-full sm:w-auto`}>
              <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true">
                <path d="M8 5v14l11-7z" fill="currentColor" />
              </svg>
              {primary.label}
            </Link>
          )}
          {fromStart && (
            <Link to="/play/$fileId" params={{ fileId: String(fromStart.fileId) }} search={{ start: fromStart.start, title: playTitle, back: here }} className={pairBtn}>
              {fromStart.label}
            </Link>
          )}
          {toggle && (
            <button type="button" disabled={mark.isPending} onClick={() => mark.mutate({ type: 'episode', id: e.id, watched: toggle.watched })} className={pairBtn}>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                {toggle.watched ? (
                  <>
                    <circle cx="12" cy="12" r="9" />
                    <path d="m8 12 3 3 5-6" />
                  </>
                ) : (
                  <>
                    <path d="M9 14 4 9l5-5" />
                    <path d="M4 9h10.5a5.5 5.5 0 0 1 0 11H11" />
                  </>
                )}
              </svg>
              {toggle.label}
            </button>
          )}
          <MoreMenu tall label="More for this episode" items={items} />
          {gap.request && (
            <button type="button" onClick={() => setActing('request')} className={primaryBtn}>
              ＋ Request {seasonName(se.number).toLowerCase()}
            </button>
          )}
          {gap.requested && (
            <span className="inline-flex min-h-9 items-center gap-1.5 rounded-full border border-line-2 px-3 text-[13px] text-neutral-300">
              <span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-neutral-400" />
              Requested
            </span>
          )}
          {gap.search && (
            <button type="button" disabled={search.isPending} onClick={() => search.mutate({ season: se.number })} className={secondaryBtn}>
              {search.isSuccess ? 'Sonarr is searching' : '⌕ Search now'}
            </button>
          )}
          {gap.monitor && (
            <button type="button" disabled={monitor.isPending || monitor.isSuccess} onClick={() => monitor.mutate({ season: se.number, monitored: true })} className={secondaryBtn}>
              Monitor {seasonName(se.number).toLowerCase()}
            </button>
          )}
          {gap.inbox && (
            <Link to="/settings/inbox" className={secondaryBtn}>
              Open in Inbox
            </Link>
          )}
          {gap.coming && <span className="text-sm text-neutral-400">Nothing to do yet. It appears here when it arrives.</span>}
        </div>
        {(mark.error ?? search.error ?? monitor.error) && <p className="text-sm text-warn">{(mark.error ?? search.error ?? monitor.error)?.message}</p>}
      </div>

      {acting === 'report' && <ReportProblemDialog itemType="episode" itemId={e.id} fileId={best?.id} title={playTitle} onClose={() => setActing(null)} />}
      {acting === 'replace' && best && s.managedBy && <ReplaceFileDialog file={best} app={s.managedBy} what="episode" onClose={() => setActing(null)} />}
      {acting === 'segments' && best && <SegmentEditorDialog fileId={best.id} title={playTitle} onClose={() => setActing(null)} />}
      {acting === 'request' && <RequestDialog mediaType="tv" tmdbId={s.tmdbId} preselect={[se.number]} onClose={() => setActing(null)} />}
      {acting === 'delete' && (
        <DeleteDialog
          item={{ type: 'episode', id: e.id, showId: s.id }}
          name={s.title}
          episode={{ title, line, files: e.files }}
          onClose={() => setActing(null)}
          onDeleted={() => {
            // Stay on the show: the next episode in the row moves on top, else the one before (§3).
            const to = afterEpisodeDelete(listed, e.id)
            const deleted = { title, season: se.number, which: to?.which }
            const params = { showId: String(s.id), number: String(se.number) }
            if (to) void navigate({ to: '/shows/$showId/seasons/$number/episodes/$episodeId', params: { ...params, episodeId: String(to.episode.id) }, state: (prev) => ({ ...prev, deleted }), replace: true })
            else void navigate({ to: '/shows/$showId/seasons/$number', params, state: (prev) => ({ ...prev, deleted }), replace: true })
          }}
        />
      )}
    </section>
  )
}

/**
 * Picks a season the way a tab does: its episode on top (its first not finished, else its first),
 * by replacing the address with that episode's, so Back still leaves the show in one step.
 */
function usePickSeason(show: ShowDetail) {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const [pending, setPending] = useState<number>()
  const showId = String(show.id)
  const pick = async (n: number) => {
    setPending(n)
    try {
      const se = await qc.ensureQueryData(seasonQuery(show.id, n))
      const e = episodeOnTop({ kind: 'season' }, se.episodes, show).episode
      if (e) {
        await navigate({ to: '/shows/$showId/seasons/$number/episodes/$episodeId', params: { showId, number: String(n), episodeId: String(e.id) }, replace: true })
        return
      }
    } catch {
      // The season's own address shows its error.
    } finally {
      setPending(undefined)
    }
    await navigate({ to: '/shows/$showId/seasons/$number', params: { showId, number: String(n) }, replace: true })
  }
  const preload = (n: number) => void qc.prefetchQuery(seasonQuery(show.id, n))
  return { pick, preload, pending }
}

/** The season tabs (a plain heading for a show with one season), the season's actions and its episodes in a row. */
function SeasonSection({
  show: s,
  se,
  listed,
  current,
  showMissing,
  onShowMissing,
  deleted,
}: {
  show: ShowDetail
  se: SeasonDetail
  listed: Episode[]
  current?: number
  showMissing: boolean
  onShowMissing: (on: boolean) => void
  deleted?: string
}) {
  const options = useQuery(requestOptionsQuery)
  const markSeason = useMarkSeasonWatched()
  const search = useSearchMissing(s.id)
  const monitor = useMonitorEpisodes(s.id)
  const [requesting, setRequesting] = useState(false)
  const { pick, preload, pending } = usePickSeason(s)
  const list = useRef<HTMLUListElement>(null)
  const tabs = seasonTabs(s.seasons, showMissing, se.number)
  const here = s.seasons.find((x) => x.number === se.number) ?? se
  const mark = seasonMark(here)
  const today = new Date().toISOString().slice(0, 10)
  const missingAired = se.episodes.filter((e) => !e.hasFile && e.aired).length
  const episodes = [...listed].sort((a, b) => a.number - b.number)
  const showId = String(s.id)
  const number = String(se.number)
  const name = (x: { number: number; name: string }) => (x.number === 0 ? 'Specials' : x.name || seasonName(x.number))

  // Bring the episode on top into the row's view without scrolling the page.
  useEffect(() => {
    const ul = list.current
    const li = ul?.querySelector<HTMLElement>('[aria-current="page"]')?.parentElement
    if (!ul || !li) return
    ul.scrollLeft = li.offsetLeft - ul.offsetLeft - (ul.clientWidth - li.clientWidth) / 2
  }, [current, se.number])
  const scrollRow = (dir: number) => list.current?.scrollBy({ left: dir * list.current.clientWidth * 0.8, behavior: 'smooth' })

  return (
    <section aria-labelledby="season-head" className="mt-10">
      {tabs.length > 1 ? (
        <>
          <h2 id="season-head" className="sr-only">
            Seasons
          </h2>
          <div role="tablist" aria-label="Seasons" className="scrollbar-hide -mx-4 flex gap-1 overflow-x-auto border-b border-line px-4 sm:mx-0 sm:px-0">
            {tabs.map((t) => {
              const on = t.number === se.number
              const count = showMissing ? seasonTabCount(t) || 'not in library' : String(t.availableCount)
              return (
                <button
                  key={t.id}
                  type="button"
                  role="tab"
                  aria-selected={on}
                  aria-busy={pending === t.number || undefined}
                  onPointerEnter={() => !on && preload(t.number)}
                  onFocus={() => !on && preload(t.number)}
                  onClick={() => !on && void pick(t.number)}
                  className={`inline-flex min-h-[44px] items-center whitespace-nowrap border-b-2 px-3 text-sm ${
                    on ? 'border-accent font-semibold text-neutral-100' : 'border-transparent text-neutral-400 hover:text-neutral-100'
                  } ${t.availableCount === 0 && !on ? 'opacity-60' : ''}`}
                >
                  {name(t)}
                  <span className="ml-1.5 text-xs font-normal text-neutral-500">{count}</span>
                </button>
              )
            })}
          </div>
        </>
      ) : (
        <h2 id="season-head" className="border-b border-line pb-2.5 text-lg font-semibold">
          {name(se)}
        </h2>
      )}

      <div className="my-3 flex flex-wrap items-center gap-x-4 gap-y-2.5 sm:mt-4">
        <p className="text-sm font-medium text-neutral-300">{seasonWatchLine(here)}</p>
        {mark && (
          <button type="button" disabled={markSeason.isPending} onClick={() => markSeason.mutate({ showId: s.id, number: se.number, watched: mark.watched })} className={smallBtn}>
            {mark.label}
          </button>
        )}
        {options.data?.shows && se.number > 0 && canRequestSeason(se, today) && (
          <button type="button" onClick={() => setRequesting(true)} className={smallBtn}>
            ＋ Request {seasonName(se.number).toLowerCase()}
          </button>
        )}
        {seasonRequested(se) && (
          <span className="inline-flex items-center gap-1.5 rounded-full border border-line-2 px-2.5 py-0.5 text-xs text-neutral-300">
            <span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-neutral-400" />
            Requested
          </span>
        )}
        {options.data?.manage && s.managedBy && missingAired > 0 && (
          <button type="button" disabled={search.isPending} onClick={() => search.mutate({ season: se.number })} className={smallBtn}>
            {search.isSuccess ? 'Sonarr is searching' : '⌕ Search now'}
          </button>
        )}
        {options.data?.manage && se.monitored === false && (
          <button type="button" disabled={monitor.isPending || monitor.isSuccess} onClick={() => monitor.mutate({ season: se.number, monitored: true })} className={smallBtn}>
            Monitor
          </button>
        )}
        {(markSeason.error ?? search.error ?? monitor.error) && <span className="text-sm text-warn">{(markSeason.error ?? search.error ?? monitor.error)?.message}</span>}
        <div className="ml-auto flex items-center gap-2">
          <button type="button" role="switch" aria-checked={showMissing} onClick={() => onShowMissing(!showMissing)} className="inline-flex min-h-[44px] items-center gap-2.5 px-1 text-sm font-medium">
            <span aria-hidden="true" className={`relative block h-5 w-9 rounded-full ${showMissing ? 'bg-accent' : 'bg-neutral-700'}`}>
              <span className={`absolute top-0.5 h-4 w-4 rounded-full ${showMissing ? 'left-[18px] bg-ground' : 'left-0.5 bg-neutral-300'}`} />
            </span>
            Show missing
          </button>
          <button type="button" aria-label="Scroll episodes left" onClick={() => scrollRow(-1)} className="hidden h-9 w-9 place-items-center rounded-md border border-line-2 bg-surface text-neutral-300 hover:bg-surface-2 sm:grid">
            <Chevron left />
          </button>
          <button type="button" aria-label="Scroll episodes right" onClick={() => scrollRow(1)} className="hidden h-9 w-9 place-items-center rounded-md border border-line-2 bg-surface text-neutral-300 hover:bg-surface-2 sm:grid">
            <Chevron />
          </button>
        </div>
      </div>
      {deleted && (
        <p role="status" className="mb-3 flex items-center gap-2.5 rounded-[10px] border border-emerald-900 bg-emerald-950/60 px-4 py-3.5 text-[15px] font-medium text-emerald-300">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="shrink-0">
            <path d="m5 12 5 5 9-10" />
          </svg>
          {deleted}
        </p>
      )}
      {se.overview && <p className="mb-3.5 max-w-3xl text-sm leading-relaxed text-neutral-400">{se.overview}</p>}

      <ul ref={list} className="relative -mx-4 flex gap-3 overflow-x-auto px-4 pb-2 pt-0.5 sm:mx-0 sm:px-0.5">
        {episodes.map((x) => {
          const sel = x.id === current
          const pct = watchedPct(x)
          const min = episodeMinutes(x)
          const st = x.hasFile ? null : missingStatus(x)
          const label = `${seasonName(se.number)}, episode ${x.number}: ${x.title || `Episode ${x.number}`}${x.watch?.completed ? ', watched' : ''}${st ? `, ${st.label}` : ''}`
          return (
            <li key={x.id} className="flex w-[10.5rem] shrink-0 sm:w-[13.5rem]">
              <Link
                to="/shows/$showId/seasons/$number/episodes/$episodeId"
                params={{ showId, number, episodeId: String(x.id) }}
                replace
                aria-current={sel ? 'page' : undefined}
                aria-label={label}
                className={`flex min-w-0 flex-1 flex-col gap-2 rounded-lg border p-2 ${
                  sel ? 'border-accent bg-surface-2 shadow-[inset_0_0_0_1px_var(--color-accent)]' : st ? 'border-dashed border-neutral-700 bg-ground hover:bg-surface' : 'border-line bg-surface hover:bg-surface-2'
                }`}
              >
                {st ? (
                  <span className="relative grid aspect-video place-items-center overflow-hidden rounded-md border border-dashed border-neutral-700 text-xs font-semibold text-neutral-400">
                    {st.thumb}
                    {st.progress !== undefined && (
                      <span className="absolute inset-x-0 bottom-0 h-1 bg-line-2">
                        <span className="block h-full bg-accent" style={{ width: `${Math.round(st.progress * 100)}%` }} />
                      </span>
                    )}
                  </span>
                ) : (
                  <span className="relative block overflow-hidden rounded-md">
                    <Poster path={x.stillPath ?? s.backdropPath} size="w300" alt="" ratio="aspect-video" />
                    {x.watch?.completed && (
                      <span className="absolute left-1.5 top-1.5 grid h-5 w-5 place-items-center rounded-full bg-accent text-black" title="Watched">
                        <Check size={11} />
                      </span>
                    )}
                    {x.isNew && !x.watch?.completed && (
                      <Badge tone="accent" className="absolute left-1.5 top-1.5">
                        New
                      </Badge>
                    )}
                    {min > 0 && <span className="absolute bottom-2 right-1.5 rounded bg-black/70 px-1.5 py-0.5 text-[11px] font-bold tracking-wide">{min} min</span>}
                    {pct > 0 && (
                      <span className="absolute inset-x-0 bottom-0 h-1 bg-black/60">
                        <span className="block h-full bg-accent" style={{ width: `${pct}%` }} />
                      </span>
                    )}
                  </span>
                )}
                <span className="flex min-w-0 flex-col gap-0.5">
                  <span className="text-xs text-neutral-400">{rowCode(x)}</span>
                  <span className={`truncate text-sm font-semibold leading-snug ${st ? 'text-neutral-400' : ''}`}>{x.title || `Episode ${x.number}`}</span>
                  {st && st.thumb === 'Missing' && (
                    <span className={`inline-flex min-w-0 items-center gap-1.5 text-xs font-semibold ${toneText[st.tone]}`}>
                      <span aria-hidden="true" className={`h-1.5 w-1.5 shrink-0 rounded-full ${toneDot[st.tone]}`} />
                      <span className="truncate">{st.label}</span>
                    </span>
                  )}
                </span>
              </Link>
            </li>
          )
        })}
      </ul>
      {requesting && <RequestDialog mediaType="tv" tmdbId={s.tmdbId} preselect={[se.number]} onClose={() => setRequesting(false)} />}
    </section>
  )
}

/** About the show: its poster, facts and overview, the show's own actions, the cast, and Details, history and On disk. */
function AboutShow({ show: s, number, showMissing }: { show: ShowDetail; number?: number; showMissing: boolean }) {
  const options = useQuery(requestOptionsQuery)
  const markShow = useMarkShowWatched()
  const manage = useCanManage()
  const auth = useAuth()
  // Fixing the match and changing the poster are for admins (the server refuses members).
  const admin = (auth.user?.isAdmin ?? false) && auth.adminAllowed
  const navigate = useNavigate()
  const { pick } = usePickSeason(s)
  const [acting, setActing] = useState<'fix' | 'poster' | 'request' | 'deleteSeason' | 'deleteShow' | null>(null)
  const today = new Date().toISOString().slice(0, 10)
  const askable = options.data?.shows ? askableSeasons(s.seasons, today) : []
  const mark = showMark(s.seasons)
  const open = s.seasons.find((x) => x.number === number)
  // Seasons with something on disk, Specials not counted.
  const onDisk = s.seasons.filter((x) => x.number > 0 && x.availableCount > 0).length
  const pill =
    s.status === 'Ended' || s.status === 'Canceled'
      ? { label: s.status, tone: 'neutral' as const }
      : s.nextAirDate
        ? { label: `Continuing · next episode ${formatDate(s.nextAirDate)}`, tone: 'accent' as const }
        : s.status
          ? { label: s.status, tone: 'accent' as const }
          : undefined

  const items: MoreItem[] = []
  if (admin) items.push({ label: 'Change poster…', icon: 'poster', onSelect: () => setActing('poster') })
  if (manage) {
    if (open) items.push({ label: `Delete ${seasonName(open.number)}…`, icon: 'delete', danger: true, divider: true, onSelect: () => setActing('deleteSeason') })
    items.push({ label: 'Delete show…', icon: 'delete', danger: true, divider: !open, onSelect: () => setActing('deleteShow') })
  }

  return (
    <section aria-labelledby="about-head" className="mt-12 grid gap-8 border-t border-line pt-8 lg:grid-cols-[minmax(0,1fr)_280px]">
      <div className="flex min-w-0 flex-col gap-6">
        <div className="flex items-start gap-4 sm:gap-5">
          <span className="w-20 shrink-0 sm:w-28" aria-hidden="true">
            <Poster path={s.posterPath} size="w185" alt="" className="ring-1 ring-line-2" />
          </span>
          <div className="flex min-w-0 flex-col gap-2">
            <h2 id="about-head" className="text-xl font-bold sm:text-2xl">
              {s.title}
            </h2>
            <p className="text-sm text-neutral-300">
              {[showYears(s), plural(onDisk, 'season'), plural(s.episodeCount, 'episode'), s.genres.slice(0, 2).join(', '), s.rating ? `★ ${s.rating.toFixed(1)}` : ''].filter(Boolean).join(' · ')}
            </p>
            {pill && (
              <span className={`inline-flex min-h-6 items-center self-start rounded-full px-2.5 text-xs font-semibold ${pill.tone === 'accent' ? 'bg-accent/10 text-accent' : 'bg-surface-2 text-neutral-300'}`}>
                {pill.label}
              </span>
            )}
            {s.overview && <p className="mt-1 hidden max-w-3xl text-[15px] leading-relaxed text-neutral-200 sm:block">{s.overview}</p>}
          </div>
        </div>
        {s.overview && <p className="-mt-2 text-sm leading-relaxed text-neutral-200 sm:hidden">{s.overview}</p>}
        <div className="flex flex-wrap items-center gap-2">
          {mark && (
            <button type="button" disabled={markShow.isPending} onClick={() => markShow.mutate({ showId: s.id, watched: mark.watched })} className={secondaryBtn}>
              {mark.label}
            </button>
          )}
          {askable.length > 0 && (
            <button type="button" onClick={() => setActing('request')} className={secondaryBtn}>
              ＋ Request missing seasons
            </button>
          )}
          {admin && (
            <button type="button" onClick={() => setActing('fix')} className={secondaryBtn}>
              Fix match
            </button>
          )}
          <MoreMenu tall label={`More for ${s.title}`} items={items} />
          {markShow.error && <span className="text-sm text-warn">{markShow.error.message}</span>}
        </div>
        <CastRow cast={s.credits} />
      </div>
      <aside className="space-y-4">
        <SideCard
          title="Details"
          rows={[
            ['Original title', s.originalTitle && s.originalTitle !== s.title ? s.originalTitle : ''],
            ['First aired', formatDate(s.firstAirDate)],
            ['Last aired', formatDate(s.lastAirDate)],
            ['Next episode', formatDate(s.nextAirDate)],
            ['Status', s.status ?? ''],
            ['Genres', s.genres.join(', ')],
            ['Rating', s.rating ? `${s.rating.toFixed(1)} / 10` : ''],
            [
              'TMDB',
              <a key="tmdb" href={`https://www.themoviedb.org/tv/${s.tmdbId}`} target="_blank" rel="noreferrer" className="underline">
                {s.tmdbId}
              </a>,
            ],
          ]}
        />
        <HistoryCard show={s.id} />
        <SideCard
          title="On disk"
          rows={[
            ['Episodes', String(s.episodeCount)],
            ['Files', String(s.fileCount)],
            ['Size', formatBytes(s.totalSize)],
            ['Best quality', [s.quality, s.hdr ? 'HDR' : ''].filter(Boolean).join(' · ')],
            ['Last added', s.addedAt ? relativeTime(s.addedAt) : ''],
          ]}
        />
      </aside>

      {acting === 'fix' && (
        <RematchDialog
          kind="show"
          current={{ id: s.id, libraryId: s.libraryId, tmdbId: s.tmdbId, title: s.title, year: s.year, files: s.fileCount }}
          onClose={() => setActing(null)}
        />
      )}
      {acting === 'poster' && (
        <ChangePosterDialog
          title={s.title}
          targets={[
            { kind: 'show', id: s.id, label: 'The show' },
            // The open season, whose poster the apps draw on its own.
            ...(open ? [{ kind: 'season' as const, id: open.id, label: seasonName(open.number) }] : []),
          ]}
          onClose={() => setActing(null)}
        />
      )}
      {acting === 'request' && <RequestDialog mediaType="tv" tmdbId={s.tmdbId} preselect={askable} onClose={() => setActing(null)} />}
      {acting === 'deleteShow' && <DeleteDialog item={{ type: 'show', id: s.id }} name={s.title} onClose={() => setActing(null)} onDeleted={() => void navigate({ to: '/tv' })} />}
      {acting === 'deleteSeason' && open && (
        <DeleteDialog
          item={{ type: 'season', showId: s.id, season: open.number }}
          name={s.title}
          season={open.number}
          onClose={() => setActing(null)}
          onDeleted={() => {
            // The next season's tab, as when arriving at it (§3); the show's address when none is left.
            setActing(null)
            const n = afterSeasonDelete(seasonTabs(s.seasons, showMissing, open.number), open.number)
            if (n !== undefined) void pick(n)
            else void navigate({ to: '/shows/$showId', params: { showId: String(s.id) }, replace: true })
          }}
        />
      )}
    </section>
  )
}
