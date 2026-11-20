import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  plexConnectionsQuery,
  plexExclusionsQuery,
  plexPreviewQuery,
  plexRunQuery,
  plexRunsQuery,
  useSavePlexExclusions,
  usePlexRunAction,
  useStartPlexPreview,
  useStartPlexRun,
  type PlexConnection,
  type PlexPreview,
  type PlexRun,
} from '../../../api/plex'
import { FlowFrame } from '../../../components/settings/Flow'
import { Button, Card, Choice, HrefLink, Message, Pill } from '../../../components/settings/Frame'
import { useGo } from '../../../components/settings/hooks'
import { addPlexExclusion, canImportPlexPreview, plexExampleTitle, plexPersonStatus, plexPreviewCounts, plexReason, plexRunActive } from '../../../lib/plex'
import {
  ago,
  canRetry,
  cancelRunMessage,
  checkMessage,
  count,
  excludeMessage,
  IMPORT_COUNT_LABELS,
  IMPORT_STEPS,
  importLabel,
  importStep,
  minutesLeft,
  nameList,
  retryMessage,
  retryUntil,
  skipped,
  startMessage,
  type PlexMessage,
} from '../../../lib/plexSettings'
import { when } from '../../activity/shared'
import { FlowWaiting, ListCard, Metrics, PlexNote, Working } from './parts'
import { importKey, NO_IMPORT, plexCrumbs, plexHref, setAsideCheck, useClock, useLinkedNames, usePlexConnection, useStored, type ImportKept } from './state'

/**
 * Check and import: Check → What will change → Choose how → Import →
 * Imported. Opening the task starts a check; once an import exists
 * there is no Back, and a stopped one stays on Import with Retry.
 * `run` (the address after the step) names a stopped import to show.
 */
export function ImportFlow({ step, run }: { step?: string; run?: string }) {
  const { query, connection } = usePlexConnection()
  const crumbs = plexCrumbs('import')
  if (query.isPending || query.isError)
    return <FlowWaiting crumbs={crumbs} steps={IMPORT_STEPS} title="Checking what will change" error={query.isError} retry={() => void query.refetch()} />
  if (!connection) return <FlowWaiting crumbs={crumbs} steps={IMPORT_STEPS} title="Checking what will change" missing />
  return <Import key={connection.id} connection={connection} step={step} runParam={run} />
}

