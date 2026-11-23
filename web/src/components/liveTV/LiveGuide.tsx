import { useEffect, useRef, useState, useSyncExternalStore, type CSSProperties, type KeyboardEvent, type ReactNode } from 'react'
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { liveTVChannelsQuery, liveTVGuideQuery, liveTVGuideSettingsQuery, liveTVSearchQuery, useLiveFavourite, useLiveReminder } from '../../api/queries'
import type { LiveChannel, LiveGuideChannel, LiveProgramme } from '../../api/liveTV'
import { filterLiveChannels } from '../../lib/liveTV'
import {
  availableFilters, BLOCK, clampStart, dayIndex, dayStart, duration, episodeLine, FAVOURITES, FETCH_HOURS, fetchBlock, guideDays, guideRows, HALF_HOUR, HOUR,
  halfHourStart, isOn, latestStart, layoutCells, moveSelection, nowAndNext, panelPicture, programmeLabel, progress, shortDayTime, showFavouritesChip, startShowing, timeOfDay,
  timeRange, windowSlots, type GuideKey, type GuideSelection,
} from '../../lib/liveGuide'
import { dayShort, leadText, onAt, reminderProgramme, searchSpan, searchTerm, searchWhen, wasWhen, type LiveReminder } from '../../lib/liveReminders'
import { guideBoxMode, parseGuideView, primeTime, type GuideView } from '../../lib/liveWords'
import type { GuideSearch } from '../../lib/routeSearch'
import { BellIcon, RemindControls, RemindersList, primaryAction, secondaryAction } from './LiveReminders'
import { RecordControls, RecordingMark } from './LiveRecordings'
import { markLook, recordingMarkLabel, recordingMarkWords } from '../../lib/liveRecordings'
import { useServerRecords } from './useRecordings'
import { useGuideExtras, useReminders } from './useGuideExtras'
import { LiveTabs } from './LiveTabs'
import { PlayIcon, SearchIcon } from '../icons'

