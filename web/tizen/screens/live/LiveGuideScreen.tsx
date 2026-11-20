import { useEffect, useLayoutEffect, useMemo, useRef, useState, type FocusEvent, type MouseEvent } from 'react'
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import type { LiveGuideChannel } from '../../../src/api/liveTV'
import { liveTVGuideQuery, liveTVRemindersQuery, useLiveFavourite, useLiveReminder } from '../../../src/api/queries'
import {
  BLOCK, FAVOURITES, FETCH_HOURS, HOUR, availableFilters, clampStart, dayIndex, dayStart, fetchBlock, guideDays, guideRows, halfHourStart, isOn, latestStart, showFavouritesChip,
} from '../../../src/lib/liveGuide'
import { leadMinutes } from '../../../src/lib/liveReminders'
import { useEntry, useFocus, useHold, useKeys, useNav } from '../../app/hooks'
import type { ScreenProps } from '../../app/routes'
import { problemWords } from '../../ui/problemWords'
import { ProblemScreen, Skeleton, SkeletonText } from '../../ui/States'
import { useNow } from './clock'
import { GoToChannelDialog } from './GoToChannel'
import { GuideHeader, GuideRow } from './GuideGrid'
import { GuideHero } from './GuideHero'
import {
  GUIDE_HOURS, VISIBLE_ROWS, cellAt, channelMenu, firstRow, guideStep, heroFor, nowFraction, parseTarget, programmeMenu, slotLabels, targetId, type GuideTarget, type MenuAction,
} from './guideModel'
import { useLiveTV } from './liveData'
import { LiveGate } from './LiveGate'
import { LiveIcon } from './LiveIcon'
import { LiveMenu } from './LiveMenu'
import { lastChannel } from './livePlayback'
import { numberKey } from './liveRemote'
import { LiveScreen } from './LiveScreen'
import { clockWords } from './liveWords'
import { RecordPanel } from './RecordPanel'
import './live.css'

type View = { start: number; filter: string; target?: GuideTarget; anchor: number }
/** Each guide in the stack keeps its window, filter and focus while a player or page is on top of it. */
const views = new WeakMap<object, View>()

const ms = (iso: string) => Date.parse(iso)
const remPx = () => window.innerWidth / 120

/** The guide's route: listings when the server has them, else Live TV's channels. */
export function LiveGuideScreen({ route }: ScreenProps<'liveGuide'>) {
  const tv = useLiveTV()
  if (!tv.available || !tv.channels.data?.items.length) return <div className="tv-page live-home"><LiveGate {...tv}/></div>
  if (!tv.listings) return <LiveScreen/>
  return <Guide route={route} canRecord={tv.canRecord}/>
}

/** Which cell has focus, kept to what the rows show now: a filtered-out channel or a programme out of view gives way. */
function resolve(target: GuideTarget | undefined, rows: LiveGuideChannel[], start: number, anchor: number, prefer?: string): GuideTarget | undefined {
  const end = start + GUIDE_HOURS * HOUR
  const row = rows.find((r) => r.id === target?.channelId) ?? rows.find((r) => r.id === prefer) ?? rows[0]
  if (!row) return undefined
  const at = Math.min(Math.max(anchor, start), end - 1)
  if (!target || target.channelId !== row.id) return cellAt(row, at, start, end)
  if (target.kind !== 'programme') return target.kind === 'none' && row.listings ? cellAt(row, at, start, end) : target
  const p = row.programmes.find((x) => x.id === target.programmeId)
  return p && ms(p.end) > start && ms(p.start) < end ? target : cellAt(row, at, start, end)
}

/**
 * The guide (LiveGuide on the canvas): the focused programme's hero, then
 * Favourites, All channels and the categories, the days, Recordings,
 * Reminders and Go to channel, then two hours across seven channels.
 * ◀ ▶ go programme by programme and move the window at its edge; ▲ ▼ keep
 * to the same time; OK watches; Hold OK on a channel stars it, on a
 * programme opens Record, Remind me and Watch. The number keys open Go to
 * channel. Only the rows in view are drawn.
 */
