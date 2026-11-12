import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { candidatesQuery, healthQuery, lineUpQuery, seasonQuery, seasonSuggestQuery, showQuery, unmatchedQuery, useApplyMatch, useIgnoreFiles, useMatchFiles, useMatchLibrary } from '../api/queries'
import { Chips } from './Controls'
import { Poster } from './Poster'
import { SeasonRows } from './SeasonLineUps'
import { episodeCode, formatBytes, formatDate } from '../lib/format'
import { ID_SEARCH_HINT } from '../lib/searchHint'
import type { components } from '../api/schema'

type Group = components['schemas']['UnmatchedGroup']
type Candidate = components['schemas']['Candidate']
type GroupResult = components['schemas']['GroupResult']
type GroupFile = Group['files'][number]
type Season = components['schemas']['Season']
type Kind = Group['kind']
type Filter = 'all' | Kind
type LibraryType = 'movie' | 'tv'

/** A match made on this screen, shown until the viewer dismisses it. */
type Done = { key: string; text: string; showId?: number; movieId?: number }

const SECTIONS: { kind: Kind; title: (t: LibraryType) => string; sub: (t: LibraryType) => string }[] = [
  {
    kind: 'show',
    title: (t) => (t === 'tv' ? 'Pick a show' : 'Pick a movie'),
    sub: (t) => `The ${t === 'tv' ? 'folder' : 'name'} fits more than one ${t === 'tv' ? 'show' : 'movie'} on TMDB, or none closely enough.`,
  },
  {
    kind: 'episodes',
    title: () => 'Line up seasons',
    sub: () => 'The show is right, but TMDB numbers these differently from your files. Say where each season starts and its episodes follow in order.',
  },
  { kind: 'lookup', title: () => 'Not looked up', sub: () => 'TMDB has not answered for these yet. Nothing is wrong with the files.' },
  { kind: 'ignored', title: () => 'Ignored', sub: () => 'Left out of matching and of the unmatched count.' },
]

