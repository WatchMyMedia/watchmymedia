import { useRecordingMutation } from './useRecordings'
import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { librariesQuery, liveTVRecordingSettingsQuery } from '../../api/queries'
import { saveRecordingSettings, type RecordingSettings as Settings } from '../../api/recordings'
import { CheckRow, Panel, Row, primaryButton, textInput } from '../SettingsPanel'
import { PaddingFields } from './LiveRecordings'

export function RecordingSettings() {
  const settings = useQuery(liveTVRecordingSettingsQuery)
  const libraries = useQuery(librariesQuery)
  if (settings.isError || libraries.isError) return <p role="alert" className="text-sm text-warn">{settings.error?.message ?? libraries.error?.message}</p>
  if (!settings.data || !libraries.data) return <p role="status" className="text-sm text-neutral-400">Loading recording settings…</p>
  return <RecordingSettingsForm initial={settings.data} libraries={libraries.data.items} />
}
function RecordingSettingsForm({ initial, libraries }: { initial: Settings; libraries: { id: number; name: string; type: string; path: string }[] }) {
  const [draft, setDraft] = useState(initial)
  const [saved, setSaved] = useState(false)
  const qc = useQueryClient()
  const change = useRecordingMutation(saveRecordingSettings)
  const patch = (p: Partial<Settings>) => { setDraft((v) => ({ ...v, ...p })); setSaved(false) }
  const tv = libraries.filter((l) => l.type === 'tv'), movies = libraries.filter((l) => l.type === 'movie')
  return <Panel title="Recordings" description="Keep the broadcast as it comes, without converting. Finished recordings join your existing libraries.">
    <form onSubmit={(e) => { e.preventDefault(); change.mutate(draft, { onSuccess: (s) => { qc.setQueryData(liveTVRecordingSettingsQuery.queryKey, s); setDraft(s); setSaved(true) } }) }}>
      <CheckRow label="Allow recordings" hint="Off until you turn it on. Recordings take priority over live viewers." checked={draft.enabled} disabled={!tv.length || change.isPending} onChange={(enabled) => patch({ enabled })} />
      {!tv.length && <p className="border-t border-line p-5 text-sm text-warn">Add a TV Shows library before turning recordings on. <Link to="/settings/libraries" className="underline">Open Libraries</Link></p>}
      <Row label="Who can record"><select aria-label="Who can record" value={draft.access} onChange={(e) => patch({ access: e.target.value as Settings['access'] })} className={textInput}><option value="none">No one</option><option value="admins">Admins only</option><option value="everyone">Everyone who can watch Live TV</option></select></Row>
      <Row label="TV Shows folder" hint="Series, news and manual recordings go here."><select aria-label="TV Shows folder" value={draft.tvLibraryId} onChange={(e) => { const lib = tv.find((l) => l.id === Number(e.target.value)); patch({ tvLibraryId: lib?.id ?? 0, tvPath: lib?.path ?? '' }) }} className={`${textInput} max-w-full`}><option value={0}>Choose a TV Shows library</option>{tv.map((l) => <option key={l.id} value={l.id}>{l.name} · {l.path}</option>)}</select></Row>
      <Row label="Movies folder" hint="Select a Movies library to record films from the guide."><select aria-label="Movies folder" value={draft.movieLibraryId ?? 0} onChange={(e) => { const lib = movies.find((l) => l.id === Number(e.target.value)); patch({ movieLibraryId: lib?.id ?? 0, moviePath: lib?.path ?? '' }) }} className={`${textInput} max-w-full`}><option value={0}>No Movies folder selected</option>{movies.map((l) => <option key={l.id} value={l.id}>{l.name} · {l.path}</option>)}</select></Row>
      <div className="border-t border-line px-5 py-4"><PaddingFields before={draft.padBefore} after={draft.padAfter} setBefore={(padBefore) => patch({ padBefore })} setAfter={(padAfter) => patch({ padAfter })} /></div>
      <Row label="Keep free" hint="A recording stops before this space is used. Existing files are kept."><input type="number" aria-label="Gigabytes to keep free" min={0} max={100000} value={draft.minFreeBytes / 1_000_000_000} onChange={(e) => patch({ minFreeBytes: Math.max(0, Number(e.target.value)) * 1_000_000_000 })} className={`${textInput} w-24`} /><span className="text-sm text-neutral-400">GB</span></Row>
      <Row label="Tuners kept for watching" hint="Recordings can use every tuner with 0; a reserved tuner stays available to live viewers."><input type="number" aria-label="Tuners reserved for watching" min={0} max={16} value={draft.tunersReserved} onChange={(e) => patch({ tunersReserved: Number(e.target.value) })} className={`${textInput} w-24`} /></Row>
      <Row label="Warn before taking a tuner"><input type="number" aria-label="Warning in seconds" min={0} max={600} value={draft.warningSeconds} onChange={(e) => patch({ warningSeconds: Number(e.target.value) })} className={`${textInput} w-24`} /><span className="text-sm text-neutral-400">seconds</span></Row>
      <CheckRow label="Find ad breaks" hint="Experimental. Looks for black frames and silence when the server is idle. Review Skip Ad suggestions; your Playback › Ads preference decides whether to skip." checked={draft.findAds} onChange={(findAds) => patch({ findAds })} />
      <p className="border-t border-line px-5 py-4 text-sm leading-relaxed text-neutral-400">The server computer must remain awake for scheduled recordings. While recording, the Mac app keeps it awake. HD broadcasts use about 3–7 GB an hour.</p>
      <div className="flex flex-wrap items-center gap-4 border-t border-line p-5"><button type="submit" disabled={change.isPending || (draft.enabled && !draft.tvLibraryId)} className={primaryButton}>{change.isPending ? 'Saving…' : 'Save recording settings'}</button>{saved && <span role="status" className="text-sm text-neutral-300">Saved</span>}{change.isError && <p role="alert" className="text-sm text-warn">{change.error.message}</p>}</div>
    </form>
  </Panel>
}
