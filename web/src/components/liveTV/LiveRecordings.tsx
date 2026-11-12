import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { liveTVRecordingQuery } from '../../api/queries'
import { useCanRecord, useRecordingMutation } from './useRecordings'
import { Link } from '@tanstack/react-router'
import { Dialog } from '../Dialog'
import { textInput } from '../SettingsPanel'
import { primaryAction, secondaryAction } from './LiveReminders'
import { cancelRecording, createRecording, preferRecording, updateRecording, updateRecordingRule, type CreateRecording, type Recording, type RecordingRule } from '../../api/recordings'
import type { LiveProgramme } from '../../api/liveTV'
import { PADDING_MINUTES, clashHeading, conflictWords, firstGivesWay, joinNames, markHoldsRecord, markLook, recordingMarkWords } from '../../lib/liveRecordings'
import { timeOfDay, timeRange } from '../../lib/liveGuide'

/** The guide's mark: red for will record (ringed for a series), hollow orange for won't record; nothing for any other state. */
export function RecordingDot({ state, series }: { state: string; series?: boolean }) {
  const look = markLook({ state, series })
  if (!look) return null
  return <span aria-hidden="true" className={`mr-1.5 inline-block h-2 w-2 shrink-0 rounded-full align-middle ${look === 'hollow' ? 'border-2 border-warn' : `bg-red-500 ${look === 'ring' ? 'ring-1 ring-red-300 ring-offset-2 ring-offset-surface' : ''}`}`} />
}
export function RecordingMark({ programme }: { programme: LiveProgramme }) {
  const mark = programme.recording
  const look = markLook(mark)
  if (!mark || !look) return null
  return <span title={recordingMarkWords(mark)}>{look === 'rec' ? <span className="mr-1.5 rounded border border-red-500/70 bg-red-500/10 px-1 text-[10px] font-bold text-red-300">REC</span> : <RecordingDot state={mark.state} series={mark.series} />}</span>
}
export function RecordingsLink() {
  const allowed = useCanRecord()
  return allowed ? <Link to="/live-tv/recordings" className={secondaryAction}><RecordingDot state="scheduled" />Recordings</Link> : null
}
/**
 * Record beside Remind me. A programme that will record, is recording or
 * clashes shows that with its actions; any other mark (already recorded,
 * left out, failed) says why and still offers Record.
 */
export function RecordControls({ programme: p, now }: { programme: LiveProgramme; now: number }) {
  const allowed = useCanRecord()
  const [open, setOpen] = useState<'record' | 'options' | null>(null)
  const cancel = useRecordingMutation(cancelRecording)
  const mark = p.recording
  if (!allowed || Date.parse(p.end) <= now) return null
  const holds = markHoldsRecord(mark)
  const look = markLook(mark)
  return <>
    {mark && <span role="status" className={`inline-flex min-h-11 items-center gap-1 text-sm ${look === 'hollow' ? 'font-semibold text-warn' : look ? 'font-semibold text-red-300' : 'text-neutral-300'}`}>{look === 'rec' ? <span className="mr-1.5 rounded border border-red-500/70 bg-red-500/10 px-1 text-[10px] font-bold text-red-300">REC</span> : <RecordingDot state={mark.state} series={mark.series} />}{recordingMarkWords(mark)}</span>}
    {holds && mark?.state === 'recording' && <Link to="/live-tv/recordings/$recordingId/watch" params={{ recordingId: mark.id }} search={{ title: p.title, back: '/live-tv' }} className={secondaryAction}>Watch from the start</Link>}
    {holds && mark?.canCancel && <button type="button" className={secondaryAction} disabled={cancel.isPending} onClick={() => setOpen('options')}>{mark.state === 'recording' ? 'Stop recording' : 'Edit / Don’t record'}</button>}
    {!holds && <button type="button" className={secondaryAction} onClick={() => setOpen('record')}><RecordingDot state="scheduled" />{Date.parse(p.start) <= now ? 'Record the rest' : 'Record'}</button>}
    {open === 'record' && <RecordChoice programme={p} onClose={() => setOpen(null)} />}
    {open === 'options' && mark && <RecordingOptions id={mark.id} title={p.title} recording={mark.state === 'recording'} onClose={() => setOpen(null)} />}
    {cancel.isError && <p role="alert" className="text-sm text-warn">{cancel.error.message}</p>}
  </>
}

