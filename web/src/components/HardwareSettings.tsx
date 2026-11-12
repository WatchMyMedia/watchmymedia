import { useState } from 'react'
import { useQuery, type UseQueryResult } from '@tanstack/react-query'
import { serverQuery } from '../api/queries'
import { settingsQuery, useUpdateSettings } from '../api/settings'
import type { components } from '../api/schema'
import { adapterChoices, adapterChoiceValue, adapterDiscoveryDetail, adapterIndex, adapterIndexHint, adapterNamespace, adapterPreference, hardwareState, softwareEncoderNote } from '../lib/transcoder'
import { Fact, Panel, Row, StatusPill, primaryButton, secondaryButton, textInput } from './SettingsPanel'

type Backend = NonNullable<components['schemas']['UpdateSettingsInputBody']['hwaccel']>
const backends: [Backend, string][] = [
  ['auto', 'Auto (best available)'], ['software', 'Software (libx264)'],
  ['videotoolbox', 'VideoToolbox (macOS)'], ['nvenc', 'NVENC (NVIDIA)'],
  ['qsv', 'Quick Sync (Intel)'], ['vaapi', 'VAAPI (Linux Intel/AMD)'], ['amf', 'AMF (Windows AMD)'],
]
const stages: Record<string, string> = {
  encode: 'Encode', fixture: 'Synthetic input', 'decode-scale': 'Decode and scale',
  'decode-tonemap': 'Decode and tone map', '4k-pipeline': '4K HDR → SDR pipeline',
  selection: 'Backend selection', device: 'Adapter selection', encoders: 'List encoders', filters: 'List filters',
}

/** Repository boards: design/mockups/web-windows/Transcoding.html. */
export function HardwareSettings({ setup = false }: { setup?: boolean }) {
  const settings = useQuery({
    ...settingsQuery,
    refetchInterval: (q) => q.state.data?.hardwareAdapters?.checking || hardwareState(q.state.data?.hwaccel ?? 'auto', q.state.data?.capabilities) === 'checking' ? 2000 : false,
  })
  const server = useQuery(serverQuery)
  // Remount on backend changes, including changes from another admin. A draft
  // CUDA ordinal must never survive into a DirectX backend (or back again).
  return <HardwareSettingsBody key={settings.data?.hwaccel ?? 'loading'} settings={settings} setup={setup} platform={server.data?.platform} host={server.data?.host} retryServer={() => { void server.refetch() }} />
}

