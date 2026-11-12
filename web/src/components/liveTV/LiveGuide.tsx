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
import type { GuideSearch } from '../../lib/routeSearch'
import { BellIcon, RemindControls, RemindersList, primaryAction, secondaryAction } from './LiveReminders'
import { RecordControls, RecordingMark, RecordingsLink } from './LiveRecordings'
import { recordingMarkLabel } from '../../lib/liveRecordings'
import { useServerRecords } from './useRecordings'
import { useGuideExtras, useReminders } from './useGuideExtras'

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

/** The guide with listings (PLAN-LIVE-TV-GUIDE.md §6.2): the selected programme above a grid of channels against half hours. From API level 15 it adds favourites, listings search and reminders (§6.5). */
export function ListingsGuide({ admin, playback, open }: { admin: boolean; playback: boolean; open?: GuideSearch }) {
  const wide = useWide()
  const hours = wide ? 2 : 1
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => { const timer = window.setInterval(() => setNow(Date.now()), 30_000); return () => window.clearInterval(timer) }, [])
  const nowStart = halfHourStart(now)
  // Opened at a programme from the Search page: its day and time, selected.
  const [chosenStart, setChosenStart] = useState<number | null>(() => (open?.at ? Math.max(halfHourStart(Date.parse(open.at)), nowStart) : null))
  const [query, setQuery] = useState('')
  const [filterId, setFilterId] = useState('')
  const [chosen, setChosen] = useState<GuideSelection | null>(() => (open?.channel ? { channelId: open.channel, programmeId: open.programme } : null))
  const [searchText, setSearchText] = useState('')
  const [term, setTerm] = useState<string | undefined>(undefined)
  const [remindersOpen, setRemindersOpen] = useState(false)
  const [gone, setGone] = useState<LiveReminder | null>(null)
  const cells = useRef(new Map<string, HTMLElement>())
  const focusSelection = useRef(!!open?.channel)
  const remindersButton = useRef<HTMLButtonElement>(null)
  const extras = useGuideExtras()
  const records = useServerRecords()
  const { items: reminders, lead, byProgramme } = useReminders(extras)
  // Search waits for a pause in typing.
  useEffect(() => { const timer = window.setTimeout(() => setTerm(searchTerm(searchText)), 300); return () => window.clearTimeout(timer) }, [searchText])
  const searching = extras && !!searchTerm(searchText)

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
  const filters = availableFilters(all)
  const favouritesChip = extras && showFavouritesChip(all, filterId)
  const filter = filterId === FAVOURITES ? (favouritesChip ? FAVOURITES : '') : (filters.find((f) => f.id === filterId)?.id ?? '')
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
  /** Opens the guide at a programme from search or the Reminders list: its day and time, its channel, selected. */
  function openAt(channelId: string, p: Pick<LiveProgramme, 'id' | 'start' | 'end'>) {
    setSearchText(''); setTerm(undefined); setQuery(''); setFilterId(''); setGone(null); setRemindersOpen(false)
    setChosenStart(clampStart(halfHourStart(ms(p.start)), earliest, latest))
    setChosen({ channelId, programmeId: p.id })
    focusSelection.current = true
  }
  function openReminder(r: LiveReminder) {
    if (r.state === 'gone' || !r.channelId) { setGone(r); setRemindersOpen(false); return }
    openAt(r.channelId, { id: r.programmeId, start: r.start, end: r.end })
  }

  const searchBox = extras && <label className={wide ? 'w-64' : 'min-w-0 flex-1'}><span className="sr-only">Search listings</span><input type="search" value={searchText} onChange={(event) => setSearchText(event.target.value)} onKeyDown={(event) => { if (event.key === 'Escape') setSearchText('') }} placeholder="Search listings" className={`min-h-11 w-full rounded-md border bg-surface px-3 text-base placeholder:text-neutral-400 sm:text-sm ${searching ? 'border-accent' : 'border-line-2'}`} /></label>
  const remindersToggle = extras && <button ref={remindersButton} type="button" aria-haspopup="dialog" aria-expanded={remindersOpen} aria-label={wide ? undefined : `Reminders${reminders.length ? `, ${reminders.length}` : ''}`} onClick={() => setRemindersOpen((open) => !open)}
    className={`inline-flex min-h-11 shrink-0 items-center justify-center gap-2 rounded-md border bg-surface text-sm font-medium hover:bg-surface-2 ${wide ? 'px-4' : 'min-w-11 px-2.5'} ${remindersOpen ? 'border-accent' : 'border-line-2'}`}>
    <BellIcon />{wide && 'Reminders'}{reminders.length > 0 && <span aria-hidden={!wide} className="rounded-full bg-accent px-1.5 text-[11px] font-bold leading-[18px] text-ground">{reminders.length}</span>}
  </button>
  const remindersPanel = remindersOpen && <section role="dialog" aria-labelledby="reminders-head" className={`rounded-xl border border-line-2 bg-surface shadow-2xl ${wide ? 'absolute right-0 top-[calc(100%+8px)] z-30 w-[28rem] max-w-[calc(100vw-2rem)]' : ''}`}>
    <RemindersList items={reminders} lead={lead} now={now} channels={all} onOpen={openReminder} onClose={() => { setRemindersOpen(false); remindersButton.current?.focus() }} />
  </section>

  const goToChannel = <label className="w-full"><span className="sr-only">Go to channel by number or name</span><input type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Go to channel: 7.1 or name" className="min-h-11 w-full rounded-md border border-line-2 bg-surface px-3 text-base placeholder:text-neutral-400" /></label>

  const nowLeft = (now - start) / (end - start) * 100
  return <div className="mx-auto max-w-7xl space-y-4">
    <header className="flex flex-wrap items-end justify-between gap-3">
      <div><p className="mb-1 text-xs font-semibold uppercase tracking-widest text-neutral-400">Your antenna, anywhere</p><h1 className="text-3xl font-semibold tracking-tight">Live TV</h1></div>
      <p className="text-sm tabular-nums text-neutral-400">{all.length} channels · {timeOfDay(now)}</p>
    </header>
    {/* On a phone, search and the Reminders button come first (the LiveExtrasPhone board). */}
    {extras && !wide && <>
      <div className="flex items-center gap-2">{searchBox}{remindersToggle}<RecordingsLink /></div>
      {remindersPanel}
    </>}
    {stale && <p role="note" className="text-sm text-neutral-400">Listings last updated {guide.data?.state.updatedAt ? shortDayTime(ms(guide.data.state.updatedAt), now) : 'over 36 hours ago'} and may be out of date. Every channel still plays.</p>}
    {flagged.length > 0 && <p role="note" className="text-sm text-neutral-400">The listings for {flagged.map((channel) => channel.number).join(', ')} name {flagged.length === 1 ? 'a different channel' : 'different channels'}. <Link to="/settings/live-tv" className="text-neutral-200 underline">Check them in Settings › Live TV</Link></p>}
    {guide.isError && <p role="alert" className="rounded-lg border border-warn/50 p-4 text-sm text-warn">Listings couldn’t be loaded: {guide.error.message}. Every channel still plays.</p>}
    {guide.isPending && <p className="py-10 text-neutral-400">Loading the guide…</p>}

    {!searching && (gone ? <GoneReminder reminder={gone} channel={all.find((c) => c.id === gone.channelId)} onRemoved={() => setGone(null)} />
      : selectedChannel && <ProgrammePanel channel={selectedChannel} programme={selectedProgramme} now={now} playback={playback} loading={placeholder} extras={extras} wide={wide} reminder={selectedProgramme && reminderOf(selectedProgramme)} lead={lead} />)}

    {/* One row of controls in both views, so the search box keeps its place and focus. */}
    {all.length > 0 && (!searching || wide) && <div className="flex flex-wrap items-center gap-2">
      {searching ? <button type="button" onClick={() => setSearchText('')} className={secondaryAction}><Chevron left />Back to the guide</button> : <>
        {!wide && extras && goToChannel}
        {days.length > 1 && <>
          <div role="radiogroup" aria-label="Day" className="hidden rounded-lg border border-line-2 bg-surface p-0.5 sm:inline-flex">
            {days.map((day, i) => <button key={day.start} type="button" role="radio" aria-checked={i === today} onClick={() => moveTo(dayStart(day, now))} className={`min-h-10 rounded-md px-3.5 text-sm ${i === today ? 'bg-neutral-100 font-semibold text-ground' : 'text-neutral-300 hover:text-neutral-100'}`}>{day.label}</button>)}
          </div>
          <label className="sm:hidden"><span className="sr-only">Day</span><select value={today} onChange={(event) => moveTo(dayStart(days[Number(event.target.value)], now))} className="min-h-11 rounded-md border border-line-2 bg-surface px-2 text-base">{days.map((day, i) => <option key={day.start} value={i}>{day.label}</option>)}</select></label>
        </>}
        <button type="button" aria-label="Earlier" disabled={start <= earliest} onClick={() => moveTo(start - HALF_HOUR)} className="ml-auto grid min-h-11 min-w-11 place-items-center rounded-md border border-line-2 bg-surface disabled:opacity-40 sm:ml-0"><Chevron left /></button>
        <button type="button" onClick={() => { setChosenStart(null); setChosen(null) }} className="min-h-11 rounded-md border border-line-2 bg-surface px-4 text-sm">Now</button>
        <button type="button" aria-label="Later" disabled={start >= latest} onClick={() => moveTo(start + HALF_HOUR)} className="grid min-h-11 min-w-11 place-items-center rounded-md border border-line-2 bg-surface disabled:opacity-40"><Chevron /></button>
        {(filters.length > 0 || favouritesChip) && <div role="group" aria-label="Show channels with" className="-mx-4 flex w-[calc(100%+2rem)] gap-1.5 overflow-x-auto px-4 sm:mx-0 sm:w-auto sm:px-0">
          {[...(favouritesChip ? [{ id: FAVOURITES, label: 'Favourites' }] : []), { id: '', label: 'All channels' }, ...filters].map((f) => <button key={f.id || 'all'} type="button" aria-pressed={filter === f.id} onClick={() => setFilterId(f.id)} className={`inline-flex min-h-9 shrink-0 items-center gap-1.5 rounded-full border px-3.5 text-sm ${filter === f.id ? 'border-neutral-100 bg-neutral-100 font-medium text-ground' : 'border-line-2 text-neutral-300'}`}>{f.id === FAVOURITES && <StarIcon size={13} filled />}{f.label}</button>)}
        </div>}
        {/* Below level 15 the phone keeps G2's order: Go to channel last. */}
        {!wide && !extras && goToChannel}
      </>}
      {wide && <div className="relative ml-auto flex flex-wrap items-center gap-2">
        {searchBox}
        <label className="w-60"><span className="sr-only">Go to channel by number or name</span><input type="search" value={query} onChange={(event) => { setQuery(event.target.value); if (searching) setSearchText('') }} placeholder="Go to channel: 7.1 or name" className="min-h-11 w-full rounded-md border border-line-2 bg-surface px-3 text-sm placeholder:text-neutral-400" /></label>
        {remindersToggle}
        <RecordingsLink />
        {remindersPanel}
      </div>}
    </div>}

    {searching ? <ListingsSearch term={term} wide={wide} now={now} playback={playback} lead={lead} reminderOf={reminderOf} onOpen={openAt} onBack={() => setSearchText('')} listedThrough={listedThrough} /> : <>
      {rows.length > 0 ? <section aria-label={`Guide, ${days[today]?.label ?? 'Today'}, ${timeRange(start, end, ' to ')}`} onKeyDown={onKey} className="-mx-4 overflow-hidden border-y border-line bg-ground sm:mx-0 sm:rounded-xl sm:border">
        <div className="flex border-b border-line-2 bg-surface">
          <div className="flex w-19 shrink-0 items-center border-r border-line-2 px-2 text-xs font-semibold sm:w-46 sm:px-3.5">{days[today]?.short}</div>
          <div className="relative flex flex-1">{windowSlots(start, hours).map((slot) => <div key={slot.getTime()} className="min-w-0 flex-1 border-l border-line px-2.5 py-3 text-xs font-medium tabular-nums text-neutral-400 first:border-l-0 sm:px-3">{timeOfDay(slot.getTime())}</div>)}</div>
        </div>
        <div className="relative">
          <ul className="divide-y divide-line">{shown.map(({ row, cells: track }) => <li key={row.id} className="flex min-h-16">
            {row.available && playback ? <div className="flex w-19 shrink-0 items-stretch border-r border-line-2 sm:w-46">
              <Link to="/live-tv/watch/$channelId" params={{ channelId: row.id }} aria-label={`Watch ${row.number} ${row.name}${extras && row.favourite ? ', favourite' : ''}`} className="flex min-w-0 flex-1 flex-col justify-center gap-0.5 px-2 hover:bg-surface-2 sm:flex-row sm:items-center sm:justify-start sm:gap-2.5 sm:pl-3 sm:pr-1"><ChannelName channel={row} star={extras && !wide && !!row.favourite} /></Link>
              {extras && wide && <FavouriteStar channel={row} />}
            </div>
              : <div className={`flex w-19 shrink-0 flex-col justify-center gap-0.5 border-r border-line-2 px-2 sm:w-46 sm:flex-row sm:items-center sm:justify-start sm:gap-2.5 sm:px-3 ${row.available ? '' : 'opacity-60'}`}><ChannelName channel={row} /></div>}
            <div className="relative min-w-0 flex-1">
              {!row.available ? <span className="absolute inset-0 flex flex-col justify-center border-l border-line px-3 text-sm text-neutral-400 first:border-l-0">Unavailable<span className="text-xs">Not in the tuner’s latest lineup</span></span>
                : !row.listings ? <GuideButton cellRefs={cells} selection={{ channelId: row.id }} selected={selection} tabKey={tabKey} onSelect={(s) => { setGone(null); setChosen(s) }} label={`${row.number} ${row.name}, no listings. It still plays.`} className="inset-0 bg-ground"><span className="text-sm font-semibold text-neutral-300">No listings</span><span className="text-xs text-neutral-400">It still plays</span></GuideButton>
                  : track.map((cell) => cell.kind === 'programme'
                    ? <ProgrammeCell key={cell.programme.id} cell={cell} channel={row} now={now} cellRefs={cells} selected={selection} tabKey={tabKey} onSelect={(s) => { setGone(null); setChosen(s) }} bell={extras && (!!reminderOf(cell.programme) || !!cell.programme.reminder)} />
                    : cell.listingsEnd !== undefined && !placeholder ? <span key={`gap-${cell.start}`} style={{ left: `${cell.left}%`, width: `${cell.width}%` }} className="absolute inset-y-0 flex items-center overflow-hidden border-l border-line bg-[repeating-linear-gradient(135deg,var(--color-surface)_0_6px,var(--color-ground)_6px_12px)] px-3 text-sm font-semibold text-neutral-400">No listings after {shortDayTime(cell.listingsEnd, now)}</span>
                      : <span key={`gap-${cell.start}`} aria-hidden="true" style={{ left: `${cell.left}%`, width: `${cell.width}%` }} className="absolute inset-y-0 border-l border-line" />)}
            </div>
          </li>)}</ul>
          {nowLeft >= 0 && nowLeft <= 100 && <div aria-hidden="true" className="pointer-events-none absolute inset-y-0 right-0 left-19 sm:left-46"><span className="absolute inset-y-0 z-10 w-0.5 bg-accent" style={{ left: `${nowLeft}%` }} /></div>}
        </div>
      </section> : !guide.isPending && <section className="rounded-xl border border-dashed border-line-2 p-10 text-center"><h2 className="text-lg font-semibold">{filter === FAVOURITES && !query ? 'No favourites yet' : query || filter ? 'No matching channels' : 'No channels yet'}</h2><p className="mt-2 text-sm text-neutral-400">{filter === FAVOURITES && !query ? 'Press the star beside a channel to add it.' : query ? 'Try a channel number or another name.' : filter ? 'Nothing in this category in these hours.' : 'Run a channel scan in the HDHomeRun app, then refresh the lineup in Live TV settings.'}</p>{admin && !query && !filter && <Link to="/settings/live-tv" className="mt-4 inline-flex min-h-11 items-center underline">Open Live TV settings</Link>}</section>}
    </>}
    <p className="text-xs leading-relaxed text-neutral-400">Live broadcasts use a tuner while you watch.{records && ' Scheduled recordings have priority and may release a tuner someone is watching on.'}</p>
  </div>
}

