import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { candidatesQuery, useApplyMatch } from '../api/queries'
import { Poster } from './Poster'
import { ID_SEARCH_HINT } from '../lib/searchHint'
import type { components } from '../api/schema'

type Guess = components['schemas']['Guess']

/** The parts of a file the dialog reads; a MediaFile or an unmatched group's file. */
export type FixTarget = {
  id: number
  relativePath: string
  matchError?: string
  parsed: Pick<Guess, 'title' | 'year' | 'season' | 'episodes'>
}

export function FixMatchDialog({ file, type, onClose }: { file: FixTarget; type: 'movie' | 'tv'; onClose: () => void }) {
  const g = file.parsed
  const [query, setQuery] = useState(g.title)
  const [year, setYear] = useState<string>(g.year ? String(g.year) : '')
  const [season, setSeason] = useState<string>(g.season != null ? String(g.season) : '')
  const [episodes, setEpisodes] = useState<string>(g.episodes?.join(',') ?? '')
  const [submitted, setSubmitted] = useState({ q: g.title, year: g.year ?? undefined })
  const candidates = useQuery(candidatesQuery(file.id, submitted.q, submitted.year))
  const apply = useApplyMatch()

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const needsEpisode = type === 'tv'
  const parsedEpisodes = episodes
    .split(/[,\s]+/)
    .map((s) => Number(s))
    .filter((n) => Number.isInteger(n) && n > 0)

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/70 p-4 sm:p-10" onClick={onClose}>
      <div
        className="w-full max-w-2xl rounded-lg border border-neutral-700 bg-neutral-900 p-5 shadow-xl"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
      >
        <div className="flex items-start justify-between gap-4">
          <div>
            <h2 className="text-lg font-semibold">Fix match</h2>
            <p className="break-all text-xs text-neutral-500">{file.relativePath}</p>
            {file.matchError && <p className="mt-1 text-xs text-amber-400">{file.matchError}</p>}
          </div>
          <button type="button" onClick={onClose} className="text-neutral-400 hover:text-neutral-100">
            ✕
          </button>
        </div>

        <form
          className="mt-4 flex flex-wrap gap-2"
          onSubmit={(e) => {
            e.preventDefault()
            setSubmitted({ q: query, year: year ? Number(year) : undefined })
          }}
        >
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={type === 'tv' ? 'Show name, ID or link' : 'Movie title, ID or link'}
            aria-describedby="fix-id-hint"
            className="min-w-0 flex-1 rounded-md border border-neutral-700 bg-neutral-950 px-3 py-1.5 text-sm"
          />
          <input
            value={year}
            onChange={(e) => setYear(e.target.value)}
            placeholder="Year"
            inputMode="numeric"
            className="w-20 rounded-md border border-neutral-700 bg-neutral-950 px-3 py-1.5 text-sm"
          />
          <button type="submit" className="rounded-md border border-neutral-600 px-3 py-1.5 text-sm hover:bg-neutral-800">
            Search
          </button>
          <p id="fix-id-hint" className="basis-full text-xs text-neutral-400">
            {ID_SEARCH_HINT}
          </p>
        </form>

        {needsEpisode && (
          <div className="mt-3 flex flex-wrap items-center gap-2 text-sm text-neutral-400">
            <span>Season</span>
            <input
              value={season}
              onChange={(e) => setSeason(e.target.value)}
              inputMode="numeric"
              className="w-16 rounded-md border border-neutral-700 bg-neutral-950 px-2 py-1 text-sm text-neutral-100"
            />
            <span>Episode(s)</span>
            <input
              value={episodes}
              onChange={(e) => setEpisodes(e.target.value)}
              placeholder="e.g. 1 or 1,2"
              className="w-28 rounded-md border border-neutral-700 bg-neutral-950 px-2 py-1 text-sm text-neutral-100"
            />
          </div>
        )}

        <div className="mt-4 max-h-[50vh] space-y-2 overflow-y-auto">
          {candidates.isPending && <p className="text-sm text-neutral-400">Searching…</p>}
          {candidates.isError && <p className="text-sm text-red-400">{candidates.error.message}</p>}
          {candidates.data && candidates.data.items.length === 0 && <p className="text-sm text-neutral-400">No results.</p>}
          {candidates.data?.items.map((c) => {
            const disabled = apply.isPending || (needsEpisode && (!season || parsedEpisodes.length === 0))
            return (
              <button
                key={c.tmdbId}
                type="button"
                disabled={disabled}
                onClick={() =>
                  apply.mutate(
                    {
                      fileId: file.id,
                      tmdbId: c.tmdbId,
                      season: needsEpisode ? Number(season) : undefined,
                      episodes: needsEpisode ? parsedEpisodes : undefined,
                    },
                    { onSuccess: onClose },
                  )
                }
                className="flex w-full gap-3 rounded-md border border-neutral-800 p-2 text-left hover:border-neutral-600 disabled:opacity-50"
              >
                <div className="w-14 shrink-0">
                  <Poster path={c.posterPath} size="w92" alt={c.title} />
                </div>
                <div className="min-w-0">
                  <div className="font-medium">
                    {c.title} {c.year ? <span className="text-neutral-400">({c.year})</span> : null}
                    {c.country && <span className="ml-2 text-xs text-neutral-500">{c.country}</span>}
                    <span className="ml-2 text-xs text-neutral-500">score {c.score.toFixed(2)}</span>
                  </div>
                  <p className="line-clamp-2 text-xs text-neutral-400">{c.overview}</p>
                </div>
              </button>
            )
          })}
        </div>
        {apply.isError && <p className="mt-3 text-sm text-red-400">{apply.error.message}</p>}
        {needsEpisode && (!season || parsedEpisodes.length === 0) && (
          <p className="mt-3 text-xs text-amber-400">Enter the season and episode number(s) to apply a match.</p>
        )}
      </div>
    </div>
  )
}
