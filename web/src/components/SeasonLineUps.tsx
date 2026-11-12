import { useEffect, useState } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { libraryCandidatesQuery, seasonPreviewQuery, useMatchSeasons } from '../api/queries'
import { Poster } from './Poster'
import { formatDate } from '../lib/format'
import { ID_SEARCH_HINT } from '../lib/searchHint'
import {
  allSuggested,
  filesLabel,
  fitLabel,
  matchedText,
  previewSummary,
  previewTarget,
  rememberLabel,
  startingRule,
  targetLabel,
  type SeasonLineUp,
  type SeasonRule,
} from '../lib/seasonLineUp'
import type { components } from '../api/schema'

type Group = components['schemas']['UnmatchedGroup']
type GroupResult = components['schemas']['GroupResult']

/** What a season match reports back to the Unmatched view's banner. */
export type SeasonsMatched = (text: string, r: GroupResult) => void

/**
 * The seasons of a group that TMDB numbers differently (PLAN-SEASON-MAPPING.md):
 * one row each with the server's suggestion and why, and one button to match
 * them all once every row has a target.
 */
export function SeasonRows({
  g,
  lineUps,
  libraryId,
  tmdb,
  onMatched,
}: {
  g: Group
  lineUps: SeasonLineUp[]
  libraryId: number
  tmdb: boolean
  onMatched: SeasonsMatched
}) {
  const [remember, setRemember] = useState(true)
  const [editing, setEditing] = useState<SeasonLineUp | null>(null)
  const match = useMatchSeasons()
  const ready = allSuggested(lineUps)
  const seasons = lineUps.map((lu) => lu.season)
  const files = lineUps.reduce((n, lu) => n + lu.fileIds.length, 0)
  const showName = (lu: SeasonLineUp) => (lu.suggestion ? `${lu.suggestion.show.title}${lu.suggestion.show.year ? ` (${lu.suggestion.show.year})` : ''}` : '')

  const matchAll = () =>
    match.mutate(
      { libraryId, lineUps: lineUps.map((lu) => ({ fileIds: lu.fileIds, rule: lu.suggestion!.rule })), remember: remember && !!g.folder },
      { onSuccess: (r) => onMatched(matchedText(r.matched, r.needEpisodes, lineUps.map(showName), seasons, r.folder), r) },
    )

  return (
    <>
      <ul className="divide-y divide-line rounded-lg border border-line-2 bg-ground">
        {lineUps.map((lu) => {
          const s = lu.suggestion
          const label = `${g.show?.title ?? g.title} Season ${lu.season}`
          return (
            <li key={lu.season} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-3.5 py-3">
              <div className="flex-[0_0_190px]">
                <div className="text-sm font-semibold">Season {lu.season}</div>
                <div className="text-xs text-neutral-400">{filesLabel(lu)}</div>
              </div>
              <span className="hidden text-lg text-neutral-500 sm:block" aria-hidden="true">
                →
              </span>
              {s ? (
                <>
                  <div className="min-w-0 flex-[1_1_320px] space-y-0.5">
                    <div className="text-sm font-semibold">
                      <span className="sm:hidden" aria-hidden="true">
                        ↓{' '}
                      </span>
                      {targetLabel(s)}
                    </div>
                    <div className="text-xs text-neutral-400">{s.detail}</div>
                  </div>
                  <span className="inline-flex items-center gap-1.5 whitespace-nowrap text-[13px] text-accent">
                    <span className="grid h-4 w-4 place-items-center rounded-full bg-accent text-[10px] font-bold text-ground" aria-hidden="true">
                      ✓
                    </span>
                    {fitLabel(s.found, lu.fileIds.length)}
                  </span>
                  <button
                    type="button"
                    disabled={!tmdb}
                    onClick={() => setEditing(lu)}
                    aria-label={`Change where ${label} goes`}
                    className="min-h-[36px] rounded-md px-3 text-sm text-neutral-400 hover:bg-surface-2 hover:text-neutral-100 disabled:opacity-50"
                  >
                    Change…
                  </button>
                </>
              ) : (
                <>
                  <div className="min-w-0 flex-[1_1_320px] space-y-0.5">
                    <div className="text-sm text-warn">Nothing on TMDB lines up with these on its own.</div>
                    <div className="text-xs text-neutral-400">Choose the season and episode the first file is, or another show.</div>
                  </div>
                  <button
                    type="button"
                    disabled={!tmdb}
                    onClick={() => setEditing(lu)}
                    aria-label={`Line up ${label}`}
                    className="min-h-[36px] rounded-md border border-line-2 bg-surface-2 px-3 text-sm font-medium hover:bg-surface disabled:opacity-50"
                  >
                    Line up…
                  </button>
                </>
              )}
            </li>
          )
        })}
      </ul>

      {ready ? (
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
          {g.folder ? (
            <label className="flex min-h-[40px] flex-[1_1_320px] items-center gap-2.5 text-sm text-neutral-300">
              <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} className="h-[18px] w-[18px] shrink-0 accent-accent" />
              {rememberLabel(seasons, g.folder)}
            </label>
          ) : (
            <span className="flex-1" />
          )}
          {match.isError && <p className="basis-full text-sm text-red-400">{match.error.message}</p>}
          <button type="button" disabled={!tmdb || match.isPending} onClick={matchAll} className="min-h-[40px] rounded-md btn-primary px-4 text-sm font-semibold disabled:opacity-50">
            {match.isPending ? 'Matching…' : `Match ${files} file${files === 1 ? '' : 's'}`}
          </button>
        </div>
      ) : (
        lineUps.length > 1 && <p className="text-sm text-neutral-400">Line up one season and the others are suggested from it.</p>
      )}

      {editing && g.show && (
        <SeasonLineUpDialog
          g={g}
          lu={editing}
          tmdbId={g.show.tmdbId}
          libraryId={libraryId}
          onClose={() => setEditing(null)}
          onMatched={(text, r) => {
            setEditing(null)
            onMatched(text, r)
          }}
        />
      )}
    </>
  )
}