const KEYS: Record<string, GuideKey> = { ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down', Home: 'home', End: 'end' }
const ms = (iso: string) => Date.parse(iso)
const cellKey = (s: GuideSelection) => `${s.channelId}|${s.programmeId ?? ''}`

function useWide() {
  return useSyncExternalStore((change) => {
    const query = window.matchMedia('(min-width: 640px)')
    query.addEventListener('change', change)
    return () => query.removeEventListener('change', change)
  }, () => window.matchMedia('(min-width: 640px)').matches)
}

// The guide remembers its view and its channel filter on this browser.
const VIEW_KEY = 'wmm.guide.view'
const FILTER_KEY = 'wmm.guide.filter'
function stored(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}
function store(key: string, value: string) {
  try {
    localStorage.setItem(key, value)
  } catch {
    // Storage refused: the choice lasts this visit.
  }
}

/**
 * The guide with listings: the selected programme beside a grid of channels
 * against half hours (or, as a choice and on phones by default, a list of
 * what is on now). It opens on Favourites when the person has any, and
 * remembers the last filter and view here. From API level 15 it adds
 * favourites, listings search and reminders.
 */
export function ListingsGuide({ admin, playback, open }: { admin: boolean; playback: boolean; open?: GuideSearch }) {
  const wide = useWide()
  const hours = wide ? 2 : 1
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => { const timer = window.setInterval(() => setNow(Date.now()), 30_000); return () => window.clearInterval(timer) }, [])
  const nowStart = halfHourStart(now)
  // Opened at a programme from the Search page: its day and time, selected.
  const [chosenStart, setChosenStart] = useState<number | null>(() => (open?.at ? Math.max(halfHourStart(Date.parse(open.at)), nowStart) : null))
  const [filterId, setFilterId] = useState<string | null>(() => (open?.channel ? '' : stored(FILTER_KEY)))
  const [view, setView] = useState<GuideView>(() => (open?.channel ? 'grid' : parseGuideView(stored(VIEW_KEY)) ?? (window.matchMedia('(min-width: 768px)').matches ? 'grid' : 'now')))
  const [chosen, setChosen] = useState<GuideSelection | null>(() => (open?.channel ? { channelId: open.channel, programmeId: open.programme } : null))
  const [boxText, setBoxText] = useState('')
  const [forceListings, setForceListings] = useState(false)
  const [term, setTerm] = useState<string | undefined>(undefined)
  const [remindersOpen, setRemindersOpen] = useState(false)
  const [gone, setGone] = useState<LiveReminder | null>(null)
  const cells = useRef(new Map<string, HTMLElement>())
  const focusSelection = useRef(!!open?.channel)
  const remindersButton = useRef<HTMLButtonElement>(null)
  const aside = useRef<HTMLElement>(null)
  const extras = useGuideExtras()
  const records = useServerRecords()
  const { items: reminders, lead, byProgramme } = useReminders(extras)

  // Before the first reply the window follows now; after it, the latest
  // start depends on how far listings run.
  const earliest = nowStart
  const firstStart = chosenStart ?? nowStart
  const guide = useQuery({ ...liveTVGuideQuery(fetchBlock(firstStart), FETCH_HOURS), placeholderData: keepPreviousData })
  const channels = useQuery({ ...liveTVChannelsQuery, enabled: guide.isError })
  const listedThrough = guide.data?.state.listedThrough ? ms(guide.data.state.listedThrough) : undefined
  const latest = latestStart(nowStart, listedThrough, hours)
  const start = clampStart(firstStart, earliest, latest)
  const end = start + hours * HOUR
  const queryClient = useQueryClient()
  const block = fetchBlock(start)
  useEffect(() => { void queryClient.prefetchQuery(liveTVGuideQuery(block + BLOCK, FETCH_HOURS)) }, [queryClient, block])
  const settings = useQuery({ ...liveTVGuideSettingsQuery, enabled: admin })
  const flagged = settings.data?.channels.filter((channel) => channel.match === 'differs') ?? []

  // A failed guide still lists every channel, without listings, and they play.
  const all: LiveGuideChannel[] = guide.data?.channels ?? (guide.isError ? (channels.data?.items ?? []).map((channel) => ({ ...channel, listings: false, programmes: [] })) : [])
  // One box: a channel number or name goes to those channels; anything else searches the listings.
  const mode = forceListings && extras ? 'listings' : guideBoxMode(boxText, all, extras)
  const query = mode === 'channels' ? boxText : ''
  const searchText = mode === 'listings' ? boxText : ''
  // Search waits for a pause in typing.
  useEffect(() => { const timer = window.setTimeout(() => setTerm(searchTerm(searchText)), 300); return () => window.clearTimeout(timer) }, [searchText])
  const searching = extras && !!searchTerm(searchText)
  const filters = availableFilters(all)
  const favourites = all.filter((channel) => channel.favourite).length
  const wanted = filterId ?? (extras && favourites > 0 ? FAVOURITES : '')
  const favouritesChip = extras && showFavouritesChip(all, wanted)
  const filter = wanted === FAVOURITES ? (favouritesChip ? FAVOURITES : '') : (filters.find((f) => f.id === wanted)?.id ?? '')
  const chooseFilter = (id: string) => { setFilterId(id); store(FILTER_KEY, id) }
  const chooseView = (v: GuideView) => { setView(v); store(VIEW_KEY, v) }
  const rows = guideRows(filterLiveChannels(all, query), filter, start, end)
  const navRows = rows.map((row) => ({ id: row.id, listings: row.listings && row.available, programmes: row.programmes }))
  const fallback: GuideSelection | undefined = rows[0] && { channelId: rows[0].id, programmeId: rows[0].listings ? rows[0].programmes.find((p) => isOn(p, Math.max(now, start)))?.id : undefined }
  const selection = chosen && rows.some((row) => row.id === chosen.channelId) ? chosen : fallback
  const selectedChannel = rows.find((row) => row.id === selection?.channelId)
  const selectedProgramme = selectedChannel?.programmes.find((p) => p.id === selection?.programmeId)
  const days = guideDays(now, listedThrough)
  const today = dayIndex(days, start)
  const stale = guide.data?.state.stale
  const placeholder = guide.isPlaceholderData
  const reminderOf = (p: LiveProgramme) => (extras ? byProgramme.get(p.id) : undefined)

  const shown = rows.map((row) => ({ row, cells: row.listings && row.available ? layoutCells(row.programmes, start, end) : [] }))
  const shownKeys = shown.flatMap(({ row, cells: c }) => row.listings && row.available ? c.flatMap((cell) => cell.kind === 'programme' ? [cellKey({ channelId: row.id, programmeId: cell.programme.id })] : []) : [cellKey({ channelId: row.id })])
  const tabKey = selection && shownKeys.includes(cellKey(selection)) ? cellKey(selection) : shownKeys[0]

  useEffect(() => {
    if (!focusSelection.current || !selection) return
    focusSelection.current = false
    const cell = cells.current.get(cellKey(selection))
    cell?.focus()
    cell?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' })
  })

  function moveTo(next: number) { setChosenStart(clampStart(next, earliest, latest)) }
  function onKey(event: KeyboardEvent<HTMLElement>) {
    const key = KEYS[event.key]
    if (!key || !selection || !(event.target instanceof HTMLElement) || !event.target.dataset.cell) return
    event.preventDefault()
    const next = moveSelection(navRows, selection, key, start, end)
    if (!next) return
    const programme = rows.find((row) => row.id === next.channelId)?.programmes.find((p) => p.id === next.programmeId)
    if (programme) {
      const moved = startShowing(programme, start, hours, earliest, latest)
      if (ms(programme.end) <= moved || ms(programme.start) >= moved + hours * HOUR) return
      if (moved !== start) setChosenStart(moved)
    }
    setChosen(next)
    focusSelection.current = true
  }
  /** A pointer choice brings the programme's details into view when they sit below the listings. */
  function pick(s: GuideSelection) {
    setGone(null)
    setChosen(s)
    requestAnimationFrame(() => {
      const box = aside.current?.getBoundingClientRect()
      if (box && box.top > window.innerHeight - 80) aside.current?.scrollIntoView({ block: 'start', behavior: 'smooth' })
    })
  }
  /** Opens the guide at a programme from search or the Reminders list: its day and time, its channel, selected. */
  function openAt(channelId: string, p: Pick<LiveProgramme, 'id' | 'start' | 'end'>) {
    setBoxText(''); setForceListings(false); setTerm(undefined); setFilterId(''); setGone(null); setRemindersOpen(false); setView('grid')
    setChosenStart(clampStart(halfHourStart(ms(p.start)), earliest, latest))
    setChosen({ channelId, programmeId: p.id })
    focusSelection.current = true
  }
  function openReminder(r: LiveReminder) {
    if (r.state === 'gone' || !r.channelId) { setGone(r); setRemindersOpen(false); return }
    openAt(r.channelId, { id: r.programmeId, start: r.start, end: r.end })
  }

  const box = <label className="flex min-h-10 min-w-0 flex-[1_1_240px] items-center gap-2 rounded-[10px] border border-line-2 bg-surface pl-3 pr-2 text-neutral-400 focus-within:border-line-3 md:max-w-[340px]">
    <SearchIcon size={16} className="shrink-0" />
    <span className="sr-only">{extras ? 'Search listings or type a channel' : 'Go to channel by number or name'}</span>
    <input type="search" value={boxText} onChange={(event) => { setBoxText(event.target.value); setForceListings(false) }} onKeyDown={(event) => { if (event.key === 'Escape') { setBoxText(''); setForceListings(false) } }} placeholder={extras ? 'Search listings or type a channel' : 'Go to channel: 7.1 or name'} className="min-w-0 flex-1 border-0 bg-transparent text-base text-neutral-100 outline-none placeholder:text-neutral-400 sm:text-sm" />
  </label>
  const remindersToggle = extras && <button ref={remindersButton} type="button" aria-haspopup="dialog" aria-expanded={remindersOpen} aria-label={wide ? undefined : `Reminders${reminders.length ? `, ${reminders.length}` : ''}`} onClick={() => setRemindersOpen((o) => !o)}
    className={`btn shrink-0 ${wide ? '' : 'w-10 px-0'} ${remindersOpen ? 'border-accent' : ''}`}>
    <BellIcon />{wide && 'Reminders'}{reminders.length > 0 && <span aria-hidden={!wide} className="rounded-full bg-accent px-1.5 text-[11px] font-bold leading-[18px] text-ground">{reminders.length}</span>}
  </button>
  const remindersPanel = remindersOpen && <section role="dialog" aria-labelledby="reminders-head" className="absolute right-0 top-[calc(100%+8px)] z-30 w-[28rem] max-w-[calc(100vw-2rem)] rounded-xl border border-line-2 bg-surface shadow-2xl">
    <RemindersList items={reminders} lead={lead} now={now} channels={all} onOpen={openReminder} onClose={() => { setRemindersOpen(false); remindersButton.current?.focus() }} />
  </section>
  const chips = [...(favouritesChip ? [{ id: FAVOURITES, label: 'Favourites', n: favourites }] : []), { id: '', label: 'All channels', n: all.length }, ...filters.map((f) => ({ ...f, n: undefined }))]
  const evening = primeTime(dayStart(days[today] ?? days[0], now))
  const nowLeft = (now - start) / (end - start) * 100
  const panel = gone ? <GoneReminder reminder={gone} channel={all.find((c) => c.id === gone.channelId)} onRemoved={() => setGone(null)} />
    : selectedChannel && <ProgrammePanel channel={selectedChannel} programme={selectedProgramme} now={now} playback={playback} loading={placeholder} extras={extras} admin={admin} reminder={selectedProgramme && reminderOf(selectedProgramme)} reminderOf={reminderOf} lead={lead} />

  return <div className="flex flex-col">
    <LiveTabs active="guide" right={<p className="text-[13.5px] tabular-nums text-neutral-400">{new Intl.DateTimeFormat(undefined, { weekday: 'short', month: 'short', day: 'numeric' }).format(now)} · {timeOfDay(now)}</p>} />
    <div className="mt-3 space-y-2 empty:hidden">
      {stale && <p role="note" className="text-sm text-neutral-400">Listings last updated {guide.data?.state.updatedAt ? shortDayTime(ms(guide.data.state.updatedAt), now) : 'over 36 hours ago'} and may be out of date. Every channel still plays.</p>}
      {flagged.length > 0 && <p role="note" className="text-sm text-neutral-400">The listings for {flagged.map((channel) => channel.number).join(', ')} name {flagged.length === 1 ? 'a different channel' : 'different channels'}. <Link to="/settings/live-tv" className="text-neutral-200 underline">Check them in Settings › Live TV</Link></p>}
      {guide.isError && <p role="alert" className="rounded-lg border border-warn/50 p-4 text-sm text-warn">Listings couldn’t be loaded: {guide.error.message}. Every channel still plays.</p>}
    </div>

    {all.length > 0 && <>
      {/* The day and the time shown. */}
      {!searching && view === 'grid' && <div className="mt-4 flex flex-wrap items-center gap-x-4 gap-y-2.5">
        {days.length > 1 && <div role="group" aria-label="Day" className="scrollbar-hide -mx-4 flex min-w-0 max-w-[calc(100%+2rem)] gap-2 overflow-x-auto px-4 md:mx-0 md:max-w-full md:flex-wrap md:px-0">
          {days.map((day, i) => <button key={day.start} type="button" aria-pressed={i === today} onClick={() => moveTo(dayStart(day, now))} className="chip shrink-0">{day.label}</button>)}
        </div>}
        <div className="flex items-center gap-1.5 md:ml-auto">
          <button type="button" aria-label="Earlier" disabled={start <= earliest} onClick={() => moveTo(start - HALF_HOUR)} className="ib ib-sm"><Chevron left /></button>
          <button type="button" onClick={() => { setChosenStart(null); setChosen(null) }} className="btn btn-sm">Now</button>
          <button type="button" aria-label="Later" disabled={start >= latest} onClick={() => moveTo(start + HALF_HOUR)} className="ib ib-sm"><Chevron /></button>
          <button type="button" disabled={evening < earliest || evening > latest} onClick={() => moveTo(evening)} className="btn btn-sm">Prime time</button>
        </div>
      </div>}
      {/* Which channels, the one box, and how to see them. */}
      <div className={`${view === 'grid' && !searching ? 'mt-2.5' : 'mt-4'} flex flex-wrap items-center gap-x-3 gap-y-2.5`}>
        {!searching && (filters.length > 0 || favouritesChip) && <div role="group" aria-label="Channels" className="scrollbar-hide -mx-4 flex max-w-[calc(100%+2rem)] gap-2 overflow-x-auto px-4 md:mx-0 md:max-w-full md:flex-wrap md:px-0">
          {chips.map((f) => <button key={f.id || 'all'} type="button" aria-pressed={filter === f.id} onClick={() => chooseFilter(f.id)} className="chip shrink-0">{f.id === FAVOURITES && <StarIcon size={13} filled />}{f.label}{f.n !== undefined && <span className="n">{f.n}</span>}</button>)}
        </div>}
        <div className="relative flex min-w-0 flex-[1_1_320px] items-center gap-2">
          {box}
          {remindersToggle}
          {remindersPanel}
        </div>
        {!searching && <div role="group" aria-label="View" className="seg md:ml-auto">
          <button type="button" aria-pressed={view === 'grid'} onClick={() => chooseView('grid')}>Grid</button>
          <button type="button" aria-pressed={view === 'now'} onClick={() => chooseView('now')}>On now</button>
        </div>}
      </div>
      {mode === 'channels' && !!query && extras && <p className="mt-2 text-[13px] text-neutral-400">Showing channels that match “{query.trim()}”. <button type="button" onClick={() => setForceListings(true)} className="text-neutral-200 underline hover:text-accent">Search the listings for it instead</button></p>}
    </>}

    {guide.isPending && <p className="py-10 text-neutral-400">Loading the guide…</p>}

    {searching ? <div className="mt-4"><ListingsSearch term={term} wide={wide} now={now} playback={playback} lead={lead} reminderOf={reminderOf} onOpen={openAt} onBack={() => setBoxText('')} listedThrough={listedThrough} /></div> : <div className="mt-[18px] flex flex-wrap items-start gap-5">
      <section aria-label={view === 'grid' ? `Guide, ${days[today]?.label ?? 'Today'}, ${timeRange(start, end, ' to ')}` : 'On now'} className="min-w-0 flex-[999_1_720px]">
        {rows.length > 0 ? view === 'grid' ? <>
          <div onKeyDown={onKey} className="panel overflow-x-auto max-md:-mx-4 max-md:rounded-none max-md:border-x-0">
            <div className="relative md:min-w-[820px]">
              <div className="flex">
                <div className="sticky left-0 z-20 flex w-19 shrink-0 items-center border-r border-line bg-surface px-2 text-xs font-semibold text-neutral-400 md:w-[184px] md:px-3 md:text-[12.5px]">{days[today]?.short}</div>
                <div className="flex flex-1">{windowSlots(start, hours).map((slot) => <div key={slot.getTime()} className="min-w-0 flex-1 border-l border-line-2 pb-1.5 pl-2 pt-2.5 text-[12.5px] font-semibold tabular-nums text-neutral-400">{timeOfDay(slot.getTime())}</div>)}</div>
              </div>
              <ul>{shown.map(({ row, cells: track }) => <li key={row.id} className="flex min-h-[72px] border-t border-line">
                <div className={`sticky left-0 z-20 flex w-19 shrink-0 items-stretch border-r border-line bg-surface md:w-[184px] ${row.available ? '' : 'opacity-60'}`}>
                  {row.available && playback ? <Link to="/live-tv/watch/$channelId" params={{ channelId: row.id }} aria-label={`Watch ${row.number} ${row.name}${extras && row.favourite ? ', favourite' : ''}`} className="flex min-w-0 flex-1 flex-col justify-center gap-0.5 px-2 hover:bg-surface-2 md:flex-row md:items-center md:justify-start md:gap-2.5 md:pl-3 md:pr-1"><ChannelName channel={row} star={extras && !wide && !!row.favourite} /></Link>
                    : <div className="flex min-w-0 flex-1 flex-col justify-center gap-0.5 px-2 md:flex-row md:items-center md:justify-start md:gap-2.5 md:px-3"><ChannelName channel={row} /></div>}
                  {extras && wide && row.available && <FavouriteStar channel={row} />}
                </div>
                <div className="relative min-w-0 flex-1">
                  {!row.available ? <span className="absolute inset-1.5 flex flex-col justify-center rounded-lg border border-dashed border-line-3 px-3 text-sm text-neutral-400">Unavailable<span className="text-xs">Not in the tuner’s latest lineup</span></span>
                    : !row.listings ? <GuideButton cellRefs={cells} selection={{ channelId: row.id }} selected={selection} tabKey={tabKey} onSelect={(s) => { setGone(null); setChosen(s) }} onPick={pick} label={`${row.number} ${row.name}, no listings. It still plays.`} className="inset-1.5 flex-row items-center justify-start gap-2.5 border-dashed border-line-3 bg-[repeating-linear-gradient(135deg,var(--color-surface)_0_10px,var(--color-surface-2)_10px_20px)]"><span className="text-[13px] text-neutral-400">No listings for this channel. It still plays.</span></GuideButton>
                      : track.map((cell) => cell.kind === 'programme'
                        ? <ProgrammeCell key={cell.programme.id} cell={cell} channel={row} now={now} cellRefs={cells} selected={selection} tabKey={tabKey} onSelect={(s) => { setGone(null); setChosen(s) }} onPick={pick} bell={extras && (!!reminderOf(cell.programme) || !!cell.programme.reminder)} />
                        : cell.listingsEnd !== undefined && !placeholder ? <span key={`gap-${cell.start}`} style={{ left: `calc(${cell.left}% + 3px)`, width: `calc(${cell.width}% - 6px)` }} className="absolute inset-y-1.5 flex items-center overflow-hidden rounded-lg border border-dashed border-line-3 bg-[repeating-linear-gradient(135deg,var(--color-surface)_0_10px,var(--color-surface-2)_10px_20px)] px-3 text-[13px] text-neutral-400">No listings after {shortDayTime(cell.listingsEnd, now)}</span>
                          : null)}
                </div>
              </li>)}</ul>
              {nowLeft >= 0 && nowLeft <= 100 && <div aria-hidden="true" className="pointer-events-none absolute inset-y-0 left-19 right-0 md:left-[184px]">
                <span className="absolute inset-y-0 z-10 w-0.5 bg-accent" style={{ left: `${nowLeft}%` }} />
                <span className="absolute top-2 z-10 -translate-x-1/2 whitespace-nowrap rounded-full bg-accent px-[7px] py-px text-[11px] font-bold text-ground" style={{ left: `${nowLeft}%` }}>{timeOfDay(now).replace(/\s?[AP]M$/i, '')}</span>
              </div>}
            </div>
          </div>
          <p className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1.5 text-[13px] text-neutral-400">
            {records && <span className="inline-flex items-center gap-1.5"><span aria-hidden="true" className="size-2 rounded-full bg-red-400" />Will record</span>}
            {extras && <span className="inline-flex items-center gap-1.5"><span className="text-accent"><BellIcon size={13} filled /></span>Reminder</span>}
            {listedThrough && <span>Listings run to {new Intl.DateTimeFormat(undefined, { weekday: 'short', month: 'short', day: 'numeric' }).format(listedThrough)}</span>}
            {filter && <button type="button" onClick={() => chooseFilter('')} className="text-neutral-300 hover:text-accent md:ml-auto">Show all {all.length} channels ›</button>}
          </p>
        </> : <OnNowList rows={rows} now={now} playback={playback} selected={selection?.channelId} onPick={(channelId, programmeId) => pick({ channelId, programmeId })} />
          : !guide.isPending && <div className="rounded-xl border border-dashed border-line-2 p-10 text-center"><h2 className="text-lg font-semibold">{filter === FAVOURITES && !query ? 'No favourites yet' : query || filter ? 'No matching channels' : 'No channels yet'}</h2><p className="mt-2 text-sm text-neutral-400">{filter === FAVOURITES && !query ? 'Press the star beside a channel to add it.' : query ? 'Try a channel number or another name.' : filter ? 'Nothing in this category in these hours.' : 'Run a channel scan in the HDHomeRun app, then refresh the lineup in Live TV settings.'}</p>{filter && <button type="button" onClick={() => chooseFilter('')} className={`${secondaryAction} mt-4`}>Show all channels</button>}{admin && !query && !filter && <Link to="/settings/live-tv" className="mt-4 inline-flex min-h-11 items-center underline">Open Live TV settings</Link>}</div>}
      </section>
      {panel && <aside ref={aside} aria-label="The selected programme" className="min-w-0 flex-[1_1_340px] scroll-mt-20 md:sticky md:top-[76px]">{panel}</aside>}
    </div>}
    <p className="mt-6 text-xs leading-relaxed text-neutral-400">Live broadcasts use a tuner while you watch.{records && ' Scheduled recordings have priority and may release a tuner someone is watching on.'}</p>
  </div>
}

