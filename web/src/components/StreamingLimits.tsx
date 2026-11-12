import { useId, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { settingsQuery, useUpdateSettings } from '../api/settings'
import { visibleConfirmationError } from '../api/withConfirm'
import { Fact, Panel, Row, primaryButton, textInput } from './SettingsPanel'
import { formatBytes } from '../lib/format'
import { parseUploadMbps, uploadMbps } from '../lib/uploadSettings'
import type { components } from '../api/schema'

type Upload = components['schemas']['Snapshot']

/** The server's upload cap is a configured policy, never a measured link speed. */
export function StreamingLimits() {
  const settings = useQuery({ ...settingsQuery, refetchInterval: 15_000 })
  const upload = settings.data?.upload
  const storage = settings.data?.storage
  return <div className="space-y-6">
    {upload && <UploadLimits upload={upload} />}
    {storage && <Panel title="Playback temporary storage" description="Owned playback output only. Library files and recordings are outside this budget. Set these values in config.yaml or environment variables and restart the server.">
      <dl><Fact label="Total cache limit">{formatBytes(storage.maxCacheBytes)}</Fact>
        <Fact label="Per stream limit">{formatBytes(storage.maxSessionBytes)}</Fact>
        <Fact label="Keep free on disk">{formatBytes(storage.minFreeBytes)}</Fact>
        <Fact label="In use now">{formatBytes(storage.temporaryBytes)} · {storage.temporaryFiles} files</Fact>
        <Fact label="Free on volume">{storage.freeBytes === undefined ? 'Measurement unavailable' : formatBytes(storage.freeBytes)}</Fact>
      </dl>
      {!storage.scanComplete && <p role="status" className="border-t border-line px-5 py-3 text-sm text-warn">Temporary storage could not be measured completely. Check the server logs and cache volume.</p>}
      {storage.freeBytes !== undefined && storage.freeBytes < storage.minFreeBytes && <p role="alert" className="border-t border-line px-5 py-3 text-sm text-warn">Storage headroom is below the configured floor. New playback preparation can be refused.</p>}
    </Panel>}
  </div>
}

function UploadLimits({ upload }: { upload: Upload }) {
  const update = useUpdateSettings()
  const id = useId()
  const [mbps, setMbps] = useState(() => uploadMbps(upload.configuredBps))
  const [enforce, setEnforce] = useState(() => upload.enforcing)
  const [baseRevision, setBaseRevision] = useState(() => upload.policyRevision ?? 0)
  const [dirty, setDirty] = useState(false)
  const shownMbps = dirty ? mbps : uploadMbps(upload.configuredBps)
  const shownEnforce = dirty ? enforce : upload.enforcing
  const bps = parseUploadMbps(shownMbps)
  const changedElsewhere = dirty && (upload.policyRevision ?? 0) !== baseRevision
  const unchanged = bps === upload.configuredBps && shownEnforce === upload.enforcing
  const useLatest = () => { setDirty(false); update.reset() }
  const beginEdit = () => {
    if (!dirty) { setMbps(uploadMbps(upload.configuredBps)); setEnforce(upload.enforcing); setBaseRevision(upload.policyRevision ?? 0) }
    setDirty(true)
  }
  return <Panel title="Remote streaming upload" description="An operator ceiling for media sent away from home. It is not a measurement of your internet connection.">
      <form onSubmit={(event) => {
        event.preventDefault()
        if (bps === undefined || changedElsewhere) return
        update.mutate({ upload: { bps, enforce: shownEnforce, expectedPolicyRevision: baseRevision } }, { onSuccess: () => {
          setDirty(false)
        } })
      }}>
        <Row label="Upload ceiling" htmlFor={`${id}-bps`} hint="Aggregate for remote streams and downloads. 0 means no ceiling; local streams do not count.">
          <input id={`${id}-bps`} className={`${textInput} w-28 tabular-nums`} inputMode="decimal" value={shownMbps} onChange={(e) => { beginEdit(); setMbps(e.target.value) }} aria-invalid={bps === undefined} aria-describedby={`${id}-validation`} />
          <span className="text-sm text-neutral-400">Mbps</span>
        </Row>
        <Row label="Enforce the ceiling" hint="When on, remote media is paced and a new play may be refused if its quality cannot fit. First test the limit against your stream mix.">
          <input type="checkbox" checked={shownEnforce} onChange={(e) => { beginEdit(); setEnforce(e.target.checked) }} className="h-[18px] w-[18px] accent-accent" aria-label="Enforce remote upload ceiling" />
        </Row>
        {bps === undefined && <p id={`${id}-validation`} role="alert" className="px-5 pt-3 text-sm text-warn">Enter 0 to 1,000,000 Mbps, with up to three decimal places.</p>}
        {changedElsewhere && <p role="alert" className="px-5 pt-3 text-sm text-warn">Upload settings changed in another browser. <button type="button" className="underline" onClick={useLatest}>Use the latest values</button> before saving.</p>}
        {update.isError && visibleConfirmationError(update.error) && <p role="alert" className="px-5 pt-3 text-sm text-warn">{update.error.message}</p>}
        <div className="flex flex-wrap items-center gap-3 border-t border-line px-5 py-3">
          <button type="submit" className={primaryButton} disabled={bps === undefined || changedElsewhere || unchanged || update.isPending}>{update.isPending ? 'Saving…' : 'Save upload limit'}</button>
          <span className="text-xs text-neutral-400">Takes effect immediately. Admins away from home confirm their password.</span>
        </div>
      </form>
      <dl><Fact label="Reserved for plays">{upload.reservedBps > 0 ? `${uploadMbps(upload.reservedBps)} Mbps` : 'None'} <span className="text-neutral-400">· {upload.activeViews} active remote requests</span></Fact>
        <Fact label="Delivered recently">{uploadMbps(upload.deliveredBps)} Mbps <span className="text-neutral-400">· recent 30-second average</span></Fact>
      </dl>
    </Panel>
}
