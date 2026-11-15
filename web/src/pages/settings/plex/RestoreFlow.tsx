import { useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import { plexRunsQuery, useRebaselinePlex, type PlexConnection } from '../../../api/plex'
import { FlowFrame } from '../../../components/settings/Flow'
import { useGo } from '../../../components/settings/hooks'
import { plexRunActive } from '../../../lib/plex'
import { restoreMessage, restoreStep, RESTORE_STEPS } from '../../../lib/plexSettings'
import { FlowWaiting, ListCard, PlexNote } from './parts'
import { plexCrumbs, plexHref, setAsideCheck, usePlexConnection } from './state'

/** Check a restored Plex server: What this does → Ready to check again
 * (§5.1), in place of the dialog inside today's fold. */
export function RestoreFlow({ step }: { step?: string }) {
  const { query, connection } = usePlexConnection()
  const crumbs = plexCrumbs('restore')
  if (query.isPending || query.isError)
    return <FlowWaiting crumbs={crumbs} steps={RESTORE_STEPS} title="Did you restore or rebuild Plex?" error={query.isError} retry={() => void query.refetch()} />
  if (!connection) return <FlowWaiting crumbs={crumbs} steps={RESTORE_STEPS} title="Did you restore or rebuild Plex?" missing />
  return <Restore key={connection.id} connection={connection} step={step} />
}

function Restore({ connection, step }: { connection: PlexConnection; step?: string }) {
  const go = useGo()
  const crumbs = plexCrumbs('restore')
  const runs = useQuery(plexRunsQuery(connection.id))
  const restore = useRebaselinePlex(connection.id)
  const current = restoreStep({ restarted: restore.isSuccess })
  useEffect(() => {
    if (current !== step) go(plexHref('restore', current), { replace: true })
  }, [current, step, go])
  const name = connection.name

  if (current === 'ready')
    return (
      <FlowFrame
        tab="plex"
        crumbs={crumbs}
        steps={RESTORE_STEPS}
        current="ready"
        finished="ok"
        title={`Ready to check ${name} again`}
        lede="Updates are paused until you import."
        skip={{ label: 'Back to Plex', href: plexHref() }}
        next={{ label: 'Check what will change →', href: plexHref('import') }}
      >
        <div className="grid gap-4 md:grid-cols-2">
          <ListCard title="What changed" items={['Updates from Plex are paused.', 'The restored server can be read again.']} />
          <ListCard
            title="What happens next"
            items={['Check what will change. Only new watched marks are added.', 'Choose Import and keep up to date to turn updates back on.']}
          />
        </div>
      </FlowFrame>
    )

  const importing = !!runs.data?.active || (runs.data?.items.some((r) => plexRunActive(r.status)) ?? false)
  const ready = connection.status === 'connected'
  const run = () => restore.mutate(connection.generation, { onSuccess: () => setAsideCheck(connection.id) })
  return (
    <FlowFrame
      tab="plex"
      crumbs={crumbs}
      steps={RESTORE_STEPS}
      current="what"
      title={`Did you restore or rebuild ${name}?`}
      lede="A restored Plex server can hold older watched marks than the ones already imported. Updates stop by themselves when that looks likely."
      skip={{ label: 'Cancel', href: plexHref() }}
      next={{
        label: 'Pause and start fresh',
        onClick: run,
        busy: restore.isPending,
        busyLabel: 'Pausing…',
        disabled: !ready || importing,
        reason: !ready ? 'Plex needs you to sign in again first.' : 'An import is running. Wait for it, or cancel it, first.',
      }}
    >
      <div className="space-y-4">
        <ListCard
          title="What this does"
          items={[
            'Pauses updates from Plex.',
            `Clears the hold on reading ${name}.`,
            'Keeps everything imported so far, and your links.',
            'Asks you to check what will change, and import, again.',
          ]}
        />
        <p className="text-sm text-neutral-400">Continue only if you restored or rebuilt this Plex server. Some earlier viewings may appear twice after a restore.</p>
        <PlexNote message={restoreMessage(restore.error)} focus on={{ 'try-again': run }} busy={restore.isPending} />
      </div>
    </FlowFrame>
  )
}