/** The Unmatched view of the TV Shows and Movies screens: one decision per folder. */
export function UnmatchedView({ libraryId, type }: { libraryId: number; type: LibraryType }) {
  const health = useQuery(healthQuery)
  const tmdb = health.data?.tmdbConfigured ?? false
  const groups = useQuery(unmatchedQuery(libraryId))
  const rematch = useMatchLibrary()
  const [filter, setFilter] = useState<Filter>('all')
  const [searching, setSearching] = useState<Group | null>(null)
  const [done, setDone] = useState<Done[]>([])

  const onMatched = (g: Group, c: Candidate, r: GroupResult) => {
    let text = `Matched ${r.matched} file${r.matched === 1 ? '' : 's'} to ${c.title}${c.year ? ` (${c.year})` : ''}.`
    if (r.needEpisodes) text += ` ${r.needEpisodes} need${r.needEpisodes === 1 ? 's' : ''} lining up under Line up seasons.`
    if (r.folder) text += ` New files in ${r.folder} will use it.`
    setDone((d) => [{ key: g.key, text, showId: r.showId, movieId: r.movieId }, ...d.filter((x) => x.key !== g.key)])
  }

  if (groups.isPending) return <p className="text-neutral-400">Loading…</p>
  if (groups.isError) return <p className="text-red-400">{groups.error.message}</p>
  const all = groups.data.groups.filter((g) => g.kind !== 'ignored')
  const ignored = groups.data.groups.filter((g) => g.kind === 'ignored')
  const count = (k: Kind) => groups.data.groups.filter((g) => g.kind === k).length
  const files = all.reduce((n, g) => n + g.files.length, 0)
  const ignoredFiles = ignored.reduce((n, g) => n + g.files.length, 0)
  const what = type === 'tv' ? 'show' : 'movie'

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <p className="max-w-3xl text-neutral-300">
          {all.length === 0
            ? 'Every file is matched.'
            : `${files} file${files === 1 ? '' : 's'} in ${all.length} group${all.length === 1 ? '' : 's'} need a decision. A ${what} you pick is remembered for its folder, so files added there later match on their own.`}
        </p>
        <div className="flex flex-wrap gap-2">
          <Link
            to="/libraries/$libraryId/files"
            params={{ libraryId: String(libraryId) }}
            className="inline-flex min-h-[40px] items-center rounded-md px-3 text-sm text-neutral-400 hover:bg-surface-2 hover:text-neutral-100"
          >
            Full file list
          </Link>
          {count('lookup') > 0 && (
            <button
              type="button"
              disabled={!tmdb || rematch.isPending}
              onClick={() => rematch.mutate({ id: libraryId, rematch: true })}
              className="min-h-[40px] rounded-md border border-line-2 bg-surface px-4 text-sm font-medium hover:bg-surface-2 disabled:opacity-50"
            >
              Retry lookups
            </button>
          )}
        </div>
      </div>

      {!tmdb && health.data && (
        <p className="rounded-lg border border-warn/40 px-4 py-3 text-sm text-warn">Add a TMDB API key in Settings to look up and match files.</p>
      )}

      {done.map((d) => (
        <div key={d.key} role="status" className="flex flex-wrap items-center gap-3 rounded-lg border border-accent bg-surface-2 px-4 py-3">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" className="text-accent" aria-hidden="true">
            <path d="m5 12.5 4.5 4.5L19 7.5" />
          </svg>
          <p className="min-w-0 flex-[1_1_220px] text-sm text-neutral-200">{d.text}</p>
          {d.showId != null && (
            <Link to="/shows/$showId" params={{ showId: String(d.showId) }} className="min-h-[36px] content-center rounded-md border border-line-2 px-3 text-sm hover:bg-surface">
              Open show
            </Link>
          )}
          {d.movieId != null && (
            <Link to="/movies/$movieId" params={{ movieId: String(d.movieId) }} className="min-h-[36px] content-center rounded-md border border-line-2 px-3 text-sm hover:bg-surface">
              Open movie
            </Link>
          )}
          <button type="button" onClick={() => setDone((x) => x.filter((y) => y.key !== d.key))} className="min-h-[36px] rounded-md px-3 text-sm text-neutral-400 hover:text-neutral-100">
            Dismiss
          </button>
        </div>
      ))}

      {all.length + ignored.length > 0 && (
        <Chips<Filter>
          options={[
            { value: 'all', label: 'All', count: all.length },
            ...SECTIONS.filter((s) => count(s.kind) > 0).map((s) => ({ value: s.kind as Filter, label: s.title(type), count: count(s.kind) })),
          ]}
          value={filter}
          onChange={setFilter}
        />
      )}

      {SECTIONS.filter((s) => (filter === 'all' && s.kind !== 'ignored') || filter === s.kind).map((s) => {
        const list = groups.data.groups.filter((g) => g.kind === s.kind)
        if (list.length === 0) return null
        return (
          <section key={s.kind} aria-labelledby={`unmatched-${s.kind}`} className="space-y-3 pt-2">
            <div>
              <h2 id={`unmatched-${s.kind}`} className="text-lg font-semibold">
                {s.title(type)}
              </h2>
              <p className="text-sm text-neutral-400">{s.sub(type)}</p>
            </div>
            {list.map((g) =>
              g.kind === 'show' ? (
                <ShowGroup key={g.key} g={g} libraryId={libraryId} type={type} tmdb={tmdb} onSearch={() => setSearching(g)} onMatched={onMatched} />
              ) : g.kind === 'episodes' ? (
                <EpisodesGroup
                  key={g.key}
                  g={g}
                  libraryId={libraryId}
                  tmdb={tmdb}
                  onSearch={() => setSearching(g)}
                  onSeasonsMatched={(text, r) => setDone((d) => [{ key: `${g.key}:${Date.now()}`, text, showId: r.showId }, ...d])}
                />
              ) : g.kind === 'ignored' ? (
                <IgnoredGroup key={g.key} g={g} libraryId={libraryId} />
              ) : (
                <LookupGroup key={g.key} g={g} libraryId={libraryId} tmdb={tmdb} retrying={rematch.isPending} onRetry={() => rematch.mutate({ id: libraryId, rematch: true })} />
              ),
            )}
          </section>
        )
      })}

      {searching && (
        <MatchGroupDialog
          g={searching}
          libraryId={libraryId}
          type={type}
          onClose={() => setSearching(null)}
          onMatched={(c, r) => {
            onMatched(searching, c, r)
            setSearching(null)
          }}
        />
      )}
      {filter === 'all' && ignoredFiles > 0 && (
        <p className="flex flex-wrap items-center gap-3 border-t border-line pt-5 text-sm text-neutral-400">
          {ignoredFiles} ignored file{ignoredFiles === 1 ? ' is' : 's are'} hidden and left out of the count.
          <button type="button" onClick={() => setFilter('ignored')} className="min-h-[36px] text-neutral-200 underline underline-offset-4 hover:text-white">
            Show ignored
          </button>
        </p>
      )}
    </div>
  )
}

