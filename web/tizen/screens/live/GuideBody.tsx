import { useEffect, useLayoutEffect, useMemo, useRef, useState, type FocusEvent, type MouseEvent } from 'react'
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import type { LiveGuideChannel } from '../../../src/api/liveTV'
import { liveTVGuideQuery, liveTVRemindersQuery, useLiveFavourite, useLiveReminder } from '../../../src/api/queries'
import { createRecording, type Recording } from '../../../src/api/recordings'
import {
  BLOCK, FAVOURITES, FETCH_HOURS, HOUR, availableFilters, clampStart, dayIndex, dayStart, fetchBlock, guideDays, guideRows, halfHourStart, isOn, latestStart, showFavouritesChip,
} from '../../../src/lib/liveGuide'
import { leadMinutes } from '../../../src/lib/liveReminders'
import { useEntry, useFocus, useHold, useKeys, useNav, useSession } from '../../app/hooks'
import { Button } from '../../ui/Button'
import { Icon } from '../../ui/Icon'
import { showNote } from '../../ui/notes'
import { Option, SidePanel } from '../../ui/Overlays'
import { problemWords } from '../../ui/problemWords'
import { ProblemScreen, Skeleton, SkeletonText } from '../../ui/States'
import { NoTuner } from './ChannelTiles'
import { useNow } from './clock'
import { GoToChannelDialog } from './GoToChannel'
import { GuideHeader, GuideRow } from './GuideGrid'
import { GuideStrip } from './GuideHero'
import {
  GUIDE_HOURS, VISIBLE_ROWS, cellAt, dayHeading, firstRow, guideStep, nowFraction, pageStep, parseTarget, programmePanel, slotLabels, stripFor, targetId,
  type GuideTarget, type PanelRowId,
} from './guideModel'
import { useRecordingAction } from './liveData'
import { lastChannel } from './livePlayback'
import { numberKey } from './liveRemote'
import { LiveTabs, type TabsProps } from './LiveTabs'
import { useStartFocus } from './tabFocus'
import { ProgrammePanel } from './ProgrammePanel'
import { RecordPanel } from './RecordPanel'
import { DEFAULT_OPTIONS, createBody, recordedNote } from './recordModel'

type View = { start: number; filter: string; target?: GuideTarget; anchor: number }
/** Each guide in the stack keeps its window, filter and focus while a player or page is on top of it. */
const views = new WeakMap<object, View>()

const ms = (iso: string) => Date.parse(iso)

/** Which cell has focus, kept to what the rows show now: a filtered-out channel or a programme out of view gives way. */
function resolve(target: GuideTarget | undefined, rows: LiveGuideChannel[], start: number, anchor: number, prefer?: string): GuideTarget | undefined {
  const end = start + GUIDE_HOURS * HOUR
  const row = rows.find((r) => r.id === target?.channelId) ?? rows.find((r) => r.id === prefer) ?? rows.find((r) => r.favourite) ?? rows[0]
  if (!row) return undefined
  const at = Math.min(Math.max(anchor, start), end - 1)
  if (!target || target.channelId !== row.id || target.kind === 'channel') return cellAt(row, at, start, end)
  if (target.kind !== 'programme') return target.kind === 'none' && row.listings ? cellAt(row, at, start, end) : target
  const p = row.programmes.find((x) => x.id === target.programmeId)
  return p && ms(p.end) > start && ms(p.start) < end ? target : cellAt(row, at, start, end)
}

type Panel = { kind: 'programme'; channelId: string; programmeId: string } | { kind: 'record'; channelId: string; programmeId: string; clash?: Recording } | { kind: 'filter' } | { kind: 'day' }