type CellRefs = { current: Map<string, HTMLElement> }

function GuideButton({ cellRefs, selection, selected, tabKey, onSelect, label, className, style, children }: { cellRefs: CellRefs; selection: GuideSelection; selected?: GuideSelection; tabKey?: string; onSelect: (s: GuideSelection) => void; label: string; className: string; style?: CSSProperties; children: ReactNode }) {
  const key = cellKey(selection)
  const on = !!selected && cellKey(selected) === key
  return <button type="button" data-cell="true" tabIndex={key === tabKey ? 0 : -1} aria-label={label} aria-current={on ? 'true' : undefined} ref={(element) => { if (element) cellRefs.current.set(key, element); else cellRefs.current.delete(key) }}
    onClick={() => onSelect(selection)} onFocus={() => { if (!on) onSelect(selection) }} style={style}
    className={`absolute flex flex-col justify-center gap-0.5 overflow-hidden border-l border-line px-2.5 text-left hover:bg-surface-2 focus-visible:outline-none sm:px-3 ${on ? 'z-10 shadow-[inset_0_0_0_2px_var(--color-accent)]' : ''} ${className}`}>{children}</button>
}

function ProgrammeCell({ cell, channel, now, cellRefs, selected, tabKey, onSelect, bell }: { cell: Extract<ReturnType<typeof layoutCells>[number], { kind: 'programme' }>; channel: LiveGuideChannel; now: number; cellRefs: CellRefs; selected?: GuideSelection; tabKey?: string; onSelect: (s: GuideSelection) => void; bell?: boolean }) {
  const p = cell.programme
  const on = isOn(p, now)
  const sub = episodeLine(p) || timeRange(ms(p.start), ms(p.end)).replace(/\s?[AP]M$/i, '')
  return <GuideButton cellRefs={cellRefs} selection={{ channelId: channel.id, programmeId: p.id }} selected={selected} tabKey={tabKey} onSelect={onSelect} label={`${programmeLabel(p)} on ${channel.number}${bell ? ', reminder set' : ''}${recordingMarkLabel(p.recording)}`}
    style={{ left: `${cell.left}%`, width: `${cell.width}%` }} className={`inset-y-0 ${on ? 'bg-surface-2' : 'bg-surface'}`}>
    <span className="truncate text-sm font-semibold">{cell.before && <span aria-hidden="true">‹ </span>}{bell && <span className="mr-1.5 inline-block align-[-1px] text-accent"><BellIcon size={12} filled /></span>}<RecordingMark programme={p} />{p.title}{cell.after && <span aria-hidden="true"> ›</span>}</span>
    <span className="truncate text-xs text-neutral-400"><Tags programme={p} small />{sub}</span>
    {on && <span aria-hidden="true" className="absolute bottom-0 left-0 h-[3px] bg-accent" style={{ width: `${progress(p, now)}%` }} />}
  </GuideButton>
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
    {logo && <img src={channel.logoUrl} alt="" loading="lazy" onError={() => setBroken(true)} className="h-6 w-8 shrink-0 object-contain sm:h-8 sm:w-11" />}
    <span className="flex min-w-0 flex-col"><strong className={`${logo ? 'text-[15px]' : 'text-lg'} inline-flex items-center gap-1 tabular-nums leading-tight`}>{channel.number}{star && <span className="text-accent"><StarIcon size={11} filled /></span>}</strong><span className={`truncate text-[11px] text-neutral-400 sm:text-xs ${logo ? 'hidden sm:block' : ''}`}>{channel.name}</span></span>
  </>
}