function HardwareSettingsBody({ settings, platform, host, retryServer, setup }: { settings: UseQueryResult<components['schemas']['Settings']>; platform?: string; host?: string; retryServer: () => void; setup: boolean }) {
  const update = useUpdateSettings()
  const [draft, setDraft] = useState<string | null>(null)
  const [manual, setManual] = useState(false)
  const s = settings.data
  const caps = s?.capabilities
  const state = hardwareState(s?.hwaccel ?? 'auto', caps)
  const inventory = s?.hardwareAdapters
  const checkingNames = !!inventory?.checking
  const checking = update.isPending || state === 'checking' || checkingNames
  const namespace = adapterNamespace(s?.hwaccel ?? 'auto', platform)
  const value = draft ?? (s?.hwDevice === undefined ? '' : String(s.hwDevice))
  const device = adapterIndex(value)
  const choices = adapterChoices(s?.hwaccel ?? 'auto', platform, inventory)
  const choice = adapterChoiceValue(value, choices, manual)
  const preference = adapterPreference(s?.hwaccel ?? 'auto', value)
  const discoveryDetail = adapterDiscoveryDetail(s?.hwaccel ?? 'auto', platform, inventory)
  const diagnostics = s?.hardwareDiagnostics ?? []
  const unavailable = diagnostics.filter((d) => !d.ok).length
  const resetDraft = { onSuccess: () => { setDraft(null); setManual(false) } }
  return (
    <>
      <Panel>
        <Row label={setup ? 'Video conversion' : 'Hardware acceleration'} hint={setup ? 'Leave Auto selected to use the best option this server has. Conversion lets devices play files they cannot play as they are.' : 'Auto chooses an available encoder. Changing it checks the server again.'} htmlFor="hwaccel">
          <select id="hwaccel" value={s?.hwaccel ?? 'auto'} disabled={!s || checking} onChange={(e) => update.mutate({ hwaccel: e.target.value as Backend }, resetDraft)} className={`${textInput} max-w-full px-2 disabled:opacity-60`}>
            {backends.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </select>
          <button type="button" disabled={!s || checking || draft !== null} onClick={() => s && update.mutate({ hwaccel: s.hwaccel, hwDevice: s.hwDevice ?? -1 })} className={secondaryButton}>Check again</button>
        </Row>
        {namespace && !setup && (
          <Row label={choices.length ? 'GPU' : `${namespace} device index`} hint={choices.length ? `Choose a ${namespace} device or use the default. Check again after changing GPUs or drivers.` : adapterIndexHint(namespace)} htmlFor={choices.length ? 'hwdevice-choice' : 'hwdevice'} controlsClassName="w-full sm:w-[28rem]">
            <form className="flex w-full max-w-md flex-wrap items-start gap-2" onSubmit={(e) => { e.preventDefault(); if (preference) update.mutate(preference, resetDraft) }}>
              {choices.length > 0 && <select id="hwdevice-choice" value={choice} disabled={checking} onChange={(e) => { if (e.target.value === 'manual') setManual(true); else { setManual(false); setDraft(e.target.value) } }} className={`${textInput} w-full min-w-0 truncate disabled:opacity-60 sm:w-0 sm:flex-1`}>
                <option value="">Default GPU</option>
                {choices.map((a) => <option key={a.index} value={String(a.index)}>{a.name} · {namespace} device {a.index}</option>)}
                <option value="manual">Enter a device index…</option>
              </select>}
              {(!choices.length || choice === 'manual') && <div className={choices.length ? 'w-full text-sm text-neutral-300' : ''}>{choices.length > 0 && <label htmlFor="hwdevice" className="mr-2">{namespace} device index</label>}<input id="hwdevice" type="text" inputMode="numeric" value={value} onChange={(e) => { setManual(true); setDraft(e.target.value) }} disabled={checking} aria-invalid={device === undefined} aria-describedby="hwdevice-help" placeholder="Default" className={`${textInput} w-32 disabled:opacity-60`} /></div>}
              <button type="submit" disabled={checking || draft === null || !preference} className={primaryButton}>Apply GPU</button>
              <span id="hwdevice-help" className={device === undefined ? 'w-full max-w-xs text-sm text-warn' : 'sr-only'}>Enter a whole-number index from 0 to 2147483647, or leave it blank for the default.</span>
              {inventory?.supported && choices.length === 0 && !checkingNames && <p className="w-full text-sm text-neutral-400">{discoveryDetail ? 'GPU names are unavailable. You can still enter an index or use the default.' : 'No matching named GPU was found. You can still enter an index or use the default.'}</p>}
              {discoveryDetail && <details className="w-full text-sm text-neutral-400"><summary className="cursor-pointer">GPU discovery details</summary><p className="mt-2 whitespace-pre-wrap [overflow-wrap:anywhere]">{discoveryDetail}</p></details>}
            </form>
          </Row>
        )}
        <div className="px-5 pb-4 text-sm text-neutral-400">
          <p>{setup ? 'New videos use this choice. Videos already playing can finish with their current settings.' : 'New plays use this choice. Current plays can finish on their existing GPU.'}</p>
          <p role="status" className="mt-2 text-neutral-200">{checkingNames ? 'Checking GPU names…' : checking ? 'Checking GPU capabilities…' : update.isSuccess ? 'GPU preference saved.' : settings.isPending ? 'Loading transcoder settings…' : ''}</p>
          {state === 'fallback' && !checking && <p className="mt-2 text-warn">The selected hardware is unavailable. New plays will use software. Open Probe details below for the failure reason.</p>}
          {(update.error || settings.error) && <p role="alert" className="mt-2 text-red-400">{(update.error || settings.error)?.message}</p>}
          {settings.isError && !s && <button type="button" onClick={() => { void settings.refetch(); retryServer() }} className={`${secondaryButton} mt-3`}>Try again</button>}
        </div>
      </Panel>
      {caps && (
        <Panel title="What this server can do" actions={<StatusPill tone={checking ? 'off' : caps.hwaccel === 'software' ? 'off' : 'ok'}>{checking ? 'Checking' : caps.hwaccel === 'software' ? 'Software' : 'Hardware'}</StatusPill>}>
          <dl>
            {setup ? <>
              <Fact label="Video conversion">{caps.hwaccel === 'software' ? 'Uses the processor' : 'Uses the graphics hardware'}</Fact>
              <Fact label="Maximum converted video">{checking ? 'Checking…' : caps.maxHeight >= 2160 ? 'Up to 4K' : 'Up to 1080p'}</Fact>
              <Fact label="HDR on ordinary screens">{caps.toneMap ? 'Converts the colors for an SDR screen' : 'Depends on the file and the device playing it'}</Fact>
            </> : <>
            <Fact label="Encoder in use"><code>{caps.encoder}</code>{caps.device !== undefined && <span> · device {caps.device}</span>}</Fact>
            <Fact label="GPU filtering">{caps.gpuFilters ? 'Available' : 'Software filters'}</Fact>
            <Fact label="Transcode limit">{checking ? 'Checking…' : caps.maxHeight >= 2160 ? 'Up to 4K' : 'Up to 1080p'}{!checking && !!caps.speed4k && ` · ${caps.speed4k} fps in the pipeline test`}</Fact>
            <Fact label="Available encoders"><span className="flex flex-wrap gap-1.5">{caps.encoders.length === 0 ? 'None detected yet' : caps.encoders.map((encoder) => <code key={encoder} className="rounded border border-line-2 px-1.5 text-xs text-neutral-300">{encoder}</code>)}</span></Fact>
            <Fact label="HDR tone mapping">{caps.toneMap ? 'Yes' : 'No'}</Fact>
            <Fact label="Dolby Vision 5 tone mapping">{caps.doviToneMap ? 'Yes' : 'No'}</Fact>
            <Fact label="Subtitle burn-in">{caps.libass ? 'Yes' : 'No'}</Fact>
            {!setup && <Fact label="FFmpeg"><code className="break-all text-xs text-neutral-300">{caps.ffmpeg}</code></Fact>}
            </>}
          </dl>
          <p className="px-5 py-4 text-sm text-neutral-400">{setup ? 'Files that a device can play as they are keep their original quality. You can change conversion settings later in Settings › Transcoding.' : 'A synthetic HEVC HDR → SDR test determines the 4K limit. Concurrent streams may run slower.'}</p>
          {!setup && <details className="border-t border-line px-5 py-4">
            <summary className="cursor-pointer text-sm text-neutral-200">Probe details{diagnostics.length > 0 && ` · ${unavailable ? `${unavailable} ${unavailable === 1 ? 'check' : 'checks'} unavailable` : 'all checks passed'}`}</summary>
            <p className="mt-2 text-sm text-neutral-400">Synthetic tests show what worked on this server. Driver and media support may vary.</p>
            {diagnostics.length === 0 && <p className="mt-3 text-sm text-neutral-400">{checking ? 'Results will appear when detection finishes.' : 'No probe details are available yet. Choose Check again to collect them.'}</p>}
            <ul className="mt-3 divide-y divide-line">
              {diagnostics.map((d, i) => (
                <li key={i} className="py-3 text-sm">
                  <div className="flex flex-wrap items-center justify-between gap-2"><span><code>{d.encoder}</code> · {stages[d.stage] ?? d.stage}{d.device !== undefined && ` · device ${d.device}`}</span><StatusPill tone={d.ok ? 'ok' : 'off'}>{d.ok ? 'Passed' : 'Unavailable'}{!!d.fps && ` · ${d.fps} fps`}</StatusPill></div>
                  {d.detail && <pre className="mt-2 whitespace-pre-wrap text-xs text-neutral-300 [overflow-wrap:anywhere]">{d.detail}</pre>}
                </li>
              ))}
            </ul>
          </details>}
        </Panel>
      )}
      {caps?.hwaccel === 'software' && <p className="max-w-2xl text-sm text-neutral-400">{softwareEncoderNote(setup, host)}</p>}
    </>
  )
}
