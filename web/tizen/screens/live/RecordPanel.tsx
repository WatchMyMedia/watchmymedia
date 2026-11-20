import { useEffect, useState } from 'react'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import type { LiveChannel, LiveProgramme } from '../../../src/api/liveTV'
import { liveTVRecordingQuery, liveTVRecordingsQuery } from '../../../src/api/queries'
import { cancelRecording, createRecording, deleteRecordingRule, preferRecording, updateRecordingRule, type Recording, type RecordingRule } from '../../../src/api/recordings'
import { markHoldsRecord } from '../../../src/lib/liveRecordings'
import { useFocus, useKeys } from '../../app/hooks'
import { Button } from '../../ui/Button'
import { Modal } from '../../ui/Overlays'
import { problemWords } from '../../ui/problemWords'
import { ProgrammeArt } from './ChannelArt'
import { useNow } from './clock'
import { useRecordingAction } from './liveData'
import { LiveIcon } from './LiveIcon'
import {
  DEFAULT_OPTIONS, createBody, keepWords, optionsSummary, paddingWords, recordChoice, recordSet, recordedNote, ruleBody, ruleOptions, stepKeep, stepPadding,
  type Choice, type ChoiceId, type RecordOptions,
} from './recordModel'

type Page = { kind: 'choice' } | { kind: 'options'; rule?: RecordingRule } | { kind: 'clash'; recording: Recording }

function Seg<V extends string | boolean>({ id, value, options, onChange }: { id: string; value: V; options: { value: V; label: string }[]; onChange(v: V): void }) {
  return <span className="live-seg" role="radiogroup" data-row>
    {options.map((o) => <button key={String(o.value)} type="button" role="radio" aria-checked={o.value === value} data-focus={`${id}/${String(o.value)}`}
      className={`live-seg__opt${o.value === value ? ' live-seg__opt--on' : ''}`} onClick={() => onChange(o.value)}>{o.label}</button>)}
  </span>
}

/** A value ◀ ▶ change while its row has focus: Keep, Start, End. */
function Stepper({ id, label, value }: { id: string; label: string; value: string }) {
  return <div className="live-orow">
    <span className="live-orow__label">{label}</span>
    <button type="button" className="live-step" data-focus={id} aria-label={`${label}: ${value}. Left and right change it.`}>
      <LiveIcon name="chevronLeft"/>{value}<LiveIcon name="chevron"/>
    </button>
  </div>
}

/** Options: episodes, channel, keep, start and end. ◀ ▶ change the focused value; Return goes back. */
export function OptionsPage({ series, number, options, onChange, onSave, onBack, saveLabel, busy }: {
  series: boolean; number: string; options: RecordOptions; onChange(o: RecordOptions): void; onSave(): void; onBack(): void; saveLabel: string; busy: boolean
}) {
  const focus = useFocus()
  useKeys((i) => {
    if (i.action === 'back') { onBack(); return true }
    if (i.action !== 'left' && i.action !== 'right') return false
    const dir = i.action === 'right' ? 1 : -1
    const id = (document.activeElement as HTMLElement | null)?.dataset?.focus
    if (id === 'opt/keep') onChange({ ...options, keep: stepKeep(options.keep, dir) })
    else if (id === 'opt/start') onChange({ ...options, padBefore: stepPadding(options.padBefore, dir) })
    else if (id === 'opt/end') onChange({ ...options, padAfter: stepPadding(options.padAfter, dir) })
    else return false
    return true
  })
  useEffect(() => { focus.restore([series ? 'opt/eps/true' : 'opt/start'], 'modal') }, [focus, series])
  return <>
    <div className="live-options">
      {series && <>
        <div className="live-orow"><span className="live-orow__label">Episodes</span><Seg id="opt/eps" value={options.newOnly} options={[{ value: true, label: 'New only' }, { value: false, label: 'Every episode' }]} onChange={(newOnly) => onChange({ ...options, newOnly })}/></div>
        <div className="live-orow"><span className="live-orow__label">Channel</span><Seg id="opt/chan" value={options.anyChannel} options={[{ value: false, label: `${number} only` }, { value: true, label: 'Any channel' }]} onChange={(anyChannel) => onChange({ ...options, anyChannel })}/></div>
        <Stepper id="opt/keep" label="Keep" value={keepWords(options.keep)}/>
      </>}
      <Stepper id="opt/start" label="Start" value={paddingWords(options.padBefore, 'early')}/>
      <Stepper id="opt/end" label="End" value={paddingWords(options.padAfter, 'late')}/>
    </div>
    <p className="live-panel__summary">{optionsSummary(options, number, series)}</p>
    <div className="live-panel__save"><Button id="opt/save" variant="primary" busy={busy} onPress={onSave}>{saveLabel}</Button></div>
  </>
}

/**
 * The record side panel (LiveRecord on the canvas), opened from Hold OK on
 * a programme: Just this episode, Every new episode, Every episode and the
 * options; what is set already (Don't record, Stop, Edit series); and the
 * clash, where one of the recordings holding the tuners can give way.
 */