/** The selected programme's details (§6.1): its 16:9 picture, episode line, times, tags, description and Watch. */
function ProgrammePanel({ channel, programme: p, now, playback, loading, extras, wide, reminder, lead }: { channel: LiveGuideChannel; programme?: LiveProgramme; now: number; playback: boolean; loading: boolean; extras: boolean; wide: boolean; reminder?: LiveReminder; lead: number }) {
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
  const timing = p ? `${weekday} ${timeRange(ms(p.start), ms(p.end))}${on ? ` · ${duration(ms(p.end) - now)} left` : later && sameDay ? ` · Starts in ${duration(ms(p.start) - now)}` : ''}` : ''
  return <section aria-label="Selected programme" className="flex flex-wrap gap-5 rounded-xl border border-line bg-surface p-4 sm:gap-6 sm:p-5">
    <div className="w-full shrink-0 space-y-1.5 sm:w-[min(400px,40%)] sm:min-w-70">
      <div className="relative aspect-video w-full overflow-hidden rounded-lg bg-surface-2">
        {/* Keyed by URL: a picture still loading never leaves the last programme's under this one's title. */}
        {showPicture ? <img key={picture.url} src={picture.url} alt="" onError={() => setBrokenPicture(picture.url)} className="h-full w-full object-cover" />
          : logo ? <img src={logo} alt="" onError={() => setBrokenLogo(true)} className="absolute inset-0 m-auto h-1/2 w-1/2 object-contain" />
            : <span aria-hidden="true" className="absolute inset-0 flex flex-col items-center justify-center gap-1 text-neutral-300"><strong className="text-4xl tabular-nums text-neutral-100">{channel.number}</strong><span className="text-sm">{channel.name}</span></span>}
      </div>
      {showPicture && picture.tmdb && <p className="text-xs text-neutral-400">Picture: TMDB</p>}
    </div>
    <div className="min-w-0 flex-1 basis-80 space-y-2">
      <p className="flex flex-wrap items-center gap-2 text-sm text-neutral-400">
        {logo && <img src={logo} alt="" className="h-6 w-8 object-contain" />}
        <strong className="tabular-nums text-neutral-100">{channel.number}</strong><span>{channel.name}</span><span aria-hidden="true">·</span><span className={`font-semibold ${on || !p ? 'text-red-300' : ''}`}>{when}</span>
        {extras && <FavouriteButton channel={channel} label={wide} />}
      </p>
      {p ? <>
        <h2 className="break-words text-2xl font-bold tracking-tight sm:text-[28px]">{p.title}</h2>
        {episodeLine(p) && <p className="text-base text-neutral-300">{episodeLine(p)}</p>}
        <p className="text-sm tabular-nums text-neutral-400">{timing}</p>
        {on && <div aria-hidden="true" className="h-1 max-w-md overflow-hidden rounded-full bg-line-2"><div className="h-full bg-accent" style={{ width: `${progress(p, now)}%` }} /></div>}
        <p className="flex flex-wrap items-center gap-1.5 text-sm text-neutral-400"><Tags programme={p} />{p.categories?.length ? <span>{p.categories.join(' · ')}</span> : null}</p>
        {p.description && <p className="max-w-3xl text-[15px] leading-relaxed text-neutral-300">{p.description}{p.descriptionSource === 'tmdb' && <span className="ml-2 text-xs text-neutral-400">Description: TMDB</span>}</p>}
      </> : <>
        <h2 className="break-words text-2xl font-bold sm:text-[28px]">{channel.name}</h2>
        <p className="text-[15px] text-neutral-400">{!channel.available ? 'This channel is no longer in the tuner’s latest lineup.' : !channel.listings ? 'No listings for this channel. It still plays.' : loading ? 'Loading listings…' : 'Nothing is listed at this time. It still plays.'}</p>
      </>}
      <div className="flex flex-wrap items-center gap-3 pt-1">
        {canWatch && (later && p ? <>
          <span className="basis-full font-semibold sm:basis-auto">{extras ? onAt(channel.number, ms(p.start), now) : `On ${channel.number} at ${timeOfDay(ms(p.start))}`}</span>
          {/* A later programme's first action is Remind me (§6.5); Watch now stays beside it. */}
          {extras && <RemindControls programme={p} reminder={reminder} lead={lead} now={now} />}
          <Link to="/live-tv/watch/$channelId" params={{ channelId: channel.id }} className="inline-flex min-h-11 items-center gap-2 rounded-md border border-line-2 px-4 text-sm font-medium hover:bg-surface-2"><span aria-hidden="true">▶</span> Watch {channel.number} now</Link>
        </> : <Link to="/live-tv/watch/$channelId" params={{ channelId: channel.id }} className="inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-md btn-primary px-6 font-semibold sm:w-auto"><span aria-hidden="true">▶</span> Watch {channel.number}</Link>)}
        {p && channel.available && <RecordControls programme={p} now={now} />}
        {channel.available && !playback && <span className="text-sm text-warn">Playback unavailable</span>}
      </div>
      {extras && later && reminder?.state === 'moved' && <p className="text-[13px] text-neutral-400">The listings moved it, so the reminder moved with it. It still comes {leadText(lead)}.</p>}
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
    className={`my-auto mr-1 grid h-11 w-8 shrink-0 place-items-center rounded-md hover:bg-surface-2 ${on ? 'text-accent' : 'text-neutral-500 hover:text-neutral-300'}`}><StarIcon filled={on} /></button>
}

