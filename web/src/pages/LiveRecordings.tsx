import { useRecordingAccess, useRecordingMutation } from '../components/liveTV/useRecordings'
import { useEffect, useState, type FormEvent } from 'react'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { liveTVChannelsQuery, liveTVRecordingsQuery } from '../api/queries'
import { createManualRecording, deleteRecordingFile, deleteRecordingRule, type ManualRecording, type Recording, type RecordingRule } from '../api/recordings'
import { RECORDING_HEADINGS, RECORDING_SECTIONS, canChooseClash, gapWords, localDateTime, manualRepeatWords, recordingProgress, recordingWords, validateManual, type RecordingSection } from '../lib/liveRecordings'
import { timeRange } from '../lib/liveGuide'
import { formatBytes } from '../lib/format'
import { Dialog } from '../components/Dialog'
import { textInput } from '../components/SettingsPanel'
import { primaryAction, secondaryAction } from '../components/liveTV/LiveReminders'
import { ClashDialog, RecordingDot, RecordingOptions, RuleOptions } from '../components/liveTV/LiveRecordings'

/** Each section is one bounded cursor query; event notifications refresh it. */
export function LiveRecordings() {
  const { allowed, pending } = useRecordingAccess()
  const [manual, setManual] = useState(false)
  // Until the server and capabilities answer, say nothing rather than "not available".
  if (pending) return <p role="status" className="mx-auto max-w-5xl py-10 text-sm text-neutral-400">Loading recordings…</p>
  if (!allowed) return <section className="mx-auto max-w-2xl space-y-4 py-10"><h1 className="text-2xl font-semibold">Recordings aren’t available</h1><p className="text-neutral-400">An admin can set up recording and choose who may record in Live TV settings.</p><Link to="/live-tv" className={secondaryAction}>Back to Live TV</Link></section>
  return <div className="mx-auto max-w-5xl space-y-6">
    <nav aria-label="Breadcrumb" className="text-sm text-neutral-400"><Link to="/live-tv" className="underline">Live TV</Link><span aria-hidden="true"> › </span>Recordings</nav>
    <header className="flex flex-wrap items-center justify-between gap-4"><h1 className="text-3xl font-bold tracking-tight">Recordings</h1><button type="button" onClick={() => setManual(true)} className={primaryAction}>＋ New recording</button></header>
    <p className="text-sm leading-relaxed text-neutral-400">Your household shares the tuners and these recordings. A recording can release a tuner someone is watching on. Finished recordings appear in the library.</p>
    {RECORDING_SECTIONS.map((section) => <RecordingSectionList key={section} section={section} />)}
    {manual && <ManualRecordingDialog onClose={() => setManual(false)} />}
  </div>
}
function RecordingSectionList({ section }: { section: RecordingSection }) {
  const query = useInfiniteQuery(liveTVRecordingsQuery(section))
  const pages = query.data?.pages ?? []
  const items = pages.flatMap((p) => p.items)
  const rules = pages.flatMap((p) => p.rules ?? [])
  const [now, setNow] = useState(() => Date.now())
  const ticking = section === 'now' && items.length > 0
  useEffect(() => { if (!ticking) return; const timer = window.setInterval(() => setNow(Date.now()), 15_000); return () => window.clearInterval(timer) }, [ticking])
  if (query.isSuccess && !items.length && !rules.length && section !== 'upcoming') return null
  return <section aria-labelledby={`dvr-${section}`} className="space-y-3">
    <div className="flex flex-wrap items-center justify-between gap-2"><h2 id={`dvr-${section}`} className="text-lg font-semibold">{RECORDING_HEADINGS[section]}</h2>{section === 'upcoming' && pages[0]?.freeBytes !== undefined && <p className="text-sm text-neutral-400">{formatBytes(pages[0].freeBytes)} free · {pages[0].tuners ?? 0} tuners</p>}</div>
    {query.isPending && <p role="status" className="text-sm text-neutral-400">Loading…</p>}
    {query.isError && <div role="alert" className="space-y-2 text-sm text-warn"><p>{query.error.message}</p><button type="button" onClick={() => void query.refetch()} className={secondaryAction}>Try again</button></div>}
    {!!items.length && <ul className="divide-y divide-line overflow-hidden rounded-xl border border-line bg-surface">{items.map((r) => <RecordingRow key={r.id} item={r} now={now} />)}</ul>}
    {!!rules.length && <ul className="divide-y divide-line overflow-hidden rounded-xl border border-line bg-surface">{rules.map((r) => <RuleRow key={r.id} rule={r} />)}</ul>}
    {query.isSuccess && !items.length && !rules.length && <p className="rounded-xl border border-dashed border-line-2 px-5 py-8 text-sm text-neutral-400">Nothing is scheduled. Record a programme from the guide or choose New recording.</p>}
    {query.hasNextPage && <button type="button" className={secondaryAction} disabled={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>{query.isFetchingNextPage ? 'Loading…' : 'Show more'}</button>}
  </section>
}
function RecordingRow({ item: r, now }: { item: Recording; now: number }) {
  const [edit, setEdit] = useState(false), [deleting, setDeleting] = useState(false), [dontRecord, setDontRecord] = useState(true)
  const remove = useRecordingMutation(deleteRecordingFile)
  const [clash, setClash] = useState(false)
  const recording = r.state === 'recording'
  const playable = recording || r.state === 'done' || r.state === 'partial'
  const time = new Date(r.start).toLocaleString([], { weekday: 'short', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })
  const finished = r.state === 'done' || r.state === 'partial'
  const progress = recordingProgress(r.start, r.end, now)
  return <li className="flex flex-wrap items-center gap-x-4 gap-y-3 p-4">
    {r.pictureUrl && <img src={r.pictureUrl} alt="" loading="lazy" className="aspect-video w-28 shrink-0 rounded-md bg-surface-2 object-cover sm:w-36" />}
    <div className="min-w-0 flex-1 basis-48 space-y-1">
      <h3 className="break-words text-base font-semibold"><RecordingDot state={r.state} series={r.series} />{r.title}</h3>
      {r.episodeTitle && <p className="text-sm text-neutral-300">{r.season != null && r.episode != null && `S${r.season} E${r.episode} · `}{r.episodeTitle}</p>}
      <p className="text-xs tabular-nums text-neutral-400">{time} · {r.number} {r.channelName}</p>
      <p className={`text-sm ${recording ? 'text-red-300' : r.state === 'conflict' || r.state === 'failed' || r.state === 'partial' ? 'text-warn' : 'text-neutral-300'}`}>{recordingWords(r.state, r.reason, r.conflictsWith)}</p>
      {recording && <div role="progressbar" aria-label={`Recording ${r.title}`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(progress)} aria-valuetext={`${Math.round(progress)}% recorded`} className="h-1 max-w-sm overflow-hidden rounded-full bg-line-2"><div className="h-full bg-red-500" style={{ width: `${progress}%` }} /></div>}
      {r.gaps?.map((g) => <p key={`${g.start}-${g.end}`} className="text-xs text-warn">{gapWords(g, (a, b) => timeRange(a, b))}</p>)}
      {finished && <p className="text-xs text-neutral-400">{formatBytes(r.bytes)}{r.mediaFileId ? ' · In your library' : ' · Joining your library'}</p>}
      <p className="text-xs text-neutral-400">Set by {r.userName}</p>
    </div>
    <div className="flex flex-wrap items-center gap-2 sm:ml-auto">
      {playable && (r.mediaFileId && !recording ? <Link to="/play/$fileId" params={{ fileId: String(r.mediaFileId) }} search={{ title: r.title, back: '/live-tv/recordings' }} className={primaryAction}>Watch</Link> : <Link to="/live-tv/recordings/$recordingId/watch" params={{ recordingId: r.id }} search={{ title: r.title, back: '/live-tv/recordings' }} className={primaryAction}>{recording ? 'Watch from the start' : 'Watch'}</Link>)}
      {r.canCancel && canChooseClash(r) && <button type="button" onClick={() => setClash(true)} className={primaryAction}>Record this instead</button>}
      {r.canDelete && <button type="button" onClick={() => setDeleting(true)} className={secondaryAction}>Delete</button>}
      {r.canCancel && !finished && r.state !== 'failed' && <button type="button" onClick={() => setEdit(true)} className={secondaryAction}>{recording ? 'Stop recording' : 'Options'}</button>}
    </div>
    {deleting && <Dialog title={`Delete ${r.title}?`} subtitle="This recording file is permanently deleted from the server." onClose={() => setDeleting(false)}><div className="space-y-4 p-6">
      <label className="flex min-h-11 items-center gap-3 text-sm"><input type="checkbox" checked={dontRecord} onChange={(e) => setDontRecord(e.target.checked)} className="h-4 w-4 accent-accent" />Don’t record it again</label>
      <button type="button" disabled={remove.isPending} className={primaryAction} onClick={() => remove.mutate({ id: r.id, recordAgain: !dontRecord }, { onSuccess: () => setDeleting(false) })}>{remove.isPending ? 'Deleting…' : 'Delete recording'}</button>
      {remove.isError && <p role="alert" className="text-sm text-warn">{remove.error.message}</p>}
    </div></Dialog>}
    {clash && <ClashDialog recording={r} onClose={() => setClash(false)} />}
    {edit && <RecordingOptions id={r.id} title={r.title} recording={recording} padBefore={r.padBefore} padAfter={r.padAfter} onClose={() => setEdit(false)} />}
  </li>
}
function RuleRow({ rule: r }: { rule: RecordingRule }) {
  const [edit, setEdit] = useState(false), [remove, setRemove] = useState(false)
  const change = useRecordingMutation(deleteRecordingRule)
  return <li className="flex flex-wrap items-center gap-3 p-4"><div className="min-w-0 flex-1 basis-64"><h3 className="font-semibold"><RecordingDot state="scheduled" series />{r.title}</h3><p className="mt-1 text-sm text-neutral-400">{r.kind === 'series' ? `${r.newOnly ? 'New episodes' : 'Every episode'} · ${r.anyChannel ? 'Any channel' : r.number} · ${r.keep ? `Keep the latest ${r.keep}` : 'Keep all'}` : `${manualRepeatWords(r)}${r.number ? ` · ${r.number}` : ''}`} · Set by {r.userName}</p></div>{r.canCancel && <div className="flex flex-wrap gap-2">{r.kind !== 'manual' && <button type="button" className={secondaryAction} onClick={() => setEdit(true)}>Options</button>}<button type="button" className={secondaryAction} onClick={() => setRemove(true)}>Stop scheduling</button></div>}
    {edit && <RuleOptions rule={r} onClose={() => setEdit(false)} />}
    {remove && <Dialog title={`Stop scheduling ${r.title}?`} subtitle="Existing recordings are kept. Upcoming recordings from this rule are cancelled." onClose={() => setRemove(false)}><div className="space-y-4 p-6"><button type="button" disabled={change.isPending} className={primaryAction} onClick={() => change.mutate(r.id, { onSuccess: () => setRemove(false) })}>Stop scheduling</button>{change.isError && <p role="alert" className="text-warn">{change.error.message}</p>}</div></Dialog>}
  </li>
}
function ManualRecordingDialog({ onClose }: { onClose: () => void }) {
  const channels = useQuery(liveTVChannelsQuery)
  const [channel, setChannel] = useState(''), [name, setName] = useState('')
  const [start, setStart] = useState(() => localDateTime(new Date(Date.now() + 5 * 60_000)))
  const [end, setEnd] = useState(() => localDateTime(new Date(Date.now() + 65 * 60_000)))
  const [repeat, setRepeat] = useState<ManualRecording['repeat']>('once')
  const [error, setError] = useState<string>()
  const create = useRecordingMutation(createManualRecording)
  const [clash, setClash] = useState<Recording | null>(null)
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
  const available = channels.data?.items.filter((c) => c.available && c.enabled) ?? []
  function submit(event: FormEvent<HTMLFormElement>) {
      event.preventDefault(); const invalid = validateManual(start, end, name, performance.timeOrigin + event.timeStamp); setError(invalid); if (invalid) return
      const id = channel || available[0]?.id; if (!id) { setError('Choose an available channel.'); return }
      create.mutate({ channelId: id, name: name.trim(), start: new Date(start).toISOString(), end: new Date(end).toISOString(), repeat, zone }, { onSuccess: (r) => (r.state === 'conflict' ? setClash(r) : onClose()) })

  }
  // The tuners are booked then: choose which recording gives way (DvrTakeover's clash).
  if (clash) return <ClashDialog recording={clash} onClose={onClose} />
  return <Dialog title="New recording" subtitle="Record a channel at a time you choose, even without listings." onClose={onClose}>
    <form className="space-y-4 px-6 pb-6 pt-4" onSubmit={submit}>
      <label className="block text-sm text-neutral-300">Name<input autoFocus required maxLength={200} value={name} onChange={(e) => setName(e.target.value)} className={`${textInput} mt-1 block w-full`} placeholder="Evening news" /></label>
      <label className="block text-sm text-neutral-300">Channel<select required value={channel || available[0]?.id || ''} onChange={(e) => setChannel(e.target.value)} className={`${textInput} mt-1 block w-full`}>{available.map((c) => <option key={c.id} value={c.id}>{c.number} {c.name}</option>)}</select></label>
      <div className="grid gap-4 sm:grid-cols-2"><label className="text-sm text-neutral-300">Starts<input type="datetime-local" required value={start} onChange={(e) => setStart(e.target.value)} className={`${textInput} mt-1 block w-full`} /></label><label className="text-sm text-neutral-300">Ends<input type="datetime-local" required value={end} onChange={(e) => setEnd(e.target.value)} className={`${textInput} mt-1 block w-full`} /></label></div>
      <label className="block text-sm text-neutral-300">Repeat<select value={repeat} onChange={(e) => setRepeat(e.target.value as ManualRecording['repeat'])} className={`${textInput} mt-1 block w-full`}><option value="once">Once</option><option value="daily">Every day</option><option value="weekdays">Weekdays</option><option value="weekly">Every week on this day</option></select></label>
      <p className="text-sm text-neutral-400">Times in {zone}. Repeating recordings keep this local time across daylight saving changes. Up to 12 hours each.</p>
      {(error || create.isError || channels.isError) && <p role="alert" className="text-sm text-warn">{error ?? create.error?.message ?? channels.error?.message}</p>}
      <div className="flex justify-end gap-3"><button type="button" className={secondaryAction} onClick={onClose}>Cancel</button><button type="submit" disabled={create.isPending || !available.length} className={primaryAction}>{create.isPending ? 'Scheduling…' : 'Record'}</button></div>
    </form>
  </Dialog>
}
