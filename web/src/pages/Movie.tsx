import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { movieQuery } from '../api/queries'
import { RematchDialog } from '../components/RematchDialog'
import { ChangePosterDialog } from '../components/ChangePosterDialog'
import { useAuth } from '../authContext'
import { useCanDelete, useCanManage } from '../api/delete'
import { DeleteDialog, ReplaceFileDialog } from '../components/DeleteDialog'
import { MoreMenu, type MoreItem } from '../components/MoreMenu'
import { RelatedRows } from '../components/rows/RelatedRows'
import { Poster } from '../components/Poster'
import { BackLink, ChoiceSelect, DetailsPanel, FactsLine, PeopleRow, TitleBand, WatchedHere } from '../components/TitleParts'
import { usePlans } from '../components/usePlans'
import { MovieFileDetails, TrackChoices, type TrackChoice } from '../components/TitleFiles'
import { CheckIcon, PlayIcon } from '../components/icons'
import { useNow } from '../components/useNow'
import { formatDate, formatRuntime, relativeTime } from '../lib/format'
import { endsAt, leftAndEnds, percentWatched } from '../lib/endTime'
import { useMarkWatched } from '../api/watch'
import type { components } from '../api/schema'
import { ReportProblemDialog } from '../components/ReportProblemDialog'
import { downloadLabel, toneText } from '../lib/requests'
import { firstTrailer } from '../lib/trailers'
import { watchlistTitle } from '../lib/watchlists'
import { planWords, playChoices, qualityLine, sizeAndRate, versionName, yourPlace } from '../lib/titlePage'
import { scoreWords } from '../lib/cardFacts'
import { crewLine } from '../lib/personPage'
import { collectionPartWords } from '../lib/collections'
import { WatchlistButton } from '../components/watchlists/WatchlistButton'

type MovieDetail = components['schemas']['MovieDetail']

/** A movie's page: what it is, how long, whether it's for you, where you were and when it would end, then the cast, the collection and File details. */
export function Movie({ movieId }: { movieId: number }) {
  const movie = useQuery(movieQuery(movieId))
  if (movie.isPending) return <p className="text-neutral-400">Loading…</p>
  if (movie.isError) return <p className="text-red-400">{movie.error.message}</p>
  // Keyed by movie, so another one starts with its own choices.
  return <MovieView key={movie.data.id} m={movie.data} />
}

