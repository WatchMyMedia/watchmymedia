import { useRef, useState, type FocusEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { movieQuery, movieRelatedQuery, playbackPlansQuery } from '../../../src/api/queries'
import { requestOptionsQuery, useReportProblem } from '../../../src/api/requests'
import { useMarkWatched } from '../../../src/api/watch'
import type { components } from '../../../src/api/schema'
import { useEntry, useFocus, useKeys, useNav } from '../../app/hooks'
import type { ScreenProps } from '../../app/routes'
import { samsungProfile } from '../../player'
import { Button } from '../../ui/Button'
import { ProblemScreen, Skeleton, SkeletonText } from '../../ui/States'
import { VScroll } from '../../ui/VScroll'
import { problemWords } from '../../ui/problemWords'
import { Backdrop, CastList, Facts, Hint, MoreButton, MorePanel, Poster, Progress, RelatedSection, ReportPanel, Tag, NoticeBanner } from './DetailParts'
import { markedMessage, reportSent, useNotice } from './detailWords'
import { Glyph } from './Glyph'
import { mainLabel, moviePlay, movieFacts, movieTags, movieVersions, partway, progressOf, resumeVersion, trailerPlay, versionLine, versionTags, versionTitle, versionTracks } from './movieLabels'
import { relatedPosterRows, type RelatedRow } from './relatedCards'
import './details.css'

type MovieDetail = components['schemas']['MovieDetail']
type MediaFileDetail = components['schemas']['MediaFileDetail']

// The TV's profile asks the plans; one per page, as it never changes while the app runs.
const PROFILE = samsungProfile()

/**
 * A movie (Movie and MovieRelated on the Samsung TV canvas; the Android TV
 * app's MovieScreen): the header with Resume or Play, Play from start,
 * Trailer, Mark watched and More › Report a problem; the versions, each with
 * Play this and how it would play here, beside the focused version's tracks
 * and the cast; then the movie's collection and More like this. Return
 * leaves the movie; Return from a related title comes back to its card.
 */
export function MovieScreen({ route }: ScreenProps<'movie'>) {
  const movie = useQuery(movieQuery(route.id))
  const nav = useNav()
  if (movie.isPending) return <MovieLoading/>
  if (movie.isError) {
    const words = problemWords(movie.error)
    return <div className="dt-page"><ProblemScreen icon="alert" title="Couldn’t load this movie" actions={[
      { id: 'retry', label: 'Try again', onPress: () => void movie.refetch() },
      { id: 'back', label: 'Back', onPress: () => nav.back() },
    ]}>{words.title}. {words.body}</ProblemScreen></div>
  }
  return <MoviePage m={movie.data}/>
}

function MovieLoading() {
  return <div className="dt-page" aria-busy="true">
    <div className="dt-movie"><div className="dt-movie__top">
      <span className="dt-movie__poster" style={{ display: 'block' }}><Skeleton width="15rem" height="22.5rem" radius="var(--radius-m)"/></span>
      <div className="dt-movie__head"><SkeletonText width="14rem"/><Skeleton width="40rem" height="3.75rem"/><SkeletonText width="30rem"/><SkeletonText width="56rem"/></div>
    </div></div>
  </div>
}

type Zone = 'actions' | 'versions' | 'cast' | `related/${string}`

function MoviePage({ m }: { m: MovieDetail }) {
  const nav = useNav()
  const focus = useFocus()
  const entry = useEntry()
  const page = useRef<HTMLDivElement>(null)
  const options = useQuery(requestOptionsQuery)
  const versions = movieVersions(m)
  const plans = useQuery(playbackPlansQuery(versions.map((v) => v.id), PROFILE))
  const related = useQuery(movieRelatedQuery(m.id))
  const rows = relatedPosterRows(related.data)
  const mark = useMarkWatched()
  const report = useReportProblem()
  const { notice, say, fail } = useNotice()
  const [panel, setPanel] = useState<'more' | 'report'>()
  const [zone, setZone] = useState<Zone>('actions')
  const [tracksOf, setTracksOf] = useState<number>()

  const watched = !!m.watch?.completed
  const resume = resumeVersion(m)
  const p = partway(m)
  const progress = progressOf(m)
  const trailer = trailerPlay(m)
  const canReport = !!options.data?.problems && versions.length > 0
  const shown = versions.find((v) => v.id === tracksOf) ?? versions[0]
  const planFor = (v: MediaFileDetail) => plans.data?.items.find((x) => x.fileId === v.id)

  const onFocus = (e: FocusEvent) => {
    const z = (e.target as HTMLElement).closest<HTMLElement>('[data-zone]')?.dataset.zone as Zone | undefined
    if (z) setZone(z)
  }
  const toggleWatched = () => mark.mutate({ type: 'movie', id: m.id, watched: !watched }, {
    onSuccess: () => say({ title: markedMessage(!watched) }),
    onError: (e) => fail('Couldn’t change the watched mark', e),
  })
  const sendReport = (kind: components['schemas']['Problem']['kind']) => {
    setPanel(undefined)
    report.mutate({ itemType: 'movie', itemId: m.id, fileId: versions[0]?.id, kind }, {
      onSuccess: (r) => say({ title: reportSent(!!r.seerrIssueId) }),
      onError: (e) => fail('Couldn’t send the report', e),
    })
  }
  // ▲ ▼ between the parts as the board has them, rather than to whatever is nearest: the
  // header to the versions, the last version and the cast to the first row, and back.
  useKeys((i) => {
    if (panel || (i.action !== 'up' && i.action !== 'down')) return false
    const active = document.activeElement as HTMLElement | null
    const z = active?.closest<HTMLElement>('[data-zone]')?.dataset.zone
    const root = page.current
    const go = (id: string | undefined) => !!id && focus.focusId(id, root)
    const first = versions[0] && `version/${versions[0].id}`, last = versions.length ? `version/${versions[versions.length - 1].id}` : undefined
    const toRows = () => !!rows[0] && (go(entry?.memory.groups[`related/${rows[0].id}`]) || focus.focusFirst(root?.querySelector(`[data-zone="related/${rows[0].id}"]`) ?? null))
    if (i.action === 'down') {
      if (z === 'actions') return go(first) || go('cast/0') || toRows()
      if ((z === 'versions' && active?.dataset.focus === last) || z === 'cast') return toRows()
    } else {
      if (z === 'versions' && active?.dataset.focus === first) return focus.focusFirst(root?.querySelector('[data-zone="actions"]') ?? null)
      if (rows[0] && z === `related/${rows[0].id}`) return go('cast/0') || go(last)
    }
    return false
  })
  const rowHint = (row: RelatedRow, i: number) => zone === `related/${row.id}` && <Hint items={[
    { key: '◀ ▶', label: row.id === 'collection' ? 'the collection' : 'more like this' },
    { key: 'OK', label: 'opens the film' },
    { key: '▲', label: i === 0 ? 'the cast' : rows[i - 1].id === 'collection' ? 'the collection' : rows[i - 1].title },
    ...(rows[i + 1] ? [{ key: '▼', label: rows[i + 1].title }] : [{ key: 'Return', label: `leaves ${m.title}` }]),
  ]}/>

  return <div ref={page} className="dt-page" onFocus={onFocus}>
    <VScroll className="dt-scroll" pad={48}>
      <div className="dt-movie">
        <Backdrop path={m.backdropPath} seed={m.title} kind="movie"/>
        {/* The header comes back whole as focus returns to its buttons. */}
        <div className="dt-movie__top" data-vitem>
          <Poster path={m.posterPath} seed={m.title} className="dt-movie__poster"/>
          <section className="dt-movie__head" aria-label={m.title}>
            <p className="dt-crumb">Movies › {m.title}</p>
            <h1 className="dt-title">{m.title}</h1>
            <Facts parts={movieFacts(m)} tags={movieTags(m)}/>
            {m.overview && <p className="dt-overview">{m.overview}</p>}
            <div className="dt-actions" data-zone="actions">
              {resume && <Button id="play" variant="primary" icon="play" autoFocus onPress={() => nav.play(moviePlay(m, resume))}>{mainLabel(m)}</Button>}
              {resume && p && <Button id="start" onPress={() => nav.play(moviePlay(m, resume, 0))}>Play from start</Button>}
              {trailer && <Button id="trailer" onPress={() => nav.play(trailer)}><Glyph name="clapper"/>Trailer</Button>}
              <Button id="watched" busy={mark.isPending} autoFocus={!resume} onPress={toggleWatched}>{watched ? 'Mark unwatched' : 'Mark watched'}</Button>
              {canReport && <MoreButton id="more" onPress={() => setPanel('more')}/>}
              {progress !== undefined && <Progress value={progress}/>}
            </div>
          </section>
        </div>

        <div className="dt-movie__body">
          <section className="dt-versions" aria-label="Versions" data-zone="versions">
            <h2 className="dt-h3">Versions</h2>
            {versions.length === 0 && <p className="dt-empty">No file for this movie is in the library.</p>}
            {versions.map((v, i) => <div key={v.id} className="dt-ver" onFocus={() => setTracksOf(v.id)}>
              <div className="dt-ver__text">
                <div className="dt-ver__name">{versionTitle(v)}{i === 0 && <Tag gold>Default</Tag>}</div>
                <div className="dt-ver__tags">{versionTags(v).map((t) => <Tag key={t}>{t}</Tag>)}</div>
                <div className="dt-ver__line">{versionLine(v, planFor(v))}</div>
              </div>
              <span className="dt-small"><Button id={`version/${v.id}`} icon="play" onPress={() => nav.play(moviePlay(m, v))}>Play this</Button></span>
            </div>)}
            {zone === 'versions' && versions.length > 0 && <Hint items={[
              { key: '▲ ▼', label: 'the versions, and Tracks follows the focused one' }, { key: 'OK', label: 'plays this version' },
              ...(m.credits.length ? [{ key: '▶', label: 'the cast' }] : []), { key: 'Return', label: `leaves ${m.title}` },
            ]}/>}
          </section>
          <section className="dt-side" aria-label="Tracks and cast">
            {shown && <Tracks file={shown}/>}
            {m.credits.length > 0 && <div data-zone="cast" style={{ display: 'flex', flexDirection: 'column', gap: '.875rem', marginTop: shown ? '.875rem' : 0 }}>
              <h2 className="dt-h3">Cast</h2>
              <CastList cast={m.credits.slice(0, 5)} focusable/>
              {zone === 'cast' && rows[0] && <Hint items={[{ key: '▼', label: rows[0].title }]}/>}
            </div>}
          </section>
        </div>

        {rows.map((row, i) => <RelatedSection key={row.id} row={row} hint={rowHint(row, i)}/>)}
      </div>
    </VScroll>
    <NoticeBanner notice={notice}/>
    {panel === 'more' && <MorePanel kicker={['Movie', m.year].filter(Boolean).join(' · ')} title={m.title} onClose={() => setPanel(undefined)}
      items={[{ id: 'more/report', label: 'Report a problem', icon: 'flag', onPress: () => setPanel('report') }]}/>}
    {panel === 'report' && <ReportPanel onPick={sendReport} onClose={() => setPanel(undefined)}/>}
  </div>
}

/** A version's audio and subtitles, under "Tracks · 4K Dolby Vision". */
function Tracks({ file }: { file: MediaFileDetail }) {
  const lines = versionTracks(file)
  return <>
    <h2 className="dt-h3">Tracks <small>· {versionTitle(file)}</small></h2>
    {lines.length > 0 && <div className="dt-tracks">
      {lines.map((l, i) => <div key={i} className="dt-track-line">
        <span className="dt-track-line__kind">{l.kind}</span>
        <span className="dt-track-line__text">{l.text}</span>
        {l.default && <Tag gold>Default</Tag>}
        {l.burnIn && <span className="dt-burn">Burn-in required</span>}
      </div>)}
    </div>}
  </>
}