/** The selected programme's Favourite toggle: worded on wide screens, the star alone on a phone. */
function FavouriteButton({ channel, label }: { channel: Pick<LiveGuideChannel, 'id' | 'number' | 'name' | 'favourite'>; label: boolean }) {
  const favourite = useLiveFavourite()
  const on = !!channel.favourite
  return <button type="button" aria-pressed={on} aria-label={`Favourite ${channel.number} ${channel.name}`} onClick={() => favourite.mutate({ channelId: channel.id, favourite: !on })}
    className={`ml-1 inline-flex min-h-8 items-center gap-1.5 rounded-full border px-2.5 text-[13px] ${on ? 'border-accent/55 text-accent' : 'border-line-2 text-neutral-300 hover:text-neutral-100'} ${label ? '' : 'min-w-11 justify-center'}`}>
    <StarIcon size={14} filled={on} />{label && 'Favourite'}
  </button>
}

/** A reminder whose programme left the listings, opened from the Reminders list (the LiveGuideExtras board's gone state). */
function GoneReminder({ reminder: r, channel, onRemoved }: { reminder: LiveReminder; channel?: Pick<LiveGuideChannel, 'logoUrl'>; onRemoved: () => void }) {
  const change = useLiveReminder()
  const ep = episodeLine(reminderProgramme(r))
  return <section aria-label="Reminder" className="flex flex-wrap gap-5 rounded-xl border border-line bg-surface p-4 sm:gap-6 sm:p-5">
    <div className="relative aspect-video w-full shrink-0 overflow-hidden rounded-lg bg-surface-2 sm:w-[min(400px,40%)] sm:min-w-70">
      {channel?.logoUrl ? <img src={channel.logoUrl} alt="" className="absolute inset-0 m-auto h-1/2 w-1/2 object-contain" />
        : <span aria-hidden="true" className="absolute inset-0 flex flex-col items-center justify-center gap-1 text-neutral-300"><strong className="text-4xl tabular-nums text-neutral-100">{r.number}</strong><span className="text-sm">{r.channelName}</span></span>}
    </div>
    <div className="min-w-0 flex-1 basis-80 space-y-2">
      <p className="flex flex-wrap items-center gap-2 text-sm text-neutral-400"><strong className="tabular-nums text-neutral-100">{r.number}</strong>{r.channelName && <span>{r.channelName}</span>}<span aria-hidden="true">·</span><span className="font-semibold">Your reminder</span></p>
      <h2 className="break-words text-2xl font-bold tracking-tight sm:text-[28px]">{r.title}</h2>
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
 * Search listings (§6.5): what is on now and later whose titles have the
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
      {!wide && <button type="button" onClick={onBack} className="inline-flex min-h-11 items-center px-2 text-sm text-neutral-300 underline">Back to the guide</button>}
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

/** The player's channel banner with listings (§6.1): logo and number, the programme's series card, episode line, times, progress and Next. */
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