function seasonsLabel(seasons: number[]): string {
  if (seasons.length === 0) return ''
  if (seasons.length === 1) return seasons[0] === 0 ? 'Specials' : `Season ${seasons[0]}`
  const contiguous = seasons.every((n, i) => i === 0 || n === seasons[i - 1] + 1)
  return contiguous ? `Seasons ${seasons[0]}–${seasons[seasons.length - 1]}` : `Seasons ${seasons.join(', ')}`
}

function groupFacts(g: Group): string {
  const size = g.files.reduce((n, f) => n + f.size, 0)
  return [`${g.files.length} file${g.files.length === 1 ? '' : 's'}`, seasonsLabel(g.seasons), formatBytes(size)].filter(Boolean).join(' · ')
}

function FolderLine({ g }: { g: Group }) {
  const path = g.folder || (g.files.length === 1 ? g.files[0].relativePath : '')
  if (!path) return null
  return (
    <div className="flex items-center gap-2 break-all font-mono text-xs text-neutral-400">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinejoin="round" aria-hidden="true" className="shrink-0">
        <path d="M3 6.5A1.5 1.5 0 0 1 4.5 5H10l2 2h7.5A1.5 1.5 0 0 1 21 8.5v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 17.5z" />
      </svg>
      {path}
    </div>
  )
}

function FileList({ g }: { g: Group }) {
  if (g.files.length < 2) return null
  const shown = g.files.slice(0, 50)
  return (
    <details>
      <summary className="flex min-h-[32px] cursor-pointer items-center text-sm text-neutral-400 hover:text-neutral-100">Show the {g.files.length} files</summary>
      <ul className="mt-2 space-y-1 rounded-md border border-line bg-ground p-3 font-mono text-xs text-neutral-400">
        {shown.map((f) => (
          <li key={f.id} className="break-all">
            {f.relativePath}
          </li>
        ))}
        {g.files.length > shown.length && <li>… {g.files.length - shown.length} more</li>}
      </ul>
    </details>
  )
}

function ShowGroup({
  g,
  libraryId,
  type,
  tmdb,
  onSearch,
  onMatched,
}: {
  g: Group
  libraryId: number
  type: LibraryType
  tmdb: boolean
  onSearch: () => void
  onMatched: (g: Group, c: Candidate, r: GroupResult) => void
}) {
  const candidates = g.candidates.slice(0, 3)
  return (
    <article className="flex flex-wrap gap-7 rounded-xl border border-line bg-surface p-5">
      <div className="min-w-0 flex-[1_1_340px] space-y-2">
        <FolderLine g={g} />
        <h3 className="text-lg font-semibold">
          {g.title || 'No name found'}
          {g.year ? <span className="font-normal text-neutral-400"> ({g.year})</span> : null}
        </h3>
        <p className="text-sm text-neutral-400">{groupFacts(g)}</p>
        <p className="text-sm text-warn">{g.reason}</p>
        <FileList g={g} />
      </div>
      <div className="flex min-w-0 flex-[2_1_520px] flex-col justify-center gap-2.5">
        {candidates.map((c, i) => (
          <CandidateRow key={c.tmdbId} g={g} c={c} primary={i === 0} lineUp={i < 2} libraryId={libraryId} type={type} tmdb={tmdb} onMatched={onMatched} />
        ))}
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            disabled={!tmdb}
            onClick={onSearch}
            className={`min-h-[36px] rounded-md px-3 text-sm disabled:opacity-50 ${
              candidates.length === 0 ? 'btn-primary font-semibold' : 'text-neutral-400 hover:bg-surface-2 hover:text-neutral-100'
            }`}
          >
            Search TMDB…
          </button>
          <IgnoreButton g={g} libraryId={libraryId} />
        </div>
      </div>
    </article>
  )
}

/** Ignores every file of a group: samples, extras, stray clips. */
function IgnoreButton({ g, libraryId, undo = false }: { g: Group; libraryId: number; undo?: boolean }) {
  const ignore = useIgnoreFiles()
  const many = g.files.length > 1
  return (
    <button
      type="button"
      disabled={ignore.isPending}
      onClick={() => ignore.mutate({ libraryId, fileIds: g.files.map((f) => f.id), ignored: !undo })}
      title={ignore.isError ? ignore.error.message : undefined}
      className="min-h-[36px] rounded-md px-3 text-sm text-neutral-400 hover:bg-surface-2 hover:text-neutral-100 disabled:opacity-50"
    >
      {undo ? 'Stop ignoring' : many ? (g.folder ? 'Ignore folder' : 'Ignore files') : 'Ignore'}
    </button>
  )
}

