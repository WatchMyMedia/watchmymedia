import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { settingsQuery, useCreateBackup, useDownloadBackup, useUpdateSettings } from '../../../api/settings'
import { visibleConfirmationError } from '../../../api/withConfirm'
import { Button, Card, HubPage, ItemRow, LoadingRows, Message, SaveNote, Section, fieldInput } from '../../../components/settings/Frame'
import { errorText, useInstantSave } from '../../../components/settings/hooks'
import { backupFailure, backupFolder, backupMadeLine, backupPill, keepChoices, keepLine } from '../../../lib/backupSettings'
import { formatBytes, plural, relativeTime } from '../../../lib/format'
import { settingsCrumbs, settingsHref } from '../../../lib/settingsRoutes'

const LEDE = 'Copies of the server’s database and settings, so a failed disk or a bad change doesn’t lose your library’s matches, people or history.'

/** Settings › Server › Backups: whether there is a recent backup, and what each button made (board bak-hub). */
export function BackupsHub() {
  const settings = useQuery(settingsQuery)
  const update = useUpdateSettings()
  const keep = useInstantSave()
  const create = useCreateBackup()
  const [made, setMade] = useState<string | null>(null)
  const s = settings.data
  const crumbs = settingsCrumbs('backups')
  if (!s) {
    return (
      <HubPage crumbs={crumbs} title="Backups" lede={LEDE}>
        {settings.isError ? <Message tone="err" title="Couldn’t load backups">{errorText(settings.error)}</Message> : <LoadingRows rows={3} />}
      </HubPage>
    )
  }
  const folder = backupFolder(s.dataDir)
  const pill = backupPill(s.backups)
  const backUp = () => {
    const before = s.backups.length
    setMade(null)
    create.mutate(undefined, { onSuccess: (info) => setMade(backupMadeLine(info, before, s.backupKeep, folder)) })
  }
  const failure = create.isError && visibleConfirmationError(create.error) ? backupFailure(create.error, folder, errorText) : null
  const total = s.backups.reduce((n, b) => n + b.size, 0)
  return (
    <HubPage
      crumbs={crumbs}
      title="Backups"
      lede={LEDE}
      pill={pill}
      banner={s.backupKeep === 0 ? <Message tone="warn" title="Nightly backups are off">Nothing is backed up unless you do it by hand.</Message> : undefined}
    >
      <Section title="Nightly">
        <Card>
          <ItemRow
            title="Nightly backups to keep"
            detail={<><SaveNote state={keep.state} />{keep.state.status === 'idle' || keep.state.status === 'saving' ? keepLine(s.backupKeep, folder) : ''}</>}
            control={
              <select aria-label="Nightly backups to keep" value={s.backupKeep} disabled={keep.state.status === 'saving'}
                onChange={(e) => { const n = Number(e.target.value); void keep.run(() => update.mutateAsync({ backupKeep: n }), keepLine(n, folder)) }}
                className={`${fieldInput} min-w-[7rem]`}>
                {keepChoices(s.backupKeep).map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
              </select>
            }
          />
        </Card>
      </Section>

      <Section title="By hand">
        <Card>
          <ItemRow
            title="Back up now"
            detail={made ? <span role="status" className="settings-fade text-emerald-300">✓ Saved {made}</span> : `Saves a backup in ${folder}${s.backupKeep > 0 ? `, alongside the nightly ones; the newest ${s.backupKeep} are kept` : ''}.`}
            control={<Button small guarded busy={create.isPending} busyLabel="Backing up…" onClick={backUp}>Back up now</Button>}
          />
          <DownloadRow />
        </Card>
        {failure && <Message tone="err" focus className="mt-3" title={failure.title}>{failure.body}</Message>}
        {s.backups.length > 0 && (
          <details className="mt-3 rounded-xl border border-line bg-surface px-5 py-3 text-sm">
            <summary className="cursor-pointer text-neutral-300 hover:text-neutral-100">On this server · {plural(s.backups.length, 'backup')} · {formatBytes(total)}</summary>
            <ul className="mt-2 divide-y divide-line">
              {s.backups.map((b) => (
                <li key={b.name} className="flex flex-wrap justify-between gap-x-4 gap-y-1 py-2">
                  <span className="font-mono text-xs leading-5 break-all text-neutral-200">{b.name}</span>
                  <span className="text-neutral-400">{formatBytes(b.size)} · {relativeTime(b.modTime)}</span>
                </li>
              ))}
            </ul>
          </details>
        )}
      </Section>

      <Section title="What’s in a backup">
        <Card padded>
          <div className="grid gap-5 sm:grid-cols-2">
            <div>
              <h4 className="font-semibold">In</h4>
              <ul className="mt-1.5 list-disc space-y-1 pl-5 text-sm text-neutral-300 marker:text-neutral-500">
                <li>The database: libraries, matches, people, history, settings</li>
                <li>config.yaml</li>
                <li>Downloaded subtitles and artwork</li>
              </ul>
            </div>
            <div>
              <h4 className="font-semibold">Not in</h4>
              <ul className="mt-1.5 list-disc space-y-1 pl-5 text-sm text-neutral-300 marker:text-neutral-500">
                <li>Your media files</li>
                <li>Sign-ins: everyone signs in again after a restore</li>
                <li>The Plex connection: sign in to Plex again</li>
                <li>Seek thumbnails: they’re made again</li>
              </ul>
            </div>
          </div>
        </Card>
      </Section>

      <Card>
        <ItemRow href={settingsHref('backups', 'restore')} title="Restore from a backup" detail="Four steps, done on the server itself." />
      </Card>
    </HubPage>
  )
}

/** Download a backup: a fresh archive straight to this browser, not kept on the server. */
function DownloadRow() {
  const download = useDownloadBackup()
  return (
    <ItemRow
      title="Download a backup"
      detail={download.isSuccess
        ? <span role="status" className="settings-fade text-emerald-300">✓ Your browser is downloading watchmymedia-backup.tar.gz. It isn’t kept on the server.</span>
        : 'Makes a fresh backup and sends it to this browser. It isn’t kept on the server.'}
      control={<Button small guarded busy={download.isPending} busyLabel="Preparing…" onClick={() => download.mutate()}>Download</Button>}
    >
      {download.isError && visibleConfirmationError(download.error) && <p role="alert" className="mt-1 text-sm text-red-300">Couldn’t start the download. {errorText(download.error)}</p>}
    </ItemRow>
  )
}