function Guide({ route, canRecord }: { route: ScreenProps<'liveGuide'>['route']; canRecord: boolean }) {
  const nav = useNav()
  const focus = useFocus()
  const entry = useEntry()
  const qc = useQueryClient()
  const now = useNow()
  const saved = entry ? views.get(entry) : undefined
  const [rawStart, setRawStart] = useState(() => saved?.start ?? halfHourStart(route.at ?? Date.now()))
  const [filter, setFilter] = useState(saved?.filter ?? 'all')
  const [target, setTarget] = useState<GuideTarget | undefined>(saved?.target)
  const [anchor, setAnchor] = useState(() => saved?.anchor ?? route.at ?? Date.now())
  const [listedThrough, setListedThrough] = useState<number>()
  const [menu, setMenu] = useState<{ target: GuideTarget; at: { left: number; top: number } }>()
  const [record, setRecord] = useState<{ channelId: string; programmeId: string }>()
  const [goto, setGoto] = useState<string>()
  const [note, setNote] = useState<string>()
  const [initial, setInitial] = useState<GuideTarget>()
  const pending = useRef<string>(undefined)
  const grid = useRef<HTMLDivElement>(null)

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
  const channels = useMemo(() => guide.data?.channels ?? [], [guide.data])
  const listed = ms(guide.data?.state.listedThrough ?? '')
  // Where the listings end, kept across window moves (a reply for a later block still knows it).
  if (Number.isFinite(listed) && listed !== listedThrough) setListedThrough(listed)
  // The next block, once the window is within an hour of needing it.
  useEffect(() => { if (start + GUIDE_HOURS * HOUR > block + BLOCK) void qc.prefetchQuery(liveTVGuideQuery(block + BLOCK, FETCH_HOURS)) }, [qc, block, start])

  const rows = useMemo(() => guideRows(channels, filter, start, end), [channels, filter, start, end])
  const navRows = useMemo(() => rows.filter((r) => r.available), [rows])
  const current = resolve(target, navRows, start, anchor, route.channelId ?? lastChannel())
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

  const lead = leadMinutes(reminders.data?.leadMinutes)
  const reminderFor = (programmeId: string) => reminders.data?.items.find((r) => r.programmeId === programmeId)
  const channelOf = (id?: string) => channels.find((c) => c.id === id)
  const programmeOf = (t?: GuideTarget) => t?.kind === 'programme' ? channelOf(t.channelId)?.programmes.find((p) => p.id === t.programmeId) : undefined
  const heroChannel = channelOf(current?.channelId)
  const heroProgramme = current?.kind === 'programme' ? programmeOf(current) : current?.kind === 'channel' ? heroChannel?.programmes.find((p) => isOn(p, now)) : undefined
  const hero = heroChannel && (() => {
    const h = heroFor(heroChannel, heroProgramme, { now, favourite: !!heroChannel.favourite, reminder: heroProgramme && reminderFor(heroProgramme.id), lead, canRecord })
    // On a channel's own cell, Hold OK is its favourite and OK watches it live.
    return current?.kind === 'channel' ? { ...h, hold: heroChannel.favourite ? 'Remove from favourites' : 'Add to favourites', watch: `Watch ${heroChannel.number}`, later: undefined } : h
  })()

  const watch = (channelId: string) => nav.play({ kind: 'live', channelId })

  // The arrows inside the grid are the guide's own; leaving it goes to the chips above or the rail.
  useKeys((i) => {
    const k = numberKey(i.action, i.digit)
    if (i.action === 'digit' && k) { setGoto(k); return true }
    if (i.action !== 'up' && i.action !== 'down' && i.action !== 'left' && i.action !== 'right') return false
    const el = document.activeElement as HTMLElement | null
    const t = parseTarget(el?.dataset?.focus)
    if (!t || !el || !grid.current?.contains(el)) return false
    const step = guideStep(navRows, t, i.action, start, anchor, { earliest, latest })
    if (step === undefined) return true
    if (step === 'above' || step === 'rail') return false
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
      // Focus came from elsewhere (the chips, a click): ▲ ▼ keep to where this programme starts in view.
      const p = programmeOf(t)
      setAnchor(p ? Math.max(ms(p.start), start) : Math.max(now, start))
    }
    setTarget(t)
  }
  const onClick = (e: MouseEvent) => {
    const t = parseTarget((e.target as HTMLElement).closest<HTMLElement>('[data-focus]')?.dataset.focus)
    if (t) watch(t.channelId)
  }
  useHold(grid, () => {
    const el = document.activeElement as HTMLElement | null
    const t = parseTarget(el?.dataset?.focus)
    if (!t || !el) return
    if (t.kind === 'none') return
    const r = el.getBoundingClientRect(), px = remPx()
    const items = t.kind === 'channel' ? 2 : 3
    const height = (110 + items * 82 + 50) * px / 16
    const left = Math.min(Math.max(r.left / px, 26.25), 116.375 - 32.5)
    const top = r.top - height - 14 * px / 16 >= 300 * px / 16 ? (r.top - height) / px - .875 : r.bottom / px + .875
    setMenu({ target: t, at: { left, top } })
  })

  useEffect(() => {
    if (!note) return
    const timer = setTimeout(() => setNote(undefined), 4000)
    return () => clearTimeout(timer)
  }, [note])

  const pickDay = (i: number) => {
    const s = clampStart(dayStart(days[i], now), earliest, latest)
    setRawStart(s)
    setAnchor(s)
    setTarget(undefined)
  }

  const menuChannel = channelOf(menu?.target.channelId)
  const menuProgramme = programmeOf(menu?.target)
  const menuView = menuChannel && (menu?.target.kind === 'channel' ? channelMenu(menuChannel, !!menuChannel.favourite)
    : menuProgramme ? programmeMenu(menuChannel, menuProgramme, { now, favourite: !!menuChannel.favourite, reminder: reminderFor(menuProgramme.id), lead, canRecord }) : undefined)
  const pick = (id: MenuAction) => {
    const c = menuChannel, p = menuProgramme
    setMenu(undefined)
    if (!c) return
    switch (id) {
      case 'watch': return watch(c.id)
      case 'favourite': case 'unfavourite': return favourite.mutate({ channelId: c.id, favourite: id === 'favourite' }, { onSuccess: () => setNote(id === 'favourite' ? `${c.number} ${c.name} is a favourite` : `${c.number} ${c.name} is no longer a favourite`) })
      case 'record': case 'recordOptions': if (p) setRecord({ channelId: c.id, programmeId: p.id }); return
      case 'remind': if (p) remind.mutate({ programmeId: p.id }, { onSuccess: () => setNote(`Reminder set for ${p.title}`) }); return
      case 'forget': { const r = p && reminderFor(p.id); if (r) remind.mutate({ reminderId: r.id }, { onSuccess: () => setNote('Reminder removed') }); return }
    }
  }
  const recordChannel = channelOf(record?.channelId)
  const recordProgramme = recordChannel?.programmes.find((p) => p.id === record?.programmeId)
  const failed = favourite.error ?? remind.error

  if (guide.isPending) return <div className="tv-page live-guide" aria-busy="true">
    <div className="live-hero"><SkeletonText width="16rem"/><Skeleton width="40rem" height="3.5rem"/><SkeletonText width="30rem"/></div>
    <div className="live-guide__grid"><Skeleton width="100%" height="34rem"/></div>
  </div>
  if (guide.error && !guide.data) return <div className="tv-page live-guide">
    <ProblemScreen icon="offline" title="Couldn’t load listings" actions={[{ id: 'guide/retry', label: 'Try again', onPress: () => void guide.refetch() }]}>{problemWords(guide.error).body}</ProblemScreen>
  </div>
  const frac = nowFraction(start, now)
  const autofocus = initial ? targetId(initial) : undefined
  return <div className="tv-page live-guide">
    <GuideHero hero={hero}/>
    <div className="live-guide__clock">
      {guide.data?.state.stale && <span className="live-guide__stale"><LiveIcon name="clock"/>Listings may be out of date</span>}
      <b>{clockWords(now)}</b>
    </div>
    <div className="live-guide__chips">
      <div className="live-chips" role="group" aria-label="Show channels with">
        {filters.map((f) => <button key={f.id} type="button" className="live-chip" data-focus={`guide-chip/${f.id}`} aria-pressed={f.id === filter}
          onClick={() => { setFilter(f.id); setTarget(undefined) }}>{f.id === FAVOURITES && <LiveIcon name={f.id === filter ? 'starFilled' : 'star'}/>}{f.label}</button>)}
      </div>
      <span className="live-sep" aria-hidden="true"/>
      <div className="live-chips" role="group" aria-label="Day">
        {days.map((d, i) => <button key={d.start} type="button" className="live-chip" data-focus={`guide-day/${i}`} aria-pressed={i === day} onClick={() => pickDay(i)}>{d.label}</button>)}
      </div>
      <span className="live-guide__spacer"/>
      {canRecord && <button type="button" className="live-chip" data-focus="guide/recordings" onClick={() => nav.push({ name: 'recordings' })}><LiveIcon name="record"/>Recordings</button>}
      <button type="button" className="live-chip" data-focus="guide/reminders" onClick={() => nav.push({ name: 'reminders' })}><LiveIcon name="bell"/>Reminders</button>
      <button type="button" className="live-chip" data-focus="guide/goto" onClick={() => setGoto('')}><LiveIcon name="keypad"/>Go to channel</button>
    </div>
    <div className="live-guide__grid" ref={grid} role="region" aria-label="Guide" onFocus={onFocus} onClick={onClick}>
      <GuideHeader day={days[day]?.label ?? ''} slots={slotLabels(start)}/>
      <div className="live-guide__rows">
        {shown.map((r) => <GuideRow key={r.id} row={r} start={start} now={now} favourite={!!r.favourite} canRecord={canRecord} autofocus={initial?.channelId === r.id ? autofocus : undefined}/>)}
        {frac !== undefined && shown.length > 0 && <span className="live-nowline" aria-hidden="true" style={{ left: `calc(var(--live-chc-w) + (100% - var(--live-chc-w)) * ${frac.toFixed(4)})` }}/>}
        {!rows.length && <p className="live-guide__empty">{filter === FAVOURITES ? 'No favourite channels yet. Hold OK on a channel’s number to add it.' : 'No channels show this now. Choose All channels to see every channel.'}</p>}
      </div>
    </div>
    {(note || failed) && <div className="live-note" role="status">{failed ? problemWords(failed).body : note}</div>}
    {menu && menuView && <LiveMenu menu={menuView} at={menu.at} onPick={pick} onClose={() => setMenu(undefined)}/>}
    {recordChannel && recordProgramme && <RecordPanel channel={recordChannel} programme={recordProgramme} onClose={() => setRecord(undefined)} onNote={setNote}
      onWatchRecording={(id, title) => { setRecord(undefined); nav.play({ kind: 'recording', id, startMs: 0, title }) }}/>}
    {goto !== undefined && <GoToChannelDialog channels={channels} initial={goto} now={now} onWatch={(c) => { setGoto(undefined); watch(c.id) }} onClose={() => setGoto(undefined)}/>}
  </div>
}
