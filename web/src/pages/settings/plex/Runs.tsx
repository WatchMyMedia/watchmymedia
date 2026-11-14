import { memo, useEffect, useMemo, useState } from 'react'
import { useInfiniteQuery, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query'
import {
  patchPlexRunHistory,
  plexExclusionsQuery,
  plexLibrariesQuery,
  plexRunHistoryQuery,
  plexRunQuery,
  usePlexRunAction,
  useSavePlexExclusions,
  type PlexConnection,
  type PlexRun,
  type PlexRunPage,
} from '../../../api/plex'
import { usersQuery } from '../../../api/queries'
import { Button, HrefLink, Message, Pill } from '../../../components/settings/Frame'
import { plexHistoryStatus, plexReason, plexRunActive, plexRunStatus } from '../../../lib/plex'
import { canRetry, cancelRunMessage, count, excludeMessage, retryMessage, skipped } from '../../../lib/plexSettings'
import { when } from '../../activity/shared'
import { PlexNote } from './parts'
import { plexHref, setAsideCheck, useClock } from './state'

/** One import in a line: when, what it marked, and its state when not complete. */
export function RunSummary({ run }: { run: PlexRun }) {
  return (
    <span className="inline-flex flex-wrap items-center gap-x-2 gap-y-1">
      <span>
        {when(run.startedAt)} · {run.counts.marked.toLocaleString()} marked watched · {skipped(run.counts).toLocaleString()} skipped
      </span>
      {run.status !== 'complete' && <Pill tone={plexRunActive(run.status) ? 'now' : 'warn'}>{plexRunStatus(run.status)}</Pill>}
    </span>
  )
}

/** An import's details, as today's page showed them: every count, earlier
 * viewings and the dates read for them, why it stopped, and Cancel or Retry. */
export function RunDetails({ connection, run, anotherActive = false }: { connection: PlexConnection; run: PlexRun; anotherActive?: boolean }) {
  const cancel = usePlexRunAction(connection.id, 'cancel')
  const retry = usePlexRunAction(connection.id, 'retry')
  const users = useQuery({ ...usersQuery, enabled: !!run.historyCoverage?.length })
  const libraries = useQuery({ ...plexLibrariesQuery(connection.id, connection.generation), enabled: false })
  const userNames = useMemo(() => new Map(users.data?.items.map((u) => [u.id, u.name])), [users.data?.items])
  const libraryNames = useMemo(() => new Map(libraries.data?.libraries.map((l) => [l.sectionId, l.title])), [libraries.data?.libraries])
  const now = useClock(60_000)
  const active = plexRunActive(run.status)
  const retryable = canRetry(run, connection, now).ok
  return (
    <div className="mt-3 space-y-3 rounded-lg border border-line bg-ground/50 p-4">
      <dl className="grid grid-cols-2 gap-x-6 gap-y-2 text-sm sm:grid-cols-3">
        {(
          [
            ['Marked watched', run.counts.marked],
            ['Already watched here', run.counts.alreadyWatched],
            ['Your choices kept', run.counts.localKept],
            ['Not found here', run.counts.unmatched],
            ['Need a closer match', run.counts.ambiguous],
            ['No longer available', run.counts.unavailable],
          ] as const
        ).map(([label, n]) => (
          <div key={label}>
            <dt className="text-xs text-neutral-400">{label}</dt>
            <dd className="mt-0.5 tabular-nums">{n.toLocaleString()}</dd>
          </div>
        ))}
      </dl>
      <p className="text-sm text-neutral-400">
        {run.finishedAt ? `Finished ${when(run.finishedAt)}. ` : ''}
        {plexHistoryStatus(run.history)} · {count(run.counts.historyImported, 'earlier viewing')} added
        {run.counts.historySkipped ? ` · ${run.counts.historySkipped.toLocaleString()} left out by your history settings` : ''}.
      </p>
      {!!run.historyCoverage?.length && (
        <details className="rounded-lg border border-line p-3">
          <summary className="cursor-pointer text-sm text-neutral-200">Dates checked for earlier viewings</summary>
          <ul className="mt-3 space-y-3 text-xs text-neutral-400">
            {run.historyCoverage.map((c) => (
              <li key={`${c.userId}:${c.sectionId}`}>
                <strong className="font-medium text-neutral-300">
                  {userNames.get(c.userId) ?? 'Chosen person'} · {libraryNames.get(c.sectionId) ?? 'Plex library'}
                </strong>
                <br />
                {c.from.startsWith('1970-') ? 'Available earlier viewings' : `From ${when(c.from)}`} through {when(c.through)}
                <br />
                {c.complete ? 'This range was checked.' : 'This range is still being checked. Retry the import to continue.'}
              </li>
            ))}
          </ul>
          <p className="mt-3 text-xs text-neutral-400">Plex may no longer have every older viewing, even when a date range has been checked.</p>
        </details>
      )}
      {run.status === 'partial' && (
        <p className="text-sm text-warn">Some earlier viewings couldn’t be checked yet. Your completed watched marks stay. Retry to continue, or keep updates paused until you’re ready.</p>
      )}
      {run.reason && <p className="text-sm text-warn">{plexReason(run.reason)}</p>}
      {(active || retryable) && (
        <div className="flex flex-wrap gap-2">
          {active && <Button small busy={cancel.isPending} busyLabel="Cancelling…" onClick={() => cancel.mutate(run.id)}>Cancel import</Button>}
          {retryable && <Button small busy={retry.isPending} busyLabel="Retrying…" disabled={anotherActive} onClick={() => retry.mutate(run.id)}>Retry import</Button>}
        </div>
      )}
      <PlexNote message={cancelRunMessage(cancel.error) ?? retryMessage(retry.error)} />
    </div>
  )
}

/** Every import, newest first, each with Undo and its details. Older pages
 * are read only when asked for. */
export function EarlierImports({ connection, runs }: { connection: PlexConnection; runs: UseQueryResult<PlexRunPage> }) {
  // Disabled historical observers ignore intervals, focus and invalidation. Seed
  // the current head only when more are asked for; retain it so newly arriving
  // runs can't roll an original row past the pagination boundary.
  const history = useInfiniteQuery(plexRunHistoryQuery(connection.id))
  const qc = useQueryClient()
  const active = runs.data?.active
  const unique = new Map<number, PlexRun>()
  for (const run of [...(active ? [active] : []), ...(runs.data?.items ?? []), ...(history.data?.pages.flatMap((p) => p.items) ?? [])])
    if (!unique.has(run.id)) unique.set(run.id, run)
  const items = [...unique.values()]
  const activeIDs = new Set(items.filter((r) => plexRunActive(r.status)).map((r) => r.id))
  const liveIDs = new Set(runs.data?.items.map((r) => r.id))
  const more = async () => {
    if (!history.data && runs.data) qc.setQueryData(plexRunHistoryQuery(connection.id).queryKey, { pages: [runs.data], pageParams: [0] })
    await history.fetchNextPage()
  }
  return (
    <div className="mt-3 space-y-3">
      {!items.length ? (
        <p className="text-sm text-neutral-400">Nothing has been imported yet.</p>
      ) : (
        <ul className="divide-y divide-line rounded-lg border border-line">
          {items.map((run) => (
            <RunItem key={run.id} connection={connection} run={run} anotherActive={activeIDs.size > (activeIDs.has(run.id) ? 1 : 0)} follow={!liveIDs.has(run.id)} />
          ))}
        </ul>
      )}
      {(runs.isError || history.isError) && (
        <Message tone="err" title="Couldn’t read the imports" actions={<Button small onClick={() => void (history.isError ? more() : runs.refetch())}>Try again</Button>}>
          The saved results are still there. Try again to see them.
        </Message>
      )}
      {(history.data ? history.hasNextPage : !!runs.data?.next) && (
        <Button small busy={history.isFetchingNextPage} busyLabel="Reading earlier imports…" onClick={() => void more().catch(() => {})}>Show earlier imports</Button>
      )}
    </div>
  )
}

// Only an old running import needs its own observer; finished history may be
// hundreds of rows and makes no requests of its own.
function RunFollow({ connectionId, runId }: { connectionId: number; runId: number }) {
  const detail = useQuery(plexRunQuery(connectionId, runId))
  const qc = useQueryClient()
  useEffect(() => {
    if (detail.data) patchPlexRunHistory(qc, connectionId, detail.data)
  }, [detail.data, qc, connectionId])
  return null
}

const RunItem = memo(function RunItem({ connection, run, anotherActive, follow }: { connection: PlexConnection; run: PlexRun; anotherActive: boolean; follow: boolean }) {
  const [open, setOpen] = useState(false)
  const active = plexRunActive(run.status)
  return (
    <li className="px-4 py-3">
      {follow && active && <RunFollow connectionId={connection.id} runId={run.id} />}
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <div className="min-w-0 flex-1 text-sm"><RunSummary run={run} /></div>
        <div className="flex flex-wrap gap-2">
          {!active && run.counts.marked > 0 && (
            <HrefLink href={plexHref('undo', run.id)} className="inline-flex min-h-[36px] items-center rounded-md border border-line-2 px-3 text-sm hover:bg-surface-2">Undo…</HrefLink>
          )}
          <Button small aria-expanded={open} onClick={() => setOpen(!open)}>{open ? 'Hide details' : 'Details'}</Button>
        </div>
      </div>
      {open && <RunDetails connection={connection} run={run} anotherActive={anotherActive} />}
    </li>
  )
})

/** Items left out of imports, each of which can be let back in. */
export function LeftOutItems({ connection, importing }: { connection: PlexConnection; importing: boolean }) {
  const exclusions = useQuery(plexExclusionsQuery(connection.id))
  const save = useSavePlexExclusions(connection.id)
  const [limit, setLimit] = useState(25)
  const [changed, setChanged] = useState(false)
  const items = exclusions.data?.items ?? []
  if (exclusions.isPending) return <p role="status" className="mt-3 text-sm text-neutral-400">Checking items left out…</p>
  return (
    <div className="mt-3 space-y-3">
      {!items.length ? (
        <p className="text-sm text-neutral-400">None. On a check, choose Leave this unwatched for an item you want to keep unwatched here.</p>
      ) : (
        <ul className="divide-y divide-line rounded-lg border border-line">
          {items.slice(0, limit).map((item) => (
            <li key={`${item.userId}:${item.itemType}:${item.itemId}`} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3">
              <div className="min-w-0 flex-1 basis-52">
                <b className="text-sm font-medium break-words">{item.title ?? (item.itemType === 'movie' ? 'A movie you left out' : 'An episode you left out')}</b>
                {!!item.userName && <p className="text-[13px] text-neutral-400">For {item.userName}</p>}
              </div>
              <Button
                small
                disabled={importing || save.isPending || !exclusions.data}
                onClick={() => {
                  if (!exclusions.data) return
                  save.mutate(
                    { generation: exclusions.data.generation, items: items.filter((x) => x.userId !== item.userId || x.itemType !== item.itemType || x.itemId !== item.itemId) },
                    { onSuccess: () => { setAsideCheck(connection.id); setChanged(true) } },
                  )
                }}
              >
                Let this import again
              </Button>
            </li>
          ))}
        </ul>
      )}
      {items.length > limit && <Button small onClick={() => setLimit(limit + 25)}>Show more items</Button>}
      {changed && <Message tone="ok">Items left out were updated. Check what will change again before importing.</Message>}
      {importing && !!items.length && <p className="text-sm text-neutral-400">An import is running. Items can be let back in once it ends.</p>}
      <PlexNote message={excludeMessage(save.error ?? exclusions.error)} />
    </div>
  )
}