function IgnoredGroup({ g, libraryId }: { g: Group; libraryId: number }) {
  return (
    <div className="flex flex-wrap items-center gap-5 rounded-xl border border-line bg-surface px-5 py-4">
      <div className="min-w-0 flex-[1_1_420px] space-y-1">
        <div className="font-semibold">
          {g.title || g.folder || 'Unnamed files'} · {g.files.length} file{g.files.length === 1 ? '' : 's'}
        </div>
        <div className="break-all font-mono text-xs text-neutral-400">{g.folder || g.files[0].relativePath}</div>
      </div>
      <IgnoreButton g={g} libraryId={libraryId} undo />
    </div>
  )
}

function lineUpText(found: number, total: number): string {
  if (found === total) return total === 1 ? 'The file lines up with its episodes' : `All ${total} files line up with its episodes`
  if (found === 0) return `None of the ${total} files line up with its episodes`
  return `${found} of ${total} files line up · ${total - found} would need episodes picked`
}

function CandidateRow({
  g,
  c,
  primary,
  lineUp,
  libraryId,
  type,
  tmdb,
  onMatched,
}: {
  g: Group
  c: Candidate
  primary: boolean
  lineUp: boolean
  libraryId: number
  type: LibraryType
  tmdb: boolean
  onMatched: (g: Group, c: Candidate, r: GroupResult) => void
}) {
  const ids = g.files.map((f) => f.id)
  const lu = useQuery({ ...lineUpQuery(libraryId, ids, c.tmdbId), enabled: tmdb && lineUp && type === 'tv' })
  const match = useMatchFiles()
  const n = g.files.length
  return (
    <div className="flex flex-wrap items-center gap-3.5 rounded-lg border border-line-2 bg-surface-2 px-3 py-2.5">
      <div className="w-11 shrink-0">
        <Poster path={c.posterPath} size="w92" alt={c.title} />
      </div>
      <div className="min-w-0 flex-[1_1_220px] space-y-0.5">
        <div className="font-semibold">
          {c.title} {c.year ? <span className="font-medium text-neutral-400">({c.year})</span> : null}
          {c.country && <span className="ml-2 rounded border border-neutral-700 px-1 align-[2px] text-[11px] font-semibold text-neutral-300">{c.country}</span>}
        </div>
        {type === 'tv' && lu.data ? (
          <p className={`text-sm ${lu.data.found === lu.data.total ? 'text-neutral-400' : 'text-warn'}`}>{lineUpText(lu.data.found, lu.data.total)}</p>
        ) : (
          c.overview && <p className="line-clamp-1 text-sm text-neutral-400">{c.overview}</p>
        )}
        {match.isError && <p className="text-sm text-red-400">{match.error.message}</p>}
      </div>
      <button
        type="button"
        disabled={!tmdb || match.isPending}
        onClick={() => match.mutate({ libraryId, fileIds: ids, tmdbId: c.tmdbId, remember: true }, { onSuccess: (r) => onMatched(g, c, r) })}
        className={`min-h-[36px] rounded-md px-3 text-sm font-semibold disabled:opacity-50 ${primary ? 'btn-primary' : 'border border-line-2 bg-surface hover:bg-ground'}`}
      >
        {match.isPending ? 'Matching…' : n === 1 ? 'Match' : `Match ${n} files`}
      </button>
    </div>
  )
}

type Pick = { season: number; episode: number | null }

/**
 * Where an episode number past the end of its season lands if the numbering
 * runs on into the next seasons (S02E11 when season 2 has 10 → S03E01), or
 * null when it does not fit.
 */
function carryOver(seasons: Season[], season: number, episode: number): Pick | null {
  const counts = new Map(seasons.map((s) => [s.number, s.episodeCount]))
  let s = season
  let e = episode
  while (counts.has(s) && e > (counts.get(s) ?? 0)) {
    e -= counts.get(s) ?? 0
    s++
  }
  return counts.has(s) && e >= 1 ? { season: s, episode: e } : null
}

/** The season that was airing on a date: the latest one that started on or before it. */
function seasonOn(seasons: Season[], date?: string): number | undefined {
  if (!date) return undefined
  return seasons.filter((s) => s.number > 0 && s.airDate && s.airDate <= date).sort((a, b) => b.number - a.number)[0]?.number
}