/** On now: each channel's programme with its progress and time left, what is next, and Watch. */
function OnNowList({ rows, now, playback, selected, onPick }: { rows: LiveGuideChannel[]; now: number; playback: boolean; selected?: string; onPick: (channelId: string, programmeId?: string) => void }) {
  return <ul className="panel px-4">{rows.map((row) => {
    const on = row.listings ? row.programmes.find((p) => isOn(p, now)) ?? nowAndNext(row, now).now : undefined
    const next = row.listings ? row.programmes.find((p) => ms(p.start) >= (on ? ms(on.end) : now)) ?? nowAndNext(row, now).next : undefined
    const canWatch = row.available && playback
    return <li key={row.id} className="flex flex-wrap items-center gap-x-[18px] gap-y-2.5 border-b border-line py-3 last:border-b-0">
      <span className={`flex min-w-0 flex-[0_0_150px] items-center gap-2.5 md:flex-[0_0_170px] ${row.available ? '' : 'opacity-60'}`}><ChannelName channel={row} /></span>
      <button type="button" aria-pressed={selected === row.id} onClick={() => onPick(row.id, on?.id)} className="flex min-w-0 flex-[1_1_200px] flex-col items-start rounded-md text-left hover:text-accent">
        <span className="max-w-full truncate text-[15px] font-semibold">{on ? on.title : row.available ? 'No listings' : 'Unavailable'}</span>
        <span className="max-w-full truncate text-[12.5px] text-neutral-400">{on ? (episodeLine(on) || timeRange(ms(on.start), ms(on.end))) : row.available ? 'It still plays' : 'Not in the tuner’s latest lineup'}</span>
      </button>
      {on && <span className="flex min-w-0 flex-[1_1_180px] items-center gap-2.5">
        <span className="meter flex-1" role="img" aria-label={`${Math.round(progress(on, now))}% through`}><i style={{ width: `${progress(on, now)}%` }} /></span>
        <span className="whitespace-nowrap text-[13px] text-neutral-300">{duration(ms(on.end) - now)} left</span>
      </span>}
      {next && <span className="hidden min-w-0 flex-[1_1_180px] truncate text-[13px] text-neutral-400 md:block">Next {timeOfDay(ms(next.start))} · {next.title}</span>}
      {canWatch && <Link to="/live-tv/watch/$channelId" params={{ channelId: row.id }} aria-label={`Watch ${on ? `${on.title} on ` : ''}${row.number} ${row.name}`} className="btn btn-sm ml-auto"><PlayIcon size={13} />Watch</Link>}
    </li>
  })}</ul>
}

