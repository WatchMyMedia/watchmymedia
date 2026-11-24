import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useRouterState } from '@tanstack/react-router'
import { fileQuery, seasonQuery, showQuery } from '../api/queries'
import { useCanDelete, useCanManage } from '../api/delete'
import { requestOptionsQuery, useMonitorEpisodes, useSearchMissing } from '../api/requests'
import type { components } from '../api/schema'
import { useMarkSeasonWatched, useMarkShowWatched, useMarkWatched } from '../api/watch'
import { useAuth } from '../authContext'
import { ChangePosterDialog } from '../components/ChangePosterDialog'
import { DeleteDialog, ReplaceFileDialog } from '../components/DeleteDialog'
import { RecordingProvenance } from '../components/liveTV/RecordingProvenance'
import { MoreMenu, type MoreItem } from '../components/MoreMenu'
import { useNotifyMe } from '../components/newEpisodeHooks'
import { FollowExplainer, NotifyMeButton, NotifyMeFailure } from '../components/NotifyMe'
import { Poster } from '../components/Poster'
import { RematchDialog } from '../components/RematchDialog'
import { ReportProblemDialog } from '../components/ReportProblemDialog'
import { RelatedRows } from '../components/rows/RelatedRows'
import { RequestDialog } from '../components/RequestDialog'
import { SegmentEditorDialog } from '../components/SegmentEditorDialog'
import { BackLink, DetailsPanel, FactsLine, PeopleRow, TitleBand, WatchedHere } from '../components/TitleParts'
import { usePlans } from '../components/usePlans'
import { EpisodeFileDetails, TrackChoices, type TrackChoice } from '../components/TitleFiles'
import { CheckIcon, PlayIcon } from '../components/icons'
import { useEpisodesView } from '../components/useEpisodesView'
import { useNow } from '../components/useNow'
import { episodeActions, episodeFacts, episodeLine, episodeMinutes, isPartlyWatched, pageEpisodes, rowCode, showYears } from '../lib/episode'
import { endsAt, leftAndEnds } from '../lib/endTime'
import { formatDate, plural, seCode } from '../lib/format'
import { canRequestSeason, missingStatus, toneDot, toneText } from '../lib/requests'
import { scoreWords } from '../lib/cardFacts'
import { crewLine } from '../lib/personPage'
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
  showMark,
  type Arrival,
  type OnTopReason,
} from '../lib/showPage'
import { useShowMissing } from '../lib/showMissing'
import { HIDDEN_SYNOPSIS, episodeRowFacts, hasTrackChoice, episodeRowState, playChoices, seasonLine, seasonTabWords, showState, spoilerHidden } from '../lib/titlePage'
import { useHideSpoilers } from '../components/useHideSpoilers'
import { watchlistTitle } from '../lib/watchlists'
import { hasShowTracks, pickBody, rememberedChoice } from '../lib/showTracks'
import { forgetShowTracks, setShowTracks } from '../api/showTracks'
import { showToast } from '../lib/toast'
import { WatchlistButton } from '../components/watchlists/WatchlistButton'

type Episode = components['schemas']['Episode']
type SeasonDetail = components['schemas']['SeasonDetail']
type ShowDetail = components['schemas']['ShowDetail']

const watchedPct = (e: Episode) => (e.watch && isPartlyWatched(e) ? Math.round((e.watch.positionMs / Math.max(e.watch.durationMs || e.files[0]?.durationMs || 0, 1)) * 100) : 0)

/** The file an episode plays: the version its saved place belongs to, else its best. */
const playFile = (e: Episode) => (e.watch?.fileId ? e.files.find((f) => f.id === e.watch?.fileId) : undefined) ?? e.files[0]