export function RecordPanel({ channel, programme, onClose, onNote, onWatchRecording }: {
  channel: LiveChannel; programme: LiveProgramme; onClose(): void; onNote(text: string): void; onWatchRecording(id: string, title: string): void
}) {
  const now = useNow()
  const focus = useFocus()
  const [page, setPage] = useState<Page>({ kind: 'choice' })
  const [options, setOptions] = useState<RecordOptions>(DEFAULT_OPTIONS)
  const mark = markHoldsRecord(programme.recording) ? programme.recording : undefined
  const recording = useQuery({ ...liveTVRecordingQuery(mark?.id ?? ''), enabled: !!mark })
  const rules = useInfiniteQuery({ ...liveTVRecordingsQuery('series'), enabled: !!recording.data?.ruleId })
  const rule = rules.data?.pages.flatMap((p) => p.rules ?? []).find((r) => r.id === recording.data?.ruleId)
  const series = !!programme.seriesKey

  const create = useRecordingAction(createRecording)
  // What the choices act on: the clash just made, or the programme's recording.
  const subject = page.kind === 'clash' ? page.recording.id : mark?.id
  const change = useRecordingAction(async (choice: ChoiceId) => {
    if (!subject) return undefined
    if (choice === 'cancel' || choice === 'stop') return cancelRecording(subject)
    if (choice === 'deleteSeries') return rule ? deleteRecordingRule(rule.id) : undefined
    if (choice.startsWith('instead:')) return preferRecording({ id: subject, over: choice.slice(8) })
    return undefined
  })
  const saveRule = useRecordingAction((r: RecordingRule) => updateRecordingRule(r.id, ruleBody(r, options)))
  const error = create.error ?? change.error ?? saveRule.error
  const busy = create.isPending || change.isPending || saveRule.isPending

  const record = (scope: 'once' | 'series', newOnly: boolean) => {
    const o = { ...options, newOnly }
    create.mutate(createBody(programme, scope, o), {
      onSuccess: (r) => {
        if (r.state === 'conflict') { setPage({ kind: 'clash', recording: r }); return }
        onNote(recordedNote(r, scope, newOnly, Date.now()))
        onClose()
      },
    })
  }
  const pick = (c: Choice) => {
    if (busy) return
    switch (c.id) {
      case 'once': return record('once', options.newOnly)
      case 'series': case 'seriesFromOnce': return record('series', true)
      case 'all': return record('series', false)
      case 'options': return setPage({ kind: 'options' })
      case 'editSeries': if (rule) { setOptions(ruleOptions(rule)); setPage({ kind: 'options', rule }) } return
      case 'watch': if (mark) onWatchRecording(mark.id, programme.title); return
    }
    const holder = (page.kind === 'clash' ? page.recording : recording.data)?.conflictsWith?.find((h) => `instead:${h.id}` === c.id)
    change.mutate(c.id, {
      onSuccess: () => {
        onNote(c.id === 'stop' ? `Stopped. What recorded of ${programme.title} is kept.`
          : c.id === 'deleteSeries' ? `Series deleted. What it recorded stays.`
            : holder ? `${programme.title} will record. ${holder.title} won’t.` : `${programme.title} won’t record.`)
        onClose()
      },
    })
  }

  // A new page starts on its first choice.
  useEffect(() => { if (page.kind !== 'options') focus.restore([], 'modal') }, [focus, page.kind])

  const clash = page.kind === 'clash' ? page.recording : undefined
  const view = clash ? recordSet({ ...programme, recording: { id: clash.id, state: 'conflict', series: clash.series, reason: clash.reason, canCancel: clash.canCancel } }, channel, clash, undefined, now)
    : mark ? recordSet(programme, channel, recording.data, rule, now)
      : recordChoice(programme, channel, now, options)
  const optionsPage = page.kind === 'options'
  return <Modal onClose={onClose} className="live-panel-host">
    <aside role="dialog" aria-modal="true" aria-label={`${view.eyebrow}: ${view.title}`} className="live-panel">
      <div className="live-panel__head">
        <ProgrammeArt channel={channel} programme={programme} size="panel" overlay={false}/>
        <span className="live-panel__titles">
          <span className={`live-panel__eyebrow live-panel__eyebrow--${view.tone}`}>{optionsPage ? `${page.rule ? 'Edit series' : 'Record'} · Options` : view.eyebrow}</span>
          <h2 className="live-panel__title">{view.title}</h2>
          <span className="live-panel__sub">{view.sub}</span>
        </span>
      </div>
      {optionsPage ? <OptionsPage series={page.rule ? page.rule.kind === 'series' : series} number={channel.number} options={options} onChange={setOptions} busy={busy}
        onBack={() => setPage({ kind: 'choice' })}
        saveLabel={page.rule ? 'Save options' : series ? (options.newOnly ? 'Record every new episode' : 'Record every episode') : 'Record this programme'}
        onSave={() => page.rule ? saveRule.mutate(page.rule, { onSuccess: () => { onNote('Series options saved.'); onClose() } }) : record(series ? 'series' : 'once', options.newOnly)}/>
        : <>
          {view.lines && view.lines.length > 0 && <div className="live-panel__lines">{view.lines.map((l) => <span key={l}><span className="live-mark live-mark--dot" aria-hidden="true"/>{l}</span>)}</div>}
          {mark && recording.isPending && <p className="tv-note">Loading the recording…</p>}
          <div className="live-panel__choices" role="group" aria-label={view.title}>
            {view.choices.map((c, i) => <button key={c.id} type="button" className="live-opt" data-focus={`record/${c.id}`} data-autofocus={i === 0 || undefined} aria-disabled={busy || undefined} onClick={() => pick(c)}>
              <span className="live-opt__text"><span className="live-opt__label">{c.label}</span>{c.detail && <span className="live-opt__detail">{c.detail}</span>}</span>
              {c.more && <LiveIcon name="chevron" className="live-opt__more"/>}
            </button>)}
          </div>
        </>}
      {error && <p className="tv-error" role="alert">{problemWords(error).body}</p>}
      <p className="live-panel__foot">{optionsPage ? '◀ ▶ change the focused setting. Return goes back to the choice.' : view.foot}</p>
    </aside>
  </Modal>
}