function MovieView({ m }: { m: MovieDetail }) {
  const [picked, setPicked] = useState<number | null>(null)
  const [choice, setChoice] = useState<TrackChoice>({ audio: '', subtitle: '' })
  const [filesOpen, setFilesOpen] = useState(false)
  const [acting, setActing] = useState<'fix' | 'poster' | 'report' | null>(null)
  // The file to replace is held, so the dialog can say what happened once the page has refetched without it.
  const [managing, setManaging] = useState<{ kind: 'delete' } | { kind: 'replace'; file: components['schemas']['MediaFile']; app: string } | null>(null)
  const manage = useCanManage()
  const auth = useAuth()
  const admin = (auth.user?.isAdmin ?? false) && auth.adminAllowed
  const canDelete = useCanDelete()
  const navigate = useNavigate()
  const mark = useMarkWatched()
  const now = useNow()
  const versions = m.files.filter((f) => f.status === 'present')
  const plans = usePlans(versions.map((f) => f.id))
  const selected = versions.find((f) => f.id === picked) ?? versions[0]
  const label = `${m.title}${m.year ? ` (${m.year})` : ''}`
  const back = `/movies/${m.id}`
  const choices = playChoices({ audio: choice.audio === '' ? undefined : Number(choice.audio), subtitle: choice.subtitle || undefined })
  // Trailer plays the first trailer the server lists that this app can play.
  const trailer = firstTrailer(m.trailers)
  // A title TMDB hasn't matched can't be saved: watchlists keep titles by TMDB id.
  const saving = watchlistTitle('movie', m.tmdbId)
  const watch = m.watch
  // Resume the version the saved position belongs to unless another was chosen.
  const resumeFile = picked === null && watch?.fileId ? (versions.find((f) => f.id === watch.fileId) ?? selected) : selected
  const partway = !!watch && !watch.completed && watch.positionMs > 0
  const durationMs = (partway && watch?.durationMs) || resumeFile?.durationMs || (m.runtimeMin ? m.runtimeMin * 60_000 : 0)
  const left = partway && watch ? leftAndEnds(watch.positionMs, durationMs, now) : undefined
  const pct = partway && watch ? percentWatched(watch.positionMs, durationMs) : 0
  const plan = selected ? planWords(plans.get(selected.id), selected) : undefined
  // The chosen version's picture and sound in words; tags and codecs stay in File details.
  const quality = selected ? qualityLine(selected) : ''
  const crew = crewLine(m.crew)
  const runtime = formatRuntime(m.runtimeMin ?? 0) || (selected?.durationMs ? formatRuntime(Math.round(selected.durationMs / 60_000)) : '')

  const pickVersion = (id: number) => {
    setPicked(id)
    // Another version has its own tracks.
    setChoice({ audio: '', subtitle: '' })
  }
  const showFiles = () => {
    setFilesOpen(true)
    requestAnimationFrame(() => document.getElementById('file-details')?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
  }

  const items: MoreItem[] = []
  if (selected) items.push({ label: 'Report a problem…', icon: 'flag', onSelect: () => setActing('report') })
  if (versions.length) items.push({ label: 'File details', icon: 'file', onSelect: showFiles })
  if (admin) {
    items.push({ label: 'Fix match…', icon: 'match', group: 'Admin', onSelect: () => setActing('fix') })
    items.push({ label: 'Change poster…', icon: 'poster', group: 'Admin', onSelect: () => setActing('poster') })
  }
  if (manage && m.managedBy && selected) items.push({ label: 'Replace file…', icon: 'replace', group: admin ? 'Admin' : undefined, divider: !admin, onSelect: () => setManaging({ kind: 'replace', file: selected, app: m.managedBy ?? '' }) })
  // Members delete a movie with a file when admins allow it; admins can also remove one without. Last, and it asks first.
  if (canDelete && (manage || selected)) items.push({ label: 'Delete movie…', icon: 'delete', group: admin ? 'Admin' : undefined, divider: !admin, onSelect: () => setManaging({ kind: 'delete' }) })

  return (
    <div>
      <TitleBand backdropPath={m.backdropPath}>
        <BackLink link={{ to: '/movies' }} label="Movies" />
        <div className="mt-4 flex flex-wrap items-end gap-x-10 gap-y-6 md:mt-5">
          <div className="w-[clamp(120px,17vw,236px)] shrink-0 max-md:w-[132px]">
            <Poster path={m.posterPath} size="w342" alt={m.title} className="rounded-xl shadow-[0_22px_44px_rgba(0,0,0,.55)] ring-1 ring-white/10" />
          </div>
          <div className="flex min-w-0 max-w-[820px] flex-[1_1_380px] flex-col gap-3">
            <h1 className="text-[clamp(30px,3.6vw,48px)] font-bold leading-[1.05] tracking-[-.025em]">{m.title}</h1>
            <FactsLine className="text-[15px] text-neutral-100" facts={[m.year ? String(m.year) : '', runtime, m.genres.slice(0, 2).join(', '), scoreWords(m.rating)]} rating={m.contentRating} />
            {m.tagline && <p className="text-[15px] italic text-neutral-300">{m.tagline}</p>}
            {m.overview && <p className="max-w-[68ch] text-[15px] leading-[1.55] text-neutral-200">{m.overview}</p>}

            <div className="mt-2 flex flex-wrap items-center gap-2.5">
              {resumeFile ? (
                <Link to="/play/$fileId" params={{ fileId: String(resumeFile.id) }} search={{ title: label, back, ...choices }} className="btn btn-primary btn-lg max-md:flex-1">
                  <PlayIcon />
                  {partway ? 'Resume' : watch?.completed ? 'Play again' : 'Play'}
                </Link>
              ) : (
                <button type="button" disabled title="No file on disk" className="btn btn-primary btn-lg">
                  <PlayIcon />
                  Play
                </button>
              )}
              {partway && resumeFile && (
                <Link to="/play/$fileId" params={{ fileId: String(resumeFile.id) }} search={{ start: 0, title: label, back, ...choices }} className="btn btn-clear btn-lg max-md:flex-1">
                  From the start
                </Link>
              )}
              {trailer?.kind === 'file' && (
                <Link to="/play/$fileId" params={{ fileId: String(trailer.fileId) }} search={{ start: 0, title: `${label} · Trailer`, back, trailer: true }} className="btn btn-clear btn-lg">
                  <TrailerIcon />
                  Trailer
                </Link>
              )}
              {trailer?.kind === 'youtube' && (
                <Link to="/movies/$movieId/trailer" params={{ movieId: String(m.id) }} className="btn btn-clear btn-lg">
                  <TrailerIcon />
                  Trailer
                </Link>
              )}
              {saving && <WatchlistButton target={saving} title={m.title} holding={m.watchlists ?? []} look="hero" />}
              <button
                type="button"
                aria-pressed={!!watch?.completed}
                disabled={mark.isPending}
                onClick={() => mark.mutate({ type: 'movie', id: m.id, watched: !watch?.completed })}
                className={`btn btn-clear btn-lg ${watch?.completed ? 'text-accent' : ''}`}
              >
                <CheckIcon />
                {watch?.completed ? 'Watched' : 'Mark watched'}
              </button>
              <MoreMenu items={items} label={`More for ${m.title}`} look="hero" />
            </div>
            {mark.error && <p className="text-sm text-warn">{mark.error.message}</p>}
            {m.download && (
              <p className={`text-sm font-medium ${toneText[downloadLabel(m.download).tone]}`}>
                {m.managedBy ?? 'Radarr'}: {downloadLabel(m.download).label}
              </p>
            )}
            {left ? (
              <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
                <span className="meter min-w-[140px] flex-[0_1_240px] bg-white/20" role="img" aria-label={`${pct}% watched`}>
                  <i style={{ width: `${pct}%` }} />
                </span>
                <span className="text-[13.5px]">{left}</span>
              </div>
            ) : (
              durationMs > 0 && selected && <p className="text-[13.5px] text-neutral-300">Start now and it ends at {endsAt(durationMs, now)}.</p>
            )}

            {quality && <p className="mt-1 text-sm text-neutral-200">{quality}</p>}
            {selected && (
              <div className="mt-1 flex flex-wrap items-center gap-2">
                {versions.length > 1 && (
                  <ChoiceSelect
                    label="Version"
                    value={String(selected.id)}
                    onChange={(v) => pickVersion(Number(v))}
                    options={versions.map((f) => ({ value: String(f.id), label: `${versionName(f)} · ${sizeAndRate(f).split(' · ')[0]}` }))}
                  />
                )}
                <TrackChoices probe={selected.probe} choice={choice} onChange={setChoice} />
                {plan && <span className="text-[13px] text-neutral-400 max-md:hidden">{plan}.</span>}
              </div>
            )}
          </div>
        </div>
      </TitleBand>

      <div className="flex flex-wrap items-start gap-x-12 gap-y-2">
        <div className="flex min-w-0 flex-[999_1_620px] flex-col gap-8 pt-7 md:gap-10">
          <PeopleRow people={m.credits} heading={crew ? 'Cast and crew' : 'Cast'} note={crew} />
          <RelatedRows kind="movie" id={m.id} notes={{ collection: collectionPartWords(m.collection) }} />
        </div>
        <aside className="mt-7 flex min-w-0 flex-[1_1_300px] flex-col gap-4">
          <WatchedHere movie={m.id} you={yourPlace(watch)} />
          <DetailsPanel
            title="Details"
            rows={[
              ['Original title', m.originalTitle && m.originalTitle !== m.title ? m.originalTitle : ''],
              ['Released', formatDate(m.releaseDate)],
              ['Rated', m.contentRating ?? ''],
              ['Runtime', runtime],
              ['Genres', m.genres.join(', ')],
              ['Rating', m.rating ? `${m.rating.toFixed(1)} of 10 on TMDB` : ''],
              ['Added', m.addedAt ? relativeTime(m.addedAt) : ''],
              [
                'More',
                <span key="more">
                  <a href={`https://www.themoviedb.org/movie/${m.tmdbId}`} target="_blank" rel="noreferrer" className="underline hover:text-accent">
                    TMDB
                  </a>
                  {m.imdbId && (
                    <>
                      {' · '}
                      <a href={`https://www.imdb.com/title/${m.imdbId}/`} target="_blank" rel="noreferrer" className="underline hover:text-accent">
                        IMDb
                      </a>
                    </>
                  )}
                </span>,
              ],
            ]}
          />
        </aside>
      </div>

      <div className="mt-10 md:mt-11">
        <MovieFileDetails
          files={versions}
          selectedId={selected?.id}
          plans={plans}
          admin={auth.user?.isAdmin ?? false}
          open={filesOpen}
          onToggle={setFilesOpen}
          playThis={(f) => (
            <span className="flex gap-2">
              {f.id !== selected?.id && (
                <button type="button" onClick={() => pickVersion(f.id)} className="btn btn-sm">
                  Choose
                </button>
              )}
              <Link to="/play/$fileId" params={{ fileId: String(f.id) }} search={{ title: label, back }} className="btn btn-sm">
                Play this
              </Link>
            </span>
          )}
        />
      </div>

      {managing?.kind === 'delete' && (
        <DeleteDialog item={{ type: 'movie', id: m.id }} name={m.title} onClose={() => setManaging(null)} onDeleted={() => void navigate({ to: '/movies' })} />
      )}
      {managing?.kind === 'replace' && <ReplaceFileDialog file={managing.file} app={managing.app} what="movie" onClose={() => setManaging(null)} />}
      {acting === 'report' && selected && <ReportProblemDialog itemType="movie" itemId={m.id} fileId={selected.id} title={label} onClose={() => setActing(null)} />}
      {acting === 'poster' && <ChangePosterDialog title={label} targets={[{ kind: 'movie', id: m.id, label: 'Movie' }]} onClose={() => setActing(null)} />}
      {acting === 'fix' && (
        <RematchDialog
          kind="movie"
          current={{ id: m.id, libraryId: m.libraryId, tmdbId: m.tmdbId, title: m.title, year: m.year, files: m.files.length }}
          onClose={() => setActing(null)}
        />
      )}
    </div>
  )
}

/** The Trailer button's film strip; the button looks the same for a file and for YouTube. */
function TrailerIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="3" y="4" width="18" height="16" rx="2" />
      <path d="M7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4" />
    </svg>
  )
}
