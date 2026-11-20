import { useEffect, useLayoutEffect, useMemo, useRef, useState, type FocusEvent } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import type { LiveChannel, LiveProgramme } from '../../../src/api/liveTV'
import { liveTVRecordingsQuery } from '../../../src/api/queries'
import { cancelRecording, deleteRecordingFile, deleteRecordingRule, updateRecordingRule, type Recording, type RecordingRule } from '../../../src/api/recordings'
import { formatBytes } from '../../../src/lib/format'
import { RECORDING_SECTIONS, type RecordingSection } from '../../../src/lib/liveRecordings'
import { useEntry, useFocus, useHold, useNav, useSession } from '../../app/hooks'
import { Dialog, Modal } from '../../ui/Overlays'
import { problemWords } from '../../ui/problemWords'
import { ProblemScreen, SkeletonRow } from '../../ui/States'
import { Row } from '../../ui/Row'
import { VScroll } from '../../ui/VScroll'
import { ProgrammeArt } from './ChannelArt'
import { useNow } from './clock'
import { useLiveTV, useRecordingAction } from './liveData'
import { LiveGate } from './LiveGate'
import { LiveIcon } from './LiveIcon'
import { LiveMenu } from './LiveMenu'
import { clockWords } from './liveWords'
import { OptionsPage, RecordPanel } from './RecordPanel'
import { ruleBody, ruleOptions, seriesWords, type RecordOptions } from './recordModel'
import { SECTION_TITLES, actions, card, details, entryKey, playRequest, rowCount, spaceWords, type Entry, type RecordingAction } from './recordingsModel'
import './live.css'

function useSection(section: RecordingSection, enabled: boolean) {
  return useInfiniteQuery({ ...liveTVRecordingsQuery(section), enabled })
}

/** A recording's programme, enough for the record panel's clash. */
function programmeOf(r: Recording): { channel: LiveChannel; programme: LiveProgramme } {
  return {
    channel: { id: r.channelId, sourceId: r.sourceId, number: r.number, name: r.channelName, enabled: true, available: true },
    programme: { id: r.programmeId ?? r.id, title: r.title, start: r.start, end: r.end, episodeTitle: r.episodeTitle, season: r.season, episode: r.episode, pictureUrl: r.pictureUrl,
      recording: { id: r.id, state: r.state, series: r.series, reason: r.reason, canCancel: r.canCancel } },
  }
}

/** A series or a repeating recording's options, as the record panel's: episodes, channel, keep, start and end. */
function RuleEditor({ rule, onClose, onNote }: { rule: RecordingRule; onClose(): void; onNote(text: string): void }) {
  const [options, setOptions] = useState<RecordOptions>(() => ruleOptions(rule))
  const save = useRecordingAction(() => updateRecordingRule(rule.id, ruleBody(rule, options)))
  return <Modal onClose={onClose} className="live-panel-host">
    <aside role="dialog" aria-modal="true" aria-label={`Edit ${rule.title}`} className="live-panel">
      <div className="live-panel__head"><span className="live-panel__titles">
        <span className="live-panel__eyebrow live-panel__eyebrow--rec">{rule.kind === 'manual' ? 'Repeating recording' : 'Edit series'}</span>
        <h2 className="live-panel__title">{rule.title}</h2>
        <span className="live-panel__sub">{seriesWords(rule)}</span>
      </span></div>
      <OptionsPage series={rule.kind === 'series'} number={rule.number ?? ''} options={options} onChange={setOptions} onBack={onClose} busy={save.isPending}
        saveLabel="Save options" onSave={() => save.mutate(undefined, { onSuccess: () => { onNote('Options saved. They apply to upcoming recordings.'); onClose() } })}/>
      {save.error && <p className="tv-error" role="alert">{problemWords(save.error).body}</p>}
      <p className="live-panel__foot">◀ ▶ change the focused setting. Return closes this panel.</p>
    </aside>
  </Modal>
}

/**
 * Recordings: the focused card's details on
 * top, then rows for Recording now, Won't record, Upcoming, Series and
 * Recorded. OK does the first of a card's actions (Watch from the start,
 * Watch, Record this instead, Edit series); Hold OK lists them all.
 */
export function RecordingsScreen() {
  const tv = useLiveTV()
  if (!tv.available || !tv.channels.data?.items.length) return <div className="tv-page live-home"><LiveGate {...tv}/></div>
  return <Recordings canRecord={tv.canRecord}/>
}

