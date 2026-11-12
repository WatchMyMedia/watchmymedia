import { useEffect, useId, useMemo, useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { itemsQuery, librariesQuery, mediaListPreviewQuery, mediaListQuery, mediaListSourcesQuery, useCreateMediaList, useUpdateMediaList } from '../api/queries'
import type { components } from '../api/schema'
import { Poster } from '../components/Poster'
import { filterOptions, sortOptions, type ItemFilter, type ItemSort } from '../lib/browseOptions'
import { formatBytes, languageName } from '../lib/format'
import {
  DEFAULT_ENTRIES, MAX_ENTRIES, MAX_IMPORT_BYTES, chartLabel, chartLine, chartsFor, discoverLanguages, discoverSorts, draftFromList, draftProblem, emptyDraft, entryLimit,
  importFormatLabel, knownKind, sentence, listMediaType, listPatch, mediaTypeLabel, previewFromLine, previewHaveWords, previewKey, previewRestWords, sourceOf, suggestName, tmdbMode,
  type ListKind, type MediaList, type MediaListEntry, type MediaType, type SourceDraft,
} from '../lib/mediaLists'
import { screenLibraryId, screenTarget } from '../lib/screenRows'
import { useSettingsAccess } from './settings/shared'

type Sources = components['schemas']['MediaListSources']
type Library = components['schemas']['Library']
type Preview = components['schemas']['MediaListPreview']

const panel = 'rounded-xl border border-line bg-surface'
const input = 'min-h-[44px] w-full rounded-md border border-line-2 bg-ground px-3 text-sm text-neutral-100 placeholder:text-neutral-500 disabled:opacity-50'
const num = 'min-h-[44px] w-28 rounded-md border border-line-2 bg-ground px-3 text-sm text-neutral-100 placeholder:text-neutral-500 disabled:opacity-50'
const b1 = 'inline-flex min-h-[44px] items-center justify-center rounded-md btn-primary px-5 text-sm font-semibold disabled:opacity-50'
const b2 = 'inline-flex min-h-[44px] items-center justify-center gap-2 rounded-md border border-line-2 bg-surface px-4 text-sm font-medium hover:bg-surface-2'
const hint = 'mt-1.5 text-[13px] leading-snug text-neutral-400'

const tabLines: Record<ListKind, string> = { tmdb: 'Charts and searches', mdblist: 'Lists that follow other charts', library: 'A saved filter', import: 'Paste or a file' }
const tabNames: Record<ListKind, string> = { tmdb: 'TMDB', mdblist: 'MDBList', library: 'Your library', import: 'Import' }

/** Make a list (design/mockups/web/MakeList): its row goes at the top of screen. */
export function MakeListPage({ screen, back }: { screen: string; back?: 'settings' }) {
  return <Loaded>{(sources, libraries) => <Builder screen={screen} back={back} sources={sources} libraries={libraries} />}</Loaded>
}

/** A list's Edit: the same page with its options, name and limit. */
export function EditListPage({ listId }: { listId: number }) {
  const list = useQuery(mediaListQuery(listId))
  if (list.isError) return <p className="text-red-400">{list.error.message}</p>
  if (!list.data) return <p className="text-neutral-400">Loading…</p>
  return <Loaded>{(sources, libraries) => <Builder screen={list.data.screen} list={list.data} sources={sources} libraries={libraries} />}</Loaded>
}

function Loaded({ children }: { children: (sources: Sources, libraries: Library[]) => ReactNode }) {
  const sources = useQuery(mediaListSourcesQuery)
  const libraries = useQuery(librariesQuery)
  if (sources.isError) return <p role="alert" className="text-warn">Couldn’t load the sources: {sources.error.message}</p>
  if (libraries.isError) return <p role="alert" className="text-warn">{libraries.error.message}</p>
  if (!sources.data || !libraries.data) return <p className="text-neutral-400">Loading…</p>
  return children(sources.data, libraries.data.items)
}

/** A new list's options: from the screen's library, on TMDB's Popular when the server offers it. */
function freshDraft(kind: ListKind, screenType: 'movie' | 'tv' | undefined, libraryId: number, charts: string[] | undefined): SourceDraft {
  const mediaType: MediaType = kind === 'tmdb' ? (screenType === 'tv' ? 'tv' : 'movie') : screenType ?? 'all'
  const offered = chartsFor(charts, mediaType)
  return emptyDraft(kind, { mediaType, libraryId, chart: offered.includes('popular') ? 'popular' : offered[0] ?? '' })
}

function Builder({ screen, back, list, sources, libraries: libs }: { screen: string; back?: 'settings'; list?: MediaList; sources: Sources; libraries: Library[] }) {
  const { admin } = useSettingsAccess()
  const navigate = useNavigate()
  const editing = !!list
  const known = sources.sources.filter((s) => knownKind(s.kind))
  const screenLib = libs.find((l) => l.id === screenLibraryId(screen))
  const screenName = screenLib?.name ?? 'Home'
  const libraryId = (screenLib ?? libs[0])?.id ?? 0
  const tmdbCharts = known.find((s) => s.kind === 'tmdb')?.charts
  const [d, setDraft] = useState<SourceDraft>(() =>
    list ? draftFromList(list) : freshDraft((known.find((s) => s.ready)?.kind ?? 'tmdb') as ListKind, screenLib?.type, libraryId, tmdbCharts),
  )
  const [name, setName] = useState(list?.name ?? '')
  const [named, setNamed] = useState(editing)
  const [limit, setLimit] = useState(String(list?.limit ?? DEFAULT_ENTRIES))
  const [shared, setShared] = useState(list ? list.owner === 'everyone' : back === 'settings')
  const create = useCreateMediaList()
  const update = useUpdateMediaList()

  const edit = (patch: Partial<SourceDraft>) => setDraft({ ...d, ...patch })
  const source = known.find((s) => s.kind === d.kind)
  const blocked = !!source && !source.ready && !editing
  const mediaType = listMediaType(d, libs)
  const genres = (mediaType === 'tv' ? sources.tvGenres : sources.movieGenres) ?? []
  const lockedImport = editing && d.kind === 'import'
  const problem = lockedImport || blocked ? undefined : draftProblem(d)
  // An import keeps what it holds, up to the most a list may.
  const entries = d.kind === 'import' ? MAX_ENTRIES : entryLimit(limit)
  const suggested = suggestName(d, { genres, libraries: libs, languageName })
  const finalName = (named ? name : suggested).trim()

  const body = useMemo(() => ({ mediaType, source: sourceOf(d), limit: (d.kind === 'import' ? MAX_ENTRIES : entryLimit(limit)) ?? DEFAULT_ENTRIES }), [mediaType, d, limit])
  const key = previewKey(body)
  // The preview asks once the options have rested for half a second.
  const [asked, setAsked] = useState<{ key: string; body: typeof body } | null>(null)
  const askedKey = asked?.key
  const quiet = !!problem || lockedImport || blocked
  useEffect(() => {
    if (quiet || askedKey === key) return
    const t = setTimeout(() => setAsked({ key, body }), 500)
    return () => clearTimeout(t)
  }, [key, body, quiet, askedKey])
  const preview = useQuery({ ...mediaListPreviewQuery(asked?.key ?? '', asked?.body ?? body), enabled: !!asked && !quiet })

  const saving = create.isPending || update.isPending
  const error = create.error ?? update.error
  const canSave = (editing || !!source?.ready) && !problem && !!entries && !!finalName && !saving

  // Back where Make a list was opened: Edit rows with Add a row open, Settings › Lists, or the list.
  const leave = (made: boolean) => {
    if (list) return void navigate({ to: '/lists/$listId', params: { listId: String(list.id) } })
    if (back === 'settings') return void navigate({ to: '/settings/lists' })
    const t = screenTarget(screen, libs)
    const edit = { edit: true as const, add: made ? undefined : (true as const) }
    if (t.to === '/') void navigate({ to: '/', search: edit })
    else void navigate({ to: t.to, search: { library: t.library, ...edit } })
  }
  const save = () => {
    if (!canSave || !entries) return
    const details = { name: finalName, limit: entries, shared: admin && shared }
    if (list) {
      const patch = listPatch(list, d, details, admin)
      if (!patch) return leave(true)
      update.mutate({ id: list.id, body: patch }, { onSuccess: () => leave(true) })
      return
    }
    create.mutate({ name: details.name, mediaType, source: sourceOf(d), screen, limit: entries, shared: details.shared || undefined }, { onSuccess: () => leave(true) })
  }
  const backLabel = list ? list.name : back === 'settings' ? 'Lists' : 'Add a row'
  const p = preview.data
  const typeChoices: MediaType[] = ['movie', 'tv', 'all']
  const typeAllowed = (t: MediaType) => (d.kind === 'tmdb' ? t !== 'all' : d.kind === 'library' ? t === mediaType : true)

  return (
    <div className="mx-auto max-w-[84rem] pb-16">
      <button type="button" onClick={() => leave(false)} className="-ml-1 inline-flex min-h-[44px] items-center gap-1 pr-2 text-sm text-neutral-300 hover:text-neutral-100">
        <Chevron />
        {backLabel}
      </button>
      <h1 className="mt-1 text-[28px] font-bold">{list ? `Edit ${list.name}` : 'Make a list'}</h1>
      <p className="mt-1.5 text-[15px] text-neutral-400">{list ? 'Change where it comes from, its name and how many titles it keeps.' : `Its row goes at the top of ${screenName}. Move it from there.`}</p>

      <div className="mt-6 flex flex-wrap items-start gap-6">
        <form className="flex min-w-0 flex-[999_1_36rem] flex-col gap-5" onSubmit={(e) => { e.preventDefault(); save() }}>
          <section aria-labelledby="src-head" className={panel}>
            <div className="px-5 pb-0 pt-4">
              <h2 id="src-head" className="text-base font-semibold">Where it comes from</h2>
              <p className="mt-1 text-sm leading-normal text-neutral-400">
                The server fetches TMDB and MDBList lists in the background and keeps them up to date. Your library and imports never reach outside.
              </p>
            </div>
            <div className="flex flex-col gap-5 px-5 pb-5 pt-4">
              {!editing && (
                <div role="radiogroup" aria-labelledby="src-head" className="grid grid-cols-2 gap-1 rounded-[10px] border border-line-2 bg-ground p-[3px] sm:grid-cols-4">
                  {known.map((s) => {
                    const kind = s.kind as ListKind
                    const on = d.kind === kind
                    return (
                      <button
                        key={kind}
                        type="button"
                        role="radio"
                        aria-checked={on}
                        onClick={() => !on && setDraft(freshDraft(kind, screenLib?.type, libraryId, tmdbCharts))}
                        className={`flex min-h-[56px] flex-col items-start gap-0.5 rounded-[7px] px-3 py-2 text-left text-sm font-semibold ${on ? 'bg-surface-2 text-neutral-100 ring-1 ring-inset ring-neutral-700' : 'text-neutral-400 hover:text-neutral-100'}`}
                      >
                        {tabNames[kind]}
                        <span className="text-xs font-normal text-neutral-400">{s.ready ? tabLines[kind] : 'Needs a key'}</span>
                      </button>
                    )
                  })}
                </div>
              )}
              {editing && (
                <p className="text-sm text-neutral-400">
                  From <span className="text-neutral-200">{source?.name ?? tabNames[d.kind]}</span>. To change the source, make a new list.
                </p>
              )}
              {blocked ? (
                <NotReady source={source} admin={admin} />
              ) : (
                <>
                  {d.kind === 'tmdb' && <TMDBOptions d={d} edit={edit} charts={tmdbCharts} genres={genres} />}
                  {d.kind === 'mdblist' && <MDBListOptions d={d} edit={edit} />}
                  {d.kind === 'library' && <LibraryOptions d={d} edit={edit} libraries={libs} />}
                  {d.kind === 'import' &&
                    (lockedImport ? (
                      <p className="text-sm text-neutral-400">An import is read once and never fetched again, so its titles can’t be changed. To import a new version, make a new list.</p>
                    ) : (
                      <ImportOptions d={d} edit={edit} preview={p && asked?.key === key ? p : undefined} />
                    ))}
                </>
              )}
            </div>
          </section>

          <section aria-labelledby="about-head" className={panel}>
            <h2 id="about-head" className="px-5 pt-4 text-base font-semibold">The list</h2>
            <div className="flex flex-col gap-5 px-5 pb-5 pt-4">
              <Field label="Name" className="max-w-md">
                {(id) => <input id={id} value={named ? name : suggested} maxLength={80} onChange={(e) => { setName(e.target.value); setNamed(true) }} className={input} />}
              </Field>
              <div className="flex flex-wrap gap-x-6 gap-y-4">
                <div>
                  <span id="type-lab" className="mb-2 block text-sm font-medium text-neutral-200">Shows</span>
                  <div role="radiogroup" aria-labelledby="type-lab" className="inline-flex rounded-lg border border-line-2 bg-ground p-0.5">
                    {typeChoices.map((t) => (
                      <button
                        key={t}
                        type="button"
                        role="radio"
                        aria-checked={mediaType === t}
                        disabled={editing || !typeAllowed(t)}
                        onClick={() => {
                          if (d.kind !== 'tmdb') return edit({ mediaType: t })
                          // Genre IDs differ between movies and TV, and some charts are only for one.
                          const offered = chartsFor(tmdbCharts, t)
                          edit({ mediaType: t, genres: [], chart: offered.includes(d.chart) ? d.chart : offered.includes('popular') ? 'popular' : offered[0] ?? '' })
                        }}
                        className={`min-h-[40px] rounded-md px-3.5 text-sm disabled:cursor-default disabled:text-neutral-600 ${mediaType === t ? 'bg-surface-2 text-neutral-100 ring-1 ring-inset ring-neutral-700 disabled:text-neutral-300' : 'text-neutral-400 hover:text-neutral-100'}`}
                      >
                        {t === 'all' ? 'Both' : mediaTypeLabel(t)}
                      </button>
                    ))}
                  </div>
                  <p className={hint}>
                    {editing
                      ? 'A list keeps its kind of titles. Make a new list for the other.'
                      : d.kind === 'tmdb'
                        ? 'TMDB lists are movies or TV shows, not both.'
                        : d.kind === 'library'
                          ? 'Follows the library you chose.'
                          : 'Both keeps movies and shows on one row.'}
                  </p>
                </div>
                <div>
                  <label htmlFor="list-limit" className="mb-2 block text-sm font-medium text-neutral-200">
                    Keep up to
                  </label>
                  <span className="flex items-center gap-2 text-sm text-neutral-400">
                    <input
                      id="list-limit"
                      type="number"
                      inputMode="numeric"
                      min={1}
                      max={MAX_ENTRIES}
                      disabled={d.kind === 'import'}
                      value={d.kind === 'import' ? String(p?.total ?? MAX_ENTRIES) : limit}
                      onChange={(e) => setLimit(e.target.value)}
                      aria-invalid={!entries}
                      aria-describedby="list-limit-hint"
                      className={num}
                    />
                    titles
                  </span>
                  <span id="list-limit-hint" className={`block ${hint} ${!entries ? 'text-warn' : ''}`}>
                    {d.kind === 'import' ? 'An import keeps every title it reads, up to 250.' : `${MAX_ENTRIES} at most.`}
                  </span>
                </div>
              </div>
              {admin ? (
                <fieldset>
                  <legend className="mb-2 block text-sm font-medium text-neutral-200">Who sees it</legend>
                  <div className="grid gap-2 sm:grid-cols-2">
                    <RadioCard name="who" checked={!shared} onChange={() => setShared(false)} title="For me" line="On your screens only" />
                    <RadioCard name="who" checked={shared} onChange={() => setShared(true)} title="For everyone" line="On everyone’s Home until they hide it; members can add it too" />
                  </div>
                </fieldset>
              ) : (
                <p className="text-sm text-neutral-400">
                  Only you see your lists.{!editing && ` You’ve made ${sources.lists} of ${sources.maxLists}.`}
                </p>
              )}
            </div>
          </section>

          {error && (
            <p role="alert" className="text-sm text-warn">
              {error.message}
            </p>
          )}
          <div className="flex flex-wrap items-center gap-3">
            <button type="submit" disabled={!canSave} className={b1}>
              {saving ? 'Saving…' : editing ? 'Save changes' : 'Make list'}
            </button>
            <button type="button" onClick={() => leave(false)} className={b2}>
              Cancel
            </button>
          </div>
        </form>

        <aside aria-labelledby="pv-head" className="min-w-0 max-w-[34rem] flex-[1_1_26rem] lg:sticky lg:top-20">
          <section className={`${panel} px-5 pb-5 pt-4`}>
            <div className="flex items-baseline justify-between gap-3">
              <h2 id="pv-head" className="text-base font-semibold">
                Preview
              </h2>
              <span className="text-xs text-neutral-400">{preview.isFetching || (asked?.key !== key && !quiet) ? 'Looking…' : 'Updates as you change the options'}</span>
            </div>
            <PreviewBody
              d={d}
              preview={p}
              stale={!!asked && asked.key !== key}
              error={preview.isError && !p ? preview.error?.message : undefined}
              empty={blocked ? `Nothing to preview until ${source?.name ?? 'it'} is connected.` : lockedImport ? 'An import’s titles are on its page.' : problem}
              fromLine={previewFromLine(d, { genres, libraries: libs, format: p?.format, notFound: p?.notFound, languageName })}
            />
          </section>
        </aside>
      </div>
    </div>
  )
}

function NotReady({ source, admin }: { source?: components['schemas']['MediaListSourceKind']; admin: boolean }) {
  const mdblist = source?.kind === 'mdblist'
  return (
    <div role="status" className="flex items-start gap-3 rounded-[10px] border border-line-2 bg-ground px-4 py-3.5 text-sm leading-normal text-neutral-300">
      <LockIcon />
      <span className="flex-1">
        <span className="block font-semibold text-neutral-100">{source?.name ?? 'This source'} isn’t connected</span>
        {sentence(source?.reason) ||
          (mdblist ? (admin ? 'Add your MDBList key in Settings › Lists to make lists from it.' : 'An admin can add an MDBList key in Settings › Lists.') : 'It can’t be used now.')}
        {admin && (
          <>
            {' '}
            <Link to="/settings/lists" className="text-neutral-100 underline">
              Open Settings › Lists
            </Link>
          </>
        )}
      </span>
    </div>
  )
}

function Field({ label, hint: help, className = '', children }: { label: string; hint?: string; className?: string; children: (id: string) => ReactNode }) {
  const id = useId()
  return (
    <div className={`min-w-0 ${className}`}>
      <label htmlFor={id} className="mb-2 block text-sm font-medium text-neutral-200">
        {label}
      </label>
      {children(id)}
      {help && <p className={hint}>{help}</p>}
    </div>
  )
}

function RadioCard({ name, checked, onChange, title, line }: { name: string; checked: boolean; onChange: () => void; title: string; line?: string }) {
  return (
    <label className={`flex min-h-[44px] cursor-pointer items-start gap-2.5 rounded-lg border px-3 py-2.5 ${checked ? 'border-accent bg-accent/5' : 'border-line-2'}`}>
      <input type="radio" name={name} checked={checked} onChange={onChange} className="mt-px h-[18px] w-[18px] flex-none accent-accent" />
      <span className="flex flex-col gap-0.5 text-sm font-medium">
        {title}
        {line && <span className="text-xs font-normal text-neutral-400">{line}</span>}
      </span>
    </label>
  )
}

function Chip({ on, onClick, disabled, role, children }: { on: boolean; onClick: () => void; disabled?: boolean; role?: 'radio'; children: ReactNode }) {
  return (
    <button
      type="button"
      role={role}
      aria-checked={role ? on : undefined}
      aria-pressed={role ? undefined : on}
      disabled={disabled}
      onClick={onClick}
      className={`inline-flex min-h-[36px] items-center rounded-full border px-3 text-sm disabled:opacity-45 ${on ? 'border-accent bg-accent/10 text-neutral-100' : 'border-line-2 text-neutral-400 hover:text-neutral-100'}`}
    >
      {children}
    </button>
  )
}

function Segmented<T extends string>({ label, options, value, onChange }: { label: string; options: { value: T; label: string }[]; value: T; onChange: (v: T) => void }) {
  const id = useId()
  return (
    <div>
      <span id={id} className="mb-2 block text-sm font-medium text-neutral-200">
        {label}
      </span>
      <div role="radiogroup" aria-labelledby={id} className="inline-flex flex-wrap rounded-lg border border-line-2 bg-ground p-0.5">
        {options.map((o) => (
          <button
            key={o.value}
            type="button"
            role="radio"
            aria-checked={value === o.value}
            onClick={() => onChange(o.value)}
            className={`min-h-[40px] rounded-md px-3.5 text-sm ${value === o.value ? 'bg-surface-2 text-neutral-100 ring-1 ring-inset ring-neutral-700' : 'text-neutral-400 hover:text-neutral-100'}`}
          >
            {o.label}
          </button>
        ))}
      </div>
    </div>
  )
}

function TMDBOptions({ d, edit, charts, genres }: { d: SourceDraft; edit: (p: Partial<SourceDraft>) => void; charts?: string[]; genres: { id: number; name: string }[] }) {
  const offered = chartsFor(charts, d.mediaType)
  const mode = tmdbMode(d.chart)
  const plainCharts = offered.filter((c) => tmdbMode(c) === 'chart')
  return (
    <>
      <div role="radiogroup" aria-label="What on TMDB" className="flex flex-wrap gap-2">
        <Chip role="radio" on={mode === 'chart'} onClick={() => mode !== 'chart' && edit({ chart: plainCharts.includes('popular') ? 'popular' : plainCharts[0] ?? '' })}>
          A chart
        </Chip>
        {offered.includes('discover') && (
          <Chip role="radio" on={mode === 'discover'} onClick={() => edit({ chart: 'discover' })}>
            Discover: your own search
          </Chip>
        )}
        {(offered.includes('list') || offered.includes('collection')) && (
          <Chip role="radio" on={mode === 'ref'} onClick={() => mode !== 'ref' && edit({ chart: offered.includes('list') ? 'list' : 'collection' })}>
            A TMDB list or collection
          </Chip>
        )}
      </div>
      {mode === 'chart' && (
        <fieldset>
          <legend className="mb-2 block text-sm font-medium text-neutral-200">Chart</legend>
          <div className="grid gap-2 sm:grid-cols-2">
            {plainCharts.map((c) => (
              <RadioCard key={c} name="chart" checked={d.chart === c} onChange={() => edit({ chart: c })} title={chartLabel(c)} line={chartLine(c)} />
            ))}
          </div>
          <p className={hint}>{d.mediaType === 'tv' ? 'Movies also have Now playing and Upcoming.' : 'TV shows have Trending, Popular, Top rated, On the air and Airing today.'}</p>
        </fieldset>
      )}
      {mode === 'ref' && (
        <div className="flex flex-col gap-4">
          {offered.includes('list') && offered.includes('collection') && (
            <Segmented label="Which" options={[{ value: 'list', label: 'A TMDB list' }, { value: 'collection', label: 'A collection' }]} value={d.chart} onChange={(chart) => edit({ chart })} />
          )}
          <Field label={d.chart === 'collection' ? 'The collection’s link or ID' : 'The list’s link or ID'} hint={d.chart === 'collection' ? 'Such as themoviedb.org/collection/10' : 'Such as themoviedb.org/list/8136'}>
            {(id) => <input id={id} value={d.ref} onChange={(e) => edit({ ref: e.target.value })} maxLength={200} autoComplete="off" className={`${input} font-mono text-[13px]`} />}
          </Field>
        </div>
      )}
      {mode === 'discover' && (
        <div className="flex flex-col gap-5">
          {genres.length > 0 && (
            <fieldset>
              <legend className="mb-2 block text-sm font-medium text-neutral-200">Genres</legend>
              <div className="flex flex-wrap gap-2">
                {genres.map((g) => {
                  const on = d.genres.includes(g.id)
                  return (
                    <Chip key={g.id} on={on} disabled={!on && d.genres.length >= 10} onClick={() => edit({ genres: on ? d.genres.filter((x) => x !== g.id) : [...d.genres, g.id] })}>
                      {g.name}
                    </Chip>
                  )
                })}
              </div>
              <p className={hint}>A title needs every genre you pick.</p>
            </fieldset>
          )}
          <div className="flex flex-wrap gap-x-6 gap-y-4">
            <div>
              <span className="mb-2 block text-sm font-medium text-neutral-200">Released</span>
              <span className="flex items-center gap-2 text-sm text-neutral-400">
                <label>
                  <span className="sr-only">From year</span>
                  <input inputMode="numeric" placeholder="1980" value={d.yearFrom} onChange={(e) => edit({ yearFrom: e.target.value })} className={num} />
                </label>
                to
                <label>
                  <span className="sr-only">To year</span>
                  <input inputMode="numeric" placeholder="1989" value={d.yearTo} onChange={(e) => edit({ yearTo: e.target.value })} className={num} />
                </label>
              </span>
            </div>
            <Field label="Rated at least">
              {(id) => <input id={id} inputMode="decimal" placeholder="7.0" value={d.minRating} onChange={(e) => edit({ minRating: e.target.value })} className={num} />}
            </Field>
            <Field label="With at least">
              {(id) => (
                <span className="flex items-center gap-2 text-sm text-neutral-400">
                  <input id={id} inputMode="numeric" placeholder="200" value={d.minVotes} onChange={(e) => edit({ minVotes: e.target.value })} className={num} />
                  votes
                </span>
              )}
            </Field>
          </div>
          <div className="flex flex-wrap gap-x-6 gap-y-4">
            <Field label="Original language" className="max-w-[18rem] flex-[1_1_14rem]">
              {(id) => (
                <select id={id} value={d.language} onChange={(e) => edit({ language: e.target.value })} className={input}>
                  <option value="">Any language</option>
                  {discoverLanguages.map((l) => (
                    <option key={l} value={l}>
                      {languageName(l)}
                    </option>
                  ))}
                </select>
              )}
            </Field>
            <Segmented
              label="Sort by"
              options={discoverSorts.map((s) => ({ value: s.value as string, label: s.value === 'popularity' ? 'Popularity' : s.value === 'rating' ? 'Rating' : s.value === 'votes' ? 'Most votes' : 'Newest' }))}
              value={d.sortBy}
              onChange={(sortBy) => edit({ sortBy })}
            />
          </div>
          <p className="text-[13px] text-neutral-400">Never shows adult titles.</p>
        </div>
      )}
    </>
  )
}

function MDBListOptions({ d, edit }: { d: SourceDraft; edit: (p: Partial<SourceDraft>) => void }) {
  return (
    <div className="flex flex-col gap-4">
      <Field label="The list’s link or ID" hint="Its address on mdblist.com, user/list-name, or its ID.">
        {(id) => <input id={id} value={d.ref} onChange={(e) => edit({ ref: e.target.value })} maxLength={200} autoComplete="off" placeholder="https://mdblist.com/lists/…" className={`${input} font-mono text-[13px]`} />}
      </Field>
      <p className="text-[13px] leading-snug text-neutral-400">The row will say From MDBList. The server fetches the list once a day with the household’s MDBList key.</p>
    </div>
  )
}

function LibraryOptions({ d, edit, libraries }: { d: SourceDraft; edit: (p: Partial<SourceDraft>) => void; libraries: Library[] }) {
  const lib = libraries.find((l) => l.id === d.libraryId)
  const type = lib?.type === 'tv' ? 'tv' : 'movie'
  const facets = useQuery({ ...itemsQuery(d.libraryId, { sort: 'title', filter: '', limit: 1, offset: 0 }), enabled: !!d.libraryId }).data?.facets
  return (
    <div className="flex flex-col gap-5">
      {libraries.length > 0 && (
        <Segmented
          label="Library"
          options={libraries.map((l) => ({ value: String(l.id), label: l.name }))}
          value={String(d.libraryId)}
          onChange={(v) => edit({ libraryId: Number(v), genre: '', decade: 0, filter: '', sort: 'added' })}
        />
      )}
      <fieldset>
        <legend className="mb-2 block text-sm font-medium text-neutral-200">Show</legend>
        <div className="flex flex-wrap gap-2">
          {filterOptions(type).map((f) => (
            <Chip key={f.value} on={d.filter === f.value} onClick={() => edit({ filter: f.value as ItemFilter })}>
              {f.label}
            </Chip>
          ))}
        </div>
      </fieldset>
      <div className="flex flex-wrap gap-x-6 gap-y-4">
        {!!facets?.genres.length && (
          <Field label="Genre" className="max-w-[14rem] flex-[1_1_10rem]">
            {(id) => (
              <select id={id} value={d.genre} onChange={(e) => edit({ genre: e.target.value })} className={input}>
                <option value="">Any</option>
                {facets.genres.map((g) => (
                  <option key={g} value={g}>
                    {g}
                  </option>
                ))}
              </select>
            )}
          </Field>
        )}
        {type === 'movie' && !!facets?.decades.length && (
          <Field label="Decade" className="max-w-[14rem] flex-[1_1_10rem]">
            {(id) => (
              <select id={id} value={String(d.decade)} onChange={(e) => edit({ decade: Number(e.target.value) })} className={input}>
                <option value="0">Any</option>
                {facets.decades.map((dec) => (
                  <option key={dec} value={dec}>
                    {dec}s
                  </option>
                ))}
              </select>
            )}
          </Field>
        )}
        <Field label="Sort" className="max-w-[14rem] flex-[1_1_10rem]">
          {(id) => (
            <select id={id} value={d.sort} onChange={(e) => edit({ sort: e.target.value as ItemSort })} className={input}>
              {sortOptions(type).map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          )}
        </Field>
      </div>
      <p className="text-[13px] leading-snug text-neutral-400">The row follows the library: a film you add or watch tonight moves in or out of it without a refresh.</p>
    </div>
  )
}

function ImportOptions({ d, edit, preview }: { d: SourceDraft; edit: (p: Partial<SourceDraft>) => void; preview?: Preview }) {
  const [fileError, setFileError] = useState('')
  const textId = useId()
  const format = importFormatLabel(preview?.format)
  return (
    <div className="flex flex-col gap-3">
      <div>
        <label htmlFor={textId} className="mb-2 block text-sm font-medium text-neutral-200">
          Paste a list
        </label>
        <textarea
          id={textId}
          rows={7}
          value={d.text}
          onChange={(e) => edit({ text: e.target.value, fileName: '' })}
          placeholder={'Blade Runner (1982)\ntt0083658\nhttps://www.themoviedb.org/movie/78'}
          className="min-h-[156px] w-full resize-y rounded-md border border-line-2 bg-ground px-3 py-2.5 font-mono text-[13px] leading-normal text-neutral-100 placeholder:text-neutral-500"
        />
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <label className={`${b2} relative cursor-pointer overflow-hidden focus-within:ring-2 focus-within:ring-accent`}>
          Choose a file…
          <input
            type="file"
            accept=".csv,.txt,text/csv,text/plain"
            className="absolute inset-0 cursor-pointer opacity-0"
            onChange={(e) => {
              const file = e.target.files?.[0]
              e.target.value = ''
              if (!file) return
              if (file.size > MAX_IMPORT_BYTES) return setFileError(`${file.name} is ${formatBytes(file.size)}. Files can be up to 1 MB.`)
              setFileError('')
              void file.text().then((text) => edit({ text, fileName: file.name }), () => setFileError(`Couldn’t read ${file.name}.`))
            }}
          />
        </label>
        <span className="text-[13px] text-neutral-400">{d.fileName ? `${d.fileName} · ${formatBytes(new Blob([d.text]).size)}` : 'CSV or text, up to 1 MB and 250 titles'}</span>
      </div>
      {fileError && (
        <p role="alert" className="text-sm text-warn">
          {fileError}
        </p>
      )}
      <p className="text-[13px] leading-snug text-neutral-400">An IMDb list export or a Letterboxd export (CSV), or one title a line: IMDb IDs (tt…), TMDB links or Title (Year).</p>
      {preview && format && (
        <p role="status" className="flex items-center gap-2 text-sm text-emerald-300">
          <CheckIcon />
          Read as {format} · {preview.total.toLocaleString()} {preview.total === 1 ? 'title' : 'titles'}
          {!!preview.notFound && ` · ${preview.notFound.toLocaleString()} not found on TMDB`}
        </p>
      )}
      <p className="text-[13px] leading-snug text-neutral-400">An import is read once and never fetched again. Titles TMDB can’t find stay on the list as Not found.</p>
    </div>
  )
}

/** "23 of 50 in your library", a bar, what the list is, and its first 20 titles, the ones the library has bright. */
function PreviewBody({ d, preview: p, stale, error, empty, fromLine }: { d: SourceDraft; preview?: Preview; stale: boolean; error?: string; empty?: string; fromLine: string }) {
  if (empty || error || !p)
    return (
      <p className={`mt-4 rounded-[10px] border border-dashed border-line-2 px-4 py-8 text-center text-sm leading-normal ${error ? 'text-warn' : 'text-neutral-400'}`}>
        {empty ?? error ?? 'Looking for the list…'}
      </p>
    )
  const first = p.items.slice(0, 20)
  const pct = p.total ? Math.round((p.inLibrary / p.total) * 100) : 0
  return (
    <div className={stale ? 'opacity-60' : ''}>
      <div className="mt-3">
        <p aria-live="polite" className="text-xl font-bold">
          {previewHaveWords(d.kind, p.inLibrary, p.total)}
        </p>
        <div aria-hidden="true" className="mt-2 h-1.5 overflow-hidden rounded-full bg-line-2">
          <div className="h-full bg-accent" style={{ width: `${pct}%` }} />
        </div>
        <p className="mt-2 text-[13px] text-neutral-400">{fromLine}</p>
      </div>
      {p.total === 0 ? (
        <p className="mt-4 text-sm text-neutral-400">The list is empty. Try other options.</p>
      ) : (
        <ol aria-label="The first 20 titles" className="mt-4 grid grid-cols-4 gap-x-2.5 gap-y-3 sm:grid-cols-5">
          {first.map((e) => (
            <li key={e.rank} className="min-w-0">
              <PreviewEntry e={e} />
            </li>
          ))}
        </ol>
      )}
      {previewRestWords(first.length, p.total, d.kind) && <p className="mt-4 text-[13px] text-neutral-400">{previewRestWords(first.length, p.total, d.kind)}</p>}
    </div>
  )
}

function PreviewEntry({ e }: { e: MediaListEntry }) {
  const rank = <span className="absolute left-1 top-1 z-10 min-w-[18px] rounded bg-black/75 px-1 text-center text-[10px] font-bold">{e.rank}</span>
  if (!e.found)
    return (
      <>
        <span className="relative flex aspect-[2/3] items-center justify-center rounded-[5px] border border-dashed border-neutral-600 bg-ground p-1.5 text-center text-[11px] leading-tight text-neutral-400">
          {rank}
          {e.title}
        </span>
        <span className="mt-1 block truncate text-xs font-medium">{e.title}</span>
        <span className="block truncate text-[11px] text-orange-300">Not found</span>
      </>
    )
  return (
    <>
      <span className={`relative block ${e.item ? '' : 'opacity-40 grayscale-[.6]'}`}>
        {rank}
        <Poster path={e.item?.posterPath ?? e.posterPath} alt="" size="w154" className="rounded-[5px] ring-1 ring-line-2" />
      </span>
      <span className="mt-1 block truncate text-xs font-medium">{e.title}</span>
      <span className="block truncate text-[11px] text-neutral-500">{e.item ? e.year || '' : 'Not in library'}</span>
    </>
  )
}

function Chevron() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="m15 18-6-6 6-6" />
    </svg>
  )
}

function LockIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="mt-0.5 shrink-0 text-neutral-400">
      <rect x="4" y="11" width="16" height="10" rx="2" />
      <path d="M8 11V7a4 4 0 0 1 8 0v4" />
    </svg>
  )
}

function CheckIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="m5 12 4 4 10-10" />
    </svg>
  )
}