/**
 * The one show page: the show's, a season's and an episode's addresses all
 * draw it, and only the episode on top differs. The show's name, state and
 * Following lead; the episode to watch next is under it; then the season
 * tabs and the season's episodes, as a list or a strip; then About the show.
 * Picking a tab or an episode replaces the address with the episode's, so
 * Back leaves the show in one step.
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

  let top: React.ReactNode
  let below: React.ReactNode = null
  if (number === undefined) top = <p className="mt-6 text-neutral-300">{s.title} has no seasons yet.</p>
  else if (season.isPending) top = <p className="mt-6 text-neutral-400">Loading…</p>
  else if (season.isError) top = <p className="mt-6 text-red-400">{season.error.message}</p>
  else {
    const se = season.data
    const on = episodeOnTop(arrival, se.episodes, s)
    const e = on.episode
    const listed = e ? pageEpisodes(se.episodes, e.id, showMissing) : se.episodes
    top = (
      <>
        {on.gone && (
          <p role="status" className="mt-6 text-neutral-300">
            That episode isn&rsquo;t in {seasonName(se.number)} of {s.title} any more.
          </p>
        )}
        {e ? (
          // Keyed by episode, so moving to another starts without the last one's menu, dialog, choices or error.
          <EpisodeTop key={e.id} show={s} se={se} e={e} why={arrival.kind === 'show' ? on.why : undefined} listed={listed} />
        ) : (
          <p className="mt-6 text-neutral-300">{seasonName(se.number)} has no episodes yet.</p>
        )}
      </>
    )
    below = (
      <SeasonSection
        show={s}
        se={se}
        listed={listed}
        current={e?.id}
        showMissing={showMissing}
        onShowMissing={setShowMissing}
        deleted={deleted && deleted.season === se.number ? deletedLine(deleted.title, deleted.which) : undefined}
      />
    )
  }

  return (
    <div>
      <TitleBand backdropPath={s.backdropPath}>
        <BackLink link={{ to: '/tv' }} label="TV Shows" />
        <ShowHeader show={s} />
        {top}
      </TitleBand>
      {below}
      <AboutShow show={s} number={season.data?.number} showMissing={showMissing} />
      <div className="mt-10">
        <RelatedRows kind="show" id={s.id} />
      </div>
    </div>
  )
}

/** The show's name, facts and state, with Following (Notify me) beside them. */
function ShowHeader({ show: s }: { show: ShowDetail }) {
  // Notify me, on servers from API level 29.
  const notify = useNotifyMe(s)
  const state = showState(s)
  // Seasons with something on disk, Specials not counted.
  const onDisk = s.seasons.filter((x) => x.number > 0 && x.availableCount > 0).length
  return (
    <>
      <div className="mt-2.5 flex flex-wrap items-center gap-x-5 gap-y-3">
        <h1 className="text-[clamp(26px,2.8vw,38px)] font-bold leading-[1.1] tracking-[-.02em]">{s.title}</h1>
        <FactsLine className="text-[14.5px] text-neutral-100" facts={[showYears(s), onDisk ? plural(onDisk, 'season') : '', s.genres.slice(0, 2).join(', '), scoreWords(s.rating)]} rating={s.contentRating} />
        {state && <span className={`pill min-h-[26px] ${state.gold ? 'pill-gold' : ''}`}>{state.label}</span>}
        {notify.able && (
          <span className="md:ml-auto">
            <NotifyMeButton show={s.title} following={notify.following} onPress={notify.press} look="clear" />
          </span>
        )}
      </div>
      {notify.failure && (
        <div className="mt-2">
          <NotifyMeFailure failure={notify.failure} show={s.title} />
        </div>
      )}
      {notify.asking && <FollowExplainer show={s.title} onAnswer={notify.answer} />}
    </>
  )
}

