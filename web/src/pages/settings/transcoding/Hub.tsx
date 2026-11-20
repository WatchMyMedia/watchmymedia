import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { serverQuery } from '../../../api/queries'
import { settingsQuery, trickplayQuery, useDeleteTrickplay, useUpdateSettings } from '../../../api/settings'
import type { components } from '../../../api/schema'
import { visibleConfirmationError } from '../../../api/withConfirm'
import { ConfirmDialog } from '../../../components/settings/Dialogs'
import { Button, Card, HubPage, ItemRow, LoadingRows, Message, Pill, SaveNote, Section, Spinner, Switch } from '../../../components/settings/Frame'
import { errorText, useGo, useInstantSave } from '../../../components/settings/hooks'
import { formatBytes, plural } from '../../../lib/format'
import { settingsCrumbs, settingsHref } from '../../../lib/settingsRoutes'
import { softwareEncoderNote } from '../../../lib/transcoder'
import { deleteThumbnailsWords, encoderLabel, encoderLine, encoderTest, fallbackBanner, gpuName, testedAt, uploadTitle } from '../../../lib/transcodingSettings'
import { currentTitle, filesLine, percentDone, pollInterval, thumbnailCopy, thumbnailState } from '../../../lib/trickplay'
import { hardwarePoll } from './shared'

type Settings = components['schemas']['Settings']

const LEDE = 'When a device can’t play a file as it is, the server converts it while it streams. A hardware encoder keeps that fast and quiet.'

/** Settings › Server › Transcoding: each section leads with what is in use. */
export function TranscodingHub() {
  const settings = useQuery({ ...settingsQuery, refetchInterval: (q) => hardwarePoll(q) || 15_000 })
  const server = useQuery(serverQuery)
  const update = useUpdateSettings()
  const go = useGo()
  const s = settings.data
  const crumbs = settingsCrumbs('transcoding')
  if (!s) {
    return (
      <HubPage crumbs={crumbs} title="Transcoding" lede={LEDE}>
        {settings.isError ? <Message tone="err" title="Couldn’t load the transcoding settings" actions={<Button small onClick={() => void settings.refetch()}>Try again</Button>}>{errorText(settings.error)}</Message> : <LoadingRows rows={4} />}
      </HubPage>
    )
  }
  const test = encoderTest(s.hwaccel, s.capabilities)
  const testAgain = () => update.mutate({ hwaccel: s.hwaccel, hwDevice: s.hwDevice ?? -1 })
  const banner = test === 'failed' && s.capabilities && (() => {
    const words = fallbackBanner(s.hwaccel, s.capabilities)
    return (
      <Message tone="warn" title={words.title} actions={<>
        <Button small busy={update.isPending} onClick={testAgain}>Test again</Button>
        <Button small onClick={() => go(settingsHref('transcoding', 'encoder', 'choose'))}>Choose another</Button>
      </>}>{words.body}</Message>
    )
  })()
  return (
    <HubPage crumbs={crumbs} title="Transcoding" lede={LEDE} banner={banner || undefined}>
      <EncoderSection s={s} platform={server.data?.platform} host={server.data?.host} install={server.data?.install} testing={update.isPending || test === 'testing'} onTestAgain={testAgain} error={update.isError && visibleConfirmationError(update.error) ? errorText(update.error) : undefined} />
      <ThumbnailsSection />
      {s.upload && (
        <Section title="Remote upload limit">
          <Card>
            <ItemRow
              title={uploadTitle(s.upload)}
              detail="Streams to people away from home share this. When it’s full, quality steps down or a new play is refused. A tailnet counts as away."
              control={<Button small guarded onClick={() => go(settingsHref('transcoding', 'upload-limit'))}>Change</Button>}
            />
          </Card>
        </Section>
      )}
      {s.storage && <StorageSection storage={s.storage} />}
    </HubPage>
  )
}