function EpisodesGroup({
  g,
  libraryId,
  tmdb,
  onSearch,
  onSeasonsMatched,
}: {
  g: Group
  libraryId: number
  tmdb: boolean
  onSearch: () => void
  onSeasonsMatched: (text: string, r: GroupResult) => void
}) {
  const show = g.show
  const detail = useQuery({ ...showQuery(show?.id ?? 0), enabled: show != null })
  // Whole seasons TMDB numbers differently are lined up a season at a time
  // (admins only: for anyone else the request is refused and the files are
  // picked one by one, as before).
  const lineUps = useQuery({ ...seasonSuggestQuery(libraryId, g.files.map((f) => f.id), show?.tmdbId ?? 0), enabled: tmdb && show != null })
  const seasonRows = lineUps.data?.seasons ?? []
  const inSeasonRows = new Set(seasonRows.flatMap((lu) => lu.fileIds))
  const [each, setEach] = useState(false)
  // Files a season row covers are picked one by one only when asked.
  const listed = each ? g.files : g.files.filter((f) => !inSeasonRows.has(f.id))
  const seasons = [...(detail.data?.seasons ?? [])].sort((a, b) => a.number - b.number)
  // Only rows a person changed are stored; the rest default from the name
  // once the show's seasons have loaded.
  const [picks, setPicks] = useState<Record<number, Pick>>({})
  const pickFor = (f: GroupFile): Pick =>
    picks[f.id] ?? { season: f.season ?? seasonOn(seasons, f.airDate) ?? seasons[0]?.number ?? 1, episode: null }
  const [errors, setErrors] = useState<Record<number, string>>({})
  const [busy, setBusy] = useState(false)
  const apply = useApplyMatch()

  // One suggestion for the whole group, when every numbered file carries over.
  const numbered = g.files.filter((f) => f.season != null && f.episodes?.length)
  const suggestion =
    seasons.length > 0 && numbered.length > 0
      ? numbered.map((f) => ({ f, to: carryOver(seasons, f.season!, f.episodes![0]) }))
      : []
  const canSuggest = (each || seasonRows.length === 0) && suggestion.length > 0 && suggestion.every((x) => x.to && (x.to.season !== x.f.season || x.to.episode !== x.f.episodes![0]))
  const code = (p: Pick | null) => (p && p.episode != null ? episodeCode(p.season, [p.episode]) : '')
  const ready = g.files.filter((f) => pickFor(f).episode != null)

  const link = async () => {
    if (!show) return
    setBusy(true)
    const failed: Record<number, string> = {}
    for (const f of ready) {
      const p = pickFor(f)
      const n = f.episodes?.length || 1
      try {
        await apply.mutateAsync({ fileId: f.id, tmdbId: show.tmdbId, season: p.season, episodes: Array.from({ length: n }, (_, i) => p.episode! + i) })
      } catch (e) {
        failed[f.id] = e instanceof Error ? e.message : String(e)
      }
    }
    setErrors(failed)
    setBusy(false)
  }

  return (
    <article className="space-y-4 rounded-xl border border-line bg-surface p-5">
      <div className="flex flex-wrap gap-4">
        {show && (
          <div className="w-16 shrink-0">
            <Poster path={show.posterPath} size="w154" alt={show.title} />
          </div>
        )}
        <div className="min-w-0 flex-1 space-y-2">
          <FolderLine g={g} />
          <h3 className="text-lg font-semibold">
            {show ? (
              <Link to="/shows/$showId" params={{ showId: String(show.id) }} className="hover:underline">
                {show.title}
              </Link>
            ) : (
              g.title
            )}
            {show?.year ? <span className="font-normal text-neutral-400"> ({show.year})</span> : null}
          </h3>
          <p className="text-sm text-neutral-400">{groupFacts(g)}</p>
          <p className="text-sm text-warn">{g.reason}</p>
          <div className="flex flex-wrap gap-2">
            <button type="button" disabled={!tmdb} onClick={onSearch} className="min-h-[36px] rounded-md px-3 text-sm text-neutral-400 hover:bg-surface-2 hover:text-neutral-100 disabled:opacity-50">
              Wrong show? Change it…
            </button>
            <IgnoreButton g={g} libraryId={libraryId} />
            {seasonRows.length > 0 && (
              <button type="button" onClick={() => setEach((v) => !v)} className="min-h-[36px] rounded-md px-3 text-sm text-neutral-400 hover:bg-surface-2 hover:text-neutral-100">
                {each ? 'Line up by season' : 'Pick each episode instead'}
              </button>
            )}
          </div>
        </div>
      </div>

      {!each && seasonRows.length > 0 && <SeasonRows g={g} lineUps={seasonRows} libraryId={libraryId} tmdb={tmdb} onMatched={onSeasonsMatched} />}

      {canSuggest && (
        <div className="flex flex-wrap items-center gap-3 rounded-lg border border-accent/40 bg-accent/5 px-4 py-3">
          <p className="min-w-0 flex-[1_1_320px] text-sm text-neutral-200">
            Read {suggestion.map((x) => episodeCode(x.f.season, [x.f.episodes![0]])).join(', ')} as {suggestion.map((x) => code(x.to)).join(', ')}?
            <span className="block text-neutral-400">The numbers run past the end of their season, so they may continue into the next one.</span>
          </p>
          <button
            type="button"
            onClick={() => setPicks((p) => ({ ...p, ...Object.fromEntries(suggestion.map((x) => [x.f.id, x.to!])) }))}
            className="min-h-[36px] rounded-md border border-line-2 bg-surface-2 px-3 text-sm font-medium hover:bg-ground"
          >
            Apply to all {suggestion.length}
          </button>
        </div>
      )}

      {listed.length > 0 && (
      <ul className="divide-y divide-line rounded-lg border border-line-2">
        {listed.map((f) => (
          <EpisodeRow
            key={f.id}
            f={f}
            showId={show?.id}
            seasons={seasons}
            pick={pickFor(f)}
            error={errors[f.id]}
            onPick={(p) => setPicks((x) => ({ ...x, [f.id]: p }))}
          />
        ))}
      </ul>
      )}

      {listed.length > 0 && (
      <div className="flex flex-wrap items-center justify-end gap-3">
        <span className="text-sm text-neutral-400">Linked files are locked: rescans leave them alone.</span>
        <button
          type="button"
          disabled={!tmdb || !show || busy || ready.length === 0}
          onClick={() => void link()}
          className="min-h-[40px] rounded-md btn-primary px-4 text-sm font-semibold disabled:opacity-50"
        >
          {busy ? 'Linking…' : ready.length ? `Link ${ready.length} file${ready.length === 1 ? '' : 's'}` : 'Pick episodes to link'}
        </button>
      </div>
      )}
    </article>
  )
}