/**
 * The guide: the tabs with Favourites, the day and Go to channel, the
 * focused programme in a two-line strip, then two hours across six
 * channels. ◀ ▶ go programme by programme and move the window at its edge;
 * ▲ ▼ keep to the same time and stop at the first and last channel (▲ from
 * the first goes to the tabs); ◀ from the first programme opens the menu.
 * OK on what is on now watches it; OK on anything later opens its panel
 * (Record, Remind me, Watch the channel now, its favourite), which Hold OK
 * opens on any programme. The number keys open Go to channel; CH ▲ ▼ move a
 * screen of channels. Only the rows in view are drawn.
 */
export function Guide({ tabs, canRecord, channelId, at }: { tabs: TabsProps; canRecord: boolean; channelId?: string; at?: number }) {
  const nav = useNav()
  const focus = useFocus()
  const entry = useEntry()
  const session = useSession()
  const qc = useQueryClient()
  const now = useNow()
  const saved = entry ? views.get(entry) : undefined
  const [rawStart, setRawStart] = useState(() => saved?.start ?? halfHourStart(at ?? Date.now()))
  const [filter, setFilter] = useState(saved?.filter ?? 'all')
  const [target, setTarget] = useState<GuideTarget | undefined>(saved?.target)
  const [anchor, setAnchor] = useState(() => saved?.anchor ?? at ?? Date.now())
  const [listedThrough, setListedThrough] = useState<number>()
  const [panel, setPanel] = useState<Panel>()
  const [goto, setGoto] = useState<string>()
  const [initial, setInitial] = useState<GuideTarget>()
  const pending = useRef<string>(undefined)
  const grid = useRef<HTMLDivElement>(null)
  // The page is there from the first draw (the grid only once listings are in), so Hold OK is listened for on it.
  const page = useRef<HTMLDivElement>(null)

  const earliest = halfHourStart(now)
  // Until a reply says where the listings end, the window may go where it was asked.
  const latest = listedThrough === undefined ? Infinity : latestStart(earliest, listedThrough, GUIDE_HOURS)
  const start = clampStart(rawStart, earliest, latest)
  const end = start + GUIDE_HOURS * HOUR
  const block = fetchBlock(start)
  const guide = useQuery({ ...liveTVGuideQuery(block, FETCH_HOURS), placeholderData: keepPreviousData })
  const reminders = useQuery(liveTVRemindersQuery)
  const favourite = useLiveFavourite()
  const remind = useLiveReminder()
  const create = useRecordingAction(createRecording)
  const channels = useMemo(() => guide.data?.channels ?? [], [guide.data])
  const listed = ms(guide.data?.state.listedThrough ?? '')
  // Where the listings end, kept across window moves (a reply for a later block still knows it).
  if (Number.isFinite(listed) && listed !== listedThrough) setListedThrough(listed)
  // The next block, once the window is within an hour of needing it.
  useEffect(() => { if (start + GUIDE_HOURS * HOUR > block + BLOCK) void qc.prefetchQuery(liveTVGuideQuery(block + BLOCK, FETCH_HOURS)) }, [qc, block, start])

  const rows = useMemo(() => guideRows(channels, filter, start, end), [channels, filter, start, end])
  const navRows = useMemo(() => rows.filter((r) => r.available), [rows])
  const current = resolve(target, navRows, start, anchor, channelId ?? lastChannel())
  // Where focus starts, fixed the first time the rows draw.
  if (!initial && current) setInitial(current)
  useEffect(() => { if (entry) views.set(entry, { start: rawStart, filter, target: current, anchor }) })
  const focusIndex = Math.max(0, rows.findIndex((r) => r.id === current?.channelId))
  const first = firstRow(focusIndex, rows.length, VISIBLE_ROWS)
  const shown = rows.slice(first, first + VISIBLE_ROWS)

  const days = useMemo(() => guideDays(now, listedThrough), [now, listedThrough])
  const day = dayIndex(days, start)
  const filters = [
    ...(showFavouritesChip(channels, filter) ? [{ id: FAVOURITES, label: 'Favourites' }] : []),
    { id: 'all', label: 'All channels' },
    ...availableFilters(channels).map((f) => ({ id: f.id, label: f.label })),
  ]
  const filterLabel = filters.find((f) => f.id === filter)?.label ?? 'All channels'

  const lead = leadMinutes(reminders.data?.leadMinutes)
  const reminderFor = (programmeId: string) => reminders.data?.items.find((r) => r.programmeId === programmeId)
  const channelOf = (id?: string) => channels.find((c) => c.id === id)
  const programmeOf = (t?: { channelId: string; programmeId?: string }) => t?.programmeId ? channelOf(t.channelId)?.programmes.find((p) => p.id === t.programmeId) : undefined
  const focusedChannel = channelOf(current?.channelId)
  const focusedProgramme = current?.kind === 'programme' ? programmeOf(current) : undefined
  const ctx = (favourite: boolean, programmeId?: string) => ({ now, favourite, reminder: programmeId ? reminderFor(programmeId) : undefined, lead, canRecord })
  const strip = focusedChannel && stripFor(focusedChannel, focusedProgramme, ctx(!!focusedChannel.favourite, focusedProgramme?.id))

  const watch = (id: string) => nav.play({ kind: 'live', channelId: id })
  const openPanel = (t: GuideTarget) => { if (t.kind === 'programme') setPanel({ kind: 'programme', channelId: t.channelId, programmeId: t.programmeId }) }

  /** Focus goes to the guide's focused cell (from the tabs, or after a panel). */
  const toCell = () => {
    const id = current ? targetId(current) : undefined
    return !!id && focus.focusId(id, grid.current)
  }

  // The arrows inside the grid are the guide's own; leaving it goes to the tabs above or the menu.
  useKeys((i) => {
    const k = numberKey(i.action, i.digit)
    if (i.action === 'digit' && k) { setGoto(k); return true }
    const el = document.activeElement as HTMLElement | null
    const inGrid = !!el && !!grid.current?.contains(el)
    // ▼ from the tabs or the buttons above goes into the listings, on the cell the guide is at.
    if (i.action === 'down' && !inGrid && el?.closest('.live-tabs')) return toCell() || false
    const t = parseTarget(el?.dataset?.focus)
    if (!t || !inGrid) return false
    if (i.action === 'channelUp' || i.action === 'channelDown') {
      const step = pageStep(navRows, t, i.action === 'channelUp' ? 'up' : 'down', start, anchor)
      if (step) { pending.current = targetId(step.target); setTarget(step.target) }
      return true
    }
    if (i.action !== 'up' && i.action !== 'down' && i.action !== 'left' && i.action !== 'right') return false
    const step = guideStep(navRows, t, i.action, start, anchor, { earliest, latest })
    if (step === undefined) return true
    if (step === 'above') return focus.focusId(`live-tab/${tabs.tab}`) || true
    if (step === 'rail') return focus.focusRail() || true
    pending.current = targetId(step.target)
    setAnchor(step.anchor)
    setRawStart(step.start)
    setTarget(step.target)
    return true
  })
  // Focus follows the step once its cell is drawn.
  useLayoutEffect(() => {
    const id = pending.current
    if (id && focus.focusId(id, grid.current)) pending.current = undefined
  })

  const onFocus = (e: FocusEvent) => {
    const id = (e.target as HTMLElement).dataset?.focus
    const t = parseTarget(id)
    if (!t) return
    if (pending.current !== id) {
      // Focus came from elsewhere (the tabs, a click): ▲ ▼ keep to where this programme starts in view.
      const p = programmeOf(t.kind === 'programme' ? t : undefined)
      setAnchor(p ? Math.max(ms(p.start), start) : Math.max(now, start))
    }
    setTarget(t)
  }
  // OK (or a click): what is on now plays; anything later opens its panel.
  const onClick = (e: MouseEvent) => {
    const t = parseTarget((e.target as HTMLElement).closest<HTMLElement>('[data-focus]')?.dataset.focus)
    if (!t) return
    const p = programmeOf(t.kind === 'programme' ? t : undefined)
    if (p && !isOn(p, now)) openPanel(t)
    else watch(t.channelId)
  }
  // Hold OK: the panel, on what is on now too.
  useHold(page, () => {
    const el = document.activeElement as HTMLElement | null
    const t = parseTarget(el?.dataset?.focus)
    if (t && el && grid.current?.contains(el)) openPanel(t)
  })

  const pickDay = (i: number) => {
    const s = clampStart(dayStart(days[i], now), earliest, latest)
    setRawStart(s)
    setAnchor(s)
    setTarget(undefined)
  }

  const panelChannel = panel && 'channelId' in panel ? channelOf(panel.channelId) : undefined
  const panelProgramme = panel && 'programmeId' in panel ? programmeOf(panel) : undefined
  const onNowTitle = panelChannel?.programmes.find((p) => isOn(p, now))?.title
  const run = (id: PanelRowId) => {
    const c = panelChannel, p = panelProgramme
    if (!c || !p) return
    const close = () => setPanel(undefined)
    switch (id) {
      case 'watch': close(); return watch(c.id)
      case 'favourite': case 'unfavourite':
        close()
        return favourite.mutate({ channelId: c.id, favourite: id === 'favourite' }, { onSuccess: () => showNote(id === 'favourite' ? `${c.number} ${c.name} is a favourite` : `${c.number} ${c.name} is no longer a favourite`, 'check') })
      case 'remind': close(); return remind.mutate({ programmeId: p.id }, { onSuccess: () => showNote(`Reminder set for ${p.title}`, 'bell') })
      case 'forget': { close(); const r = reminderFor(p.id); if (r) remind.mutate({ reminderId: r.id }, { onSuccess: () => showNote('Reminder removed', 'check') }); return }
      case 'recordMore': case 'recordOptions': return setPanel({ kind: 'record', channelId: c.id, programmeId: p.id })
      case 'recordOnce': case 'recordSeries': {
        if (create.isPending) return
        const scope = id === 'recordSeries' ? 'series' : 'once'
        return create.mutate(createBody(p, scope, DEFAULT_OPTIONS), {
          onSuccess: (r) => {
            // A clash: the record panel says who holds the tuners and offers the choice.
            if (r.state === 'conflict') { setPanel({ kind: 'record', channelId: c.id, programmeId: p.id, clash: r }); return }
            close()
            showNote(recordedNote(r, scope, true, Date.now()), 'check')
          },
        })
      }
    }
  }
  useStartFocus(!guide.isPending, tabs.chosen)
  const failed = favourite.error ?? remind.error
  useEffect(() => { if (failed) showNote(problemWords(failed).body, 'alert') }, [failed])

  const noTuner = channels.length > 0 && !channels.some((c) => c.available)
  const header = <LiveTabs {...tabs}>
    {!noTuner && <>
    <Button id="guide/filter" onPress={() => setPanel({ kind: 'filter' })} label={`Channels shown: ${filterLabel}`}>{filterLabel}<Icon name="down"/></Button>
    <Button id="guide/day" onPress={() => setPanel({ kind: 'day' })} label={`Day: ${days[day]?.label ?? 'Today'}`}>{days[day]?.label ?? 'Today'}<Icon name="down"/></Button>
    </>}
    <Button id="guide/goto" onPress={() => setGoto('')}>Go to channel</Button>
  </LiveTabs>

  if (guide.isPending) return <div ref={page} className="live-guide" aria-busy="true">
    {header}
    <div className="live-strip"><Skeleton width="16rem" height="9rem"/><span className="live-strip__text"><SkeletonText width="30rem"/><SkeletonText width="24rem"/><SkeletonText width="40rem"/></span></div>
    <div className="live-guide__grid"><Skeleton width="100%" height="40rem"/></div>
  </div>
  if (guide.error && !guide.data) return <div ref={page} className="live-guide">
    {header}
    <ProblemScreen title="Couldn’t load the listings" actions={[{ id: 'guide/retry', label: 'Try again', onPress: () => void guide.refetch() }]}>{problemWords(guide.error).body}</ProblemScreen>
  </div>
  if (noTuner) return <div ref={page} className="live-guide">
    {header}
    <div className="live-body"><NoTuner server={session.server.name} canRecord={canRecord} onRetry={() => void guide.refetch()} onRecordings={() => tabs.onTab('recordings')}/></div>
  </div>
  const frac = nowFraction(start, now)
  const autofocus = initial ? targetId(initial) : undefined
  return <div ref={page} className="live-guide">
    {header}
    {strip && focusedChannel && <GuideStrip strip={strip} channel={focusedChannel} programme={focusedProgramme} stale={guide.data?.state.stale}/>}
    <div className="live-guide__grid live-body" ref={grid} role="region" aria-label="Guide" onFocus={onFocus} onClick={onClick}>
      <GuideHeader day={dayHeading(start, now)} slots={slotLabels(start)} now={frac}/>
      <div className="live-guide__rows">
        {shown.map((r, i) => <GuideRow key={r.id} row={r} index={i} start={start} now={now} favourite={!!r.favourite} canRecord={canRecord} autofocus={initial?.channelId === r.id ? autofocus : undefined}/>)}
        {frac !== undefined && shown.length > 0 && <span className="live-nowline" aria-hidden="true" style={{ left: `calc(var(--live-chc-w) + var(--live-track-w) * ${frac.toFixed(4)})` }}/>}
        {!rows.length && <div className="live-guide__empty">
          <p className="tv-lede">{filter === FAVOURITES ? 'No favourite channels yet. Press OK on a later programme and choose Add to favourites.' : 'No channel shows this now.'}</p>
          <Button id="guide/all" variant="primary" autoFocus onPress={() => { setFilter('all'); setTarget(undefined) }}>Show all channels</Button>
        </div>}
      </div>
    </div>
    {panel?.kind === 'programme' && panelChannel && panelProgramme && <ProgrammePanel channel={panelChannel} programme={panelProgramme} busy={create.isPending}
      view={programmePanel(panelChannel, panelProgramme, { ...ctx(!!panelChannel.favourite, panelProgramme.id), onNow: onNowTitle })} error={create.error}
      onPick={run} onClose={() => setPanel(undefined)}/>}
    {panel?.kind === 'record' && panelChannel && panelProgramme && <RecordPanel channel={panelChannel} programme={panelProgramme} clash={panel.clash}
      onClose={() => setPanel(undefined)} onNote={(text) => showNote(text, 'check')}
      onWatchRecording={(id, title) => { setPanel(undefined); nav.play({ kind: 'recording', id, startMs: 0, title }) }}/>}
    {panel?.kind === 'filter' && <SidePanel title="Show channels" sub="Which channels the guide lists" onClose={() => setPanel(undefined)}>
      {filters.map((f) => <Option key={f.id} id={`filter/${f.id}`} label={f.label} checked={f.id === filter}
        detail={f.id === FAVOURITES ? 'The channels you starred' : undefined} onPress={() => { setPanel(undefined); setFilter(f.id); setTarget(undefined) }}/>)}
    </SidePanel>}
    {panel?.kind === 'day' && <SidePanel title="Day" sub="Listings run as far as the server has them" onClose={() => setPanel(undefined)}>
      {days.map((d, i) => <Option key={d.start} id={`day/${i}`} label={d.label === d.short ? new Intl.DateTimeFormat(undefined, { weekday: 'long' }).format(d.start) : d.label} checked={i === day}
        onPress={() => { setPanel(undefined); pickDay(i) }}/>)}
    </SidePanel>}
    {goto !== undefined && <GoToChannelDialog channels={channels} initial={goto} now={now} onWatch={(c) => { setGoto(undefined); watch(c.id) }} onClose={() => setGoto(undefined)}/>}
  </div>
}