function EncoderSection({ s, platform, host, install, testing, onTestAgain, error }: { s: Settings; platform?: string; host?: string; install?: string; testing: boolean; onTestAgain: () => void; error?: string }) {
  const go = useGo()
  const caps = s.capabilities
  const controls = (
    <>
      <Button small busy={testing} busyLabel="Testing…" onClick={onTestAgain}>Test again</Button>
      <Button small onClick={() => go(settingsHref('transcoding', 'encoder'))}>Change</Button>
    </>
  )
  let row
  if (!caps) {
    row = <ItemRow title={encoderLabel(s.hwaccel)} detail="The server hasn’t said which encoder it uses yet." control={controls} />
  } else if (testing) {
    row = (
      <ItemRow
        title={`Testing ${encoderLabel(s.hwaccel)}`}
        pill={<Pill tone="off"><Spinner />Testing</Pill>}
        detail="Converting a few seconds of test video. This takes under a minute; plays already running keep the encoder they started with."
        control={controls}
      />
    )
  } else {
    const gpu = gpuName(caps.hwaccel, caps.device, platform, s.hardwareAdapters)
    const software = caps.hwaccel === 'software'
    row = (
      <ItemRow
        title={`${encoderLabel(caps.hwaccel)}${gpu ? ` · ${gpu}` : ''}`}
        pill={software ? <Pill tone="off">No GPU</Pill> : <Pill tone="ok">Passed its test</Pill>}
        detail={encoderLine(s.hwaccel, caps, testedAt(caps.detected))}
        control={controls}
      >
        {software && <p className="mt-1.5 text-[13px] text-neutral-400">{softwareEncoderNote(false, host, install)}</p>}
      </ItemRow>
    )
  }
  return (
    <Section title="Encoder">
      <Card>
        {row}
        {caps && <CapabilityDetails s={s} />}
      </Card>
      {error && <Message tone="err" className="mt-3" focus title="Couldn’t start the test">{error}</Message>}
    </Section>
  )
}

const STAGES: Record<string, string> = {
  encode: 'Encode', fixture: 'Test video', 'decode-scale': 'Decode and scale', 'decode-tonemap': 'Decode and tone map', '4k-pipeline': '4K HDR to SDR',
  selection: 'Choosing the encoder', device: 'Finding the GPU', encoders: 'Listing encoders', filters: 'Listing filters',
}

/** What this server can do and what each probe found: kept from today's page, folded away. */
function CapabilityDetails({ s }: { s: Settings }) {
  const caps = s.capabilities!
  const diagnostics = s.hardwareDiagnostics ?? []
  const unavailable = diagnostics.filter((d) => !d.ok).length
  const facts: [string, React.ReactNode][] = [
    ['Encoder in use', <code key="e">{caps.encoder}{caps.device !== undefined ? ` · device ${caps.device}` : ''}</code>],
    ['GPU filtering', caps.gpuFilters ? 'Available' : 'Software filters'],
    ['Converts up to', `${caps.maxHeight >= 2160 ? '4K' : '1080p'}${caps.speed4k ? ` · ${Math.round(caps.speed4k)} frames a second in the 4K test` : ''}`],
    ['Encoders found', caps.encoders.length ? caps.encoders.join(', ') : 'None yet'],
    ['HDR tone mapping', caps.toneMap ? 'Yes' : 'No'],
    ['Dolby Vision 5 tone mapping', caps.doviToneMap ? 'Yes' : 'No'],
    ['Subtitle burn-in', caps.libass ? 'Yes' : 'No'],
    ['ffmpeg', <code key="f" className="break-all text-xs">{caps.ffmpeg}</code>],
  ]
  return (
    <details className="group px-5 py-3 text-sm">
      <summary className="cursor-pointer text-neutral-300 hover:text-neutral-100">
        What this server can do{diagnostics.length > 0 && ` · ${unavailable ? `${plural(unavailable, 'check')} didn’t pass` : 'every check passed'}`}
      </summary>
      <dl className="mt-3 grid gap-x-6 gap-y-1.5 sm:grid-cols-[12rem_minmax(0,1fr)]">
        {facts.map(([label, value]) => (
          <div key={label} className="contents">
            <dt className="text-neutral-400">{label}</dt>
            <dd className="text-neutral-200 [overflow-wrap:anywhere]">{value}</dd>
          </div>
        ))}
      </dl>
      {diagnostics.length > 0 && (
        <ul className="mt-3 divide-y divide-line border-t border-line">
          {diagnostics.map((d, i) => (
            <li key={i} className="py-2.5">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span><code>{d.encoder}</code> · {STAGES[d.stage] ?? d.stage}{d.device !== undefined && ` · device ${d.device}`}</span>
                <Pill tone={d.ok ? 'ok' : 'off'}>{d.ok ? 'Passed' : 'Didn’t pass'}{d.fps ? ` · ${Math.round(d.fps)} fps` : ''}</Pill>
              </div>
              {d.detail && <pre className="mt-1.5 text-xs whitespace-pre-wrap text-neutral-400 [overflow-wrap:anywhere]">{d.detail}</pre>}
            </li>
          ))}
        </ul>
      )}
    </details>
  )
}

