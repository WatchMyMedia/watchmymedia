import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { plexRunQuery, previewPlexUndo, useUndoPlexRun, type PlexConnection, type PlexUndoPreview } from '../../../api/plex'
import { FlowFrame } from '../../../components/settings/Flow'
import { LoadingRows } from '../../../components/settings/Frame'
import { useGo } from '../../../components/settings/hooks'
import { count, UNDO_STEPS, undoMessage, undoStep } from '../../../lib/plexSettings'
import { when } from '../../activity/shared'
import { FlowWaiting, ListCard, Metrics, PlexNote } from './parts'
import { plexCrumbs, plexHref, useClock, usePlexConnection } from './state'

/** Undo an import: Check undo → Confirm → Undone (§5.1). The check runs
 * first, so the numbers on Confirm are real. */
export function UndoFlow({ run, step }: { run?: string; step?: string }) {
  const { query, connection } = usePlexConnection()
  const crumbs = plexCrumbs('undo')
  const runId = run && /^\d+$/.test(run) ? Number(run) : 0
  if (query.isPending || query.isError)
    return <FlowWaiting crumbs={crumbs} steps={UNDO_STEPS} title="Undo an import" error={query.isError} retry={() => void query.refetch()} />
  if (!connection) return <FlowWaiting crumbs={crumbs} steps={UNDO_STEPS} title="Undo an import" missing />
  return <Undo key={`${connection.id}:${runId}`} connection={connection} runId={runId} step={step} />
}

function Undo({ connection, runId, step }: { connection: PlexConnection; runId: number; step?: string }) {
  const go = useGo()
  const crumbs = plexCrumbs('undo')
  const id = connection.id
  const href = (key: string) => plexHref('undo', runId, key)
  const run = useQuery({ ...plexRunQuery(id, runId), enabled: runId > 0 })
  const [checked, setChecked] = useState<PlexUndoPreview | null>(null)
  const check = useMutation({ mutationFn: () => previewPlexUndo(id, runId), onSuccess: setChecked })
  const undo = useUndoPlexRun(id)
  const now = useClock(5_000)
  const current = undoStep(step, { checked: !!checked && checked.willUndo > 0, undone: undo.isSuccess })
  useEffect(() => {
    if (current !== step) go(href(current), { replace: true })
    // href is built from runId, which keys this component.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current, step, go])
  // The check starts with the flow.
  const started = useRef(false)
  useEffect(() => {
    if (started.current || runId <= 0) return
    started.current = true
    check.mutate()
  }, [check, runId])
  const recheck = () => {
    setChecked(null)
    undo.reset()
    check.mutate()
    go(href('check'))
  }

  const from = run.data ? when(run.data.startedAt) : 'this import'
  if (runId <= 0 || run.isError)
    return (
      <FlowFrame tab="plex" crumbs={crumbs} steps={UNDO_STEPS} current="check" title="Undo an import" next={{ label: 'Back to Plex', href: plexHref() }}>
        <PlexNote message={undoMessage('run_not_found', 'check')} />
      </FlowFrame>
    )

  if (current === 'done' && undo.data)
    return (
      <FlowFrame
        tab="plex"
        crumbs={crumbs}
        steps={UNDO_STEPS}
        current="done"
        finished="ok"
        title={`${count(undo.data.undone, 'watched mark')} removed`}
        lede={`The import from ${from} is undone.`}
        next={{ label: 'Back to Plex', href: plexHref() }}
      >
        <div className="grid gap-4 md:grid-cols-2">
          <ListCard
            title="What changed"
            items={[
              `${count(undo.data.undone, 'item is', 'items are')} unwatched again.`,
              undo.data.localKept > 0 && `${count(undo.data.localKept, 'item')} changed since the import ${undo.data.localKept === 1 ? 'was' : 'were'} kept.`,
              'Updates from Plex are paused.',
            ]}
          />
          <ListCard
            title="What happens next"
            items={[
              'Nothing comes across until you resume or import again.',
              'To bring fewer things across, change the links or leave items out, then check and import.',
            ]}
          />
        </div>
      </FlowFrame>
    )

  if (current === 'confirm' && checked) {
    const expired = !checked.expiresAt || Date.parse(checked.expiresAt) <= now
    return (
      <FlowFrame
        tab="plex"
        crumbs={crumbs}
        steps={UNDO_STEPS}
        current="confirm"
        stepHref={href}
        title={`Undo ${count(checked.willUndo, 'watched mark')}?`}
        lede="This can be done once for this import."
        back={{ label: 'Check undo', onClick: recheck, disabled: undo.isPending }}
        next={{
          label: `Undo ${count(checked.willUndo, 'watched mark')}`,
          busy: undo.isPending,
          busyLabel: 'Undoing…',
          disabled: expired,
          reason: 'This undo check ran out. Check undo again.',
          onClick: () => undo.mutate({ runId, previewId: checked.id }),
        }}
      >
        <div className="space-y-4">
          <ListCard
            items={[
              `${count(checked.willUndo, 'movie or episode', 'movies and episodes')} go${checked.willUndo === 1 ? 'es' : ''} back to unwatched.`,
              checked.localKept > 0 && `${checked.localKept.toLocaleString()} that changed since the import stay.`,
              'Keeping up to date is paused, so they don’t come straight back.',
              'Earlier viewings and Plex stay as they are.',
            ]}
          />
          <PlexNote message={undoMessage(undo.error, 'undo')} focus on={{ 'check-undo': recheck, 'try-again': () => undo.mutate({ runId, previewId: checked.id }) }} busy={undo.isPending} />
        </div>
      </FlowFrame>
    )
  }

  const nothing = checked && checked.willUndo === 0
  const message = undoMessage(check.error ?? (nothing ? 'nothing' : null), 'check')
  return (
    <FlowFrame
      tab="plex"
      crumbs={crumbs}
      steps={UNDO_STEPS}
      current="check"
      title={`Undo the import from ${from}`}
      lede={checked ? 'These are the watched marks that can be taken back.' : 'Checking which watched marks can be taken back.'}
      next={{ label: 'Continue', href: href('confirm'), disabled: !checked || nothing || check.isPending, reason: nothing ? 'There is nothing left to undo.' : undefined }}
    >
      {check.isPending ? (
        <LoadingRows rows={2} />
      ) : (
        <div className="space-y-4">
          {checked && (
            <>
              <Metrics items={[[checked.willUndo, 'Can be undone'], [checked.localKept, 'Changed since, kept'], [0, 'Earlier viewings removed']]} />
              <ListCard title="What stays">
                <p className="mt-2 text-sm text-neutral-300">
                  {checked.localKept > 0 ? `${count(checked.localKept, 'item')} watched again or changed after the import ${checked.localKept === 1 ? 'stays' : 'stay'} as ${checked.localKept === 1 ? 'it is' : 'they are'}. ` : ''}
                  Earlier viewings stay, and Plex isn’t touched.
                </p>
              </ListCard>
            </>
          )}
          <PlexNote message={message} focus on={{ 'check-undo': recheck }} />
        </div>
      )}
    </FlowFrame>
  )
}