/**
 * A recording that won't record because the tuners are booked (DvrTakeover's
 * clash): the recordings holding them, and which one gives way. One already
 * recording can't. Keeping them leaves this one under Won't record.
 */
export function ClashDialog({ recording, onClose }: { recording: Recording; onClose: () => void }) {
  const [r, setRecording] = useState(recording)
  const holders = r.conflictsWith ?? []
  const [pick, setPick] = useState(() => firstGivesWay(holders))
  const prefer = useRecordingMutation(preferRecording)
  if (r.reason === 'no_tuners' || !holders.length) return <Dialog title={`${r.title} won’t record`} subtitle={conflictWords(r.reason)} onClose={onClose}>
    <div className="space-y-4 px-6 pb-6 pt-4">
      <p className="text-sm leading-relaxed text-neutral-300">{r.reason === 'no_tuners' ? 'Every tuner is kept for watching live. An admin can change Tuners kept for watching in Live TV settings.' : 'It stays under Won’t record in Recordings, and records if a tuner comes free.'}</p>
      <button type="button" autoFocus className={primaryAction} onClick={onClose}>OK</button>
    </div>
  </Dialog>
  const choosable = holders.some((h) => !h.running)
  // A long title wraps rather than running out of the dialog at phone width.
  const wrapping = `${primaryAction.replace('whitespace-nowrap', 'whitespace-normal')} py-2 text-center`
  const names = joinNames(holders.map((h) => h.title))
  const choose = () => pick && prefer.mutate({ id: r.id, over: pick }, {
    onSuccess: (next) => {
      if (next.state !== 'conflict') { onClose(); return }
      setRecording(next); setPick(firstGivesWay(next.conflictsWith ?? []))
    },
  })
  return <Dialog title={clashHeading(holders, r.start, (at) => timeOfDay(at))} subtitle={choosable ? `To record ${r.title} on ${r.number}, choose one that won’t record.` : `${names} ${holders.length === 1 ? 'is' : 'are'} already recording, so ${r.title} won’t record.`} onClose={onClose}>
    <fieldset className="m-0 min-w-0 space-y-2 border-0 px-6 pt-4">
      <legend className="sr-only">Which one won’t record</legend>
      {holders.map((h) => <label key={h.id} className={`flex items-start gap-3 rounded-lg border p-3.5 ${pick === h.id ? 'border-accent bg-surface-2' : 'border-line-2'} ${h.running ? 'opacity-60' : 'cursor-pointer'}`}>
        <input type="radio" name={`gives-way-${r.id}`} disabled={h.running || prefer.isPending} checked={pick === h.id} onChange={() => setPick(h.id)} className="mt-0.5 h-4 w-4 shrink-0 accent-accent" />
        <span className="min-w-0 space-y-0.5">
          <span className="block break-words text-[15px] font-semibold">{h.title}</span>
          <span className="block text-[13px] tabular-nums text-neutral-400">{h.number} {h.channelName} · {timeRange(Date.parse(h.start), Date.parse(h.end))} · Set by {h.userName}</span>
          {h.running && <span className="block text-[13px] font-semibold text-red-300">Recording now</span>}
        </span>
      </label>)}
    </fieldset>
    <div className="mt-4 flex flex-wrap items-center gap-3 border-t border-line px-6 py-4">
      {choosable && <button type="button" disabled={!pick || prefer.isPending} className={wrapping} onClick={choose}>{prefer.isPending ? 'Changing…' : `Record ${r.title} instead`}</button>}
      <button type="button" className={choosable ? 'min-h-11 px-2 text-left text-sm underline hover:text-accent' : primaryAction} onClick={onClose}>{choosable ? `Keep ${names}` : 'OK'}</button>
      {prefer.isError && <p role="alert" className="basis-full text-sm text-warn">{prefer.error.message}</p>}
    </div>
  </Dialog>
}
export function PaddingFields({ before, after, setBefore, setAfter }: { before?: number; after?: number; setBefore: (n: number) => void; setAfter: (n: number) => void }) {
  return <div className="grid grid-cols-2 gap-4">
    <label className="text-sm text-neutral-300">Start early<select value={before ?? -1} onChange={(e) => setBefore(Number(e.target.value))} className={`${textInput} mt-1 block w-full`}>{before === undefined && <option value={-1}>Server default</option>}{PADDING_MINUTES.map((n) => <option key={n} value={n}>{n} min</option>)}</select></label>
    <label className="text-sm text-neutral-300">End late<select value={after ?? -1} onChange={(e) => setAfter(Number(e.target.value))} className={`${textInput} mt-1 block w-full`}>{after === undefined && <option value={-1}>Server default</option>}{PADDING_MINUTES.map((n) => <option key={n} value={n}>{n} min</option>)}</select></label>
  </div>
}
export function SeriesFields({ newOnly, anyChannel, keep, setNewOnly, setAnyChannel, setKeep }: { newOnly: boolean; anyChannel: boolean; keep: number; setNewOnly: (v: boolean) => void; setAnyChannel: (v: boolean) => void; setKeep: (v: number) => void }) {
  return <>
    <label className="flex min-h-11 items-center gap-3 text-sm"><input type="checkbox" checked={newOnly} onChange={(e) => setNewOnly(e.target.checked)} className="h-4 w-4 accent-accent" />New episodes only</label>
    <label className="flex min-h-11 items-center gap-3 text-sm"><input type="checkbox" checked={anyChannel} onChange={(e) => setAnyChannel(e.target.checked)} className="h-4 w-4 accent-accent" />Any channel</label>
    <label className="block text-sm text-neutral-300">Keep<select value={keep} onChange={(e) => setKeep(Number(e.target.value))} className={`${textInput} mt-1 block w-full`}><option value={0}>All recordings</option>{Array.from({ length: 10 }, (_, i) => i + 1).map((n) => <option key={n} value={n}>The latest {n}</option>)}</select></label>
    {keep > 0 && <p className="text-sm text-warn">Older recordings are deleted by air time, even if nobody has watched them.</p>}
  </>
}
function RecordChoice({ programme: p, onClose }: { programme: LiveProgramme; onClose: () => void }) {
  const create = useRecordingMutation(createRecording)
  const [options, setOptions] = useState(false)
  const [newOnly, setNewOnly] = useState(true)
  const [anyChannel, setAnyChannel] = useState(false)
  const [keep, setKeep] = useState(0)
  const [before, setBefore] = useState<number | undefined>()
  const [after, setAfter] = useState<number | undefined>()
  // Series identity is provided by the listings, never guessed from a title.
  const series = !!p.seriesKey
  const [clash, setClash] = useState<Recording | null>(null)
  // A reply that won't record means the tuners are booked: the choice of which gives way follows.
  const submit = (scope: CreateRecording['scope']) => create.mutate({ programmeId: p.id, scope, newOnly, anyChannel, keep, padBefore: before, padAfter: after }, { onSuccess: (r) => (r.state === 'conflict' ? setClash(r) : onClose()) })
  if (clash) return <ClashDialog recording={clash} onClose={onClose} />
  return <Dialog title={`Record ${p.title}`} subtitle="A recording has priority over someone watching live. If the tuners are already booked then, you choose next which recording gives way." onClose={onClose}>
    <div className="space-y-4 px-6 pb-6 pt-4">
      <button type="button" autoFocus disabled={create.isPending} onClick={() => submit('once')} className={`${primaryAction} w-full`}>{series ? 'Just this episode' : 'Record this programme'}</button>
      {series && <button type="button" disabled={create.isPending} onClick={() => submit('series')} className={`${secondaryAction} w-full`}>{newOnly ? 'Every new episode' : 'Every episode'}</button>}
      <button type="button" aria-expanded={options} className="min-h-11 text-sm underline" onClick={() => setOptions((v) => !v)}>Options {options ? '−' : '+'}</button>
      {options && <div className="space-y-4 border-t border-line pt-4">
        {series && <SeriesFields newOnly={newOnly} anyChannel={anyChannel} keep={keep} setNewOnly={setNewOnly} setAnyChannel={setAnyChannel} setKeep={setKeep} />}
        <PaddingFields before={before} after={after} setBefore={setBefore} setAfter={setAfter} />
        <p className="text-sm text-neutral-400">An episode already recorded or in the library is skipped. New episodes are picked up as listings arrive.</p>
      </div>}
      {create.isError && <p role="alert" className="text-sm text-warn">{create.error.message}</p>}
    </div>
  </Dialog>
}
export function RecordingOptions({ id, title, recording, padBefore, padAfter, onClose }: { id: string; title: string; recording: boolean; padBefore?: number; padAfter?: number; onClose: () => void }) {
  const saved = useQuery({ ...liveTVRecordingQuery(id), enabled: padBefore === undefined || padAfter === undefined })
  const [changedBefore, setBefore] = useState<number>(), [changedAfter, setAfter] = useState<number>()
  const before = changedBefore ?? padBefore ?? saved.data?.padBefore
  const after = changedAfter ?? padAfter ?? saved.data?.padAfter
  const update = useRecordingMutation(({ cancel }: { cancel: boolean }) => cancel ? cancelRecording(id) : updateRecording(id, { padBefore: before, padAfter: after }))
  return <Dialog title={recording ? `Stop recording ${title}?` : title} subtitle={recording ? 'The part already recorded is kept.' : 'Change padding, or cancel this one recording. A series rule keeps its other episodes.'} onClose={onClose}><div className="space-y-4 px-6 pb-6 pt-4">
    {!recording && <PaddingFields before={before} after={after} setBefore={setBefore} setAfter={setAfter} />}
    <div className="flex flex-wrap gap-3"><button type="button" disabled={update.isPending} className={secondaryAction} onClick={() => update.mutate({ cancel: true }, { onSuccess: onClose })}>{recording ? 'Stop recording' : 'Don’t record'}</button>{!recording && <button type="button" disabled={update.isPending || before === undefined || after === undefined} className={primaryAction} onClick={() => update.mutate({ cancel: false }, { onSuccess: onClose })}>Save options</button>}</div>
    {update.isError && <p role="alert" className="text-warn">{update.error.message}</p>}
  </div></Dialog>
}
export function RuleOptions({ rule, onClose }: { rule: RecordingRule; onClose: () => void }) {
  const [newOnly, setNewOnly] = useState(rule.newOnly), [anyChannel, setAnyChannel] = useState(rule.anyChannel), [keep, setKeep] = useState(rule.keep)
  const [before, setBefore] = useState(rule.padBefore), [after, setAfter] = useState(rule.padAfter)
  // Manual recordings start and end exactly: they have no padding to change.
  const manual = rule.kind === 'manual'
  const update = useRecordingMutation(() => updateRecordingRule(rule.id, manual ? { newOnly, anyChannel, keep } : { newOnly, anyChannel, keep, padBefore: before, padAfter: after }))
  return <Dialog title={rule.title} subtitle="Changes apply to upcoming recordings." onClose={onClose}><div className="space-y-4 px-6 pb-6 pt-4">
    {rule.kind === 'series' && <SeriesFields newOnly={newOnly} anyChannel={anyChannel} keep={keep} setNewOnly={setNewOnly} setAnyChannel={setAnyChannel} setKeep={setKeep} />}
    {!manual && <PaddingFields before={before} after={after} setBefore={setBefore} setAfter={setAfter} />}
    <button type="button" disabled={update.isPending} className={primaryAction} onClick={() => update.mutate(undefined, { onSuccess: onClose })}>Save options</button>
    {update.isError && <p role="alert" className="text-warn">{update.error.message}</p>}
  </div></Dialog>
}