/** Seek thumbnails: the switch saves at once; Delete asks first. */
function ThumbnailsSection() {
  const status = useQuery({ ...trickplayQuery, refetchInterval: (q) => pollInterval(q.state.data) })
  const update = useUpdateSettings()
  const remove = useDeleteTrickplay()
  const toggle = useInstantSave()
  const [asking, setAsking] = useState(false)
  const [deleted, setDeleted] = useState<string | null>(null)
  const s = status.data
  if (!s) {
    return (
      <Section title="Seek thumbnails">
        {status.isError ? <Message tone="err" title="Couldn’t load seek thumbnails">{errorText(status.error)}</Message> : <LoadingRows rows={2} />}
      </Section>
    )
  }
  const state = thumbnailState(s)
  const copy = thumbnailCopy(state, s)
  const line = state === 'working'
    ? `Making them · ${filesLine(s)}${s.current ? ` · now ${currentTitle(s.current)}` : ''}. Pauses while anyone is watching or when under 5 GB is free.`
    : `${copy.pill} · ${filesLine(s)}. ${copy.note}`
  const words = deleteThumbnailsWords(s.bytes, s.enabled)
  return (
    <Section title="Seek thumbnails">
      <Card>
        <ItemRow
          title="Make seek thumbnails"
          detail={line}
          control={<Switch label="Make seek thumbnails" checked={s.enabled} disabled={toggle.state.status === 'saving'} onChange={(on) => void toggle.run(() => update.mutateAsync({ trickplayEnabled: on }), on ? 'Making them starts now, newest files first.' : 'No new thumbnails are made. The ones already made are kept.')} />}
        >
          {state !== 'done' && state !== 'off' && (
            <div className="mt-2 h-1.5 max-w-md overflow-hidden rounded-full bg-line" role="progressbar" aria-label="Files with seek thumbnails" aria-valuemin={0} aria-valuemax={s.files} aria-valuenow={s.done}>
              <div className={`h-full rounded-full ${state === 'working' ? 'bg-accent' : 'bg-neutral-600'}`} style={{ width: `${percentDone(s)}%` }} />
            </div>
          )}
          <div className="mt-1"><SaveNote state={toggle.state} /></div>
        </ItemRow>
        <ItemRow
          title="Delete thumbnails"
          detail={`${formatBytes(s.bytes)}${s.failed > 0 ? ` · ${plural(s.failed, 'file')} couldn’t be made` : ''}. ${s.enabled ? 'They’re made again if the switch is on.' : 'Nothing is made again while the switch is off.'} They aren’t in backups.`}
          control={<Button small variant="danger" disabled={s.bytes === 0} onClick={() => { remove.reset(); setAsking(true) }}>Delete…</Button>}
        >
          {deleted && <p role="status" className="settings-fade mt-1 text-sm text-emerald-300">✓ Deleted. Freed {deleted}.</p>}
        </ItemRow>
      </Card>
      {asking && (
        <ConfirmDialog
          title="Delete all seek thumbnails?"
          lede={words.lede}
          confirmLabel="Delete thumbnails"
          busy={remove.isPending}
          error={remove.isError ? errorText(remove.error) : undefined}
          onClose={() => setAsking(false)}
          onConfirm={() => {
            const freed = formatBytes(s.bytes)
            remove.mutate(undefined, { onSuccess: () => { setAsking(false); setDeleted(freed) } })
          }}
        >
          {words.note && <p className="mt-3 text-sm text-neutral-300">{words.note}</p>}
        </ConfirmDialog>
      )}
    </Section>
  )
}

function StorageSection({ storage }: { storage: NonNullable<Settings['storage']> }) {
  const low = storage.freeBytes !== undefined && storage.freeBytes < storage.minFreeBytes
  return (
    <Section title="Playback temporary storage">
      <Card>
        <ItemRow
          title={`Up to ${formatBytes(storage.maxCacheBytes)} · ${formatBytes(storage.temporaryBytes)} in use`}
          detail="Set in config.yaml or the environment. Change it there and restart the server."
        >
          <p className="mt-1 text-[13px] text-neutral-400">
            Up to {formatBytes(storage.maxSessionBytes)} a stream · keeps {formatBytes(storage.minFreeBytes)} free on the disk
            {storage.freeBytes !== undefined && ` · ${formatBytes(storage.freeBytes)} free now`} · {plural(storage.temporaryFiles, 'file')}. Library files and recordings don’t count.
          </p>
        </ItemRow>
      </Card>
      {!storage.scanComplete && <Message tone="warn" className="mt-3" title="Temporary storage couldn’t be measured completely">Check the server’s log and the folder it uses.</Message>}
      {low && <Message tone="warn" className="mt-3" title="The disk is below the free space it keeps">New plays that need converting can be refused until there is more room.</Message>}
    </Section>
  )
}
