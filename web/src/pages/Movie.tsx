import { RecordingProvenance } from '../components/liveTV/RecordingProvenance'
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { movieQuery } from '../api/queries'
import { RematchDialog } from '../components/RematchDialog'
import { ChangePosterDialog } from '../components/ChangePosterDialog'
import { useAuth } from '../authContext'
import { useCanDelete, useCanManage } from '../api/delete'
import { DeleteDialog, ReplaceFileDialog } from '../components/DeleteDialog'
import { MoreMenu } from '../components/MoreMenu'
import { CastRow, DetailHeader, FileTags, PrimaryButton, SecondaryButton, SideCard } from '../components/DetailHeader'
import { fileType, formatBytes, formatDate, formatDuration, formatRuntime, relativeTime } from '../lib/format'
import { useMarkWatched } from '../api/watch'
import { HistoryCard } from '../components/activity/HistoryCard'
import type { components } from '../api/schema'
import { ReportProblemDialog } from '../components/ReportProblemDialog'
import { downloadLabel, toneText } from '../lib/requests'
import { firstTrailer } from '../lib/trailers'

export function Movie({ movieId }: { movieId: number }) {
  const movie = useQuery(movieQuery(movieId))
  const [picked, setPicked] = useState<number | null>(null)
  const [fixing, setFixing] = useState(false)
  const [reporting, setReporting] = useState(false)
  // The file to replace is held, so the dialog can say what happened once the page has refetched without it.
  const [managing, setManaging] = useState<{ kind: 'delete' } | { kind: 'replace'; file: components['schemas']['MediaFile']; app: string } | null>(null)
  const manage = useCanManage()
  const auth = useAuth()
  const admin = (auth.user?.isAdmin ?? false) && auth.adminAllowed
  const [poster, setPoster] = useState(false)
  const canDelete = useCanDelete()
  const navigate = useNavigate()
  const mark = useMarkWatched()
  if (movie.isPending) return <p className="text-neutral-400">Loading…</p>
  if (movie.isError) return <p className="text-red-400">{movie.error.message}</p>
  const m = movie.data
  const versions = m.files.filter((f) => f.status === 'present')
  const selected = versions.find((f) => f.id === picked) ?? versions[0]
  const label = `${m.title}${m.year ? ` (${m.year})` : ''}`
  // Trailer plays the first trailer the server lists that this app can play (PLAN-TRAILERS.md §4.4).
  const trailer = firstTrailer(m.trailers)
  return (
    <div className="space-y-8">
      <DetailHeader
        backdropPath={m.backdropPath}
        posterPath={m.posterPath}
        title={m.title}
        year={m.year}
        crumbs={[{ label: 'Home', link: { to: '/' } }, { label: 'Movies', link: { to: '/movies' } }, { label: m.title }]}
        facts={[formatRuntime(m.runtimeMin ?? 0), m.genres.slice(0, 3).join(', '), m.rating ? `★ ${m.rating.toFixed(1)}` : '', m.quality ?? '', m.hdr ? 'HDR' : '']}
        tagline={m.tagline}
        overview={m.overview}
        actions={
          <>
            {selected && m.watch && !m.watch.completed && m.watch.positionMs > 0 ? (
              <>
                <Link to="/play/$fileId" params={{ fileId: String(selected.id) }} search={{ start: m.watch.positionMs / 1000, title: label, back: `/movies/${m.id}` }}>
                  <PrimaryButton>▶ Resume from {formatDuration(m.watch.positionMs)}</PrimaryButton>
                </Link>
                <Link to="/play/$fileId" params={{ fileId: String(selected.id) }} search={{ start: 0, title: label, back: `/movies/${m.id}` }}>
                  <SecondaryButton>Play from start</SecondaryButton>
                </Link>
              </>
            ) : selected ? (
              <Link to="/play/$fileId" params={{ fileId: String(selected.id) }} search={{ title: label, back: `/movies/${m.id}` }}>
                <PrimaryButton>▶ Play</PrimaryButton>
              </Link>
            ) : (
              <PrimaryButton disabled title="No file on disk">
                ▶ Play
              </PrimaryButton>
            )}
            {trailer?.kind === 'file' && (
              <Link to="/play/$fileId" params={{ fileId: String(trailer.fileId) }} search={{ start: 0, title: `${label} · Trailer`, back: `/movies/${m.id}`, trailer: true }}>
                <SecondaryButton>
                  <TrailerLabel />
                </SecondaryButton>
              </Link>
            )}
            {trailer?.kind === 'youtube' && (
              <Link to="/movies/$movieId/trailer" params={{ movieId: String(m.id) }}>
                <SecondaryButton>
                  <TrailerLabel />
                </SecondaryButton>
              </Link>
            )}
            <SecondaryButton disabled={mark.isPending} onClick={() => mark.mutate({ type: 'movie', id: m.id, watched: !m.watch?.completed })}>
              {m.watch?.completed ? '✓ Watched' : 'Mark watched'}
            </SecondaryButton>
            {admin && <SecondaryButton onClick={() => setFixing(true)}>Fix match</SecondaryButton>}
            {admin && <SecondaryButton onClick={() => setPoster(true)}>Change poster</SecondaryButton>}
            {selected && <SecondaryButton onClick={() => setReporting(true)}>Report a problem</SecondaryButton>}
            {/* Members delete a movie with a file when admins allow it; admins can also remove one without (PLAN-EPISODE-PAGE.md §4). */}
            <MoreMenu
              items={[
                ...(manage && m.managedBy && selected ? [{ label: 'Replace file', icon: 'replace' as const, onSelect: () => setManaging({ kind: 'replace', file: selected, app: m.managedBy ?? '' }) }] : []),
                ...(canDelete && (manage || selected) ? [{ label: 'Delete movie', icon: 'delete' as const, danger: true, onSelect: () => setManaging({ kind: 'delete' }) }] : []),
              ]}
            />
            {m.download && (
              <span className={`text-sm font-medium ${toneText[downloadLabel(m.download).tone]}`}>
                {m.managedBy ?? 'Radarr'}: {downloadLabel(m.download).label}
              </span>
            )}
          </>
        }
      />
      {managing?.kind === 'delete' && (
        <DeleteDialog item={{ type: 'movie', id: m.id }} name={m.title} onClose={() => setManaging(null)} onDeleted={() => void navigate({ to: '/movies' })} />
      )}
      {managing?.kind === 'replace' && <ReplaceFileDialog file={managing.file} app={managing.app} what="movie" onClose={() => setManaging(null)} />}
      {reporting && selected && (
        <ReportProblemDialog itemType="movie" itemId={m.id} fileId={selected.id} title={label} onClose={() => setReporting(false)} />
      )}
      {poster && <ChangePosterDialog title={label} targets={[{ kind: 'movie', id: m.id, label: 'Movie' }]} onClose={() => setPoster(false)} />}
      {fixing && (
        <RematchDialog
          kind="movie"
          current={{ id: m.id, libraryId: m.libraryId, tmdbId: m.tmdbId, title: m.title, year: m.year, files: m.files.length }}
          onClose={() => setFixing(false)}
        />
      )}

      <div className="grid gap-8 lg:grid-cols-[1fr_280px]">
        <div className="space-y-8">
          <section className="space-y-3">
            <h2 className="text-lg font-semibold">
              Versions <span className="text-sm font-normal text-neutral-500">{versions.length}</span>
            </h2>
            <ul className="space-y-2">
              {versions.map((f, i) => (
                <li
                  key={f.id}
                  className={`flex flex-wrap items-center gap-3 rounded-lg border p-3 ${selected?.id === f.id ? 'border-accent/60 bg-surface' : 'border-line bg-surface'}`}
                >
                  <button type="button" onClick={() => setPicked(f.id)} className="min-w-0 flex-1 text-left">
                    <div className="flex flex-wrap items-center gap-2 text-sm">
                      <span className="font-medium">{[f.height ? `${f.height}p` : '', fileType(f.relativePath)].filter(Boolean).join(' ')}</span>
                      {i === 0 && <span className="text-xs text-neutral-500">default</span>}
                      <FileTags file={f} />
                      <span className="text-neutral-500">{formatBytes(f.size)}</span>
                      {f.durationMs ? <span className="text-neutral-500">{formatDuration(f.durationMs)}</span> : null}
                    </div>
                    <div className="mt-1 truncate text-xs text-neutral-500">{f.relativePath}</div>
                    <RecordingProvenance recording={f.recording} />
                  </button>
                  <Link to="/play/$fileId" params={{ fileId: String(f.id) }} search={{ title: label, back: `/movies/${m.id}` }}>
                    <SecondaryButton>Play this</SecondaryButton>
                  </Link>
                </li>
              ))}
            </ul>
          </section>

          {selected?.probe && (
            <section className="space-y-3">
              <h2 className="text-lg font-semibold">Tracks</h2>
              <div className="grid gap-4 sm:grid-cols-2">
                <div className="rounded-lg border border-line bg-surface p-3 text-sm">
                  <h3 className="text-xs font-semibold uppercase tracking-wider text-neutral-500">Audio</h3>
                  <ul className="mt-2 space-y-1">
                    {selected.probe.audio.map((a) => (
                      <li key={a.index} className="flex justify-between gap-3">
                        <span>
                          {a.language?.toUpperCase() || 'und'} · {a.codec.toUpperCase()} {a.channelLayout || `${a.channels}ch`}
                          {a.title ? ` · ${a.title}` : ''}
                        </span>
                        {a.default && <span className="text-xs text-neutral-500">default</span>}
                      </li>
                    ))}
                    {selected.probe.audio.length === 0 && <li className="text-neutral-500">None</li>}
                  </ul>
                </div>
                <div className="rounded-lg border border-line bg-surface p-3 text-sm">
                  <h3 className="text-xs font-semibold uppercase tracking-wider text-neutral-500">Subtitles</h3>
                  <ul className="mt-2 space-y-1">
                    {selected.probe.subtitles.map((s) => (
                      <li key={s.index} className="flex justify-between gap-3">
                        <span>
                          {s.language?.toUpperCase() || 'und'} · {s.codec}
                          {s.title ? ` · ${s.title}` : ''}
                          {s.forced ? ' · forced' : ''}
                        </span>
                        {s.image && <span className="text-xs text-warn">burn-in required</span>}
                      </li>
                    ))}
                    {selected.probe.subtitles.length === 0 && <li className="text-neutral-500">None embedded</li>}
                  </ul>
                </div>
              </div>
            </section>
          )}

          <CastRow cast={m.credits} />
        </div>
        <aside className="space-y-4">
          <SideCard
            title="Details"
            rows={[
              ['Original title', m.originalTitle && m.originalTitle !== m.title ? m.originalTitle : ''],
              ['Released', formatDate(m.releaseDate)],
              ['Runtime', formatRuntime(m.runtimeMin ?? 0)],
              ['Genres', m.genres.join(', ')],
              ['Rating', m.rating ? `${m.rating.toFixed(1)} / 10` : ''],
              ['TMDB', <a key="tmdb" href={`https://www.themoviedb.org/movie/${m.tmdbId}`} target="_blank" rel="noreferrer" className="underline">{m.tmdbId}</a>],
              ['IMDb', m.imdbId ? <a key="imdb" href={`https://www.imdb.com/title/${m.imdbId}/`} target="_blank" rel="noreferrer" className="underline">{m.imdbId}</a> : ''],
            ]}
          />
          <SideCard
            title="Watching"
            rows={[
              ['Status', m.watch?.completed ? 'Watched' : m.watch && m.watch.positionMs > 0 ? `${Math.round((m.watch.positionMs / Math.max(m.watch.durationMs, 1)) * 100)}% watched` : ''],
              ['Last played', m.watch ? relativeTime(m.watch.updatedAt) : ''],
            ]}
          />
          <HistoryCard movie={m.id} />
          <SideCard
            title="On disk"
            rows={[
              ['Versions', String(versions.length)],
              ['Size', formatBytes(m.totalSize)],
              ['Best quality', [m.quality, m.hdr ? 'HDR' : ''].filter(Boolean).join(' · ')],
              ['Added', m.addedAt ? relativeTime(m.addedAt) : ''],
            ]}
          />
        </aside>
      </div>
    </div>
  )
}

/** The Trailer button's film strip and word; the button looks the same for a file and for YouTube. */
function TrailerLabel() {
  return (
    <span className="inline-flex items-center gap-2">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <rect x="3" y="4" width="18" height="16" rx="2" />
        <path d="M7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4" />
      </svg>
      Trailer
    </span>
  )
}