/** Choose where a season's first file starts, see what each file becomes, and match. */
function SeasonLineUpDialog({
  g,
  lu,
  tmdbId,
  libraryId,
  onClose,
  onMatched,
}: {
  g: Group
  lu: SeasonLineUp
  tmdbId: number
  libraryId: number
  onClose: () => void
  onMatched: SeasonsMatched
}) {
  const [rule, setRule] = useState<SeasonRule>(() => startingRule(lu, tmdbId))
  const [searching, setSearching] = useState(false)
  const [query, setQuery] = useState(g.show?.title ?? g.title)
  const [submitted, setSubmitted] = useState(query)
  const [remember, setRemember] = useState(true)
  const preview = useQuery({ ...seasonPreviewQuery(libraryId, lu.fileIds, rule), placeholderData: keepPreviousData })
  const results = useQuery({ ...libraryCandidatesQuery(libraryId, submitted, undefined), enabled: searching && submitted.trim().length > 0 })
  const match = useMatchSeasons()

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const pv = preview.data
  const showLoaded = pv?.show.tmdbId === rule.tmdbId
  const seasonKnown = !!pv && showLoaded && pv.seasons.some((s) => s.number === rule.toSeason)
  const found = showLoaded && pv ? pv.found : 0
  const total = lu.fileIds.length
  const paths = new Map(g.files.map((f) => [f.id, f.relativePath.split('/').pop() ?? f.relativePath]))
  const first = `S${String(lu.season).padStart(2, '0')}E${String(lu.fromEpisode).padStart(2, '0')}`
  const showName = pv && showLoaded ? `${pv.show.title}${pv.show.year ? ` (${pv.show.year})` : ''}` : ''

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/80 p-4 sm:p-10" onClick={onClose}>
      <div className="w-full max-w-3xl rounded-xl border border-line-2 bg-surface shadow-xl" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true" aria-labelledby="season-lineup-title">
        <div className="flex items-start justify-between gap-4 px-6 pt-5">
          <div className="min-w-0">
            <h2 id="season-lineup-title" className="text-xl font-bold">
              Line up Season {lu.season}
            </h2>
            <p className="mt-1 break-all font-mono text-xs text-neutral-400">
              {[g.folder || g.title, filesLabel(lu)].filter(Boolean).join(' · ')}
            </p>
          </div>
          <button type="button" onClick={onClose} aria-label="Close" className="min-h-[44px] min-w-[44px] rounded-md text-neutral-400 hover:bg-surface-2 hover:text-neutral-100">
            ✕
          </button>
        </div>

        <div className="space-y-5 px-6 pt-5">
          <section className="space-y-2">
            <h3 className="text-xs font-semibold uppercase tracking-wider text-neutral-400">Show</h3>
            {!searching ? (
              <div className="flex flex-wrap items-center gap-3 rounded-lg border border-line-2 bg-surface-2 px-3 py-2.5">
                <div className="w-9 shrink-0">
                  <Poster path={showLoaded ? pv?.show.posterPath : undefined} size="w92" alt="" />
                </div>
                <div className="min-w-0 flex-[1_1_200px]">
                  <div className="font-semibold">{showName || 'Loading…'}</div>
                  {pv && showLoaded && (
                    <div className="text-[13px] text-neutral-400">
                      {pv.show.seasons} season{pv.show.seasons === 1 ? '' : 's'} on TMDB
                    </div>
                  )}
                </div>
                <button type="button" onClick={() => setSearching(true)} className="min-h-[40px] rounded-md border border-line-2 bg-surface px-3 text-sm font-medium hover:bg-ground">
                  Another show…
                </button>
              </div>
            ) : (
              <>
                <form
                  className="flex flex-wrap items-end gap-2"
                  onSubmit={(e) => {
                    e.preventDefault()
                    setSubmitted(query)
                  }}
                >
                  <label className="flex min-w-0 flex-[1_1_280px] flex-col gap-1 text-xs font-semibold text-neutral-400">
                    Search TMDB
                    <input
                      value={query}
                      onChange={(e) => setQuery(e.target.value)}
                      placeholder="Title, ID or link"
                      aria-describedby="season-search-hint"
                      className="min-h-[44px] rounded-md border border-line-2 bg-ground px-3 text-sm font-normal text-neutral-100"
                    />
                  </label>
                  <button type="submit" className="min-h-[44px] rounded-md border border-line-2 bg-surface-2 px-4 text-sm font-medium hover:bg-ground">
                    Search
                  </button>
                  <p id="season-search-hint" className="basis-full text-xs text-neutral-400">
                    {ID_SEARCH_HINT}
                  </p>
                </form>
                <div className="max-h-[32vh] space-y-2 overflow-y-auto" role="radiogroup" aria-label="TMDB results">
                  {results.isPending && <p className="text-sm text-neutral-400">Searching…</p>}
                  {results.isError && <p className="text-sm text-red-400">{results.error.message}</p>}
                  {results.data && results.data.items.length === 0 && <p className="text-sm text-neutral-400">No results.</p>}
                  {results.data?.items.slice(0, 8).map((c) => {
                    const on = c.tmdbId === rule.tmdbId
                    return (
                      <button
                        key={c.tmdbId}
                        type="button"
                        role="radio"
                        aria-checked={on}
                        // Another show starts at its first season until the admin says otherwise.
                        onClick={() => setRule((r) => (r.tmdbId === c.tmdbId ? r : { ...r, tmdbId: c.tmdbId, toSeason: 1, toEpisode: 1 }))}
                        className={`flex w-full items-center gap-3 rounded-lg border bg-surface-2 p-2.5 text-left ${on ? 'border-accent ring-1 ring-accent' : 'border-line-2 hover:border-neutral-600'}`}
                      >
                        <span className={`flex h-5 w-5 shrink-0 items-center justify-center rounded-full border-2 ${on ? 'border-accent' : 'border-neutral-600'}`} aria-hidden="true">
                          {on && <span className="h-2.5 w-2.5 rounded-full bg-accent" />}
                        </span>
                        <span className="w-9 shrink-0">
                          <Poster path={c.posterPath} size="w92" alt="" />
                        </span>
                        <span className="min-w-0">
                          <span className="block font-semibold">
                            {c.title} {c.year ? <span className="font-medium text-neutral-400">({c.year})</span> : null}
                          </span>
                          <span className="line-clamp-1 block text-[13px] text-neutral-400">{c.tmdbId === tmdbId ? "The folder's show" : c.overview}</span>
                        </span>
                      </button>
                    )
                  })}
                </div>
              </>
            )}
          </section>

          <section className="space-y-2">
            <h3 className="text-xs font-semibold uppercase tracking-wider text-neutral-400">The first file, {first}, is</h3>
            <div className="flex flex-wrap gap-3">
              <label className="flex flex-[1_1_200px] flex-col gap-1 text-xs font-semibold text-neutral-400">
                Season
                <select
                  value={seasonKnown ? rule.toSeason : ''}
                  onChange={(e) => e.target.value && setRule((r) => ({ ...r, toSeason: Number(e.target.value), toEpisode: 1 }))}
                  className="min-h-[44px] rounded-md border border-line-2 bg-ground px-2 text-sm font-normal text-neutral-100"
                >
                  {!seasonKnown && <option value="">{preview.isFetching ? 'Loading…' : 'Choose…'}</option>}
                  {showLoaded &&
                    pv?.seasons.map((s) => (
                      <option key={s.number} value={s.number}>
                        Season {s.number} · {s.episodeCount} episode{s.episodeCount === 1 ? '' : 's'}
                        {s.airDate ? ` · ${s.airDate.slice(0, 4)}` : ''}
                      </option>
                    ))}
                </select>
              </label>
              <label className="flex min-w-0 flex-[2_1_280px] flex-col gap-1 text-xs font-semibold text-neutral-400">
                Episode
                <select
                  value={seasonKnown ? rule.toEpisode : ''}
                  disabled={!seasonKnown}
                  onChange={(e) => e.target.value && setRule((r) => ({ ...r, toEpisode: Number(e.target.value) }))}
                  className="min-h-[44px] rounded-md border border-line-2 bg-ground px-2 text-sm font-normal text-neutral-100 disabled:opacity-50"
                >
                  {!seasonKnown && <option value="">Pick a season first</option>}
                  {seasonKnown &&
                    pv?.episodes.map((e) => (
                      <option key={e.number} value={e.number}>
                        {`E${String(e.number).padStart(2, '0')} · ${e.title}${e.airDate ? ` · ${formatDate(e.airDate)}` : ''}`}
                      </option>
                    ))}
                </select>
              </label>
            </div>
            <p className="text-[13px] text-neutral-400">
              The other files follow in order from there. Episode names and air dates are TMDB&apos;s, so you can check the first and last against what you have.
            </p>
          </section>

          <section className="space-y-2" aria-live="polite">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <h3 className="text-xs font-semibold uppercase tracking-wider text-neutral-400">What each file becomes</h3>
              {seasonKnown && <span className={`text-[13px] ${found === total ? 'text-neutral-300' : 'text-warn'}`}>{previewSummary(found, total)}</span>}
            </div>
            {preview.isError && <p className="text-sm text-red-400">{preview.error.message}</p>}
            <ul className="max-h-[36vh] divide-y divide-line overflow-y-auto rounded-lg border border-line-2 bg-ground">
              {(pv?.files ?? []).map((f) => {
                const to = seasonKnown ? previewTarget(f, formatDate) : { text: 'Choose a season.', ok: false }
                return (
                  <li key={f.fileId} className="grid grid-cols-1 items-center gap-x-3 gap-y-1 px-3.5 py-2.5 text-[13px] sm:grid-cols-[minmax(0,1fr)_20px_minmax(0,1fr)]">
                    <span className="break-all font-mono text-xs text-neutral-300">{paths.get(f.fileId) ?? `File ${f.fileId}`}</span>
                    <span className="hidden text-neutral-500 sm:block" aria-hidden="true">
                      →
                    </span>
                    <span className={to.ok ? '' : 'text-warn'}>{to.text}</span>
                  </li>
                )
              })}
              {!pv && <li className="px-3.5 py-2.5 text-sm text-neutral-400">Checking…</li>}
            </ul>
          </section>

          {g.folder && (
            <label className="flex items-start gap-3 text-sm text-neutral-200">
              <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} className="mt-0.5 h-[18px] w-[18px] shrink-0 accent-accent" />
              <span>
                {rememberLabel([lu.season], g.folder)}
                <span className="block text-neutral-400">Files added to this folder later follow on from these.</span>
              </span>
            </label>
          )}
        </div>

        {match.isError && <p className="px-6 pt-3 text-sm text-red-400">{match.error.message}</p>}
        <div className="mt-5 flex flex-wrap justify-end gap-2 border-t border-line px-6 py-4">
          <button type="button" onClick={onClose} className="min-h-[44px] rounded-md px-4 text-sm text-neutral-400 hover:bg-surface-2 hover:text-neutral-100">
            Cancel
          </button>
          <button
            type="button"
            disabled={!seasonKnown || found === 0 || preview.isFetching || match.isPending}
            onClick={() =>
              match.mutate(
                { libraryId, lineUps: [{ fileIds: lu.fileIds, rule }], remember: remember && !!g.folder },
                { onSuccess: (r) => onMatched(matchedText(r.matched, r.needEpisodes, [showName], [lu.season], r.folder), r) },
              )
            }
            className="min-h-[44px] rounded-md btn-primary px-5 text-sm font-semibold disabled:opacity-50"
          >
            {match.isPending ? 'Matching…' : found > 0 ? `Match ${found} file${found === 1 ? '' : 's'}` : 'Match'}
          </button>
        </div>
      </div>
    </div>
  )
}
