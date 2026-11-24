import { useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import type { LiveChannel, LiveProgramme } from '../../../src/api/liveTV'
import { liveTVRecordingsQuery } from '../../../src/api/queries'
import { cancelRecording, deleteRecordingFile, deleteRecordingRule, updateRecordingRule, type Recording, type RecordingRule } from '../../../src/api/recordings'
import { formatBytes } from '../../../src/lib/format'
import { recordingProgress, type RecordingSection } from '../../../src/lib/liveRecordings'
import { useEntry, useFocus, useHold, useMediaUrls, useNav } from '../../app/hooks'
import { Button } from '../../ui/Button'
import { Card } from '../../ui/Card'
import { Icon } from '../../ui/Icon'
import { showNote } from '../../ui/notes'
import { Dialog, Modal, Option, PanelLine, SidePanel } from '../../ui/Overlays'
import { problemWords } from '../../ui/problemWords'
import { ProblemScreen, SkeletonRow } from '../../ui/States'
import { Row } from '../../ui/Row'
import { VScroll } from '../../ui/VScroll'
import { useNow } from './clock'
import { useRecordingAction } from './liveData'
import { LiveIcon } from './LiveIcon'
import { LiveTabs, type TabsProps } from './LiveTabs'
import { useStartFocus, useTabKeys } from './tabFocus'
import { OptionsPage, RecordPanel } from './RecordPanel'
import { ruleBody, ruleOptions, seriesWords, type RecordOptions } from './recordModel'
import {
  actions, cardLine, clashAlert, clashCount, episodeWords, isNewRecording, playRequest, recordedShows, recordingNowWords, roomWords, scheduledDays,
  type Entry, type RecordingAction, type RecordingsTab, type ShowGroup,
} from './recordingsModel'
import { BACK } from '../../app/keys'

/** The sub-tab each Recordings in the stack was on. */
const kept = new WeakMap<object, RecordingsTab>()

function useSection(section: RecordingSection) {
  return useInfiniteQuery(liveTVRecordingsQuery(section))
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
function RuleEditor({ rule, onClose }: { rule: RecordingRule; onClose(): void }) {
  const [options, setOptions] = useState<RecordOptions>(() => ruleOptions(rule))
  const save = useRecordingAction(() => updateRecordingRule(rule.id, ruleBody(rule, options)))
  return <Modal onClose={onClose} className="">
    <aside role="dialog" aria-modal="true" aria-label={`Edit ${rule.title}`} className="tv-panel live-panel">
      <div className="live-panel__head"><span className="live-panel__titles">
        <span className="live-panel__eyebrow live-panel__eyebrow--rec">{rule.kind === 'manual' ? 'Repeating recording' : 'Edit series'}</span>
        <h2 className="live-panel__title">{rule.title}</h2>
        <span className="live-panel__sub">{seriesWords(rule)}</span>
      </span></div>
      <OptionsPage series={rule.kind === 'series'} number={rule.number ?? ''} options={options} onChange={setOptions} onBack={onClose} busy={save.isPending}
        saveLabel="Save options" onSave={() => save.mutate(undefined, { onSuccess: () => { showNote('Options saved. They apply to what records next.', 'check'); onClose() } })}/>
      {save.error && <p className="tv-error" role="alert">{problemWords(save.error).body}</p>}
      <p className="live-panel__foot">◀ ▶ change the focused setting. {BACK} closes this panel.</p>
    </aside>
  </Modal>
}

/** "Season 2 · Episode 4 · Tuesday · 1 hour" under a recorded episode, with Play first and Delete one press to the right. */
function ShowPanel({ show, rule, onPlay, onDelete, onRule, onClose }: {
  show: ShowGroup; rule?: RecordingRule; onPlay(r: Recording): void; onDelete(r: Recording): void; onRule(rule: RecordingRule): void; onClose(): void
}) {
  const now = useNow()
  const n = show.items.length
  return <SidePanel title={show.title} wide onClose={onClose} sub={`${n} ${n === 1 ? 'recording' : 'recordings'} · ${show.latest.number} ${show.latest.channelName}`}
    foot={<p className="live-ppanel__foot">Delete asks first. ▶ reaches it from an episode.</p>}>
    <VScroll pad={16}>
      <div className="live-show__list">
        {show.items.map((r, i) => {
          const w = episodeWords(r, now)
          const plays = r.state === 'done' || r.state === 'partial'
          return <div key={r.id} className="live-show__row" data-row>
            <button type="button" className="tv-option live-row live-show__ep" data-focus={`ep/${r.id}`} data-autofocus={i === 0 || undefined} aria-disabled={!plays || undefined}
              aria-label={`${plays ? 'Play ' : ''}${w.title}, ${w.line}${isNewRecording(r) ? ', new' : ''}`} onClick={plays ? () => onPlay(r) : undefined}>
              <span className="live-row__mark">{plays ? <LiveIcon name="play" className="live-row__icon"/> : <LiveIcon name="warn" className="live-row__icon"/>}</span>
              <span className="tv-option__text"><span className="tv-option__label">{w.title}</span><span className="tv-option__detail">{w.line}</span></span>
              {isNewRecording(r) && <span className="tv-option__end"><span className="tv-badge tv-badge--gold">New</span></span>}
            </button>
            {r.canDelete && <Button id={`ep/${r.id}/delete`} label={`Delete ${w.title}`} icon="trash" onPress={() => onDelete(r)}/>}
          </div>
        })}
        {rule && <>
          <PanelLine/>
          <button type="button" className="tv-option live-row" data-focus="show/rule" aria-disabled={!rule.canCancel || undefined} onClick={rule.canCancel ? () => onRule(rule) : undefined}>
            <span className="live-row__mark"><span className={`live-mark live-mark--${rule.kind === 'manual' ? 'dot' : 'ring'}`} aria-hidden="true"/></span>
            <span className="tv-option__text"><span className="tv-option__label">{rule.kind === 'manual' ? 'Records again on its own' : rule.newOnly ? 'Recording every new episode' : 'Recording every episode'}</span>
              <span className="tv-option__detail">{rule.keep ? `Keeps the latest ${rule.keep}` : 'Keeps them all'}</span></span>
            {rule.canCancel && <span className="tv-option__end">Change<Icon name="right"/></span>}
          </button>
        </>}
      </div>
    </VScroll>
  </SidePanel>
}

/** What can be done with one recording or series: OK's choice first. */
function EntryPanel({ entry, onPick, onClose }: { entry: Entry; onPick(id: RecordingAction): void; onClose(): void }) {
  const now = useNow()
  const list = actions(entry)
  return <SidePanel title={entry.item.title} sub={cardLine(entry, now)} onClose={onClose}
    foot={list.some((a) => a.id === 'delete') ? <p className="live-ppanel__foot">Deleting asks first.</p> : undefined}>
    {list.length ? list.map((a, i) => <Option key={a.id} id={`entry/${a.id}`} label={a.label.replace(/…$/, '')} autoFocus={i === 0} next={a.label.endsWith('…') || a.id === 'editSeries'} onPress={() => onPick(a.id)}/>)
      : <p className="tv-lede tv-muted">Whoever set it, or an admin, can change it.</p>}
  </SidePanel>
}

/** A sub-tab's empty state: what to do instead, with the guide one press away. */
function Empty({ title, body, onGuide }: { title: string; body: string; onGuide(): void }) {
  return <section className="live-empty">
    <h1 className="tv-title">{title}</h1>
    <p className="tv-lede">{body}</p>
    <div className="tv-actions live-empty__actions"><Button id="recordings/guide" variant="primary" autoFocus onPress={onGuide}>Open the guide</Button></div>
  </section>
}

/** Focus starts here when the screen opens without a memory: the first card or row. */
function Start({ on, children }: { on: boolean; children: ReactNode }) {
  return on ? <span className="live-start" data-autofocus>{children}</span> : <>{children}</>
}

/**
 * Recordings: Ready to watch (what records now, then what has recorded,
 * one card a show), Scheduled (a clash on top with its fix, then what will
 * record by day) and Series, with the room left at the right. OK plays, or
 * opens a show's recordings or a recording's choices; Hold OK opens the
 * same choices. Nothing is reachable only by holding OK.
 */
export function RecordingsBody({ tabs }: { tabs: TabsProps }) {
  const nav = useNav()
  const media = useMediaUrls()
  const now = useNow()
  const entry = useEntry()
  const owner = entry?.memory
  const [sub, setSubState] = useState<RecordingsTab>(() => (owner && kept.get(owner)) || 'ready')
  const setSub = (t: RecordingsTab) => { if (owner) kept.set(owner, t); setSubState(t) }
  const sections = { now: useSection('now'), conflicts: useSection('conflicts'), upcoming: useSection('upcoming'), series: useSection('series'), recorded: useSection('recorded') }
  const items = (s: RecordingSection) => sections[s].data?.pages.flatMap((p) => p.items) ?? []
  const nowItems = items('now'), conflicts = items('conflicts'), upcoming = items('upcoming'), recorded = items('recorded')
  const rules = useMemo(() => sections.series.data?.pages.flatMap((p) => p.rules ?? []) ?? [], [sections.series.data])
  const shows = useMemo(() => recordedShows(recorded, now), [recorded, now])
  const days = useMemo(() => scheduledDays([...conflicts, ...upcoming], rules, now), [conflicts, upcoming, rules, now])
  const page = sections.recorded.data?.pages[0] ?? sections.now.data?.pages[0]
  const room = roomWords(page?.freeBytes, recorded, formatBytes)
  const clashes = clashCount(conflicts)
  const firstClash = conflicts[0]

  const [show, setShow] = useState<string>()
  const [picked, setPicked] = useState<Entry>()
  const [clash, setClash] = useState<Recording>()
  const [editing, setEditing] = useState<RecordingRule>()
  const [deleting, setDeleting] = useState<Recording>()
  // A dialog over a panel: closing it puts focus back in the panel, on this id once it is drawn.
  const refocus = useRef<string>(undefined)
  const focus = useFocus()
  useLayoutEffect(() => {
    const id = refocus.current
    if (id && focus.focusId(id, focus.scope('modal'))) refocus.current = undefined
  })
  const keep = () => { if (deleting) refocus.current = `ep/${deleting.id}/delete`; setDeleting(undefined) }
  const cancel = useRecordingAction(cancelRecording)
  const removeRule = useRecordingAction(deleteRecordingRule)
  const removeFile = useRecordingAction(deleteRecordingFile)
  const failed = cancel.error ?? removeRule.error ?? removeFile.error
  useEffect(() => { if (failed) showNote(problemWords(failed).body, 'alert') }, [failed])
  const openShow = show ? shows.find((s) => s.key === show) : undefined

  const play = (r: Recording) => { const req = playRequest(r); if (req) nav.play(req) }
  const run = (e: Entry, id: RecordingAction) => {
    setPicked(undefined)
    const r = e.kind === 'recording' ? e.item : undefined
    const rule = e.kind === 'rule' ? e.item : rules.find((x) => x.id === r?.ruleId)
    switch (id) {
      case 'watch': case 'watchStart': if (r) play(r); return
      case 'instead': if (r) setClash(r); return
      case 'stop': if (r) cancel.mutate(r.id, { onSuccess: () => showNote(`Stopped. What recorded of ${r.title} is kept.`, 'check') }); return
      case 'cancel': if (r) cancel.mutate(r.id, { onSuccess: () => showNote(`${r.title} won’t record.`, 'check') }); return
      case 'editSeries': if (rule) setEditing(rule); return
      case 'deleteSeries': if (rule) removeRule.mutate(rule.id, { onSuccess: () => showNote(`${rule.title} won’t record again. What it recorded stays.`, 'check') }); return
      case 'delete': if (r) setDeleting(r); return
      case 'guide': if (r) nav.push({ name: 'liveGuide', channelId: r.channelId, at: Date.parse(r.start) }); return
    }
  }
  // Hold OK on a card or row: the same choices OK leads to.
  const holder = useRef<HTMLDivElement>(null)
  useHold(holder, () => {
    const id = (document.activeElement as HTMLElement | null)?.dataset?.focus ?? ''
    const s = shows.find((x) => id === `rec-done/${x.key}`)
    if (s) { setShow(s.key); return }
    const r = [...nowItems, ...conflicts, ...upcoming].find((x) => id.endsWith(x.id))
    if (r) setPicked({ kind: 'recording', section: r.state === 'recording' ? 'now' : r.state === 'conflict' ? 'conflicts' : 'upcoming', item: r })
  })

  const loading = Object.values(sections).some((q) => q.isPending)
  useStartFocus(!loading, tabs.chosen)
  useTabKeys('recordings', `rec-tab/${sub}`)
  const error = Object.values(sections).find((q) => q.error)?.error
  const toGuide = () => tabs.onTab('guide')
  const subTabs: { id: RecordingsTab; label: string; extra?: string }[] = [{ id: 'ready', label: 'Ready to watch' }, { id: 'scheduled', label: 'Scheduled', extra: clashes }, { id: 'series', label: 'Series' }]

  const body = (() => {
    if (loading && !nowItems.length && !recorded.length) return <div className="live-rec__rows"><SkeletonRow variant="wide"/></div>
    if (error && !nowItems.length && !recorded.length && !upcoming.length) {
      return <ProblemScreen title="Couldn’t load recordings" actions={[{ id: 'recordings/retry', label: 'Try again', onPress: () => Object.values(sections).forEach((q) => void q.refetch()) }]}>{problemWords(error).body}</ProblemScreen>
    }
    if (sub === 'ready') {
      if (!nowItems.length && !shows.length) return <Empty title="Nothing recorded yet" body="Find a programme in the guide, press OK on it and choose Record. What you record shows up here, ready to watch." onGuide={toGuide}/>
      return <div className="live-rec__rows">
        {nowItems.length > 0 && <Row id="rec-now" title="Recording now" variant="wide" items={nowItems} itemKey={(r) => r.id}
          renderItem={(r, focusId, i) => <Start on={i === 0}><Card id={focusId} variant="wide" title={r.title} subtitle={recordingNowWords(r, now)} image={media.mediaUrl(r.pictureUrl)}
            badges={[{ label: 'Recording', tone: 'live' }]} progress={recordingProgress(r.start, r.end, now) / 100} label={`${r.title}, recording, ${recordingNowWords(r, now)}. Plays from the start.`}
            onPress={() => play(r)}/></Start>}/>}
        {shows.length > 0 && <Row id="rec-done" title="Recorded" variant="wide" items={shows} itemKey={(s) => s.key}
          renderItem={(s, focusId, i) => <Start on={!nowItems.length && i === 0}><span className={s.playable ? 'live-card' : 'live-card live-card--off'}>
            <Card id={focusId} variant="wide" title={s.title} subtitle={s.line} image={media.mediaUrl(s.latest.pictureUrl)} badges={s.fresh ? [{ label: 'New', tone: 'gold' }] : undefined}
              label={`${s.title}, ${s.line}`} onPress={() => s.items.length === 1 && s.playable ? play(s.items[0]) : setShow(s.key)}/></span></Start>}/>}
      </div>
    }
    if (sub === 'scheduled') {
      if (!days.length) return <Empty title="Nothing scheduled" body="Programmes you choose to record in the guide wait here until they start." onGuide={toGuide}/>
      const alert = firstClash && clashAlert(firstClash, now)
      return <div className="live-sched">
        {alert && <div className="tv-notice tv-notice--warn live-clash" role="alert">
          <span className="live-clash__text"><b>{alert.title}</b><span>{alert.line}</span></span>
          {alert.canChoose && <div data-row><Button id="clash/choose" variant="primary" autoFocus onPress={() => setClash(firstClash)}>Choose which records</Button></div>}
        </div>}
        <div className="live-sched__list"><VScroll pad={24}>
          {days.map((d, di) => <section key={d.day} className="live-sched__day" aria-label={d.day}>
            <h2 className="live-sched__head">{d.day}</h2>
            {d.rows.map((row, ri) => <Start key={row.recording.id} on={!alert?.canChoose && di === 0 && ri === 0}><div data-row>
              <button type="button" className="tv-option live-srow" data-focus={`sched/${row.recording.id}`} aria-label={`${row.time}, ${row.title}, ${row.sub}${row.end ? `, ${row.end}` : ''}`}
                onClick={() => setPicked({ kind: 'recording', section: row.recording.state === 'conflict' ? 'conflicts' : 'upcoming', item: row.recording })}>
                <span className="live-row__mark"><span className={`live-mark live-mark--${row.mark === 'wont' ? 'hollow' : row.mark}`} aria-hidden="true"/></span>
                <b className="live-srow__time">{row.time}</b>
                <span className="tv-option__text"><span className="tv-option__label">{row.title}</span><span className="tv-option__detail">{row.sub}</span></span>
                {row.end && <span className={`tv-option__end${row.mark === 'wont' ? ' live-srow__wont' : ''}`}>{row.end}</span>}
              </button>
            </div></Start>)}
          </section>)}
        </VScroll></div>
      </div>
    }
    if (!rules.length) return <Empty title="No series recording" body="Choose Record every new episode on a programme in the guide, and its series shows up here." onGuide={toGuide}/>
    return <div className="live-sched__list live-sched__list--series"><VScroll pad={24}>
      {rules.map((rule, i) => <Start key={rule.id} on={i === 0}><div data-row>
        <button type="button" className="tv-option live-srow" data-focus={`series/${rule.id}`} onClick={() => setPicked({ kind: 'rule', section: 'series', item: rule })}>
          <span className="live-row__mark"><span className={`live-mark live-mark--${rule.kind === 'manual' ? 'dot' : 'ring'}`} aria-hidden="true"/></span>
          <span className="tv-option__text"><span className="tv-option__label">{rule.title}</span><span className="tv-option__detail">{cardLine({ kind: 'rule', section: 'series', item: rule }, now)}{rule.userName ? ` · set by ${rule.userName}` : ''}</span></span>
          {rule.canCancel && <span className="tv-option__end"><Icon name="right"/></span>}
        </button>
      </div></Start>)}
    </VScroll></div>
  })()

  return <div className="live-recordings" ref={holder}>
    <LiveTabs {...tabs}/>
    <div className="live-subtabs">
      {subTabs.map((t) => <button key={t.id} type="button" className="live-tab live-tab--sub" data-focus={`rec-tab/${t.id}`} aria-current={t.id === sub ? 'page' : undefined}
        aria-label={t.extra ? `${t.label}, ${t.extra}` : t.label} onClick={() => setSub(t.id)}>{t.label}{t.extra && <span className="live-subtabs__warn">{t.extra}</span>}</button>)}
      {room && <span className="live-subtabs__room">{room}</span>}
    </div>
    <div className="live-rec__body live-body">{body}</div>
    {openShow && <ShowPanel show={openShow} rule={rules.find((x) => x.id === openShow.latest.ruleId)} onClose={() => setShow(undefined)}
      onPlay={(r) => { setShow(undefined); play(r) }} onDelete={setDeleting} onRule={setEditing}/>}
    {picked && <EntryPanel entry={picked} onPick={(id) => run(picked, id)} onClose={() => setPicked(undefined)}/>}
    {clash && <RecordPanel {...programmeOf(clash)} clash={clash} onClose={() => setClash(undefined)} onNote={(text) => showNote(text, 'check')} onWatchRecording={() => undefined}/>}
    {editing && <RuleEditor rule={editing} onClose={() => setEditing(undefined)}/>}
    {deleting && <Dialog title={`Delete ${deleting.episodeTitle || deleting.title}?`} narrow onCancel={keep} actions={[
      { id: 'delete/keep', label: 'Keep', safe: true, onPress: keep },
      { id: 'delete/ok', label: 'Delete', variant: 'danger', busy: removeFile.isPending,
        onPress: () => removeFile.mutate({ id: deleting.id, recordAgain: false }, { onSuccess: () => {
          showNote('Recording deleted.', 'check')
          const items = openShow?.items ?? []
          const i = items.findIndex((r) => r.id === deleting.id)
          const neighbour = items[i + 1] ?? items[i - 1]
          refocus.current = neighbour ? `ep/${neighbour.id}` : undefined
          setDeleting(undefined)
          if (!neighbour) setShow(undefined)
        } }) },
    ]}>
      <p className="tv-lede">The recording is deleted from the server, and it won’t record again. This can’t be undone.</p>
    </Dialog>}
  </div>
}