/** The episode on top: its still, facts and synopsis, how much is left, the choices before playing and File details; for a missing one, why it is missing and what can be done. */
function EpisodeTop({ show: s, se, e, why, listed }: { show: ShowDetail; se: SeasonDetail; e: Episode; why?: OnTopReason; listed: Episode[] }) {
  const best = playFile(e)
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
  const now = useNow()
  const plans = usePlans(best ? [best.id] : [])
  const [acting, setActing] = useState<'report' | 'replace' | 'segments' | 'delete' | 'request' | null>(null)
  // Sound and Subtitles start on what this person picked for the show, and a pick here keeps it for the show.
  const qc = useQueryClient()
  const remembered = rememberedChoice(s.tracks, file.data?.probe)
  const [picked, setPicked] = useState<TrackChoice | null>(null)
  const [keepError, setKeepError] = useState<string>()
  const choice = picked ?? remembered
  const choose = (next: TrackChoice) => {
    setPicked(next)
    setKeepError(undefined)
    const body = pickBody(choice, next, file.data?.probe)
    if (body) setShowTracks(qc, s.id, body).catch((err: Error) => setKeepError(err.message))
  }
  const code = seCode(se.number, e.number)
  const title = e.title || `Episode ${e.number}`
  const line = episodeLine(s.title, se.number, e.number)
  const here = episodePath(s.id, se.number, e.id)
  const playTitle = `${s.title} · ${code} ${title}`
  const { primary, fromStart, mark: toggle } = episodeActions(e)
  const minutes = episodeMinutes(e)
  const pct = watchedPct(e)
  const status = !e.hasFile ? missingStatus(e) : null
  const today = new Date().toISOString().slice(0, 10)
  const choices = playChoices({ audio: choice.audio === '' ? undefined : Number(choice.audio), subtitle: choice.subtitle || undefined })
  const durationMs = e.watch?.durationMs || best?.durationMs || (minutes ? minutes * 60_000 : 0)
  const left = isPartlyWatched(e) && e.watch ? leftAndEnds(e.watch.positionMs, durationMs, now) : undefined
  const facts = episodeFacts(e).filter((f) => !f.endsWith(' left'))
  const gap = missingActions(e, {
    admin: !!options.data?.manage,
    canRequest: !!options.data?.shows,
    seasonAskable: se.number > 0 && canRequestSeason(se, today),
    managed: !!s.managedBy,
    seasonUnmonitored: se.monitored === false,
  })
  // "Up next" for the next or the one to resume; the chip's other reasons as they are.
  const reason = why === 'next' || why === 'resume' ? 'Up next' : why ? onTopChip[why] : ''
  const eyebrow = [reason, seasonName(se.number), `Episode ${e.number}`].filter(Boolean).join(' · ')

  const items: MoreItem[] = []
  if (e.hasFile) {
    items.push({ label: 'Report a problem…', icon: 'flag', onSelect: () => setActing('report') })
    if (manage && s.managedBy && best) items.push({ label: 'Replace file…', icon: 'replace', onSelect: () => setActing('replace') })
    if (admin && best) items.push({ label: 'Skip segments…', icon: 'skip', onSelect: () => setActing('segments') })
    if (canDelete) items.push({ label: 'Delete episode…', icon: 'delete', divider: true, onSelect: () => setActing('delete') })
  }
  const playLabel = primary.kind === 'play' ? (fromStart ? 'Resume' : 'Play') : ''
  const hidden = spoilerHidden(e, useHideSpoilers().on)

  const still = (
    <>
      {status ? (
        <span className="grid size-full place-items-center text-[15px] font-semibold text-neutral-400">{status.thumb}</span>
      ) : (
        <Poster path={e.stillPath ?? s.backdropPath} size="w780" alt="" ratio="aspect-video" className={`rounded-none ${hidden ? 'opacity-35' : ''}`} />
      )}
      {e.isNew && !e.watch?.completed && <span className="absolute left-2.5 top-2.5 rounded-[5px] bg-accent px-[7px] py-0.5 text-[11px] font-bold text-ground">New</span>}
      {e.watch?.completed && (
        <span role="img" aria-label="Watched" className="absolute right-2.5 top-2.5 grid size-[22px] place-items-center rounded-full bg-ground/80 text-accent ring-1 ring-inset ring-accent/70">
          <CheckIcon size={13} />
        </span>
      )}
      {minutes > 0 && e.hasFile && <span className="absolute bottom-3.5 right-2.5 rounded-[5px] bg-ground/80 px-[7px] py-0.5 text-[11px] font-bold ring-1 ring-inset ring-white/15">{minutes} min</span>}
      {(pct > 0 || status?.progress !== undefined) && (
        <span aria-hidden="true" className="absolute inset-x-0 bottom-0 h-[5px] bg-black/60">
          <span className="block h-full bg-accent" style={{ width: `${status?.progress !== undefined ? Math.round(status.progress * 100) : pct}%` }} />
        </span>
      )}
    </>
  )
  const stillBox = `relative block aspect-video w-full max-w-[520px] flex-[1_1_380px] self-start overflow-hidden rounded-xl bg-surface shadow-[0_22px_44px_rgba(0,0,0,.5)] ${status ? 'border border-dashed border-line-3' : 'ring-1 ring-white/10'}`

  return (
    <section aria-labelledby="up-next" className="mt-6 flex flex-wrap items-start gap-x-9 gap-y-5 md:mt-[26px]">
      {primary.kind === 'play' ? (
        <Link to="/play/$fileId" params={{ fileId: String(primary.fileId) }} search={{ start: primary.start, title: playTitle, back: here, ...choices }} tabIndex={-1} aria-hidden="true" className={`${stillBox} hover:ring-2 hover:ring-accent`}>
          {still}
        </Link>
      ) : (
        <div className={stillBox}>{still}</div>
      )}

      <div className="flex min-w-0 max-w-[760px] flex-[1_1_400px] flex-col gap-2.5">
        <p className="eyebrow">{eyebrow}</p>
        <h2 id="up-next" className={`text-[clamp(24px,2.6vw,34px)] font-bold leading-[1.1] tracking-[-.02em] ${status ? 'text-neutral-300' : ''}`}>
          {title}
        </h2>
        {facts.length > 0 && <FactsLine facts={facts} />}
        <RecordingProvenance recording={best?.recording} />
        {e.overview && <p className={`max-w-[66ch] text-[15px] leading-[1.55] ${hidden ? 'text-neutral-400' : 'text-neutral-200'}`}>{hidden ? HIDDEN_SYNOPSIS : e.overview}</p>}
        {why === 'again' && <p className="text-sm text-neutral-300">You&rsquo;ve watched every episode. Start again here, or pick any episode below.</p>}
        {status && (
          <p className={`mt-1 inline-flex items-center gap-2 text-[15px] font-semibold ${toneText[status.tone]}`}>
            <span aria-hidden="true" className={`size-2 shrink-0 rounded-full ${toneDot[status.tone]}`} />
            {status.label}
          </p>
        )}

        <div className="mt-1.5 flex flex-wrap items-center gap-2.5">
          {primary.kind === 'play' && (
            <Link to="/play/$fileId" params={{ fileId: String(primary.fileId) }} search={{ start: primary.start, title: playTitle, back: here, ...choices }} aria-label={`${playLabel} ${title}`} className="btn btn-primary btn-lg max-md:flex-1">
              <PlayIcon />
              {playLabel}
            </Link>
          )}
          {fromStart && (
            <Link to="/play/$fileId" params={{ fileId: String(fromStart.fileId) }} search={{ start: fromStart.start, title: playTitle, back: here, ...choices }} className="btn btn-clear btn-lg max-md:flex-1">
              {fromStart.label}
            </Link>
          )}
          {toggle && (
            <button
              type="button"
              aria-pressed={!toggle.watched}
              disabled={mark.isPending}
              onClick={() => mark.mutate({ type: 'episode', id: e.id, watched: toggle.watched })}
              className={`btn btn-clear btn-lg ${toggle.watched ? '' : 'text-accent'}`}
            >
              <CheckIcon />
              {toggle.watched ? 'Mark watched' : 'Watched'}
            </button>
          )}
          <MoreMenu look="hero" label={`More for this episode: ${title}`} items={items} />
          {gap.request && (
            <button type="button" onClick={() => setActing('request')} className="btn btn-primary btn-lg">
              Request {seasonName(se.number).toLowerCase()}
            </button>
          )}
          {gap.requested && <span className="pill">Requested</span>}
          {gap.search && (
            <button type="button" disabled={search.isPending} onClick={() => search.mutate({ season: se.number })} className="btn btn-clear btn-lg">
              {search.isSuccess ? 'Sonarr is searching' : 'Search now'}
            </button>
          )}
          {gap.monitor && (
            <button type="button" disabled={monitor.isPending || monitor.isSuccess} onClick={() => monitor.mutate({ season: se.number, monitored: true })} className="btn btn-clear btn-lg">
              Monitor {seasonName(se.number).toLowerCase()}
            </button>
          )}
          {gap.inbox && (
            <Link to="/settings/inbox" className="btn btn-clear btn-lg">
              Open in Inbox
            </Link>
          )}
          {gap.coming && <span className="text-sm text-neutral-300">Nothing to do yet. It appears here when it arrives.</span>}
        </div>
        {(mark.error ?? search.error ?? monitor.error) && <p className="text-sm text-warn">{(mark.error ?? search.error ?? monitor.error)?.message}</p>}
        {left ? <p className="text-[13.5px]">{left}</p> : primary.kind === 'play' && durationMs > 0 && <p className="text-[13.5px] text-neutral-300">Start now and it ends at {endsAt(durationMs, now)}.</p>}
        {best && (
          <div className="flex flex-wrap items-center gap-2">
            <TrackChoices probe={file.data?.probe} choice={choice} onChange={choose} />
            {hasShowTracks(s.tracks) && !keepError && hasTrackChoice(file.data?.probe) && <span className="text-[13px] text-neutral-400 max-md:hidden">Remembered for this show.</span>}
            {keepError && <span className="text-[13px] text-warn">Not kept for the show: {keepError}</span>}
          </div>
        )}
        {best && (
          <div className="mt-1.5">
            <EpisodeFileDetails file={best} plan={plans.get(best.id)} admin={auth.user?.isAdmin ?? false} probe={file.data?.probe} />
          </div>
        )}
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
            // Stay on the show: the next episode in the season moves on top, else the one before.
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

/** The season tabs (a plain heading for a show with one season), the season's line and actions, and its episodes as a list or a strip. */
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
  const [view, setView] = useEpisodesView()
  const spoilers = useHideSpoilers()
  const { pick, preload, pending } = usePickSeason(s)
  const tabs = seasonTabs(s.seasons, showMissing, se.number)
  const here = s.seasons.find((x) => x.number === se.number) ?? se
  const mark = seasonMark(here)
  const today = new Date().toISOString().slice(0, 10)
  const missingAired = se.episodes.filter((e) => !e.hasFile && e.aired).length
  const episodes = [...listed].sort((a, b) => a.number - b.number)
  const name = (x: { number: number; name: string }) => (x.number === 0 ? 'Specials' : x.name || seasonName(x.number))
  const tabKeys = (e: React.KeyboardEvent) => {
    if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return
    const all = [...e.currentTarget.querySelectorAll<HTMLButtonElement>('[role="tab"]')]
    const i = all.indexOf(document.activeElement as HTMLButtonElement)
    all[(i + (e.key === 'ArrowRight' ? 1 : all.length - 1)) % all.length]?.focus()
    e.preventDefault()
  }

  return (
    <section aria-labelledby="season-head" className="mt-2">
      {tabs.length > 1 ? (
        <>
          <h2 id="season-head" className="sr-only">
            Seasons and episodes
          </h2>
          <div role="tablist" aria-label="Seasons" onKeyDown={tabKeys} className="tabs -mx-[clamp(16px,3.4vw,48px)] px-[clamp(16px,3.4vw,48px)] md:mx-0 md:px-0">
            {tabs.map((t) => {
              const on = t.number === se.number
              return (
                <button
                  key={t.id}
                  type="button"
                  role="tab"
                  aria-selected={on}
                  tabIndex={on ? 0 : -1}
                  aria-busy={pending === t.number || undefined}
                  onPointerEnter={() => !on && preload(t.number)}
                  onFocus={() => !on && preload(t.number)}
                  onClick={() => !on && void pick(t.number)}
                  className={`tab ${t.availableCount === 0 && !on ? 'opacity-60' : ''}`}
                >
                  {name(t)}
                  <span className="n">{seasonTabWords(t, showMissing)}</span>
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

      <div className="mb-2 mt-3.5 flex flex-wrap items-center gap-x-[18px] gap-y-2.5">
        <p className="text-[13.5px] text-neutral-300">{seasonLine(se.episodes)}</p>
        {mark && (
          <button type="button" disabled={markSeason.isPending} onClick={() => markSeason.mutate({ showId: s.id, number: se.number, watched: mark.watched })} className="btn btn-sm">
            {mark.label}
          </button>
        )}
        {options.data?.shows && se.number > 0 && canRequestSeason(se, today) && (
          <button type="button" onClick={() => setRequesting(true)} className="btn btn-sm">
            Request {seasonName(se.number).toLowerCase()}
          </button>
        )}
        {seasonRequested(se) && <span className="pill">Requested</span>}
        {options.data?.manage && s.managedBy && missingAired > 0 && (
          <button type="button" disabled={search.isPending} onClick={() => search.mutate({ season: se.number })} className="btn btn-sm">
            {search.isSuccess ? 'Sonarr is searching' : 'Search now'}
          </button>
        )}
        {options.data?.manage && se.monitored === false && (
          <button type="button" disabled={monitor.isPending || monitor.isSuccess} onClick={() => monitor.mutate({ season: se.number, monitored: true })} className="btn btn-sm">
            Monitor
          </button>
        )}
        {(markSeason.error ?? search.error ?? monitor.error) && <span className="text-sm text-warn">{(markSeason.error ?? search.error ?? monitor.error)?.message}</span>}
        <span className="flex flex-wrap items-center gap-x-[18px] gap-y-2 md:ml-auto">
          <Switch on={spoilers.on} onChange={spoilers.set} label="Hide spoilers" />
          <Switch on={showMissing} onChange={onShowMissing} label="Show missing" />
          <span className="seg" role="group" aria-label="Episodes as">
            <button type="button" aria-pressed={view === 'list'} onClick={() => setView('list')}>
              List
            </button>
            <button type="button" aria-pressed={view === 'strip'} onClick={() => setView('strip')}>
              Strip
            </button>
          </span>
        </span>
      </div>
      {deleted && (
        <p role="status" className="mb-3 flex items-center gap-2.5 rounded-[10px] border border-emerald-900 bg-emerald-950/60 px-4 py-3.5 text-[15px] font-medium text-emerald-300">
          <CheckIcon />
          {deleted}
        </p>
      )}
      {se.overview && <p className="mb-3.5 max-w-3xl text-sm leading-relaxed text-neutral-400">{se.overview}</p>}

      {spoilers.error && <p className="mb-3 text-sm text-warn">Hide spoilers wasn’t saved: {spoilers.error}</p>}
      {view === 'list' ? (
        <EpisodeList show={s} se={se} episodes={episodes} current={current} hide={spoilers.on} />
      ) : (
        <EpisodeStrip show={s} se={se} episodes={episodes} current={current} hide={spoilers.on} />
      )}
      {requesting && <RequestDialog mediaType="tv" tmdbId={s.tmdbId} preselect={[se.number]} onClose={() => setRequesting(false)} />}
    </section>
  )
}

/** Puts an episode on top by replacing the address, and brings the top into view. */
function onTopLink(s: ShowDetail, se: SeasonDetail, x: Episode) {
  return {
    to: '/shows/$showId/seasons/$number/episodes/$episodeId' as const,
    params: { showId: String(s.id), number: String(se.number), episodeId: String(x.id) },
    replace: true,
    onClick: () => window.scrollTo({ top: 0, behavior: 'smooth' }),
  }
}

/** The season's episodes as a list: number, still, title, when it aired and how long, the synopsis, where you are, and Play. */
function EpisodeList({ show: s, se, episodes, current, hide }: { show: ShowDetail; se: SeasonDetail; episodes: Episode[]; current?: number; hide: boolean }) {
  return (
    <ol className="flex flex-col gap-0.5">
      {episodes.map((x) => {
        const sel = x.id === current
        const title = x.title || `Episode ${x.number}`
        const st = x.hasFile ? null : missingStatus(x)
        const state = episodeRowState(x)
        const pct = watchedPct(x)
        const file = playFile(x)
        const play = file
          ? { to: '/play/$fileId' as const, params: { fileId: String(file.id) }, search: { title: `${s.title} · ${seCode(se.number, x.number)} ${title}`, back: episodePath(s.id, se.number, x.id) } }
          : undefined
        const playName = `${state?.gold ? 'Resume' : 'Play'} episode ${x.number}, ${title}`
        const hidden = spoilerHidden(x, hide)
        return (
          <li
            key={x.id}
            className={`flex items-center gap-4 rounded-xl border p-3 max-md:grid max-md:grid-cols-[112px_minmax(0,1fr)_auto] max-md:gap-3 max-md:py-2.5 ${
              sel ? 'border-accent bg-surface max-md:-mx-2 max-md:px-2' : 'border-transparent hover:bg-surface max-md:px-0'
            }`}
          >
            <span aria-hidden="true" className="w-7 shrink-0 text-center text-[15px] font-semibold text-neutral-400 max-md:hidden">
              {x.number}
            </span>
            {st || !play ? (
              <span className="relative grid aspect-video w-[168px] shrink-0 place-items-center overflow-hidden rounded-lg border border-dashed border-line-3 text-xs font-semibold text-neutral-400 max-md:w-full">
                {st?.thumb ?? 'Not in library'}
                {st?.progress !== undefined && (
                  <span className="absolute inset-x-0 bottom-0 h-1 bg-line-2">
                    <span className="block h-full bg-accent" style={{ width: `${Math.round(st.progress * 100)}%` }} />
                  </span>
                )}
              </span>
            ) : (
              <Link {...play} tabIndex={-1} aria-hidden="true" className="relative block w-[168px] shrink-0 overflow-hidden rounded-lg ring-1 ring-inset ring-white/[.07] hover:ring-2 hover:ring-accent max-md:w-full">
                <Poster path={x.stillPath ?? s.backdropPath} size="w300" alt="" ratio="aspect-video" className={`rounded-none ${hidden ? 'opacity-35' : ''}`} />
                {x.watch?.completed && (
                  <span className="absolute right-1.5 top-1.5 grid size-5 place-items-center rounded-full bg-ground/80 text-accent ring-1 ring-inset ring-accent/70">
                    <CheckIcon size={11} />
                  </span>
                )}
                {pct > 0 && (
                  <span className="absolute inset-x-0 bottom-0 h-1 bg-black/60">
                    <span className="block h-full bg-accent" style={{ width: `${pct}%` }} />
                  </span>
                )}
              </Link>
            )}
            <div className="min-w-0 flex-[1_1_220px]">
              <p className="flex flex-wrap items-center gap-x-2.5 gap-y-1">
                <span className="text-[13px] text-neutral-400 md:hidden">E{x.number}</span>
                <Link {...onTopLink(s, se, x)} aria-current={sel ? 'page' : undefined} className={`text-[15px] font-semibold hover:text-accent ${st ? 'text-neutral-400' : ''}`}>
                  {title}
                </Link>
                {x.isNew && !x.watch?.completed && x.hasFile && <span className="pill pill-gold">New</span>}
              </p>
              <p className="mt-0.5 text-[13px] text-neutral-400">{episodeRowFacts(x)}</p>
              {st && st.thumb === 'Missing' && (
                <p className={`mt-0.5 inline-flex items-center gap-1.5 text-xs font-semibold ${toneText[st.tone]}`}>
                  <span aria-hidden="true" className={`size-1.5 shrink-0 rounded-full ${toneDot[st.tone]}`} />
                  {st.label}
                </p>
              )}
              {x.overview && <p className={`mt-1 line-clamp-2 max-w-[86ch] text-[13.5px] max-md:hidden ${hidden ? 'text-neutral-400' : 'text-neutral-300'}`}>{hidden ? HIDDEN_SYNOPSIS : x.overview}</p>}
            </div>
            {state && <span className={`whitespace-nowrap text-[13px] max-md:hidden ${state.gold ? 'text-accent' : 'text-neutral-400'}`}>{state.text}</span>}
            {play ? (
              <Link {...play} aria-label={playName} className="ib shrink-0">
                <PlayIcon size={17} />
              </Link>
            ) : (
              <span className="size-10 shrink-0 max-md:hidden" />
            )}
          </li>
        )
      })}
    </ol>
  )
}

/** The season's episodes as a strip of stills, the one on top outlined in gold. */
function EpisodeStrip({ show: s, se, episodes, current, hide }: { show: ShowDetail; se: SeasonDetail; episodes: Episode[]; current?: number; hide: boolean }) {
  const list = useRef<HTMLUListElement>(null)
  // Bring the episode on top into the strip's view without scrolling the page.
  useEffect(() => {
    const ul = list.current
    const li = ul?.querySelector<HTMLElement>('[aria-current="page"]')?.parentElement
    if (!ul || !li) return
    ul.scrollLeft = li.offsetLeft - ul.offsetLeft - (ul.clientWidth - li.clientWidth) / 2
  }, [current, se.number])
  return (
    <ul ref={list} className="scrollbar-hide -mx-1 flex gap-3 overflow-x-auto px-1 pb-2 pt-1 md:gap-4">
      {episodes.map((x) => {
        const sel = x.id === current
        const pct = watchedPct(x)
        const min = episodeMinutes(x)
        const st = x.hasFile ? null : missingStatus(x)
        const label = `${seasonName(se.number)}, episode ${x.number}: ${x.title || `Episode ${x.number}`}${x.watch?.completed ? ', watched' : ''}${st ? `, ${st.label}` : ''}`
        return (
          <li key={x.id} className="flex w-[11rem] shrink-0 md:w-[232px]">
            <Link
              {...onTopLink(s, se, x)}
              aria-current={sel ? 'page' : undefined}
              aria-label={label}
              className={`group flex min-w-0 flex-1 flex-col rounded-lg ${sel ? 'outline-2 outline-offset-2 outline-accent' : ''}`}
            >
              {st ? (
                <span className="relative grid aspect-video place-items-center overflow-hidden rounded-lg border border-dashed border-line-3 text-xs font-semibold text-neutral-400">
                  {st.thumb}
                </span>
              ) : (
                <span className="relative block overflow-hidden rounded-lg ring-1 ring-inset ring-white/[.07] group-hover:ring-2 group-hover:ring-accent">
                  <Poster path={x.stillPath ?? s.backdropPath} size="w300" alt="" ratio="aspect-video" className={`rounded-none ${spoilerHidden(x, hide) ? 'opacity-35' : ''}`} />
                  {x.watch?.completed && (
                    <span className="absolute right-2 top-2 grid size-[22px] place-items-center rounded-full bg-ground/80 text-accent ring-1 ring-inset ring-accent/70">
                      <CheckIcon size={13} />
                    </span>
                  )}
                  {x.isNew && !x.watch?.completed && <span className="absolute left-2 top-2 rounded-[5px] bg-accent px-[7px] py-0.5 text-[11px] font-bold text-ground">New</span>}
                  {min > 0 && <span className="absolute bottom-2 right-2 rounded-[5px] bg-ground/80 px-1.5 py-0.5 text-[11px] font-bold">{min} min</span>}
                  {pct > 0 && (
                    <span className="absolute inset-x-0 bottom-0 h-1 bg-black/60">
                      <span className="block h-full bg-accent" style={{ width: `${pct}%` }} />
                    </span>
                  )}
                </span>
              )}
              <span className="mt-2 truncate text-sm font-medium group-hover:text-accent">
                E{x.number} · {x.title || `Episode ${x.number}`}
              </span>
              <span className="truncate text-[12.5px] text-neutral-400">{st ? rowCode(x) : episodeRowFacts(x)}</span>
            </Link>
          </li>
        )
      })}
    </ul>
  )
}

/** About the show: its poster, synopsis and actions, the cast, and Details and Watched here beside them. */
function AboutShow({ show: s, number, showMissing }: { show: ShowDetail; number?: number; showMissing: boolean }) {
  const options = useQuery(requestOptionsQuery)
  const markShow = useMarkShowWatched()
  const manage = useCanManage()
  const auth = useAuth()
  // Fixing the match and changing the poster are for admins (the server refuses members).
  const admin = (auth.user?.isAdmin ?? false) && auth.adminAllowed
  const navigate = useNavigate()
  const { pick } = usePickSeason(s)
  // Saving keeps the whole show, by its TMDB id; following stays apart.
  const saving = watchlistTitle('show', s.tmdbId)
  const [acting, setActing] = useState<'fix' | 'poster' | 'request' | 'deleteSeason' | 'deleteShow' | null>(null)
  const today = new Date().toISOString().slice(0, 10)
  const askable = options.data?.shows ? askableSeasons(s.seasons, today) : []
  const mark = showMark(s.seasons)
  const open = s.seasons.find((x) => x.number === number)
  const state = showState(s)

  const qc = useQueryClient()
  const items: MoreItem[] = []
  if (hasShowTracks(s.tracks))
    items.push({
      label: 'Forget sound and subtitles',
      onSelect: () =>
        void forgetShowTracks(qc, s.id)
          .then(() => showToast({ text: `${s.title} starts with your language settings again.` }))
          .catch((err: Error) => showToast({ text: `Not forgotten: ${err.message}` })),
    })
  if (admin) items.push({ label: 'Change poster…', icon: 'poster', onSelect: () => setActing('poster') })
  if (manage) {
    if (open) items.push({ label: `Delete ${seasonName(open.number)}…`, icon: 'delete', divider: true, onSelect: () => setActing('deleteSeason') })
    items.push({ label: 'Delete show…', icon: 'delete', divider: !open, onSelect: () => setActing('deleteShow') })
  }

  return (
    <section aria-labelledby="about-head" className="mt-11 flex flex-wrap items-start gap-x-12 gap-y-6 border-t border-line pt-8">
      <div className="flex min-w-0 flex-[999_1_560px] flex-col gap-8">
        <div className="flex flex-wrap gap-x-6 gap-y-4">
          <span className="w-[100px] shrink-0 md:w-[132px]" aria-hidden="true">
            <Poster path={s.posterPath} size="w185" alt="" className="ring-1 ring-inset ring-white/[.07]" />
          </span>
          <div className="flex min-w-0 flex-[1_1_320px] flex-col gap-2.5">
            <h2 id="about-head" className="text-[19px] font-[650]">
              About {s.title}
            </h2>
            {s.overview && <p className="max-w-[70ch] text-[14.5px] leading-[1.55] text-neutral-200">{s.overview}</p>}
            <div className="mt-1 flex flex-wrap items-center gap-2">
              {mark && (
                <button type="button" disabled={markShow.isPending} onClick={() => markShow.mutate({ showId: s.id, watched: mark.watched })} className="btn btn-sm">
                  {mark.label}
                </button>
              )}
              {saving && <WatchlistButton target={saving} title={s.title} holding={s.watchlists ?? []} look="small" />}
              {askable.length > 0 && (
                <button type="button" onClick={() => setActing('request')} className="btn btn-sm">
                  Request missing seasons
                </button>
              )}
              {admin && (
                <button type="button" onClick={() => setActing('fix')} className="btn btn-sm">
                  Fix match…
                </button>
              )}
              <MoreMenu look="small" label={`More for ${s.title}`} items={items} />
              {markShow.error && <span className="text-sm text-warn">{markShow.error.message}</span>}
            </div>
          </div>
        </div>
        <PeopleRow people={s.credits} note={crewLine(s.crew)} small />
      </div>
      <aside className="flex min-w-0 flex-[1_1_300px] flex-col gap-4">
        <DetailsPanel
          title="Details"
          rows={[
            ['Original title', s.originalTitle && s.originalTitle !== s.title ? s.originalTitle : ''],
            ['First aired', formatDate(s.firstAirDate)],
            ['Status', state?.label.split(' · ')[0] ?? ''],
            ['Next episode', formatDate(s.nextAirDate)],
            ['Rated', s.contentRating ?? ''],
            ['Genres', s.genres.join(', ')],
            ['On your server', s.totalEpisodes ? `${s.episodeCount} of ${s.totalEpisodes} episodes` : plural(s.episodeCount, 'episode')],
            [
              'More',
              <a key="tmdb" href={`https://www.themoviedb.org/tv/${s.tmdbId}`} target="_blank" rel="noreferrer" className="underline hover:text-accent">
                TMDB
              </a>,
            ],
          ]}
        />
        <WatchedHere show={s.id} />
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
            // The next season's tab, as when arriving at it; the show's address when none is left.
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

/** A labelled switch in the season's line: Hide spoilers, Show missing. */
function Switch({ on, onChange, label }: { on: boolean; onChange: (on: boolean) => void; label: string }) {
  return (
    <button type="button" role="switch" aria-checked={on} onClick={() => onChange(!on)} className="inline-flex min-h-9 items-center gap-2 text-[13px] text-neutral-300">
      <span aria-hidden="true" className={`relative block h-5 w-[34px] rounded-full ${on ? 'bg-accent' : 'bg-line-3'}`}>
        <span className={`absolute top-[3px] size-3.5 rounded-full ${on ? 'left-[17px] bg-ground' : 'left-[3px] bg-neutral-300'}`} />
      </span>
      {label}
    </button>
  )
}
