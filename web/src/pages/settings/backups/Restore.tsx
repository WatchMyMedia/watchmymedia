import { useQuery } from '@tanstack/react-query'
import { serverQuery } from '../../../api/queries'
import { settingsQuery, updateStatusQuery, useDownloadBackup } from '../../../api/settings'
import { visibleConfirmationError } from '../../../api/withConfirm'
import { Button, Card, HubPage, LoadingRows, Message } from '../../../components/settings/Frame'
import { errorText } from '../../../components/settings/hooks'
import { restoreSteps } from '../../../lib/backupSettings'
import { settingsCrumbs } from '../../../lib/settingsRoutes'

const crumbs = settingsCrumbs('backups', { label: 'Restore from a backup' })

/**
 * Restore from a backup: a numbered path to follow on the server itself,
 * with the commands for how this server is installed, and
 * Print. It can't be a flow: the server is stopped for most of it.
 */
export function RestorePage() {
  const settings = useQuery(settingsQuery)
  const updates = useQuery(updateStatusQuery)
  const server = useQuery(serverQuery)
  const download = useDownloadBackup()
  const s = settings.data
  const steps = s ? restoreSteps(updates.data?.install, server.data?.host, s.dataDir, s.backups[0]?.name ?? 'watchmymedia-backup.tar.gz', s.configFile) : []
  return (
    <HubPage
      crumbs={crumbs}
      title="Restore from a backup"
      lede="Puts the server back to how it was when the backup was made. Anything changed since then is lost."
      actions={<Button small onClick={() => window.print()} className="print:hidden">Print</Button>}
    >
      {!s ? (
        settings.isError ? <Message tone="err" title="Couldn’t load the backups">{errorText(settings.error)}</Message> : <LoadingRows rows={4} />
      ) : (
        <>
          <Message tone="warn" title="Before you start" actions={<Button small guarded busy={download.isPending} busyLabel="Preparing…" onClick={() => download.mutate()}>Download a backup first</Button>}>
            Download a backup of how things are now, in case you want to come back to it.
            {download.isSuccess && <span className="mt-1 block text-emerald-300">✓ Your browser is downloading it.</span>}
            {download.isError && visibleConfirmationError(download.error) && <span role="alert" className="mt-1 block text-red-300">Couldn’t start the download. {errorText(download.error)}</span>}
          </Message>
          <Card>
            <ol>
              {steps.map((step, i) => (
                <li key={step.title} className="border-t border-line px-5 py-4 first:border-t-0">
                  <h3 className="font-semibold">{i + 1} · {step.title}</h3>
                  <p className="mt-1 text-sm text-neutral-300">{step.text}</p>
                  {step.command && (
                    <code className="mt-2 block overflow-x-auto rounded-md border border-line bg-ground px-3 py-2 font-mono text-[13px] whitespace-pre text-neutral-100 select-all">{step.command}</code>
                  )}
                </li>
              ))}
            </ol>
          </Card>
          {s.backups.length > 0 && (
            <p className="text-sm text-neutral-400">
              The command names the newest backup, {s.backups[0].name}. To restore an older one, use its name from Backups instead.
            </p>
          )}
        </>
      )}
    </HubPage>
  )
}
