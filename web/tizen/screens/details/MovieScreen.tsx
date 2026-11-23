import { useRef, useState, type FocusEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { movieQuery, movieRelatedQuery, playbackPlansQuery } from '../../../src/api/queries'
import { requestOptionsQuery, useReportProblem } from '../../../src/api/requests'
import { useMarkWatched } from '../../../src/api/watch'
import { watchlistTitle } from '../../../src/lib/watchlists'
import type { components } from '../../../src/api/schema'
import { useBack, useEntry, useFocus, useKeys, useNav } from '../../app/hooks'
import type { PlayRequest, ScreenProps } from '../../app/routes'
import { Button } from '../../ui/Button'
import { Clock } from '../../ui/Clock'
import { ProblemScreen, Skeleton, SkeletonText } from '../../ui/States'
import { problemWords } from '../../ui/problemWords'
import { useNow } from '../live/clock'
import { Backdrop, NoticeBanner, PeopleRow, RelatedSection, WatchBar } from './DetailParts'
import { markedMessage, reportSent, useNotice } from './detailWords'
import { titleTags, trackLine, versionsLine } from './fileWords'
import { moviePlay, movieVersions, partway, resumeVersion, trailerPlay } from './movieLabels'
import { AudioPanel, FileDetailsPanel, MorePanelRows, ReportPanel, VersionsPanel } from './TitlePanels'
import { movieFactLine, movieShortLine, movieWatch } from './titleWords'
import { planProfile } from './planProfile'
import { relatedPosterRows } from './relatedCards'
import { alongButtons } from './pageKeys'
import { WatchlistButton } from '../watchlists/WatchlistButton'
import { useWatchlistsOn } from '../watchlists/useWatchlistsOn'
import './details.css'

type MovieDetail = components['schemas']['MovieDetail']

/**
 * A movie, without the menu: the title, one line of facts, how far it got
 * and when it would end, two lines of story, five buttons (Resume or Play,
 * Start over, Trailer, Watchlist, More) and three words about the picture
 * and sound. The cast peeks at the foot; one press down it is a row of
 * people cards, then the collection and More like this. Versions, tracks
 * and the file's numbers are behind More. Return from below comes back to
 * the top first, then leaves.
 */
export function MovieScreen({ route }: ScreenProps<'movie'>) {
  const movie = useQuery(movieQuery(route.id))
  const nav = useNav()
  if (movie.isPending) return <MovieLoading/>
  if (movie.isError) {
    const words = problemWords(movie.error)
    return <div className="dt-page"><ProblemScreen title="Couldn’t load this movie" actions={[
      { id: 'retry', label: 'Try again', onPress: () => void movie.refetch() },
      { id: 'back', label: 'Back', onPress: () => nav.back() },
    ]}>{words.title}. {words.body}</ProblemScreen></div>
  }
  return <MoviePage m={movie.data}/>
}

function MovieLoading() {
  return <div className="dt-page" aria-busy="true">
    <span className="tv-sr" role="status">Loading the movie</span>
    <div className="dt-head dt-head--movie" aria-hidden="true">
      <Skeleton width="40rem" height="4.5rem"/><SkeletonText width="36rem"/><SkeletonText width="30rem"/><SkeletonText width="52rem"/>
      <div className="dt-actions"><Skeleton width="13rem" height="var(--button-h)" radius="2.375rem"/><Skeleton width="12rem" height="var(--button-h)" radius="2.375rem"/><Skeleton width="10rem" height="var(--button-h)" radius="2.375rem"/></div>
    </div>
  </div>
}

type Zone = 'top' | 'cast' | `related/${string}`
type Panel = 'more' | 'versions' | 'tracks' | 'details' | 'report'

/** How far the page moves up for the part with focus: none at the top; the cast under the short header; a row below kept in view. */
function offsetFor(zone: Zone, track: HTMLElement | null, view: HTMLElement | null): number {
  if (!track || !view || zone === 'top') return 0
  const rem = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16
  const below = track.querySelector<HTMLElement>('[data-zone="cast"]') ?? track.querySelector<HTMLElement>('[data-zone^="related/"]')
  const first = below ? below.offsetTop - 8.5 * rem : 0
  if (zone === 'cast') return Math.max(0, first)
  const row = track.querySelector<HTMLElement>(`[data-zone="${zone}"]`)
  if (!row) return Math.max(0, first)
  const end = row.offsetTop + row.offsetHeight - view.clientHeight + 3.375 * rem
  return Math.max(0, first, Math.min(end, track.scrollHeight - view.clientHeight))
}

function MoviePage({ m }: { m: MovieDetail }) {
  const nav = useNav()
  const focus = useFocus()
  const entry = useEntry()
  const now = useNow()
  const page = useRef<HTMLDivElement>(null)
  const track = useRef<HTMLDivElement>(null)
  const options = useQuery(requestOptionsQuery)
  const versions = movieVersions(m)
  const plans = useQuery(playbackPlansQuery(versions.map((v) => v.id), planProfile()))
  const related = useQuery(movieRelatedQuery(m.id))
  const rows = relatedPosterRows(related.data)
  const mark = useMarkWatched()
  const report = useReportProblem()
  const { notice, say, fail } = useNotice()
  const [panel, setPanel] = useState<Panel>()
  const [moreAt, setMoreAt] = useState<string>()
  const [zone, setZone] = useState<Zone>('top')
  const [offset, setOffset] = useState(0)
  const [chosenId, setChosenId] = useState<number>()
  const watchlist = useWatchlistsOn() ? watchlistTitle('movie', m.tmdbId) : undefined

  const watched = !!m.watch?.completed
  const resume = resumeVersion(m)
  const chosen = versions.find((v) => v.id === chosenId) ?? resume
  const p = partway(m)
  const line = movieWatch(m, now)
  const trailer = trailerPlay(m)
  const canReport = !!options.data?.problems && versions.length > 0
  const planFor = (id: number | undefined) => plans.data?.items.find((x) => x.fileId === id)
  const tags = titleTags(m)
  const tracks = trackLine(chosen?.probe)
  const cast = m.credits.slice(0, 12)

  // Resume plays the chosen version from the saved place; the server resumes the version it was on by itself.
  const playRequest = (): PlayRequest | undefined => {
    if (!chosen) return undefined
    if (line.play === 'Play again') return moviePlay(m, chosen, 0)
    const request = moviePlay(m, chosen)
    if (p && chosen.id !== resume?.id && request.kind === 'file') return { ...request, startMs: p.positionMs }
    return request
  }
  const play = playRequest()

  const onFocus = (e: FocusEvent) => {
    const z = (e.target as HTMLElement).closest<HTMLElement>('[data-zone]')?.dataset.zone as Zone | undefined
    if (!z) return
    setZone(z)
    setOffset(offsetFor(z, track.current, page.current))
  }
  const toggleWatched = () => mark.mutate({ type: 'movie', id: m.id, watched: !watched }, {
    onSuccess: () => say({ title: markedMessage(!watched) }),
    onError: (e) => fail('Couldn’t change the watched mark', e),
  })
  const sendReport = (kind: components['schemas']['Problem']['kind']) => {
    setPanel(undefined)
    report.mutate({ itemType: 'movie', itemId: m.id, fileId: chosen?.id, kind }, {
      onSuccess: (r) => say({ title: reportSent(!!r.seerrIssueId) }),
      onError: (e) => fail('Couldn’t send the report', e),
    })
  }
  const open = (next: Panel, from?: string) => { if (from) setMoreAt(from); setPanel(next) }
  const backToMore = () => setPanel('more')

  // ▲ ▼ between the parts as the page has them: the buttons, the cast, then each row.
  // ◀ ▶ along the buttons, stopping at both ends (there is no menu here).
  useKeys((i) => {
    if (panel) return false
    const active = document.activeElement as HTMLElement | null
    if (i.action === 'left' || i.action === 'right') return alongButtons(active, i.action, (el) => focus.focus(el))
    if (i.action !== 'up' && i.action !== 'down') return false
    const z = active?.closest<HTMLElement>('[data-zone]')?.dataset.zone
    const root = page.current
    const toRow = (n: number) => {
      const row = rows[n]
      if (!row) return false
      return focus.focusId(entry?.memory.groups[`related/${row.id}`] ?? '', root) || focus.focusFirst(root?.querySelector(`[data-zone="related/${row.id}"]`) ?? null)
    }
    const toCast = () => focus.focusId(entry?.memory.groups['cast'] ?? '', root) || focus.focusId('cast/0', root)
    const toTop = () => focus.focusId(entry?.memory.groups['actions'] ?? '', root) || focus.focusFirst(root?.querySelector('[data-zone="top"]') ?? null)
    const at = rows.findIndex((r) => z === `related/${r.id}`)
    if (i.action === 'down') {
      if (z === 'top') return toCast() || toRow(0) || true
      if (z === 'cast') return toRow(0) || true
      if (at >= 0) return toRow(at + 1) || true
    } else {
      if (z === 'top') return true
      if (z === 'cast') return toTop() || true
      if (at === 0) return toCast() || toTop() || true
      if (at > 0) return toRow(at - 1) || true
    }
    return false
  })
  // Return from below the top comes back to the buttons first.
  useBack(() => {
    if (panel || zone === 'top') return false
    if (!focus.focusId(entry?.memory.groups['actions'] ?? '', page.current)) focus.focusFirst(page.current?.querySelector('[data-zone="top"]') ?? null)
    return true
  })

  const below = zone !== 'top'
  return <div ref={page} className="dt-page" data-vscroll onFocus={onFocus}>
    <Backdrop path={m.backdropPath} seed={m.title} kind="movie"/>
    <Clock/>
    <header className={`dt-short${below ? ' dt-short--on' : ''}`} aria-hidden={!below}>
      <h1 className="tv-head">{m.title}</h1><span className="dt-short__line">{movieShortLine(m)}</span>
    </header>
    <div ref={track} className="dt-track" style={{ transform: `translateY(${-offset}px)` }}>
      <div className="dt-movie">
        <section className={`dt-head dt-head--movie${below ? ' dt-head--away' : ''}`} aria-label={m.title} data-zone="top">
          <h1 className="tv-display dt-name">{m.title}</h1>
          <p className="dt-factline">{movieFactLine(m)}</p>
          {versions.length > 0 ? <WatchBar line={line}/> : <p className="dt-watch"><span className="dt-watch__where">No file for this movie is in the library</span></p>}
          {m.overview && <p className="dt-story">{m.overview}</p>}
          <div className="dt-actions" data-group="actions">
            {play && <Button id="play" variant="primary" icon="play" autoFocus onPress={() => nav.play(play)}>{line.play}</Button>}
            {p && chosen && <Button id="start" onPress={() => nav.play(moviePlay(m, chosen, 0))}>Start over</Button>}
            {trailer && <Button id="trailer" autoFocus={!play} onPress={() => nav.play(trailer)}>Trailer</Button>}
            {watchlist && <WatchlistButton id="watchlist" target={watchlist} holding={m.watchlists ?? []} title={m.title}
              onProblem={(title) => say({ title, problem: true })}/>}
            <Button id="more" autoFocus={!play && !trailer} onPress={() => open('more')}>More</Button>
          </div>
          {(tags.length > 0 || tracks) && <div className="dt-tags">
            {tags.map((t) => <span key={t} className="tv-tag">{t}</span>)}
            {tracks && <span className="dt-tags__line">{tracks}</span>}
          </div>}
        </section>
        {cast.length > 0 && <section className="dt-below dt-below--cast" aria-label="Cast" data-zone="cast">
          <h2 className="tv-head">Cast</h2>
          <PeopleRow cast={cast} group="cast"/>
        </section>}
        {rows.map((row) => <RelatedSection key={row.id} row={row}/>)}
      </div>
    </div>
    <NoticeBanner notice={notice}/>
    {panel === 'more' && <MorePanelRows title={m.title} start={moreAt} onClose={() => setPanel(undefined)} rows={[
      { id: 'more/watched', label: watched ? 'Mark as unwatched' : 'Mark as watched', busy: mark.isPending, onPress: toggleWatched },
      ...(versions.length > 1 ? [{ id: 'more/versions', label: 'Choose a version', detail: versionsLine(versions), next: true, onPress: () => open('versions', 'more/versions') }] : []),
      ...(chosen?.probe && (chosen.probe.audio.length > 0 || chosen.probe.subtitles.length > 0) ? [{ id: 'more/tracks', label: 'Audio and subtitles', detail: tracks, next: true, onPress: () => open('tracks', 'more/tracks') }] : []),
      ...(chosen ? [{ id: 'more/details', label: 'File details', detail: 'How it plays on this TV', next: true, onPress: () => open('details', 'more/details') }] : []),
      ...(canReport ? [{ id: 'more/report', label: 'Report a problem', next: true, onPress: () => open('report', 'more/report') }] : []),
    ]}/>}
    {panel === 'versions' && <VersionsPanel title={m.title} versions={versions} chosen={chosen?.id} planFor={planFor} onChoose={setChosenId} onClose={backToMore}/>}
    {panel === 'tracks' && chosen?.probe && <AudioPanel probe={chosen.probe} onClose={backToMore}/>}
    {panel === 'details' && chosen && <FileDetailsPanel versions={versions} chosen={chosen} planFor={planFor} onChoose={setChosenId} onClose={backToMore}/>}
    {panel === 'report' && <ReportPanel onPick={sendReport} onClose={backToMore}/>}
  </div>
}