type CellRefs = { current: Map<string, HTMLElement> }

function GuideButton({ cellRefs, selection, selected, tabKey, onSelect, onPick, label, className, style, children }: { cellRefs: CellRefs; selection: GuideSelection; selected?: GuideSelection; tabKey?: string; onSelect: (s: GuideSelection) => void; onPick: (s: GuideSelection) => void; label: string; className: string; style?: CSSProperties; children: ReactNode }) {
  const key = cellKey(selection)
  const on = !!selected && cellKey(selected) === key
  return <button type="button" data-cell="true" tabIndex={key === tabKey ? 0 : -1} aria-label={label} aria-current={on ? 'true' : undefined} ref={(element) => { if (element) cellRefs.current.set(key, element); else cellRefs.current.delete(key) }}
    onClick={() => onPick(selection)} onFocus={() => { if (!on) onSelect(selection) }} style={style}
    className={`absolute flex flex-col justify-center gap-[3px] overflow-hidden rounded-lg border px-2.5 text-left hover:border-line-3 focus-visible:outline-none ${on ? 'z-10 !border-accent shadow-[inset_0_0_0_1px_var(--color-accent)]' : ''} ${className}`}>{children}</button>
}

function ProgrammeCell({ cell, channel, now, cellRefs, selected, tabKey, onSelect, onPick, bell }: { cell: Extract<ReturnType<typeof layoutCells>[number], { kind: 'programme' }>; channel: LiveGuideChannel; now: number; cellRefs: CellRefs; selected?: GuideSelection; tabKey?: string; onSelect: (s: GuideSelection) => void; onPick: (s: GuideSelection) => void; bell?: boolean }) {
  const p = cell.programme
  const on = isOn(p, now)
  const sub = episodeLine(p) || timeRange(ms(p.start), ms(p.end)).replace(/\s?[AP]M$/i, '')
  const look = markLook(p.recording)
  const clash = look === 'hollow'
  return <GuideButton cellRefs={cellRefs} selection={{ channelId: channel.id, programmeId: p.id }} selected={selected} tabKey={tabKey} onSelect={onSelect} onPick={onPick} label={`${programmeLabel(p)} on ${channel.number}${p.rating ? `, rated ${p.rating}` : ''}${bell ? ', reminder set' : ''}${recordingMarkLabel(p.recording)}`}
    style={{ left: `calc(${cell.left}% + 3px)`, width: `calc(${cell.width}% - 6px)` }} className={`inset-y-1.5 ${clash ? 'border-warn' : 'border-line'} ${on ? 'bg-raise' : 'bg-surface-2'}`}>
    <span className="truncate text-[13.5px] font-semibold">{cell.before && <span aria-hidden="true">‹ </span>}{p.title}{cell.after && <span aria-hidden="true"> ›</span>}</span>
    <span className="flex min-w-0 items-center gap-1.5">
      {p.live && <Mark tone="live">Live</Mark>}
      {p.new && <Mark tone="new">New</Mark>}
      {look === 'rec' ? <Mark tone="live">REC</Mark> : look && !clash ? <span aria-hidden="true" title={recordingMarkWords(p.recording)} className={`size-2 shrink-0 rounded-full bg-red-400 ${look === 'ring' ? 'ring-1 ring-red-300 ring-offset-2 ring-offset-surface-2' : ''}`} /> : null}
      {bell && <span aria-hidden="true" className="shrink-0 text-accent"><BellIcon size={12} filled /></span>}
      {clash && <Mark tone="warn">Won’t record · clash</Mark>}
      <span className="min-w-0 truncate text-xs text-neutral-400">{p.rating && <span className="mr-1.5 rounded-[3px] border border-line-3 px-1 text-[10px] font-semibold text-neutral-300">{p.rating}</span>}{sub}</span>
    </span>
    {on && <span aria-hidden="true" className="absolute inset-x-0 bottom-0 h-[3px] bg-black/50"><span className="block h-full bg-accent" style={{ width: `${progress(p, now)}%` }} /></span>}
  </GuideButton>
}