function EpisodeRow({
  f,
  showId,
  seasons,
  pick,
  error,
  onPick,
}: {
  f: GroupFile
  showId?: number
  seasons: Season[]
  pick: Pick
  error?: string
  onPick: (p: Pick) => void
}) {
  const season = useQuery({ ...seasonQuery(showId ?? 0, pick.season), enabled: showId != null && seasons.some((s) => s.number === pick.season) })
  const eps = season.data?.episodes ?? []
  const named = episodeCode(f.season, f.episodes)
  const id = `ep-${f.id}`
  return (
    <li className="flex flex-wrap items-end gap-3 px-3 py-3">
      <div className="min-w-0 flex-[1_1_260px]">
        <div className="break-all font-mono text-xs text-neutral-200">{f.relativePath}</div>
        <div className="text-xs text-neutral-400">{named ? `Named ${named}` : f.airDate ? `Aired ${formatDate(f.airDate)}${f.airDateAlt ? ` or ${formatDate(f.airDateAlt)}` : ''}` : 'No episode number in the name'}</div>
        {error && <div className="text-xs text-red-400">{error}</div>}
      </div>
      <label className="flex flex-col gap-1 text-xs font-semibold text-neutral-400">
        Season
        <select
          value={pick.season}
          onChange={(e) => onPick({ season: Number(e.target.value), episode: null })}
          className="min-h-[40px] rounded-md border border-line-2 bg-ground px-2 text-sm font-normal text-neutral-100"
        >
          {!seasons.some((s) => s.number === pick.season) && <option value={pick.season}>Season {pick.season} (not on TMDB)</option>}
          {seasons.map((s) => (
            <option key={s.number} value={s.number}>
              {s.number === 0 ? 'Specials' : `Season ${s.number}`} · {s.episodeCount} episodes
            </option>
          ))}
        </select>
      </label>
      <label htmlFor={id} className="flex min-w-0 flex-[1_1_240px] flex-col gap-1 text-xs font-semibold text-neutral-400">
        Episode
        <select
          id={id}
          value={pick.episode ?? ''}
          onChange={(e) => onPick({ season: pick.season, episode: e.target.value ? Number(e.target.value) : null })}
          className="min-h-[40px] rounded-md border border-line-2 bg-ground px-2 text-sm font-normal text-neutral-100"
        >
          <option value="">{!seasons.some((s) => s.number === pick.season) ? 'Pick a season first' : season.isFetching ? 'Loading…' : 'Choose…'}</option>
          {eps.map((e) => (
            <option key={e.id} value={e.number}>
              {`E${String(e.number).padStart(2, '0')} · ${e.title}${e.airDate ? ` · ${formatDate(e.airDate)}` : ''}${e.hasFile ? ' · has a file' : ''}`}
            </option>
          ))}
        </select>
      </label>
    </li>
  )
}