function Import({ connection, step, runParam }: { connection: PlexConnection; step?: string; runParam?: string }) {
  const go = useGo()
  const qc = useQueryClient()
  const id = connection.id
  const crumbs = plexCrumbs('import')
  const runs = useQuery(plexRunsQuery(id))
  // Opening the task (no step in the address) starts afresh.
  const [kept, setKept] = useStored<ImportKept>(importKey(id), NO_IMPORT, !step)
  const preview = useQuery(plexPreviewQuery(id, kept.previewId ?? ''))
  const startCheck = useStartPlexPreview(id)

  // The import this flow follows: its own, the one running, or the stopped
  // one the hub linked to.
  const [dismissed, setDismissed] = useState<number>()
  const asked = runParam && /^\d+$/.test(runParam) ? Number(runParam) : undefined
  const named = asked !== dismissed ? asked : undefined
  const candidate = kept.runId ?? runs.data?.active?.id ?? named
  if (runs.data && candidate !== undefined && kept.runId === undefined) setKept({ ...kept, runId: candidate })
  const runQuery = useQuery({ ...plexRunQuery(id, kept.runId ?? 0), enabled: !!kept.runId })
  const run = kept.runId ? runQuery.data : undefined

  const waiting = runs.isPending || (!!kept.runId && runQuery.isPending) || (!!kept.previewId && preview.isPending)
  const current = importStep(step, {
    preview: preview.data && { status: preview.data.status },
    confirmed: kept.confirmed,
    run: run && { status: run.status },
  })
  useEffect(() => {
    if (!waiting && current !== step) go(plexHref('import', current), { replace: true })
  }, [waiting, current, step, go])

  // A finished import changes the connection (approved, keeping up to date).
  const runStatus = run?.status
  useEffect(() => {
    if (runStatus && !plexRunActive(runStatus)) void qc.invalidateQueries({ queryKey: plexConnectionsQuery.queryKey })
  }, [runStatus, qc])

  const check = () => {
    setDismissed(kept.runId ?? asked)
    setKept(NO_IMPORT)
    startCheck.mutate(undefined, { onSuccess: (p) => setKept({ ...NO_IMPORT, previewId: p.id }) })
  }
  const checkAgain = () => {
    check()
    go(plexHref('import', 'check'))
  }
  // Starting the flow starts the check.
  const started = useRef(false)
  useEffect(() => {
    if (waiting || started.current || current !== 'check' || kept.previewId || kept.runId) return
    started.current = true
    check()
    // check() is stable enough for this one-off start.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [waiting, current, kept.previewId, kept.runId])
  const seeImport = () => {
    void runs.refetch()
    setKept(NO_IMPORT)
    go(plexHref('import', 'import'))
  }

  if (waiting) return <FlowWaiting crumbs={crumbs} steps={IMPORT_STEPS} current={current} title="Checking what will change" />

  if (run && current === 'done') return <Imported crumbs={crumbs} connection={connection} run={run} />
  if (run) return <Importing crumbs={crumbs} connection={connection} run={run} planned={kept.planned} onCheckAgain={checkAgain} />
  if (kept.runId && runQuery.isError)
    return (
      <FlowFrame tab="plex" crumbs={crumbs} steps={IMPORT_STEPS} current="import" title="This import is no longer available" next={{ label: 'Check again', onClick: checkAgain }}>
        <Message tone="err">{plexReason('run_not_found')}</Message>
      </FlowFrame>
    )

  if (current === 'check') {
    const failure = preview.data?.status === 'failed' ? (preview.data.reason ?? 'failed') : null
    const message = checkMessage(startCheck.error ?? preview.error ?? failure, connection.name)
    return (
      <Checking
        crumbs={crumbs}
        connection={connection}
        message={message}
        on={{ 'check-again': check, 'see-import': seeImport }}
        onCancel={() => {
          setAsideCheck(id)
          go(plexHref())
        }}
      />
    )
  }

  const p = preview.data!
  if (current === 'how') return <ChooseHow crumbs={crumbs} connection={connection} preview={p} kept={kept} setKept={setKept} onCheckAgain={checkAgain} onSeeImport={seeImport} />
  return (
    <WhatWillChange
      crumbs={crumbs}
      connection={connection}
      preview={p}
      kept={kept}
      setKept={setKept}
      onCheckAgain={checkAgain}
      onNext={() => {
        setKept({ ...kept, confirmed: true })
        go(plexHref('import', 'how'))
      }}
    />
  )
}

type Crumbs = ReturnType<typeof plexCrumbs>

function Checking({ crumbs, connection, message, on, onCancel }: {
  crumbs: Crumbs
  connection: PlexConnection
  message: PlexMessage | null
  on: Parameters<typeof PlexNote>[0]['on']
  onCancel: () => void
}) {
  const names = useLinkedNames(connection)
  return (
    <FlowFrame
      tab="plex"
      crumbs={crumbs}
      steps={IMPORT_STEPS}
      current="check"
      title="Checking what will change"
      lede={`Reading ${connection.name} for ${nameList(names.accounts) || 'the people you linked'}. Nothing is changed here or on Plex.`}
      skip={{ label: 'Cancel check', onClick: onCancel }}
    >
      {message ? (
        <PlexNote message={message} on={on} focus />
      ) : (
        <Working title={`Reading ${connection.name}`}>
          Large libraries take a few minutes. You can leave this page; the check carries on, and this page picks it up when you come back.
        </Working>
      )}
    </FlowFrame>
  )
}

/** The check is out of date once anything it depends on changed. */
function useCheckState(connection: PlexConnection, preview: PlexPreview, kept: ImportKept) {
  const now = useClock(1000)
  const left = minutesLeft(preview.expiresAt, now)
  const outOfDate = preview.status === 'stale' || kept.excluded > 0 || preview.generation !== connection.generation
  const readAll = preview.status !== 'complete' || (preview.users ?? []).every((u) => u.status === 'complete' && !u.skippedSections)
  const importable = !outOfDate && left > 0 && canImportPlexPreview(connection, preview, now)
  let why: string | undefined
  if (kept.excluded > 0 || outOfDate) why = 'This check is out of date. Check again first.'
  else if (!left) why = 'This check is more than 15 minutes old. Check again first.'
  else if (!readAll) why = 'Not everything could be read. Check again once Plex can read it all, or leave those people out.'
  else if (!importable) why = 'This check can’t be imported. Check again first.'
  return { left, outOfDate, readAll, importable, why }
}

function WhatWillChange({ crumbs, connection, preview, kept, setKept, onCheckAgain, onNext }: {
  crumbs: Crumbs
  connection: PlexConnection
  preview: PlexPreview
  kept: ImportKept
  setKept: (fn: (k: ImportKept) => ImportKept) => void
  onCheckAgain: () => void
  onNext: () => void
}) {
  const state = useCheckState(connection, preview, kept)
  const counts = plexPreviewCounts(preview)
  const exclusions = useQuery(plexExclusionsQuery(connection.id))
  const exclude = useSavePlexExclusions(connection.id)
  const [leftOut, setLeftOut] = useState<Set<string>>(() => new Set())
  const leave = (userId: number, itemType: 'movie' | 'episode', itemId: number) => {
    if (!exclusions.data) return
    exclude.mutate(
      { generation: exclusions.data.generation, items: addPlexExclusion(exclusions.data.items, { userId, itemType, itemId }) },
      {
        onSuccess: () => {
          setLeftOut((s) => new Set(s).add(`${userId}:${itemType}:${itemId}`))
          setKept((k) => ({ ...k, excluded: k.excluded + 1, confirmed: false }))
        },
      },
    )
  }
  return (
    <FlowFrame
      tab="plex"
      crumbs={crumbs}
      steps={IMPORT_STEPS}
      current="changes"
      title={
        <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
          What will change
          {state.left > 0 && !state.outOfDate ? <Pill tone="now">Check good for {count(state.left, 'more minute')}</Pill> : <Pill tone="warn">Check out of date</Pill>}
        </span>
      }
      lede="Nothing below has happened yet."
      back={{ label: 'Check again', onClick: onCheckAgain }}
      next={{ label: 'Choose how to import', onClick: onNext, disabled: !state.importable, reason: state.why }}
      wide
    >
      <div className="space-y-5">
        {kept.excluded > 0 ? (
          <PlexNote
            message={{ tone: 'warn', title: `You left ${count(kept.excluded, 'item')} out, so this check is out of date`, body: 'Check again to see the new numbers. Items you left out stay left out.', fix: { do: 'check-again', label: 'Check again' } }}
            on={{ 'check-again': onCheckAgain }}
          />
        ) : state.outOfDate ? (
          <PlexNote message={checkMessage('stale', connection.name)} on={{ 'check-again': onCheckAgain }} />
        ) : !state.left ? (
          <PlexNote
            message={{ tone: 'warn', title: 'This check is more than 15 minutes old', body: 'Plex may have changed since. Check again before importing.', fix: { do: 'check-again', label: 'Check again' } }}
            on={{ 'check-again': onCheckAgain }}
          />
        ) : null}
        <Metrics items={IMPORT_COUNT_LABELS.map(([key, label]) => [counts[key], label])} />
        <Card>
          {(preview.users ?? []).map((person) => (
            <PersonChanges
              key={person.userId}
              person={person}
              busy={exclude.isPending || !exclusions.data}
              leftOut={leftOut}
              onLeave={leave}
            />
          ))}
          {!preview.users?.length && <p className="px-5 py-4 text-sm text-neutral-400">Nobody linked was read. Link people and libraries, then check again.</p>}
        </Card>
        <PlexNote message={excludeMessage(exclude.error ?? exclusions.error)} focus />
        <div className="space-y-2 text-sm text-neutral-400">
          <p>
            Your choices here come first: progress, rewatches and anything marked unwatched in WatchMyMedia stay as they are. Plex left {counts.notWatched.toLocaleString()} unwatched and didn’t say for {counts.unknownState.toLocaleString()}; those are left alone.
          </p>
          {!!preview.unlinked?.length && (
            <p>Left out: {preview.unlinked.map((u) => `${u.title} (${plexPersonStatus(u.status)})`).join(', ')}. Nothing of theirs is read.</p>
          )}
        </div>
      </div>
    </FlowFrame>
  )
}

const CATEGORY = Object.fromEntries(IMPORT_COUNT_LABELS) as Record<string, string>

function PersonChanges({ person, busy, leftOut, onLeave }: {
  person: NonNullable<PlexPreview['users']>[number]
  busy: boolean
  leftOut: Set<string>
  onLeave: (userId: number, itemType: 'movie' | 'episode', itemId: number) => void
}) {
  const [open, setOpen] = useState(false)
  const marks = person.examples.filter((e) => e.category === 'willMark')
  const shown = marks.slice(0, 2).map(plexExampleTitle)
  const more = person.counts.willMark - shown.length
  const incomplete = person.status !== 'complete' || !!person.skippedSections
  const all = person.examples.length >= Object.values(person.counts).reduce((a, b) => a + b, 0) - person.counts.notWatched - person.counts.unknownState
  return (
    <div className="px-5 py-3.5">
      <div className="flex flex-wrap items-start gap-x-4 gap-y-2">
        <div className="min-w-0 flex-1">
          <h4 className="font-medium break-words">{person.title} · {person.counts.willMark.toLocaleString()} to mark</h4>
          <p className="mt-0.5 text-sm break-words text-neutral-400">
            {shown.length ? [...shown, more > 0 ? `and ${more.toLocaleString()} more` : ''].filter(Boolean).join(' · ') : 'Nothing new to mark.'}
          </p>
          {incomplete && (
            <p className="mt-1 text-sm text-warn">
              Couldn’t read everything for {person.title}. Check their access and libraries in Plex, or leave them out, then check again. {plexReason(person.reason)}
            </p>
          )}
        </div>
        {person.examples.length > 0 && (
          <button type="button" aria-expanded={open} onClick={() => setOpen(!open)} className="min-h-[36px] text-sm text-neutral-200 underline underline-offset-2">
            {open ? 'Hide' : all ? 'Show all' : 'Show examples'}
          </button>
        )}
      </div>
      {open && (
        <ul className="mt-3 divide-y divide-line rounded-lg border border-line">
          {person.examples.map((item, i) => {
            const key = `${person.userId}:${item.itemType}:${item.itemId}`
            return (
              <li key={`${item.itemType}:${item.itemId ?? i}:${item.category}`} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-2.5 text-sm">
                <div className="min-w-0 flex-1 basis-56">
                  <b className="font-medium break-words">{plexExampleTitle(item)}</b>
                  <p className="mt-0.5 text-[13px] text-neutral-400">
                    {item.category === 'willMark' ? `Will be marked watched for ${person.title}.` : (CATEGORY[item.category] ?? 'Left alone')}
                    {item.reason ? ` ${plexReason(item.reason)}` : ''}
                  </p>
                </div>
                {item.category === 'willMark' && !!item.itemId && (
                  leftOut.has(key) ? (
                    <span className="text-[13px] text-emerald-300">✓ Left out</span>
                  ) : (
                    <Button small disabled={busy} onClick={() => onLeave(person.userId, item.itemType, item.itemId!)}>Leave this unwatched</Button>
                  )
                )}
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}

function ChooseHow({ crumbs, connection, preview, kept, setKept, onCheckAgain, onSeeImport }: {
  crumbs: Crumbs
  connection: PlexConnection
  preview: PlexPreview
  kept: ImportKept
  setKept: (fn: (k: ImportKept) => ImportKept) => void
  onCheckAgain: () => void
  onSeeImport: () => void
}) {
  const [keepUp, setKeepUp] = useState(true)
  const state = useCheckState(connection, preview, kept)
  const start = useStartPlexRun(connection.id)
  const willMark = plexPreviewCounts(preview).willMark
  const message = startMessage(start.error, connection.name) ?? (!state.importable ? startMessage(new Error('preview_not_ready'), connection.name) : null)
  return (
    <FlowFrame
      tab="plex"
      crumbs={crumbs}
      steps={IMPORT_STEPS}
      current="how"
      stepHref={(k) => plexHref('import', k === 'check' ? 'changes' : k)}
      title="Import once, or keep up to date?"
      lede="Either way, only things you never decided here are marked. Anything you marked watched or unwatched in WatchMyMedia stays."
      back={{ label: 'What will change', href: plexHref('import', 'changes'), disabled: start.isPending }}
      next={{
        label: importLabel(willMark),
        busy: start.isPending,
        busyLabel: 'Starting the import…',
        disabled: !state.importable,
        reason: state.why,
        onClick: () =>
          start.mutate(
            { previewId: preview.id, enableSync: keepUp },
            { onSuccess: (run) => setKept((k) => ({ ...k, runId: run.id, planned: willMark })) },
          ),
      }}
    >
      <fieldset className="space-y-0">
        <legend className="sr-only">How to import</legend>
        <Choice name="plex-how" checked={keepUp} onChange={() => setKeepUp(true)} title="Import and keep up to date">
          Import now, then look at Plex about every 15 minutes and bring across anything newly watched. You can pause it any time.
        </Choice>
        <Choice name="plex-how" checked={!keepUp} onChange={() => setKeepUp(false)} title="Import once">
          Import now and stop. Nothing more comes across unless you run this again.
        </Choice>
      </fieldset>
      <p className="mt-3 text-sm text-neutral-400">Earlier viewings come across too, for people who record history.</p>
      {message && <PlexNote className="mt-4" message={message} focus={!!start.error} on={{ 'check-again': onCheckAgain, 'see-import': onSeeImport }} />}
    </FlowFrame>
  )
}

function Importing({ crumbs, connection, run, planned, onCheckAgain }: { crumbs: Crumbs; connection: PlexConnection; run: PlexRun; planned?: number; onCheckAgain: () => void }) {
  const cancel = usePlexRunAction(connection.id, 'cancel')
  const retry = usePlexRunAction(connection.id, 'retry')
  const now = useClock(30_000)
  const marked = run.counts.marked
  const of = planned && planned >= marked ? ` of ${planned.toLocaleString()}` : ''
  if (plexRunActive(run.status))
    return (
      <FlowFrame
        tab="plex"
        crumbs={crumbs}
        steps={IMPORT_STEPS}
        current="import"
        title={`Importing from ${connection.name}`}
        lede="You can leave this page. The import carries on."
        skip={{ label: 'Cancel import', onClick: () => cancel.mutate(run.id), busy: cancel.isPending, busyLabel: 'Cancelling…' }}
      >
        <div className="space-y-4">
          <Working title={run.status === 'queued' ? 'Waiting to start' : `${marked.toLocaleString()}${of} marked`} value={planned ? marked / planned : undefined}>
            Started {ago(run.startedAt, now) ?? when(run.startedAt)}. Cancel import keeps what’s already done.
          </Working>
          <PlexNote message={cancelRunMessage(cancel.error)} focus on={{ 'try-again': () => cancel.mutate(run.id) }} />
        </div>
      </FlowFrame>
    )

  const allowed = canRetry(run, connection, now)
  const until = retryUntil(run.startedAt).toLocaleDateString(undefined, { day: 'numeric', month: 'long' })
  const why = plexReason(run.reason)
  const left = planned && planned > marked ? planned - marked : 0
  return (
    <FlowFrame
      tab="plex"
      crumbs={crumbs}
      steps={IMPORT_STEPS}
      current="import"
      finished="partial"
      title={run.status === 'cancelled' ? 'The import was cancelled' : 'The import stopped part-way'}
      lede={`${marked.toLocaleString()}${of} marked.${why && run.status !== 'cancelled' ? ` ${why}` : ''}`}
      skip={{ label: 'Back to Plex', href: plexHref() }}
      next={allowed.ok
        ? { label: 'Retry import', onClick: () => retry.mutate(run.id), busy: retry.isPending, busyLabel: 'Retrying…' }
        : { label: 'Check again', onClick: onCheckAgain }}
    >
      <div className="space-y-4">
        <div className="grid gap-4 md:grid-cols-2">
          <ListCard
            title="What changed"
            items={[
              `${count(marked, 'watched mark')} ${marked === 1 ? 'was' : 'were'} imported and ${marked === 1 ? 'is' : 'are'} kept.`,
              left > 0 && `${left.toLocaleString()} ${left === 1 ? 'is' : 'are'} still to do.`,
              run.status === 'partial' && 'Some earlier viewings couldn’t be checked yet.',
            ]}
          />
          <ListCard
            title="What to do"
            items={allowed.ok ? [
              'Check Plex is running, then retry. It carries on from where it stopped.',
              `You can retry until ${until}. After that, check and import again.`,
            ] : [allowed.why ?? 'This import can’t be retried. Check and import again.']}
          />
        </div>
        <PlexNote message={retryMessage(retry.error)} focus on={{ 'check-again': onCheckAgain, 'try-again': () => retry.mutate(run.id) }} />
      </div>
    </FlowFrame>
  )
}

function Imported({ crumbs, connection, run }: { crumbs: Crumbs; connection: PlexConnection; run: PlexRun }) {
  const now = useClock(30_000)
  const c = run.counts
  const skips = skipped(c)
  const parts = [
    c.unmatched && `${c.unmatched.toLocaleString()} not found here`,
    c.ambiguous && `${c.ambiguous.toLocaleString()} need a closer match`,
    c.unavailable && `${c.unavailable.toLocaleString()} gone`,
  ].filter(Boolean)
  const finished = run.finishedAt ? (ago(run.finishedAt, now) ?? when(run.finishedAt)) : 'a moment ago'
  return (
    <FlowFrame
      tab="plex"
      crumbs={crumbs}
      steps={IMPORT_STEPS}
      current="done"
      finished="ok"
      title={`${count(c.marked, 'watched mark')} imported`}
      lede={`Finished ${finished}. ${connection.syncEnabled ? 'Keeping up to date is on.' : 'Updates from Plex are paused.'}`}
      skip={c.marked > 0 ? { label: 'Undo this import…', href: plexHref('undo', run.id) } : undefined}
      next={{ label: 'Back to Plex', href: plexHref() }}
    >
      <div className="space-y-4">
        <Metrics items={[[c.marked, 'Marked watched'], [c.localKept, 'Your choices kept'], [skips, 'Skipped']]} />
        <div className="grid gap-4 md:grid-cols-2">
          <ListCard
            title="What changed"
            items={[
              `${count(c.marked, 'movie or episode', 'movies and episodes')} marked watched.`,
              c.historyImported > 0 && `${count(c.historyImported, 'earlier viewing')} added${c.historySkipped ? `, and ${c.historySkipped.toLocaleString()} left out by your history settings` : ''}.`,
              <>Earlier viewings are in <HrefLink href="/activity/plex-history" className="text-neutral-200 underline">Activity › Earlier viewings from Plex</HrefLink>.</>,
            ]}
          />
          <ListCard
            title="What happens next"
            items={[
              connection.syncEnabled ? 'Plex is checked about every 15 minutes for anything newly watched.' : 'Nothing more comes across unless you run this again.',
              skips > 0 && `${count(skips, 'item was', 'items were')} skipped: ${parts.join(', ')}.`,
              c.marked > 0 && 'You can undo this import from the Plex page.',
            ]}
          />
        </div>
      </div>
    </FlowFrame>
  )
}