/** A small mark on a guide cell: Live, New, REC, or a clash. */
function Mark({ tone, children }: { tone: 'live' | 'new' | 'warn'; children: ReactNode }) {
  const tones = { live: 'bg-brand text-white ring-1 ring-inset ring-brand-line', new: 'bg-accent/15 text-accent', warn: 'bg-warn/15 text-warn' }
  return <span aria-hidden="true" className={`shrink-0 whitespace-nowrap rounded px-[5px] text-[10.5px] font-bold leading-4 ${tones[tone]}`}>{children}</span>
}

export function Tags({ programme: p, small }: { programme: LiveProgramme; small?: boolean }) {
  const tag = small ? 'mr-1 inline-block rounded-[3px] border px-1 text-[10px] font-bold leading-[13px] align-[1px]' : 'inline-flex items-center rounded-full border px-2 py-px text-[11px] font-semibold'
  return <>
    {p.new && <span className={`${tag} border-accent/60 text-accent`}>New</span>}
    {p.live && <span className={`${tag} border-red-500/75 text-red-300`}>Live</span>}
    {p.hd && <span className={`${tag} border-neutral-600 text-neutral-300`}>HD</span>}
  </>
}

/** A channel's logo beside its number; without a logo, the number and name as text. */
export function ChannelName({ channel, star }: { channel: Pick<LiveGuideChannel, 'number' | 'name' | 'logoUrl'>; star?: boolean }) {
  const [broken, setBroken] = useState(false)
  const logo = channel.logoUrl && !broken
  return <>
    {logo && <img src={channel.logoUrl} alt="" loading="lazy" onError={() => setBroken(true)} className="h-6 w-8 shrink-0 rounded-[5px] object-contain sm:h-[26px] sm:w-[42px]" />}
    <span className="flex min-w-0 flex-col leading-tight"><strong className={`${logo ? 'text-sm' : 'text-[15px]'} inline-flex items-center gap-1 font-bold tabular-nums`}>{channel.number}{star && <span className="text-accent"><StarIcon size={11} filled /></span>}</strong><span className={`truncate text-[11px] text-neutral-400 sm:text-xs ${logo ? 'hidden sm:block' : ''}`}>{channel.name}</span></span>
  </>
}