function LookupGroup({ g, libraryId, tmdb, retrying, onRetry }: { g: Group; libraryId: number; tmdb: boolean; retrying: boolean; onRetry: () => void }) {
  return (
    <div className="flex flex-wrap items-center gap-5 rounded-xl border border-line bg-surface px-5 py-4">
      <div className="min-w-0 flex-[1_1_420px] space-y-1">
        <div className="font-semibold">
          {g.title || g.folder || 'Unnamed files'} · {g.files.length} file{g.files.length === 1 ? '' : 's'}
        </div>
        <div className="break-all text-sm text-neutral-400">
          {g.folder && <span className="font-mono text-xs">{g.folder} · </span>}
          {g.reason}
        </div>
      </div>
      <button
        type="button"
        disabled={!tmdb || retrying}
        onClick={onRetry}
        className="min-h-[36px] rounded-md border border-line-2 bg-surface-2 px-3 text-sm hover:bg-ground disabled:opacity-50"
      >
        Retry
      </button>
      <IgnoreButton g={g} libraryId={libraryId} />
    </div>
  )
}

/** Search TMDB for a whole group and preview how its files line up before matching. */
function MatchGroupDialog({
  g,
  libraryId,
  type,
  onClose,
  onMatched,
}: {
  g: Group
  libraryId: number
  type: LibraryType
  onClose: () => void
  onMatched: (c: Candidate, r: GroupResult) => void
}) {
  const initialTitle = g.show?.title ?? g.title
  const [query, setQuery] = useState(initialTitle)
  const [year, setYear] = useState(g.year ? String(g.year) : '')
  const [submitted, setSubmitted] = useState({ q: initialTitle, year: g.year ?? undefined })
  const [picked, setPicked] = useState<number | null>(null)
  const [remember, setRemember] = useState(true)
  const ids = g.files.map((f) => f.id)
  const results = useQuery(candidatesQuery(g.files[0].id, submitted.q, submitted.year))
  const items = results.data?.items ?? []
  const chosen = items.find((c) => c.tmdbId === picked) ?? items[0]
  const lu = useQuery({ ...lineUpQuery(libraryId, ids, chosen?.tmdbId ?? 0), enabled: type === 'tv' && chosen != null })
  const match = useMatchFiles()

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const n = g.files.length
  const what = type === 'tv' ? 'Show' : 'Movie'
  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/80 p-4 sm:p-10" onClick={onClose}>
      <div className="w-full max-w-3xl rounded-xl border border-line-2 bg-surface shadow-xl" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true" aria-labelledby="match-group-title">
        <div className="flex items-start justify-between gap-4 px-6 pt-5">
          <div className="min-w-0">
            <h2 id="match-group-title" className="text-xl font-bold">
              Match {n === 1 ? 'this file' : `${n} files`}
            </h2>
            <p className="mt-1 break-all font-mono text-xs text-neutral-400">{[g.folder || g.files[0].relativePath, seasonsLabel(g.seasons)].filter(Boolean).join(' · ')}</p>
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
            {what}
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
          {results.isPending && <p className="text-sm text-neutral-400">Searching…</p>}
          {results.isError && <p className="text-sm text-red-400">{results.error.message}</p>}
          {results.data && items.length === 0 && <p className="text-sm text-neutral-400">No results.</p>}
          {items.map((c) => {
            const on = c.tmdbId === chosen?.tmdbId
            return (
              <button
                key={c.tmdbId}
                type="button"
                role="radio"
                aria-checked={on}
                onClick={() => setPicked(c.tmdbId)}
                className={`flex w-full items-start gap-3 rounded-lg border bg-surface-2 p-3 text-left ${on ? 'border-accent ring-1 ring-accent' : 'border-line-2 hover:border-neutral-600'}`}
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
                  </span>
                  <span className="line-clamp-2 block text-sm text-neutral-400">{c.overview}</span>
                </span>
              </button>
            )
          })}
        </div>

        {type === 'tv' && chosen && (
          <div className="mx-6 mt-4 rounded-lg border border-line bg-ground p-4" aria-live="polite">
            <h3 className="mb-3 text-xs font-semibold uppercase tracking-wider text-neutral-400">
              How your files line up with {chosen.title}
              {chosen.year ? ` (${chosen.year})` : ''}
            </h3>
            {lu.isPending && <p className="text-sm text-neutral-400">Checking…</p>}
            {lu.isError && <p className="text-sm text-red-400">{lu.error.message}</p>}
            {lu.data && (
              <div className="space-y-2">
                {lu.data.seasons.map((s) => {
                  const bad = s.files - s.found
                  return (
                    <div key={s.season} className="grid grid-cols-[96px_minmax(0,1fr)] items-center gap-x-3 gap-y-1 text-sm sm:grid-cols-[110px_minmax(0,1fr)_auto]">
                      <span className="font-medium text-neutral-200">{s.season === 0 ? 'Specials' : `Season ${s.season}`}</span>
                      <span className="flex h-1.5 overflow-hidden rounded bg-line-2" aria-hidden="true">
                        <span className="bg-accent" style={{ width: `${(s.found / s.files) * 100}%` }} />
                        <span className="bg-warn" style={{ width: `${(bad / s.files) * 100}%` }} />
                      </span>
                      <span className={`col-start-2 whitespace-nowrap text-xs sm:col-start-3 ${bad ? 'text-warn' : 'text-neutral-400'}`}>
                        {s.onTmdb === 0 ? `${s.files} files · season not on TMDB` : bad ? `${s.found} of ${s.files} found · ${bad} not on TMDB` : `${s.found} of ${s.files} files found`}
                      </span>
                    </div>
                  )
                })}
                {lu.data.dated > 0 && (
                  <p className={`text-xs ${lu.data.datedFound === lu.data.dated ? 'text-neutral-400' : 'text-warn'}`}>
                    By air date: {lu.data.datedFound} of {lu.data.dated} dated files fall within the show&apos;s run on TMDB.
                  </p>
                )}
                {lu.data.unnumbered > 0 && (
                  <p className="text-xs text-warn">
                    {lu.data.unnumbered === 1 ? '1 file has' : `${lu.data.unnumbered} files have`} no season and episode numbers or air date in {lu.data.unnumbered === 1 ? "its name" : "their names"}.
                  </p>
                )}
                <p className="flex flex-wrap gap-4 pt-1 text-xs text-neutral-400">
                  <span className="inline-flex items-center gap-1.5">
                    <span className="h-2.5 w-2.5 rounded-sm bg-accent" aria-hidden="true" />
                    Episode found
                  </span>
                  <span className="inline-flex items-center gap-1.5">
                    <span className="h-2.5 w-2.5 rounded-sm bg-warn" aria-hidden="true" />
                    Not on TMDB, goes to Pick episodes
                  </span>
                </p>
              </div>
            )}
          </div>
        )}

        {g.folder && (
          <label className="flex items-start gap-3 px-6 pt-4 text-sm text-neutral-200">
            <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} className="mt-0.5 h-[18px] w-[18px] shrink-0 accent-accent" />
            <span>
              Use this {type === 'tv' ? 'show' : 'movie'} for new files in {g.folder}
              <span className="block text-neutral-400">Files added to this folder later match without searching again.</span>
            </span>
          </label>
        )}

        {match.isError && <p className="px-6 pt-3 text-sm text-red-400">{match.error.message}</p>}
        <div className="mt-5 flex flex-wrap justify-end gap-2 border-t border-line px-6 py-4">
          <button type="button" onClick={onClose} className="min-h-[44px] rounded-md px-4 text-sm text-neutral-400 hover:bg-surface-2 hover:text-neutral-100">
            Cancel
          </button>
          <button
            type="button"
            disabled={!chosen || match.isPending}
            onClick={() =>
              chosen && match.mutate({ libraryId, fileIds: ids, tmdbId: chosen.tmdbId, remember: remember && !!g.folder }, { onSuccess: (r) => onMatched(chosen, r) })
            }
            className="min-h-[44px] rounded-md btn-primary px-5 text-sm font-semibold disabled:opacity-50"
          >
            {match.isPending ? 'Matching…' : n === 1 ? 'Match' : `Match ${n} files`}
          </button>
        </div>
      </div>
    </div>
  )
}
