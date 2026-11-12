import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { libraryCandidatesQuery, rematchPreviewQuery, useRematch, type RematchKind } from '../api/queries'
import { Poster } from './Poster'
import { ID_SEARCH_HINT } from '../lib/searchHint'
import { plural } from '../lib/format'

type Current = { id: number; libraryId: number; tmdbId: number; title: string; year?: number; files: number }


/** Fix match on a show or movie page: move every file to another TMDB item, with a preview of what changes. */
export function RematchDialog({ kind, current, onClose }: { kind: RematchKind; current: Current; onClose: () => void }) {
  const [query, setQuery] = useState(current.title)
  const [year, setYear] = useState('')
  const [submitted, setSubmitted] = useState<{ q: string; year?: number }>({ q: current.title })
  const [picked, setPicked] = useState<number | null>(null)
  const [remember, setRemember] = useState(true)
  const results = useQuery(libraryCandidatesQuery(current.libraryId, submitted.q, submitted.year))
  const items = results.data?.items ?? []
  const chosen = items.find((c) => c.tmdbId === picked) ?? items.find((c) => c.tmdbId !== current.tmdbId)
  const preview = useQuery({ ...rematchPreviewQuery(kind, current.id, chosen?.tmdbId ?? 0), enabled: chosen != null })
  const rematch = useRematch()
  const navigate = useNavigate()

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const name = (c: { title: string; year?: number }) => `${c.title}${c.year ? ` (${c.year})` : ''}`
  const p = preview.data
  const what = kind === 'show' ? 'show' : 'movie'
  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/80 p-4 sm:p-10" onClick={onClose}>
      <div className="w-full max-w-3xl rounded-xl border border-line-2 bg-surface shadow-xl" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true" aria-labelledby="rematch-title">
        <div className="flex items-start justify-between gap-4 px-6 pt-5">
          <div className="min-w-0">
            <h2 id="rematch-title" className="text-xl font-bold">
              Fix match · {current.title}
            </h2>
            <p className="mt-1 text-sm text-neutral-400">
              Now matched to {name(current)}, with {plural(current.files, 'file')}.
            </p>
          </div>
          <button type="button" onClick={onClose} aria-label="Close" className="min-h-[44px] min-w-[44px] rounded-md text-neutral-400 hover:bg-surface-2 hover:text-neutral-100">
            ✕
          </button>
        </div>

        <form
          className="flex flex-wrap items-end gap-2 px-6 pt-4"
          onSubmit={(e) => {
            e.preventDefault()
            setPicked(null)
            setSubmitted({ q: query, year: year ? Number(year) : undefined })
          }}
        >
          <label className="flex min-w-0 flex-[1_1_280px] flex-col gap-1 text-xs font-semibold text-neutral-400">
            {kind === 'show' ? 'Show' : 'Movie'}
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Title, ID or link"
              aria-describedby="id-search-hint"
              className="min-h-[44px] rounded-md border border-line-2 bg-ground px-3 text-sm font-normal text-neutral-100"
            />
          </label>
          <label className="flex w-24 flex-col gap-1 text-xs font-semibold text-neutral-400">
            Year
            <input value={year} onChange={(e) => setYear(e.target.value)} placeholder="Any" inputMode="numeric" className="min-h-[44px] rounded-md border border-line-2 bg-ground px-3 text-sm font-normal text-neutral-100" />
          </label>
          <button type="submit" className="min-h-[44px] rounded-md border border-line-2 bg-surface-2 px-4 text-sm font-medium hover:bg-ground">
            Search
          </button>
          <p id="id-search-hint" className="basis-full text-xs text-neutral-400">
            {ID_SEARCH_HINT}
          </p>
        </form>

        <div className="max-h-[40vh] space-y-2 overflow-y-auto px-6 pt-4" role="radiogroup" aria-label="TMDB results">
          {results.isPending && submitted.q.trim() && <p className="text-sm text-neutral-400">Searching…</p>}
          {results.isError && <p className="text-sm text-red-400">{results.error.message}</p>}
          {results.data && items.length === 0 && <p className="text-sm text-neutral-400">No results.</p>}
          {items.map((c) => {
            const isCurrent = c.tmdbId === current.tmdbId
            const on = c.tmdbId === chosen?.tmdbId
            return (
              <button
                key={c.tmdbId}
                type="button"
                role="radio"
                aria-checked={on}
                disabled={isCurrent}
                onClick={() => setPicked(c.tmdbId)}
                className={`flex w-full items-start gap-3 rounded-lg border bg-surface-2 p-3 text-left disabled:cursor-default disabled:opacity-70 ${
                  on ? 'border-accent ring-1 ring-accent' : 'border-line-2 hover:border-neutral-600'
                }`}
              >
                <span className={`mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full border-2 ${on ? 'border-accent' : 'border-neutral-600'}`} aria-hidden="true">
                  {on && <span className="h-2.5 w-2.5 rounded-full bg-accent" />}
                </span>
                <span className="w-12 shrink-0">
                  <Poster path={c.posterPath} size="w92" alt={c.title} />
                </span>
                <span className="min-w-0 space-y-1">
                  <span className="block font-semibold">
                    {c.title} {c.year ? <span className="font-medium text-neutral-400">({c.year})</span> : null}
                    {c.country && <span className="ml-2 rounded border border-neutral-700 px-1 align-[2px] text-[11px] font-semibold text-neutral-300">{c.country}</span>}
                    {isCurrent && <span className="ml-2 rounded bg-line-2 px-1.5 py-0.5 align-[2px] text-[11px] font-bold tracking-wide text-neutral-300">CURRENT</span>}
                  </span>
                  <span className="line-clamp-2 block text-sm text-neutral-400">{c.overview}</span>
                </span>
              </button>
            )
          })}
        </div>

        {chosen && (
          <div className="mx-6 mt-4 space-y-3 rounded-lg border border-line bg-ground p-4" aria-live="polite">
            <h3 className="text-xs font-semibold uppercase tracking-wider text-neutral-400">What changes</h3>
            {preview.isPending && <p className="text-sm text-neutral-400">Checking…</p>}
            {preview.isError && <p className="text-sm text-red-400">{preview.error.message}</p>}
            {p && (
              <ul className="space-y-3 text-sm text-neutral-200">
                <li>
                  {p.found} of {plural(p.files, 'file')} move to {name(chosen)}
                  <span className="block text-neutral-400">
                    {p.found === p.files
                      ? kind === 'show'
                        ? 'Every file keeps its season and episode.'
                        : 'Every version moves.'
                      : `${plural(p.files - p.found, 'file')} ${p.files - p.found === 1 ? 'has' : 'have'} no matching episode and will wait under Pick episodes on the Unmatched tab.`}
                  </span>
                </li>
                <li>
                  {kind === 'show'
                    ? p.watched === 0
                      ? 'No watch history to move'
                      : p.watchedKept === p.watched
                        ? `Watch history: all ${plural(p.watched, 'watched episode')} carry over`
                        : `Watch history: ${p.watchedKept} of ${plural(p.watched, 'watched episode')} carry over, ${p.watched - p.watchedKept} ${p.watched - p.watchedKept === 1 ? 'is' : 'are'} cleared`
                    : p.watched === 0
                      ? 'No watch history to move'
                      : 'Watch history moves with it'}
                  {kind === 'show' && p.watched > 0 && <span className="block text-neutral-400">Episodes are paired by season and episode number.</span>}
                </li>
                <li>
                  {name(current)} leaves your library
                  <span className="block text-neutral-400">No files will point at it any more.</span>
                </li>
              </ul>
            )}
          </div>
        )}

        <label className="flex items-start gap-3 px-6 pt-4 text-sm text-neutral-200">
          <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} className="mt-0.5 h-[18px] w-[18px] shrink-0 accent-accent" />
          <span>
            Use this {what} for new files in the same folder
            <span className="block text-neutral-400">Applies when all the files share one folder.</span>
          </span>
        </label>

        {rematch.isError && <p className="px-6 pt-3 text-sm text-red-400">{rematch.error.message}</p>}
        <div className="mt-5 flex flex-wrap justify-end gap-2 border-t border-line px-6 py-4">
          <button type="button" onClick={onClose} className="min-h-[44px] rounded-md px-4 text-sm text-neutral-400 hover:bg-surface-2 hover:text-neutral-100">
            Cancel
          </button>
          <button
            type="button"
            disabled={!chosen || !p || rematch.isPending}
            onClick={() =>
              chosen &&
              rematch.mutate(
                { kind, id: current.id, tmdbId: chosen.tmdbId, remember },
                {
                  onSuccess: (r) => {
                    onClose()
                    if (kind === 'show' && r.showId != null) void navigate({ to: '/shows/$showId', params: { showId: String(r.showId) }, replace: true })
                    if (kind === 'movie' && r.movieId != null) void navigate({ to: '/movies/$movieId', params: { movieId: String(r.movieId) }, replace: true })
                  },
                },
              )
            }
            className="min-h-[44px] rounded-md btn-primary px-5 text-sm font-semibold disabled:opacity-50"
          >
            {rematch.isPending ? 'Moving…' : p ? `Move ${plural(p.found, 'file')}` : 'Move files'}
          </button>
        </div>
      </div>
    </div>
  )
}