/** The selected programme beside the grid: its picture, channel, title, times, marks, description, Watch, Record and what is on later. */
function ProgrammePanel({ channel, programme: p, now, playback, loading, extras, admin, reminder, reminderOf, lead }: { channel: LiveGuideChannel; programme?: LiveProgramme; now: number; playback: boolean; loading: boolean; extras: boolean; admin: boolean; reminder?: LiveReminder; reminderOf: (p: LiveProgramme) => LiveReminder | undefined; lead: number }) {
  const picture = p ? panelPicture(p) : undefined
  const [brokenPicture, setBrokenPicture] = useState('')
  const [brokenLogo, setBrokenLogo] = useState(false)
  const showPicture = picture && brokenPicture !== picture.url
  const logo = channel.logoUrl && !brokenLogo ? channel.logoUrl : undefined
  const on = !!p && isOn(p, now)
  const later = !!p && ms(p.start) > now
  const sameDay = p && new Date(ms(p.start)).toDateString() === new Date(now).toDateString()
  const when = !p ? 'Live' : on ? 'On now' : sameDay ? (later ? 'Later today' : 'Earlier today') : new Intl.DateTimeFormat(undefined, { weekday: 'long' }).format(ms(p.start))
  const canWatch = channel.available && playback
  const weekday = p ? new Intl.DateTimeFormat(undefined, { weekday: 'short' }).format(ms(p.start)) : ''
  const timing = p ? `${sameDay ? '' : `${weekday} `}${timeRange(ms(p.start), ms(p.end))}${on ? ` · ${duration(ms(p.end) - now)} left` : later && sameDay ? ` · starts in ${duration(ms(p.start) - now)}` : ''}` : ''
  const laterOn = channel.programmes.filter((q) => ms(q.start) >= (p ? ms(p.end) : now)).slice(0, 2)
  return <section className="panel overflow-hidden">
    <div className="relative aspect-video bg-surface-2">
      {/* Keyed by URL: a picture still loading never leaves the last programme's under this one's title. */}
      {showPicture ? <img key={picture.url} src={picture.url} alt="" onError={() => setBrokenPicture(picture.url)} className="size-full object-cover" />
        : logo ? <img src={logo} alt="" onError={() => setBrokenLogo(true)} className="absolute inset-0 m-auto h-1/2 w-1/2 object-contain" />
          : <span aria-hidden="true" className="absolute inset-0 flex flex-col items-center justify-center gap-1 text-neutral-300"><strong className="text-4xl tabular-nums text-neutral-100">{channel.number}</strong><span className="text-sm">{channel.name}</span></span>}
      <span className={`absolute left-2 top-2 rounded-[5px] px-[7px] py-0.5 text-[11px] font-bold ${on || !p ? 'bg-brand text-white ring-1 ring-inset ring-brand-line' : 'bg-ground/80 text-neutral-100 ring-1 ring-inset ring-white/15'}`}>{when}</span>
      {showPicture && picture.tmdb && <span className="absolute bottom-2 right-2 rounded bg-ground/70 px-1.5 text-[11px] text-neutral-300">Picture: TMDB</span>}
    </div>
    <div className="flex flex-col gap-3 p-4">
      <p className="flex items-center gap-2.5">
        <ChannelName channel={channel} />
        {extras && <span className="ml-auto"><FavouriteButton channel={channel} /></span>}
      </p>
      {p ? <>
        <div>
          <h2 className="break-words text-[22px] font-bold leading-tight tracking-[-.01em]">{p.title}</h2>
          {episodeLine(p) && <p className="mt-0.5 text-neutral-300">{episodeLine(p)}</p>}
        </div>
        <div className="flex flex-col gap-[7px]">
          <p className="text-[13.5px] tabular-nums">{timing}</p>
          {on && <span className="meter" role="img" aria-label={`${Math.round((now - ms(p.start)) / 60_000)} minutes in`}><i style={{ width: `${progress(p, now)}%` }} /></span>}
        </div>
        {(p.new || p.live || p.rating || p.categories?.length) && <p className="flex flex-wrap gap-1.5">
          {p.new && <span className="pill pill-gold">New</span>}
          {p.live && <span className="pill bg-brand text-white">Live</span>}
          {p.rating && <span className="pill pill-line">{p.rating}</span>}
          {p.categories?.length ? <span className="pill">{p.categories.join(' · ')}</span> : null}
        </p>}
        {p.description && <p className="line-clamp-5 text-[13.5px] leading-relaxed text-neutral-300">{p.description}{p.descriptionSource === 'tmdb' && <span className="ml-2 text-xs text-neutral-400">Description: TMDB</span>}</p>}
      </> : <>
        <h2 className="break-words text-[22px] font-bold">{channel.name}</h2>
        <p className="text-sm text-neutral-400">{!channel.available ? 'This channel is no longer in the tuner’s latest lineup.' : !channel.listings ? 'No listings for this channel. It still plays.' : loading ? 'Loading listings…' : 'Nothing is listed at this time. It still plays.'}</p>
        {admin && channel.available && !channel.listings && <Link to="/settings/live-tv" className="text-sm font-semibold text-accent">Fix in Settings</Link>}
      </>}
      <div className="flex flex-wrap items-center gap-2">
        {canWatch && (later && p ? <>
          <span className="basis-full text-sm font-semibold">{extras ? onAt(channel.number, ms(p.start), now) : `On ${channel.number} at ${timeOfDay(ms(p.start))}`}</span>
          {/* A later programme's first action is Remind me; Watch now stays beside it. */}
          {extras && <RemindControls programme={p} reminder={reminder} lead={lead} now={now} />}
          <Link to="/live-tv/watch/$channelId" params={{ channelId: channel.id }} className={secondaryAction}><PlayIcon size={14} />Watch {channel.number} now</Link>
        </> : <Link to="/live-tv/watch/$channelId" params={{ channelId: channel.id }} className={`${primaryAction} flex-[1_1_130px]`}><PlayIcon size={15} />Watch {channel.number}</Link>)}
        {p && channel.available && <RecordControls programme={p} now={now} />}
        {channel.available && !playback && <span className="text-sm text-warn">Playback unavailable</span>}
      </div>
      {extras && later && reminder?.state === 'moved' && <p className="text-[13px] text-neutral-400">The listings moved it, so the reminder moved with it. It still comes {leadText(lead)}.</p>}
      {laterOn.length > 0 && <div className="border-t border-line pt-3">
        <p className="text-[11.5px] font-bold uppercase tracking-[.07em] text-dim">Later on {channel.number}</p>
        <ul className="mt-2 flex flex-col gap-2.5">{laterOn.map((q) => <li key={q.id} className="flex flex-wrap items-center gap-2">
          <span className="flex min-w-0 flex-1 flex-col leading-snug">
            <b className="truncate font-semibold">{q.title}</b>
            <span className="inline-flex items-center gap-1.5 text-[12.5px] text-neutral-400">{timeOfDay(ms(q.start))}{q.live && ' · Live'}<RecordingMark programme={q} />{markLook(q.recording) && markLook(q.recording) !== 'hollow' && <span className="sr-only">{recordingMarkWords(q.recording)}</span>}</span>
          </span>
          {extras && <RemindControls programme={q} reminder={reminderOf(q)} lead={lead} now={now} primary={false} compact />}
        </li>)}</ul>
      </div>}
    </div>
  </section>
}