function Recordings({ canRecord }: { canRecord: boolean }) {
  const nav = useNav()
  const session = useSession()
  const entry = useEntry()
  const now = useNow()
  const sections = {
    now: useSection('now', canRecord), conflicts: useSection('conflicts', canRecord), upcoming: useSection('upcoming', canRecord),
    series: useSection('series', canRecord), recorded: useSection('recorded', canRecord),
  }
  const rows = useMemo(() => RECORDING_SECTIONS.map((section) => {
    const pages = sections[section].data?.pages ?? []
    const entries: Entry[] = section === 'series'
      ? pages.flatMap((p) => p.rules ?? []).map((item) => ({ kind: 'rule', section, item }))
      : pages.flatMap((p) => p.items).map((item) => ({ kind: 'recording', section, item }))
    return { section, entries }
  }).filter((r) => r.entries.length),
  // Each section's pages are the inputs.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  [sections.now.data, sections.conflicts.data, sections.upcoming.data, sections.series.data, sections.recorded.data])
  const all = rows.flatMap((r) => r.entries)
  const [focused, setFocused] = useState<string | undefined>(() => entry?.memory.last?.replace(/^rec-[a-z]+\//, ''))
  const current = all.find((e) => entryKey(e) === focused) ?? all[0]
  const [menu, setMenu] = useState<Entry>()
  const [clash, setClash] = useState<Recording>()
  const [editing, setEditing] = useState<RecordingRule>()
  const [deleting, setDeleting] = useState<Recording>()
  const [dontRecord, setDontRecord] = useState(true)
  const [note, setNote] = useState<string>()
  const list = useRef<HTMLDivElement>(null)
  const focus = useFocus()
  const rules = sections.series.data?.pages.flatMap((p) => p.rules ?? []) ?? []
  const first = sections.upcoming.data?.pages[0]

  const cancel = useRecordingAction(cancelRecording)
  const removeRule = useRecordingAction(deleteRecordingRule)
  const removeFile = useRecordingAction(deleteRecordingFile)
  const failed = cancel.error ?? removeRule.error ?? removeFile.error

  useEffect(() => {
    if (!note) return
    const timer = setTimeout(() => setNote(undefined), 4000)
    return () => clearTimeout(timer)
  }, [note])

  const run = (e: Entry, id: RecordingAction) => {
    setMenu(undefined)
    const r = e.kind === 'recording' ? e.item : undefined
    const rule = e.kind === 'rule' ? e.item : rules.find((x) => x.id === r?.ruleId)
    switch (id) {
      case 'watch': case 'watchStart': { const play = r && playRequest(r); if (play) nav.play(play); return }
      case 'instead': if (r) setClash(r); return
      case 'stop': if (r) cancel.mutate(r.id, { onSuccess: () => setNote(`Stopped. What recorded of ${r.title} is kept.`) }); return
      case 'cancel': if (r) cancel.mutate(r.id, { onSuccess: () => setNote(`${r.title} won’t record.`) }); return
      case 'editSeries': if (rule) setEditing(rule); return
      case 'deleteSeries': if (rule) removeRule.mutate(rule.id, { onSuccess: () => setNote(`${rule.title} won’t record again. What it recorded stays.`) }); return
      case 'delete': if (r) { setDontRecord(true); setDeleting(r) } return
      case 'guide': if (r) nav.push({ name: 'liveGuide', channelId: r.channelId, at: Date.parse(r.start) }); return
    }
  }
  useHold(list, () => { if (current && actions(current).length) setMenu(current) })
  // A card that went (stopped, deleted, given way) leaves focus nowhere: the card now in its place takes it.
  const currentId = current ? `rec-${current.section}/${entryKey(current)}` : undefined
  useLayoutEffect(() => {
    const active = document.activeElement
    if (currentId && (!active || active === document.body) && !focus.scope('modal')) focus.focusId(currentId)
  }, [focus, currentId, all.length])
  const onFocus = (e: FocusEvent) => {
    const id = (e.target as HTMLElement).dataset?.focus
    if (id?.startsWith('rec-')) setFocused(id.replace(/^rec-[a-z]+\//, ''))
  }

  if (!canRecord) return <div className="tv-page live-page"><ProblemScreen icon="live" title="Recordings aren’t available" actions={[{ id: 'recordings/back', label: 'Back', onPress: () => nav.back() }]}>
    An admin can turn recording on, and choose who may record, in the web app’s Live TV settings.
  </ProblemScreen></div>
  const loading = Object.values(sections).some((q) => q.isPending)
  const error = Object.values(sections).find((q) => q.error)?.error
  const d = current && details(current, now, session.account.name)
  const space = spaceWords(first?.freeBytes, first?.tuners, formatBytes)
  const focusedRecording = current?.kind === 'recording' ? current.item : undefined
  return <div className="tv-page live-page live-recordings">
    {current && <div className="live-hero__pic" aria-hidden="true">
      <ProgrammeArt channel={{ number: focusedRecording?.number ?? current.item.title, name: focusedRecording?.channelName ?? current.item.title }} picture={focusedRecording?.pictureUrl} size="hero" overlay={false} dim={focusedRecording?.state === 'failed'}/>
      <span className="live-hero__fade"/>
    </div>}
    <div className="live-guide__clock">{space && <span>{space}</span>}<b>{clockWords(now)}</b></div>
    <section className="live-hero live-hero--recording" role="status" aria-label="Focused recording">
      <p className="live-page__crumb">Live TV › Recordings</p>
      {d ? <>
        <div className={`live-rstatus live-rstatus--${d.tone}`}>
          {d.mark === 'rec' && <span className="live-tag live-tag--rec">REC</span>}
          {d.mark === 'ring' && <span className="live-mark live-mark--ring" aria-hidden="true"/>}
          {d.mark === 'wont' && <span className="live-mark live-mark--hollow" aria-hidden="true"/>}
          {d.mark === 'done' && <LiveIcon name="done"/>}
          {d.mark === 'warn' && <LiveIcon name="warn"/>}
          <span>{d.status}</span>
        </div>
        <h1 className="live-hero__title">{d.title}</h1>
        <div className="live-hero__meta">{d.episode && <span>{d.episode}</span>}<span className="live-hero__cats">{d.meta}</span></div>
        {d.description && <p className="live-hero__desc">{d.description}</p>}
        <div className="live-hero__keys">
          {d.ok && <><span className="tv-kbd">OK</span><span>{d.ok.label}</span></>}
          {d.hold && <><span className="live-gap" aria-hidden="true"/><span className="tv-kbd">Hold OK</span><span>{d.hold}</span></>}
        </div>
      </> : <h1 className="live-hero__title">Recordings</h1>}
    </section>
    <div className="live-recordings__rows" ref={list} onFocus={onFocus}>
      {loading && !all.length ? <SkeletonRow variant="live"/>
        : error && !all.length ? <ProblemScreen icon="offline" title="Couldn’t load recordings" actions={[{ id: 'recordings/retry', label: 'Try again', onPress: () => Object.values(sections).forEach((q) => void q.refetch()) }]}>{problemWords(error).body}</ProblemScreen>
          : !all.length ? <p className="live-reminders__empty">Nothing is set to record. Hold OK on a programme in the guide and choose Record.</p>
            : <VScroll align="start" pad={0}>
              {rows.map((r) => <div key={r.section} data-vitem className="live-recordings__row">
                <Row id={`rec-${r.section}`} title={SECTION_TITLES[r.section]} subtitle={rowCount(r.section, r.entries.length)} variant="live" items={r.entries} itemKey={entryKey}
                  renderItem={(e, focusId) => {
                    const c = card(e, now)
                    const rec = e.kind === 'recording' ? e.item : undefined
                    return <button type="button" className="live-oncard" data-focus={focusId} aria-label={c.label} onClick={() => { const ok = actions(e)[0]; if (ok) run(e, ok.id) }}>
                      <ProgrammeArt channel={{ number: c.number ?? '', name: rec?.channelName ?? '' }} picture={rec?.pictureUrl} size="card" dim={c.dim}
                        progress={c.progress}>
                        {c.badge && <span className={`live-bdg live-bdg--${c.badge.tone}`}>{c.badge.mark && <span className={`live-mark live-mark--${c.badge.mark}`} aria-hidden="true"/>}{c.badge.label}</span>}
                      </ProgrammeArt>
                      <span className="live-oncard__title">{c.title}</span>
                      <span className="live-oncard__sub">{c.line}</span>
                    </button>
                  }}/>
              </div>)}
            </VScroll>}
    </div>
    {(note || failed) && <div className="live-note" role="status">{failed ? problemWords(failed).body : note}</div>}
    {menu && <LiveMenu menu={{ title: menu.item.title, subtitle: card(menu, now).line, items: actions(menu), note: menu.kind === 'recording' && (menu.item.state === 'done' || menu.item.state === 'partial') ? 'Deleting removes the file from the server.' : undefined }}
      onPick={(id) => run(menu, id)} onClose={() => setMenu(undefined)}/>}
    {clash && <RecordPanel {...programmeOf(clash)} onClose={() => setClash(undefined)} onNote={setNote} onWatchRecording={() => undefined}/>}
    {editing && <RuleEditor rule={editing} onClose={() => setEditing(undefined)} onNote={setNote}/>}
    {deleting && <Dialog title={`Delete ${deleting.title}?`} onCancel={() => setDeleting(undefined)} actions={[
      { id: 'delete/cancel', label: 'Cancel', safe: true, onPress: () => setDeleting(undefined) },
      { id: 'delete/again', label: dontRecord ? '☑ Don’t record it again' : '☐ Don’t record it again', onPress: () => setDontRecord((v) => !v) },
      { id: 'delete/ok', label: 'Delete recording', variant: 'danger', busy: removeFile.isPending, onPress: () => removeFile.mutate({ id: deleting.id, recordAgain: !dontRecord }, { onSuccess: () => { setNote('Recording deleted.'); setDeleting(undefined) } }) },
    ]}>
      <p className="tv-body">The recorded file is deleted from the server. This can’t be undone.</p>
    </Dialog>}
  </div>
}
