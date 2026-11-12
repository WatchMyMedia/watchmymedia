import { useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { ApiError } from '../api/client'
import { librariesQuery, mediaListEntriesQuery, mediaListQuery, useDeleteMediaList, useRefreshMediaList } from '../api/queries'
import { requestOptionsQuery } from '../api/requests'
import { Dialog } from '../components/Dialog'
import { Pager } from '../components/Pager'
import { Poster } from '../components/Poster'
import { PosterCard, RankBadge } from '../components/PosterCard'
import { RequestDialog } from '../components/RequestDialog'
import { relativeTime } from '../lib/format'
import { canEditList, canRefresh, entryView, inLibraryWords, listCountWords, listMeta, retryWords, screenName, type MediaList, type MediaListEntry } from '../lib/mediaLists'
import { screenTarget } from '../lib/screenRows'
import { useSettingsAccess } from './settings/shared'

const PAGE = 100
const b1 = 'inline-flex min-h-[40px] items-center justify-center rounded-md btn-primary px-4 text-sm font-semibold disabled:opacity-50'
const b2 = 'inline-flex min-h-[40px] items-center justify-center gap-2 rounded-md border border-line-2 bg-surface px-4 text-sm font-medium hover:bg-surface-2 disabled:opacity-50'

/**
 * A list's page, a list row's See all (PLAN-ROWS.md §5,
 * design/mockups/web/ListPage): every title in the list's order. Those in
 * the library are the usual cards; missing ones are dimmed with TMDB's
 * poster and Request where the person may ask for them; imports TMDB
 * couldn't find are Not found.
 */
export function ListPage({ listId }: { listId: number }) {
  const list = useQuery(mediaListQuery(listId))
  const [offset, setOffset] = useState(0)
  const entries = useQuery({ ...mediaListEntriesQuery(listId, offset, PAGE), enabled: list.isSuccess })
  const [requesting, setRequesting] = useState<MediaListEntry | null>(null)

  if (list.isPending) return <p className="text-neutral-400">Loading…</p>
  if (list.isError) {
    const gone = list.error instanceof ApiError && list.error.status === 404
    return (
      <div className="space-y-3">
        <p className={gone ? 'text-neutral-300' : 'text-red-400'}>{gone ? 'This list was deleted, or isn’t yours to see.' : list.error.message}</p>
        <Link to="/" className="text-sm underline">
          Back to Home
        </Link>
      </div>
    )
  }
  const l = list.data
  const e = entries.data
  return (
    <div className="flex flex-col gap-6 pb-16">
      <ListHeader list={l} inLibrary={e?.inLibrary ?? l.inLibrary} total={e?.total ?? l.entries} />
      {l.error && <Failed list={l} />}
      <CountLine list={l} total={e?.total ?? l.entries} inLibrary={e?.inLibrary ?? l.inLibrary} />
      {entries.isPending && <p className="text-neutral-400">Loading titles…</p>}
      {entries.isError && <p className="text-red-400">{entries.error.message}</p>}
      {e && e.items.length === 0 && (
        <p className="text-neutral-400">{l.fetchedAt || l.source.kind === 'import' || l.source.kind === 'library' ? 'Nothing is on this list.' : 'The list hasn’t been fetched yet.'}</p>
      )}
      {e && e.items.length > 0 && (
        <ol aria-label={`${l.name}, in order`} className={`grid grid-cols-3 gap-x-4 gap-y-5 md:grid-cols-5 xl:grid-cols-8 ${entries.isPlaceholderData ? 'opacity-60' : ''}`}>
          {e.items.map((entry) => (
            <li key={entry.rank} className="flex min-w-0 flex-col">
              <EntryCard entry={entry} onRequest={() => setRequesting(entry)} />
            </li>
          ))}
        </ol>
      )}
      {e && <Pager offset={offset} limit={PAGE} total={e.total} onChange={setOffset} />}
      {requesting?.tmdbId && (requesting.mediaType === 'movie' || requesting.mediaType === 'tv') && (
        <RequestDialog mediaType={requesting.mediaType} tmdbId={requesting.tmdbId} onClose={() => setRequesting(null)} />
      )}
    </div>
  )
}

function ListHeader({ list, inLibrary, total }: { list: MediaList; inLibrary: number; total: number }) {
  const refresh = useRefreshMediaList()
  const libraries = useQuery(librariesQuery)
  const [deleting, setDeleting] = useState(false)
  const libs = libraries.data?.items ?? []
  const meta = listMeta(list)
  const target = screenTarget(list.screen, libs)
  const refreshError = refresh.error instanceof ApiError && refresh.error.status === 409 ? 'It’s being fetched already.' : refresh.error?.message
  const fetched = list.source.kind === 'tmdb' || list.source.kind === 'mdblist'
  return (
    <div>
      <Link
        {...(target.to === '/' ? { to: '/' as const } : { to: target.to, search: { library: target.library } })}
        className="-ml-1 inline-flex min-h-[44px] items-center gap-1 pr-2 text-sm text-neutral-300 hover:text-neutral-100"
      >
        <Icon d="m15 18-6-6 6-6" />
        {screenName(list.screen, libs)}
      </Link>
      <div className="mt-1 flex flex-wrap items-end justify-between gap-x-6 gap-y-4">
        <div className="min-w-0 flex-[1_1_32rem]">
          <p className="text-xs font-semibold uppercase tracking-[.06em] text-accent">List · {list.owner === 'everyone' ? 'For everyone' : 'Yours'}</p>
          <h1 className="mt-1.5 text-[30px] font-bold leading-tight">{list.name}</h1>
          <p className="mt-2 flex flex-wrap items-center gap-x-2.5 gap-y-1 text-sm text-neutral-300">
            {meta.href ? (
              <a href={meta.href} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-neutral-100 underline underline-offset-[3px]">
                {meta.from}
                <Icon d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5" size={13} />
                <span className="sr-only"> (opens {new URL(meta.href).hostname.replace(/^www\./, '')})</span>
              </a>
            ) : (
              <span>{meta.from}</span>
            )}
            <Sep />
            <span>{meta.updated}</span>
            <Sep />
            <span>{inLibraryWords(inLibrary, total)}</span>
            <Sep />
            <span>{meta.type}</span>
          </p>
          {refreshError && (
            <p role="alert" className="mt-2 text-sm text-warn">
              {refreshError}
            </p>
          )}
        </div>
        {list.canEdit && (
          <div className="flex flex-wrap gap-2">
            {fetched && (
              <button type="button" disabled={!canRefresh(list) || refresh.isPending} onClick={() => refresh.mutate(list.id)} className={b2}>
                <Icon d="M4 12a8 8 0 0 1 14-5.3L20 9M20 4v5h-5M20 12a8 8 0 0 1-14 5.3L4 15M4 20v-5h5" />
                {list.fetching || refresh.isPending ? 'Refreshing…' : 'Refresh'}
              </button>
            )}
            {canEditList(list) && (
              <Link to="/lists/$listId/edit" params={{ listId: String(list.id) }} className={b2}>
                <Icon d="M4 20h4L19 9l-4-4L4 16v4zM14 6l4 4" />
                Edit
              </Link>
            )}
            <button type="button" onClick={() => setDeleting(true)} className={`${b2} text-red-300 hover:text-red-200`}>
              <Icon d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" />
              Delete
            </button>
          </div>
        )}
      </div>
      {deleting && <DeleteListDialog list={list} onClose={() => setDeleting(false)} />}
    </div>
  )
}

/** A list whose last refresh failed keeps its titles and says why. */
function Failed({ list }: { list: MediaList }) {
  const { admin } = useSettingsAccess()
  const key = list.source.kind === 'mdblist' ? ({ to: '/settings/lists', label: 'Settings › Lists' } as const) : ({ to: '/settings/general', label: 'Settings › General' } as const)
  return (
    <div role="status" className="flex flex-wrap items-start gap-3 rounded-[10px] border border-orange-900 bg-orange-950/25 px-4 py-3.5 text-sm leading-normal">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="mt-0.5 shrink-0 text-warn">
        <path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" />
        <path d="M12 9v4" />
        <path d="M12 17h.01" />
      </svg>
      <span className="min-w-64 flex-1">
        <span className="block font-semibold text-neutral-100">
          Couldn’t refresh: {list.error}
          {list.fetchedAt && ` · last worked ${relativeTime(list.fetchedAt)}`}
        </span>
        <span className="text-neutral-300">
          {list.fetchedAt ? 'These are the titles from the last refresh that worked.' : 'It hasn’t worked yet.'} {retryWords(list.nextFetchAt)}
        </span>
        {admin && (
          <>
            {' '}
            <Link to={key.to} className="text-neutral-100 underline">
              Check the key in {key.label}
            </Link>
          </>
        )}
      </span>
    </div>
  )
}

function CountLine({ list, total, inLibrary }: { list: MediaList; total: number; inLibrary: number }) {
  const options = useQuery(requestOptionsQuery)
  const { admin } = useSettingsAccess()
  const requests = !!(options.data?.movies || options.data?.shows)
  const imported = list.source.kind === 'import'
  return (
    <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-2">
      <p className="text-sm text-neutral-400">{listCountWords(total, inLibrary, list.notFound)}</p>
      {list.source.kind !== 'library' && options.data && (
        <p className="text-[13px] text-neutral-400">
          {imported
            ? 'Titles TMDB couldn’t find stay on the list as Not found.'
            : requests
              ? 'Request sends a missing title to the household’s requests, as Search does.'
              : 'Requests aren’t set up on this server.'}
          {!imported && !requests && admin && (
            <>
              {' '}
              <Link to="/settings/integrations" className="text-neutral-300 underline">
                Connect Radarr or Seerr in Settings › Integrations
              </Link>
            </>
          )}
        </p>
      )}
    </div>
  )
}

function DeleteListDialog({ list, onClose }: { list: MediaList; onClose: () => void }) {
  const del = useDeleteMediaList()
  const navigate = useNavigate()
  const libraries = useQuery(librariesQuery)
  const back = () => {
    const t = screenTarget(list.screen, libraries.data?.items ?? [])
    void navigate(t.to === '/' ? { to: '/' } : { to: t.to, search: { library: t.library } })
  }
  return (
    <Dialog title={`Delete ${list.name}?`} onClose={onClose} width="max-w-md">
      <div className="px-6 pb-6 pt-2">
        <p className="text-sm leading-normal text-neutral-400">
          {list.owner === 'everyone'
            ? 'Its row goes from Home and every other screen, for everyone in the household. This can’t be undone.'
            : 'Its row goes from Home and every other screen. This can’t be undone.'}
        </p>
        {del.isError && (
          <p role="alert" className="mt-3 text-sm text-warn">
            {del.error.message}
          </p>
        )}
        <div className="mt-6 flex flex-wrap justify-end gap-3">
          <button type="button" onClick={onClose} className={b2}>
            Cancel
          </button>
          <button type="button" disabled={del.isPending} onClick={() => del.mutate(list.id, { onSuccess: back })} className={b1}>
            {del.isPending ? 'Deleting…' : 'Delete list'}
          </button>
        </div>
      </div>
    </Dialog>
  )
}

const pills: Record<string, { label: string; gold?: boolean }> = { requested: { label: 'Requested' }, downloading: { label: 'Downloading', gold: true }, available: { label: 'Available' } }

/** A title on the list: the library's card, a dimmed missing one, or Not found. */
function EntryCard({ entry, onRequest }: { entry: MediaListEntry; onRequest: () => void }) {
  const view = entryView(entry)
  if (view.kind === 'library') return entry.item ? <PosterCard item={entry.item} rank={entry.rank} subtitle={entry.item.year ? String(entry.item.year) : ''} /> : null
  if (view.kind === 'notFound')
    return (
      <>
        <span className="relative flex aspect-[2/3] w-full items-center justify-center rounded-md border border-dashed border-neutral-600 bg-ground p-2.5 text-center text-xs leading-snug text-neutral-400">
          <RankBadge rank={entry.rank} />
          {entry.title}
        </span>
        <span className="mt-1.5 block truncate text-sm font-medium text-neutral-400">{entry.title}</span>
        <span className="block truncate text-xs text-orange-300">Not found on TMDB</span>
      </>
    )
  const pill = entry.requestState ? pills[entry.requestState] : undefined
  return (
    <>
      <span className="relative block">
        <RankBadge rank={entry.rank} />
        <span className="block opacity-40 grayscale-[.6]">
          <Poster path={entry.posterPath} alt={entry.title} />
        </span>
      </span>
      <span className="mt-1.5 block truncate text-sm font-medium text-neutral-400">{entry.title}</span>
      <span className="block truncate text-xs text-neutral-500">{[entry.year || '', 'Not in your library'].filter(Boolean).join(' · ')}</span>
      {view.request ? (
        <button
          type="button"
          onClick={onRequest}
          aria-label={`Request ${entry.title}`}
          className="mt-2 inline-flex min-h-[32px] items-center gap-1.5 self-start rounded-md border border-line-2 bg-surface px-3 text-[13px] font-semibold hover:border-accent hover:text-accent"
        >
          <Icon d="M12 5v14M5 12h14" size={14} />
          Request
        </button>
      ) : (
        pill && <Pill gold={pill.gold}>{pill.label}</Pill>
      )}
    </>
  )
}

function Pill({ gold, children }: { gold?: boolean; children: ReactNode }) {
  return (
    <span className={`mt-2 inline-flex min-h-[28px] items-center gap-1.5 self-start whitespace-nowrap rounded-full border px-2.5 text-xs font-medium ${gold ? 'border-accent/55 text-accent' : 'border-line-2 text-neutral-400'}`}>
      <span aria-hidden="true" className="h-1.5 w-1.5 shrink-0 rounded-full bg-current" />
      {children}
    </span>
  )
}

function Sep() {
  return (
    <span aria-hidden="true" className="text-neutral-600">
      ·
    </span>
  )
}

function Icon({ d, size = 16 }: { d: string; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={d} />
    </svg>
  )
}