export function StarIcon({ size = 16, filled }: { size?: number; filled?: boolean }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill={filled ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="2" strokeLinejoin="round" aria-hidden="true"><path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8L3.5 9.7l5.9-.9z" /></svg>
}

/** The star beside a channel in the grid (wide screens): adds or removes it from this person's favourites. */
function FavouriteStar({ channel }: { channel: Pick<LiveGuideChannel, 'id' | 'number' | 'name' | 'favourite'> }) {
  const favourite = useLiveFavourite()
  const on = !!channel.favourite
  return <button type="button" aria-pressed={on} aria-label={on ? `Remove ${channel.number} ${channel.name} from favourites` : `Add ${channel.number} ${channel.name} to favourites`}
    onClick={() => favourite.mutate({ channelId: channel.id, favourite: !on })}
    className={`ib ib-ghost ib-sm my-auto mr-1 ${on ? 'text-accent' : 'text-neutral-400'}`}><StarIcon filled={on} /></button>
}

/** The selected programme's Favourite toggle: the star, named for the channel. */
function FavouriteButton({ channel }: { channel: Pick<LiveGuideChannel, 'id' | 'number' | 'name' | 'favourite'> }) {
  const favourite = useLiveFavourite()
  const on = !!channel.favourite
  return <button type="button" aria-pressed={on} aria-label={on ? `Remove ${channel.number} ${channel.name} from favourites` : `Add ${channel.number} ${channel.name} to favourites`} onClick={() => favourite.mutate({ channelId: channel.id, favourite: !on })}
    className={`ib ib-ghost ib-sm ${on ? 'text-accent' : ''}`}>
    <StarIcon size={16} filled={on} />
  </button>
}

/** A reminder whose programme left the listings, opened from the Reminders list. */
function GoneReminder({ reminder: r, channel, onRemoved }: { reminder: LiveReminder; channel?: Pick<LiveGuideChannel, 'logoUrl'>; onRemoved: () => void }) {
  const change = useLiveReminder()
  const ep = episodeLine(reminderProgramme(r))
  return <section aria-label="Reminder" className="panel flex flex-col gap-4 overflow-hidden p-4">
    <div className="relative aspect-video w-full shrink-0 overflow-hidden rounded-lg bg-surface-2">
      {channel?.logoUrl ? <img src={channel.logoUrl} alt="" className="absolute inset-0 m-auto h-1/2 w-1/2 object-contain" />
        : <span aria-hidden="true" className="absolute inset-0 flex flex-col items-center justify-center gap-1 text-neutral-300"><strong className="text-4xl tabular-nums text-neutral-100">{r.number}</strong><span className="text-sm">{r.channelName}</span></span>}
    </div>
    <div className="min-w-0 space-y-2">
      <p className="flex flex-wrap items-center gap-2 text-sm text-neutral-400"><strong className="tabular-nums text-neutral-100">{r.number}</strong>{r.channelName && <span>{r.channelName}</span>}<span aria-hidden="true">·</span><span className="font-semibold">Your reminder</span></p>
      <h2 className="break-words text-[22px] font-bold tracking-tight">{r.title}</h2>
      {ep && <p className="text-base text-neutral-300">{ep}</p>}
      <p className="text-sm tabular-nums text-neutral-400">{wasWhen(r)}</p>
      <div className="flex flex-wrap items-center gap-3 pt-1">
        <span role="status" className="inline-flex min-h-11 items-center gap-2 rounded-md border border-warn/50 bg-warn/10 px-3.5 text-sm font-semibold text-warn"><BellIcon crossed />No longer in the listings</span>
        <button type="button" disabled={change.isPending} onClick={() => change.mutate({ reminderId: r.id }, { onSuccess: onRemoved })} className="inline-flex min-h-11 items-center px-2 text-sm text-neutral-300 underline hover:text-accent">Remove</button>
      </div>
      <p className="max-w-2xl text-[13px] text-neutral-400">The listings for {r.number} changed and don’t have it any more, so no reminder will come.{r.channelId ? ` ${r.number} still plays.` : ''}</p>
    </div>
  </section>
}

/**
 * Search listings: what is on now and later whose titles have the
 * words, in place of the grid. Each result opens the guide at it, with
 * Watch while on and Remind me later.
 */
function ListingsSearch({ term, wide, now, playback, lead, reminderOf, onOpen, onBack, listedThrough }: { term?: string; wide: boolean; now: number; playback: boolean; lead: number; reminderOf: (p: LiveProgramme) => LiveReminder | undefined; onOpen: (channelId: string, p: LiveProgramme) => void; onBack: () => void; listedThrough?: number }) {
  const results = useQuery({ ...liveTVSearchQuery(term ?? ''), enabled: !!term, placeholderData: keepPreviousData })
  const hits = results.data?.items ?? []
  const shownTerm = term ?? ''
  const ends = listedThrough ? `${dayShort(listedThrough, now)} ${timeOfDay(listedThrough)}` : undefined
  return <section aria-labelledby="listings-search" className="space-y-2.5">
    <div className="flex items-center gap-2">
      <h2 id="listings-search" role="status" className="flex-1 text-lg font-semibold">{!term || results.isPending ? 'Searching…' : hits.length ? `${hits.length} ${hits.length === 1 ? 'programme matches' : 'programmes match'} “${shownTerm}”` : `No programmes match “${shownTerm}”`}</h2>
      <button type="button" onClick={onBack} className="inline-flex min-h-11 items-center px-2 text-sm text-neutral-300 underline hover:text-accent">Back to the guide</button>
    </div>
    {results.isError && <p role="alert" className="text-sm text-warn">{results.error.message}</p>}
    {hits.length > 0 && <ul className="-mx-4 overflow-hidden border-y border-line bg-surface sm:mx-0 sm:rounded-xl sm:border">{hits.map(({ channel, programme: p }) => {
      const on = isOn(p, now)
      const canWatch = on && playback && channel.available
      return <li key={p.id} className="flex flex-col gap-2.5 border-t border-line px-4 py-3 first:border-t-0 sm:flex-row sm:flex-wrap sm:items-center sm:gap-4">
        <button type="button" onClick={() => onOpen(channel.id, p)} aria-label={`${p.title}${episodeLine(p) ? `, ${episodeLine(p)}` : ''}, ${searchWhen(p, channel.number, now)} ${channel.name}. Open in the guide`} className="group flex min-w-0 flex-1 items-center gap-3 text-left sm:basis-[30rem] sm:gap-4">
          <HitPicture p={p} channel={channel} />
          <span className="flex min-w-0 flex-1 flex-col gap-0.5">
            <span className={`flex min-w-0 items-center gap-2 text-xs font-semibold sm:text-[13px] ${on ? 'text-red-300' : 'text-neutral-300'}`}>
              {channel.logoUrl && <img src={channel.logoUrl} alt="" className="hidden h-5 w-8 shrink-0 object-contain object-left sm:block" />}
              <span className="truncate">{searchWhen(p, channel.number, now)}<span className="hidden font-normal text-neutral-400 sm:inline">{searchSpan(p, now)}</span></span>
            </span>
            <span className="flex flex-wrap items-center gap-2"><span className="text-[15px] font-semibold group-hover:text-accent sm:text-base">{p.title}</span><span className="hidden sm:contents"><Tags programme={p} /></span></span>
            <span className="truncate text-xs text-neutral-400 sm:text-[13px] sm:text-neutral-300">{episodeLine(p) || p.categories?.join(' · ')}</span>
            {on && <span aria-hidden="true" className="mt-1 block h-1 max-w-80 overflow-hidden rounded-full bg-line-2"><span className="block h-full bg-accent" style={{ width: `${progress(p, now)}%` }} /></span>}
          </span>
        </button>
        <div className="flex flex-wrap items-center gap-2">
          {canWatch && <Link to="/live-tv/watch/$channelId" params={{ channelId: channel.id }} className={primaryAction}><svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M7 4v16l13-8z" /></svg>Watch {channel.number}</Link>}
          {!on && <RemindControls programme={p} reminder={reminderOf(p)} lead={lead} now={now} primary={false} compact={!wide} />}
          <RecordControls programme={p} now={now} />
        </div>
      </li>
    })}</ul>}
    {term && results.isSuccess && hits.length === 0 && <div className="rounded-xl border border-dashed border-line-2 px-6 py-10 text-center">
      <p className="text-[15px] text-neutral-200">Nothing on now or later matches “{shownTerm}”.</p>
      <p className="mt-2 text-sm text-neutral-400">Search looks at titles and episode titles{ends ? ` until the listings end, ${ends}` : ''}. Try part of a title.</p>
    </div>}
    <p className="text-[13px] text-neutral-400">Titles and episode titles of what’s on now and later. Choosing one opens the guide at it.</p>
  </section>
}

/** A result's 16:9 series card, else the channel's logo or number. */
export function HitPicture({ p, channel, className = 'w-28 sm:w-40' }: { p: LiveProgramme; channel: Pick<LiveChannel, 'number' | 'name' | 'logoUrl'>; className?: string }) {
  const [broken, setBroken] = useState('')
  const picture = p.pictureUrl && broken !== p.pictureUrl ? p.pictureUrl : undefined
  return <span aria-hidden="true" className={`relative block aspect-video shrink-0 overflow-hidden rounded-md bg-surface-2 ${className}`}>
    {picture ? <img key={picture} src={picture} alt="" loading="lazy" onError={() => setBroken(picture)} className="h-full w-full object-cover" />
      : channel.logoUrl ? <img src={channel.logoUrl} alt="" className="absolute inset-0 m-auto h-1/2 w-1/2 object-contain" />
        : <span className="absolute inset-0 flex flex-col items-center justify-center text-neutral-300"><strong className="text-lg tabular-nums text-neutral-100">{channel.number}</strong><span className="text-[10px]">{channel.name}</span></span>}
  </span>
}

function Chevron({ left }: { left?: boolean }) {
  return <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><path d={left ? 'm15 6-6 6 6 6' : 'm9 6 6 6-6 6'} /></svg>
}

/** The player's channel banner with listings: logo and number, the programme's series card, episode line, times, progress and Next. */
export function ChannelBanner({ channel, now, children }: { channel: LiveChannel; now: number; children: ReactNode }) {
  const { now: p, next } = nowAndNext(channel, now)
  const [brokenLogo, setBrokenLogo] = useState(false)
  const [brokenPicture, setBrokenPicture] = useState('')
  const logo = channel.logoUrl && !brokenLogo ? channel.logoUrl : undefined
  const picture = p?.pictureUrl && brokenPicture !== p.pictureUrl ? p.pictureUrl : undefined
  return <section aria-label="Channel" className="mb-4 flex items-stretch overflow-hidden rounded-xl border border-white/15 bg-ground/85">
    <div className="flex shrink-0 flex-col justify-center gap-0.5 border-r border-white/10 bg-white/5 px-3 py-2.5 sm:px-4">
      {logo && <img src={logo} alt="" onError={() => setBrokenLogo(true)} className="h-6 w-9 object-contain object-left sm:h-7 sm:w-10" />}
      <strong className="text-2xl tabular-nums leading-none text-accent sm:text-3xl">{channel.number}</strong>
      <span className="max-w-24 truncate text-[11px] text-neutral-300 sm:max-w-32 sm:text-xs">{channel.name}</span>
    </div>
    {p && (picture || logo) && <div className="hidden items-center py-3 pl-4 min-[480px]:flex">
      <span className="relative block aspect-video w-24 overflow-hidden rounded bg-surface-2 sm:w-40">{picture ? <img key={picture} src={picture} alt="" onError={() => setBrokenPicture(picture)} className="h-full w-full object-cover" /> : <img src={logo} alt="" className="absolute inset-0 m-auto h-1/2 w-1/2 object-contain" />}</span>
    </div>}
    <div className="min-w-0 flex-1 space-y-1 px-3 py-2.5 sm:px-4 sm:py-3">
      {p ? <>
        <p className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1"><span className="truncate font-semibold sm:text-lg">{p.title}</span><Tags programme={p} /></p>
        {episodeLine(p) && <p className="truncate text-sm text-neutral-200">{episodeLine(p)}</p>}
        <p className="text-xs tabular-nums text-neutral-400">{timeRange(ms(p.start), ms(p.end))} · {duration(ms(p.end) - now)} left</p>
        <div aria-hidden="true" className="h-1 max-w-md overflow-hidden rounded-full bg-white/15"><div className="h-full bg-accent" style={{ width: `${progress(p, now)}%` }} /></div>
        {next && <p className="truncate text-xs text-neutral-400 lg:hidden">Next · {timeOfDay(ms(next.start))} · {next.title}</p>}
      </> : <>
        <p className="truncate font-semibold">{channel.name}</p>
        <p className="text-xs text-neutral-400">Live · no listings for this channel. It still plays.</p>
        {next && <p className="truncate text-xs text-neutral-400">Next · {timeOfDay(ms(next.start))} · {next.title}</p>}
      </>}
    </div>
    {p && next && <div className="hidden w-60 min-w-0 flex-col justify-center gap-0.5 border-l border-white/10 px-4 py-3 lg:flex">
      <span className="text-[11px] font-semibold uppercase tracking-wider text-neutral-400">Next · {timeOfDay(ms(next.start))}</span>
      <span className="truncate font-medium">{next.title}</span>
      {episodeLine(next) && <span className="truncate text-xs text-neutral-400">{episodeLine(next)}</span>}
    </div>}
    <div className="flex shrink-0 items-center pr-3 sm:px-4">{children}</div>
  </section>
}

/** One channel in the player's mini guide with listings: what is on with its progress, and what is next. */
export function MiniGuideRow({ channel, now }: { channel: LiveChannel; now: number }) {
  const { now: p, next } = nowAndNext(channel, now)
  return <>
    <span className="flex w-24 shrink-0 items-center gap-2 sm:w-52"><ChannelName channel={channel} /></span>
    <span className="min-w-0 flex-1">
      {p ? <>
        <span className="block truncate text-sm font-semibold">{p.title}</span>
        <span className="block truncate text-xs text-neutral-400">{episodeLine(p) ? `${episodeLine(p)} · ` : ''}{duration(ms(p.end) - now)} left</span>
        <span aria-hidden="true" className="mt-1 block h-[3px] overflow-hidden rounded-full bg-white/15"><span className="block h-full bg-accent" style={{ width: `${progress(p, now)}%` }} /></span>
      </> : <>
        <span className="block truncate text-sm text-neutral-300">{channel.available ? 'No listings' : 'Unavailable'}</span>
        {channel.available && <span className="block text-xs text-neutral-400">It still plays</span>}
      </>}
    </span>
    <span className="hidden min-w-0 flex-1 border-l border-line pl-4 md:block">
      {next && <><span className="block truncate text-sm text-neutral-300">{next.title}</span><span className="block truncate text-xs text-neutral-400">{timeOfDay(ms(next.start))}{episodeLine(next) ? ` · ${episodeLine(next)}` : ''}</span></>}
    </span>
  </>
}
