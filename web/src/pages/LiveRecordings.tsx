import { useRecordingAccess, useRecordingMutation } from '../components/liveTV/useRecordings'
import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent, type ReactNode } from 'react'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { liveTVChannelsQuery, liveTVRecordingsQuery } from '../api/queries'
import { cancelRecording, createManualRecording, deleteRecordingFile, deleteRecordingRule, type ManualRecording, type Recording, type RecordingRule } from '../api/recordings'
import { canChooseClash, conflictWords, gapWords, localDateTime, manualRepeatWords, reasonWords, recordingProgress, validateManual, type RecordingSection } from '../lib/liveRecordings'
import {
  alsoOnWords,
  clashAlert,
  dayWords,
  groupLine,
  groupRecordings,
  hasWatchState,
  inRecordedChip,
  oneOffWords,
  recordedChips,
  recordedWords,
  recordingEpisodeWords,
  recordingLeftWords,
  recordingMinutes,
  recordingPct,
  roomWords,
  ruleWords,
  scheduleDays,
  storageFacts,
  type RecordedChip,
  type RecordingGroup,
} from '../lib/liveWords'
import { timeOfDay, timeRange } from '../lib/liveGuide'
import { formatBytes } from '../lib/format'
import { useAuth } from '../authContext'
import { Dialog } from '../components/Dialog'
import { textInput } from '../components/SettingsPanel'
import { primaryAction, secondaryAction } from '../components/liveTV/LiveReminders'
import { ClashDialog, RecordingOptions, RuleOptions } from '../components/liveTV/LiveRecordings'
import { LiveTabs } from '../components/liveTV/LiveTabs'
import { CheckIcon, MoreIcon, PlayIcon, PlusIcon, WarnIcon } from '../components/icons'

type Tab = 'recorded' | 'scheduled' | 'series'
const TABS: { id: Tab; label: string }[] = [
  { id: 'recorded', label: 'Recorded' },
  { id: 'scheduled', label: 'Scheduled' },
  { id: 'series', label: 'Series' },
]

/** One section's items and rules, every page loaded so far; events refresh them. */
function useSection(section: RecordingSection) {
  const query = useInfiniteQuery(liveTVRecordingsQuery(section))
  const pages = query.data?.pages ?? []
  return { query, items: pages.flatMap((p) => p.items), rules: pages.flatMap((p) => p.rules ?? []), first: pages[0] }
}

/**
 * Recordings: what is recording now and the room left first, then any
 * clash as an alert with its fix, then Recorded (grouped by show, ready to
 * watch), Scheduled (by day) and Series as tabs.
 */
export function LiveRecordings() {
  const { allowed, pending } = useRecordingAccess()
  const [manual, setManual] = useState(false)
  // Until the server and capabilities answer, say nothing rather than "not available".
  if (pending) return <p role="status" className="py-10 text-sm text-neutral-400">Loading recordings…</p>
  if (!allowed) return <section className="mx-auto max-w-2xl space-y-4 py-10"><h1 className="text-2xl font-semibold">Recordings aren’t available</h1><p className="text-neutral-400">An admin can set up recording and choose who may record in Live TV settings.</p><Link to="/live-tv" className={secondaryAction}>Back to Live TV</Link></section>
  return <div className="flex flex-col">
    <LiveTabs active="recordings" right={<button type="button" onClick={() => setManual(true)} className="btn"><PlusIcon size={16} />New recording</button>} />
    <RecordingsBody />
    {manual && <ManualRecordingDialog onClose={() => setManual(false)} />}
  </div>
}

function RecordingsBody() {
  const auth = useAuth()
  const [tab, setTab] = useState<Tab>('recorded')
  const tabs = useRef<(HTMLButtonElement | null)[]>([])
  const now = useSection('now')
  const conflicts = useSection('conflicts')
  const upcoming = useSection('upcoming')
  const series = useSection('series')
  const recorded = useSection('recorded')
  const [clock, setClock] = useState(() => Date.now())
  useEffect(() => { const timer = window.setInterval(() => setClock(Date.now()), 15_000); return () => window.clearInterval(timer) }, [])
  const [left, setLeft] = useState<string[]>([])
  const rules = [...series.rules, ...recorded.rules, ...upcoming.rules]
  const recordingNow = now.items.filter((r) => r.state === 'recording')
  const clashes = conflicts.items.filter((r) => !left.includes(r.id))
  const scheduled = [...upcoming.items, ...conflicts.items.filter((c) => !upcoming.items.some((u) => u.id === c.id))]
  const facts = upcoming.first ?? recorded.first
  // Each section's first page counts the whole section (API level 34); the loaded items count otherwise.
  const sections = recorded.first?.counts ?? upcoming.first?.counts ?? series.first?.counts
  const recordedComplete = recorded.query.isSuccess && !recorded.query.hasNextPage
  const unwatched = recordedComplete && hasWatchState(recorded.first) ? recorded.items.filter((r) => !r.watched).length : undefined
  const recordedCount = sections?.recorded.items ?? recorded.items.length
  const scheduledCount = sections ? sections.upcoming.items + sections.conflicts.items : scheduled.length
  const seriesCount = sections?.series.items ?? series.rules.length
  const counts: Record<Tab, ReactNode> = {
    recorded: unwatched ? <span className="n">{unwatched} unwatched</span> : recordedCount ? <span className="n">{recordedCount}{!sections && recorded.query.hasNextPage ? '+' : ''}</span> : null,
    scheduled: <>{scheduledCount ? <span className="n">{scheduledCount}</span> : null}{conflicts.items.length > 0 && <span role="img" aria-label=", one won’t record" className="text-warn"><WarnIcon size={14} /></span>}</>,
    series: seriesCount ? <span className="n">{seriesCount}</span> : null,
  }
  function onTabKey(event: KeyboardEvent<HTMLButtonElement>, i: number) {
    const step = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0
    if (!step) return
    event.preventDefault()
    const next = TABS[(i + step + TABS.length) % TABS.length]
    setTab(next.id)
    tabs.current[TABS.indexOf(next)]?.focus()
  }
  return <>
    <div className="mt-5 grid grid-cols-[repeat(auto-fit,minmax(min(420px,100%),1fr))] gap-4">
      {recordingNow.map((r) => <RecordingNowPanel key={r.id} r={r} now={clock} />)}
      <RoomPanel facts={recorded.first ?? facts} tuners={facts?.tuners} recordingNow={recordingNow.length} recorded={recorded.items} complete={recordedComplete} keeps={rules.some((r) => r.keep > 0)} />
    </div>
    {clashes.map((r) => <ClashAlert key={r.id} r={r} now={clock} onLeave={() => setLeft((ids) => [...ids, r.id])} />)}
    <div role="tablist" aria-label="Recordings" className="tabs mt-6">
      {TABS.map((t, i) => <button key={t.id} ref={(el) => { tabs.current[i] = el }} type="button" role="tab" id={`rec-tab-${t.id}`} aria-controls={`rec-panel-${t.id}`} aria-selected={tab === t.id} tabIndex={tab === t.id ? 0 : -1} onClick={() => setTab(t.id)} onKeyDown={(e) => onTabKey(e, i)} className="tab">{t.label}{counts[t.id]}</button>)}
    </div>
    <div role="tabpanel" id={`rec-panel-${tab}`} aria-labelledby={`rec-tab-${tab}`}>
      {tab === 'recorded' && <RecordedTab section={recorded} rules={rules} recordingNow={recordingNow} now={clock} />}
      {tab === 'scheduled' && <ScheduledTab items={scheduled} loading={upcoming.query.isPending} error={upcoming.query.error} rules={rules} now={clock} you={auth.user?.name} more={upcoming.query} />}
      {tab === 'series' && <SeriesTab rules={series.rules} loading={series.query.isPending} upcoming={scheduled} recordingNow={recordingNow} now={clock} you={auth.user?.name} />}
    </div>
  </>
}

/** Where a recording plays: from the library once it joined it, else from the recording itself. */
function WatchLink({ r, className, children, label }: { r: Recording; className: string; children: ReactNode; label?: string }) {
  const recording = r.state === 'recording'
  return r.mediaFileId && !recording
    ? <Link to="/play/$fileId" params={{ fileId: String(r.mediaFileId) }} search={{ title: r.title, back: '/live-tv/recordings' }} aria-label={label} className={className}>{children}</Link>
    : <Link to="/live-tv/recordings/$recordingId/watch" params={{ recordingId: r.id }} search={{ title: r.title, back: '/live-tv/recordings' }} aria-label={label} className={className}>{children}</Link>
}

function Still({ r, className, flag }: { r: Pick<Recording, 'pictureUrl' | 'title'> & Partial<Pick<Recording, 'watched' | 'progress'>>; className: string; flag?: ReactNode }) {
  const [broken, setBroken] = useState(false)
  const pct = recordingPct(r)
  return <span className={`relative block aspect-video shrink-0 overflow-hidden rounded-lg bg-surface-2 ring-1 ring-inset ring-white/[.07] ${className}`}>
    {r.pictureUrl && !broken ? <img src={r.pictureUrl} alt="" loading="lazy" onError={() => setBroken(true)} className="size-full object-cover" /> : <span aria-hidden="true" className="absolute inset-x-2 bottom-2 line-clamp-2 text-[12px] font-semibold uppercase tracking-wide text-neutral-300">{r.title}</span>}
    {flag}
    {r.watched && <span role="img" aria-label="Watched" className="absolute right-1.5 top-1.5 grid size-5 place-items-center rounded-full bg-ground/80 text-accent ring-1 ring-inset ring-accent/70"><CheckIcon size={11} /></span>}
    {pct > 0 && <span aria-hidden="true" className="absolute inset-x-0 bottom-0 h-1 bg-black/60"><span className="block h-full bg-accent" style={{ width: `${pct}%` }} /></span>}
  </span>
}

function RecordingNowPanel({ r, now }: { r: Recording; now: number }) {
  const [stop, setStop] = useState(false)
  const pct = recordingProgress(r.start, r.end, now)
  const sofar = Math.max(0, Math.round((now - Date.parse(r.startedAt ?? r.start)) / 60_000))
  const total = recordingMinutes(r)
  const line = [r.season != null || r.episodeTitle ? recordingEpisodeWords(r) : '', `${r.number} ${r.channelName}`, timeRange(Date.parse(r.start), Date.parse(r.end))].filter(Boolean).join(' · ')
  return <section aria-labelledby={`now-${r.id}`} className="panel flex flex-wrap items-center gap-x-[18px] gap-y-3.5 p-4">
    <Still r={r} className="w-[176px]" flag={<span className="absolute left-2 top-2 rounded-[5px] bg-red-700 px-[7px] py-0.5 text-[11px] font-bold text-white">Recording</span>} />
    <div className="flex min-w-[220px] flex-1 flex-col gap-1.5">
      <h2 id={`now-${r.id}`} className="text-xs font-bold uppercase tracking-[.08em] text-red-400">Recording now</h2>
      <p className="text-lg font-[650] leading-tight">{r.title}</p>
      <p className="text-[13px] text-neutral-400">{line}</p>
      <div className="flex items-center gap-2.5">
        <span className="meter flex-1" role="img" aria-label={`${sofar} of ${total} minutes recorded`}><i style={{ width: `${pct}%` }} /></span>
        <span className="whitespace-nowrap text-[13px] text-neutral-300">{sofar} min so far</span>
      </div>
      <div className="mt-1 flex flex-wrap gap-2">
        <WatchLink r={r} className="btn btn-primary btn-sm"><PlayIcon size={13} />Watch from the start</WatchLink>
        {r.canCancel && <button type="button" onClick={() => setStop(true)} className="btn btn-sm">Stop</button>}
      </div>
    </div>
    {stop && <RecordingOptions id={r.id} title={r.title} recording padBefore={r.padBefore} padAfter={r.padAfter} onClose={() => setStop(false)} />}
  </section>
}

/**
 * The room left as hours of recording, with how full the disk is; the
 * gigabytes and tuners behind it stay closed until asked for. The room
 * leaves out the space the recorder keeps free (API level 34).
 */
function RoomPanel({ facts, tuners, recordingNow, recorded, complete, keeps }: { facts?: Parameters<typeof storageFacts>[0] & { freeBytes?: number }; tuners?: number; recordingNow: number; recorded: Recording[]; complete: boolean; keeps: boolean }) {
  const store = storageFacts(facts)
  const room = roomWords(store.usable, recorded)
  const kept = store.recordings ?? (complete ? { items: recorded.length, bytes: recorded.reduce((n, r) => n + (r.bytes ?? 0), 0) } : undefined)
  if (!room && tuners === undefined) return null
  return <section aria-labelledby="h-room" className="panel flex flex-col gap-2.5 p-4">
    <h2 id="h-room" className="text-lg font-[650] leading-tight">{room ?? 'Recordings'}</h2>
    {store.usedPct !== undefined && <span className="meter" role="img" aria-label={`${store.usedPct}% of the recording disk is used`}><i style={{ width: `${store.usedPct}%` }} /></span>}
    <p className="text-[13px] text-neutral-400">{kept ? `${kept.items} ${kept.items === 1 ? 'recording' : 'recordings'} kept.` : 'Recordings join your library when they finish.'}{keeps ? ' Each series clears its own old episodes.' : ''}</p>
    <details className="tech mt-auto bg-surface-2">
      <summary className="!min-h-11">Storage and tuners</summary>
      <div className="tech-body">
        <dl className="dl">
          {facts?.freeBytes !== undefined && <><dt>Free space</dt><dd>{formatBytes(facts.freeBytes)}{facts.totalBytes ? ` of ${formatBytes(facts.totalBytes)}` : ''}</dd></>}
          {kept && <><dt>Recordings</dt><dd>{kept.items} · {formatBytes(kept.bytes)}</dd></>}
          {tuners !== undefined && <><dt>Tuners</dt><dd>{tuners}{recordingNow ? ` · ${recordingNow} recording now` : ''}</dd></>}
        </dl>
      </div>
    </details>
  </section>
}

function ClashAlert({ r, now, onLeave }: { r: Recording; now: number; onLeave: () => void }) {
  const [open, setOpen] = useState(false)
  const words = clashAlert(r, now)
  return <section role="alert" className="mt-4 flex flex-wrap items-center gap-x-4 gap-y-2.5 rounded-xl border border-warn/60 bg-warn/[.07] px-4 py-3.5">
    <span className="text-warn"><WarnIcon size={18} /></span>
    <p className="min-w-[240px] flex-1"><b className="font-semibold">{words.heading}</b> <span className="text-neutral-300">{words.detail}</span>{r.repeat && r.nextAiring && <span className="text-neutral-300"> It’s a repeat. {alsoOnWords(r.nextAiring, now)}.</span>}</p>
    {r.canCancel && canChooseClash(r) && <button type="button" aria-haspopup="dialog" onClick={() => setOpen(true)} className="btn btn-sm">Record it instead of…</button>}
    <button type="button" onClick={onLeave} className="btn btn-ghost btn-sm">Leave it</button>
    {open && <ClashDialog recording={r} onClose={() => setOpen(false)} />}
  </section>
}

function RecordedTab({ section, rules, recordingNow, now }: { section: ReturnType<typeof useSection>; rules: RecordingRule[]; recordingNow: Recording[]; now: number }) {
  const { query, items } = section
  const [chip, setChip] = useState<RecordedChip>('')
  const watchState = hasWatchState(section.first)
  const chips = recordedChips(items, watchState)
  const on = chips.some((c) => c.value === chip) ? chip : ''
  const shown = on ? items.filter((r) => inRecordedChip(r, on)) : items
  const { groups, oneOffs } = groupRecordings(shown, rules)
  if (query.isPending) return <p role="status" className="mt-4 text-sm text-neutral-400">Loading…</p>
  if (query.isError) return <QueryError error={query.error} retry={() => void query.refetch()} />
  if (!items.length) return <Empty>Nothing recorded yet. Record a programme from the guide, or choose New recording.</Empty>
  return <>
    {chips.length > 1 && <div role="group" aria-label="Show" className="mt-4 flex flex-wrap items-center gap-2">
      {chips.map((c) => <button key={c.value} type="button" aria-pressed={on === c.value} onClick={() => setChip(c.value)} className="chip max-md:min-h-10">{c.label}{c.count !== undefined && <span className="n">{c.count}</span>}</button>)}
    </div>}
    {!shown.length && <Empty>{on === 'unwatched' ? 'You’ve watched every recording here.' : 'No recordings of this kind.'}</Empty>}
    {groups.map((g) => <ShowGroup key={g.key} g={g} now={now} watchState={watchState} recording={recordingNow.find((r) => r.title.trim().toLowerCase() === g.key)} />)}
    {oneOffs.length > 0 && <section aria-labelledby="h-one" className="mt-8">
      <div className="rowh"><h2 id="h-one">Movies and one-offs</h2><span className="note">{oneOffs.length} {oneOffs.length === 1 ? 'recording' : 'recordings'}</span></div>
      <div className="mt-3.5 grid grid-cols-[repeat(auto-fill,minmax(min(250px,100%),1fr))] gap-x-4 gap-y-5">
        {oneOffs.map((r) => <OneOff key={r.id} r={r} now={now} />)}
      </div>
    </section>}
    {query.hasNextPage && <button type="button" className={`${secondaryAction} mt-4`} disabled={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>{query.isFetchingNextPage ? 'Loading…' : 'Show more'}</button>}
  </>
}

/** What went wrong with a recording, if anything: a gap, a stop, a failure. */
function trouble(r: Recording): string | undefined {
  if (r.gaps?.length) return gapWords(r.gaps[0], (a, b) => timeRange(a, b))
  if (r.state === 'failed' || r.state === 'partial') return reasonWords(r.reason, true) ?? (r.state === 'failed' ? 'It didn’t record' : 'Only part of it recorded')
  return undefined
}

function ShowGroup({ g, now, recording, watchState }: { g: RecordingGroup; now: number; recording?: Recording; watchState: boolean }) {
  const newest = g.items.find((r) => r.state !== 'failed')
  const latest = dayWords(g.latest, now)
  return <section aria-label={g.title} className="panel mt-3.5 px-4 pb-1 pt-4">
    <div className="flex flex-wrap items-center gap-x-4 gap-y-3 pb-3.5">
      <Still r={{ title: g.title, pictureUrl: g.picture }} className="w-[150px]" />
      <div className="min-w-[200px] flex-1">
        <h3 className="text-[17px] font-[650]">{g.title}</h3>
        <p className="mt-0.5 text-[13.5px] text-neutral-300">{groupLine(g.items, watchState)}{recording ? ` · ${recordingEpisodeWords(recording)} is recording now` : ''}</p>
        <p className="text-[13px] text-neutral-400">{[`Latest recorded ${latest === 'Today' || latest === 'Yesterday' ? latest.toLowerCase() : latest}`, g.rule?.keep ? `keeps the latest ${g.rule.keep}` : ''].filter(Boolean).join(' · ')}</p>
      </div>
      {newest && <WatchLink r={newest} className="btn btn-primary btn-sm"><PlayIcon size={13} />Watch {newest.season != null && newest.episode != null ? `S${newest.season} E${newest.episode}` : 'the latest'}</WatchLink>}
    </div>
    <ul>{g.items.map((r) => <EpisodeRow key={r.id} r={r} show={g.title} now={now} />)}</ul>
  </section>
}

function EpisodeRow({ r, show, now }: { r: Recording; show: string; now: number }) {
  const playable = r.state === 'done' || r.state === 'partial' || r.state === 'recording'
  const problem = trouble(r)
  const name = recordingEpisodeWords(r)
  return <li className="flex flex-wrap items-center gap-x-3.5 gap-y-2.5 border-t border-line py-3">
    {playable ? <WatchLink r={r} label={`Watch ${show}, ${name}`} className="block w-[128px] shrink-0 rounded-lg hover:ring-2 hover:ring-accent"><Still r={r} className="w-full" /></WatchLink> : <Still r={r} className="w-[128px] opacity-60" />}
    <div className="min-w-[180px] flex-1">
      <p className="flex flex-wrap items-center gap-2 font-semibold">{name}{r.new && !r.watched && <span className="pill pill-gold">New</span>}</p>
      <p className="text-[13px] text-neutral-400">{recordedWords(r, now)}{r.mediaFileId ? '' : r.state === 'done' ? ' · joining your library' : ''}</p>
      {problem && <p className="mt-0.5 flex items-center gap-1.5 text-[13px] text-warn"><WarnIcon size={13} />{problem}</p>}
    </div>
    {recordingLeftWords(r) && <span className={`whitespace-nowrap text-[13px] ${r.watched ? 'text-neutral-400' : 'text-accent'}`}>{recordingLeftWords(r)}</span>}
    {playable && <WatchLink r={r} className="btn btn-sm">Watch</WatchLink>}
    <RecordingMenu r={r} name={`${show}, ${name}`} />
  </li>
}

function OneOff({ r, now }: { r: Recording; now: number }) {
  const problem = trouble(r)
  const minutes = recordingMinutes(r)
  const body = <>
    <Still r={r} className="w-full" />
    <span className="mt-2 block truncate text-sm font-medium group-hover:text-accent">{r.title}</span>
    <span className="block truncate text-[12.5px] text-neutral-300">{r.kind !== undefined ? oneOffWords(r) : [r.episodeTitle, `${r.number} ${r.channelName}`, minutes ? `${minutes} min` : ''].filter(Boolean).join(' · ')}</span>
    <span className={`block truncate text-[12.5px] ${problem ? 'text-warn' : 'text-neutral-400'}`}>{problem ?? [recordedWords(r, now).replace(/ · \d+ min$/, ''), r.watched ? '' : recordingLeftWords(r)].filter(Boolean).join(' · ')}</span>
  </>
  return <div className="relative">
    {r.state === 'failed' ? <div className="opacity-70">{body}</div> : <WatchLink r={r} className="card group block rounded-lg">{body}</WatchLink>}
    <span className="absolute right-1.5 top-1.5"><RecordingMenu r={r} name={r.title} onPicture /></span>
  </div>
}

/** A recording's ⋯: delete it, choosing whether it records again. */
function RecordingMenu({ r, name, onPicture = false }: { r: Recording; name: string; onPicture?: boolean }) {
  const [open, setOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [dontRecord, setDontRecord] = useState(true)
  const remove = useRecordingMutation(deleteRecordingFile)
  const root = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const away = (e: PointerEvent) => { if (!root.current?.contains(e.target as Node)) setOpen(false) }
    const key = (e: globalThis.KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('pointerdown', away); document.addEventListener('keydown', key)
    return () => { document.removeEventListener('pointerdown', away); document.removeEventListener('keydown', key) }
  }, [open])
  if (!r.canDelete) return null
  return <div ref={root} className="relative">
    <button type="button" aria-haspopup="menu" aria-expanded={open} aria-label={`More for ${name}: delete, or don’t record it again`} onClick={() => setOpen((o) => !o)} className={`ib ib-sm ${onPicture ? 'border-white/15 bg-ground/70' : 'ib-ghost'}`}><MoreIcon size={16} /></button>
    {open && <div role="menu" className="menu absolute right-0 top-[calc(100%+6px)] w-56">
      <button type="button" role="menuitem" autoFocus onClick={() => { setOpen(false); setDeleting(true) }} className="menu-item">Delete…</button>
    </div>}
    {deleting && <Dialog title={`Delete ${name}?`} subtitle="This recording file is permanently deleted from the server." onClose={() => setDeleting(false)}><div className="space-y-4 p-6">
      <label className="flex min-h-11 items-center gap-3 text-sm"><input type="checkbox" checked={dontRecord} onChange={(e) => setDontRecord(e.target.checked)} className="h-4 w-4 accent-accent" />Don’t record it again</label>
      <button type="button" disabled={remove.isPending} className={primaryAction} onClick={() => remove.mutate({ id: r.id, recordAgain: !dontRecord }, { onSuccess: () => setDeleting(false) })}>{remove.isPending ? 'Deleting…' : 'Delete recording'}</button>
      {remove.isError && <p role="alert" className="text-sm text-warn">{remove.error.message}</p>}
    </div></Dialog>}
  </div>
}

function ScheduledTab({ items, loading, error, rules, now, you, more }: { items: Recording[]; loading: boolean; error: Error | null; rules: RecordingRule[]; now: number; you?: string; more: ReturnType<typeof useSection>['query'] }) {
  if (loading) return <p role="status" className="mt-4 text-sm text-neutral-400">Loading…</p>
  if (error) return <QueryError error={error} retry={() => void more.refetch()} />
  if (!items.length) return <Empty>Nothing is scheduled. Record a programme from the guide, or choose New recording.</Empty>
  return <>
    <p className="mt-4 text-[13.5px] text-neutral-400">This schedule is what will happen. A mark in the guide means the same thing.</p>
    {scheduleDays(items, now).map((d) => <section key={d.key} aria-labelledby={`day-${d.key}`} className="mt-5">
      <h2 id={`day-${d.key}`} className="text-xs font-bold uppercase tracking-[.08em] text-dim">{d.label}</h2>
      <ul className="panel mt-2 px-4">{d.items.map((r) => <ScheduledRow key={r.id} r={r} rule={rules.find((rule) => rule.id === r.ruleId)} you={you} now={now} />)}</ul>
    </section>)}
    {more.hasNextPage && <button type="button" className={`${secondaryAction} mt-4`} disabled={more.isFetchingNextPage} onClick={() => void more.fetchNextPage()}>{more.isFetchingNextPage ? 'Loading…' : 'Show more'}</button>}
  </>
}

function ScheduledRow({ r, rule, you, now }: { r: Recording; rule?: RecordingRule; you?: string; now: number }) {
  const [clash, setClash] = useState(false)
  const [options, setOptions] = useState(false)
  const skip = useRecordingMutation(cancelRecording)
  const conflict = r.state === 'conflict'
  const sub = [r.season != null || r.episodeTitle ? recordingEpisodeWords(r) : '', `${r.number} ${r.channelName}`].filter(Boolean).join(' · ')
  const who = r.userName && r.userName === you ? 'you' : r.userName
  const how = rule ? ruleWords(rule, rule.kind === 'manual' ? manualRepeatWords(rule) : undefined, you) : `Just this one${who ? ` · set by ${who}` : ''}`
  return <li className="flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-line py-3.5 last:border-b-0">
    <span className="flex-[0_0_76px] whitespace-nowrap font-semibold tabular-nums">{timeOfDay(Date.parse(r.start))}</span>
    <div className="min-w-[200px] flex-1">
      <p className="font-semibold">{r.title}</p>
      <p className="text-[13px] text-neutral-400">{sub}</p>
      {conflict && <p className="mt-0.5 flex items-center gap-1.5 text-[13px] text-warn"><WarnIcon size={13} />{conflictWords(r.reason, r.conflictsWith)}</p>}
      {conflict && r.repeat && r.nextAiring && <p className="text-[13px] text-neutral-400">A repeat. {alsoOnWords(r.nextAiring, now)}.</p>}
    </div>
    <span className="flex-[0_1_250px] text-[13px] text-neutral-400">{how}</span>
    {r.canCancel && <div className="flex flex-wrap gap-1.5">
      {conflict && canChooseClash(r) && <button type="button" aria-haspopup="dialog" onClick={() => setClash(true)} className="btn btn-sm">Record this instead</button>}
      <button type="button" disabled={skip.isPending} onClick={() => skip.mutate(r.id)} className="btn btn-ghost btn-sm">{conflict ? 'Don’t record it' : 'Skip this one'}</button>
      {!conflict && <button type="button" onClick={() => setOptions(true)} className="btn btn-ghost btn-sm">Options</button>}
    </div>}
    {skip.isError && <p role="alert" className="basis-full text-sm text-warn">{skip.error.message}</p>}
    {clash && <ClashDialog recording={r} onClose={() => setClash(false)} />}
    {options && <RecordingOptions id={r.id} title={r.title} recording={false} padBefore={r.padBefore} padAfter={r.padAfter} onClose={() => setOptions(false)} />}
  </li>
}

function SeriesTab({ rules, loading, upcoming, recordingNow, now, you }: { rules: RecordingRule[]; loading: boolean; upcoming: Recording[]; recordingNow: Recording[]; now: number; you?: string }) {
  if (loading) return <p role="status" className="mt-4 text-sm text-neutral-400">Loading…</p>
  if (!rules.length) return <Empty>No series or repeating recordings. Choose Record in the guide and then Every new episode, or make a repeating New recording.</Empty>
  return <>
    <ul className="panel mt-5 px-4">{rules.map((rule) => <RuleRow key={rule.id} rule={rule} you={you} now={now} recording={recordingNow.find((r) => r.ruleId === rule.id)} next={upcoming.filter((r) => r.ruleId === rule.id).sort((a, b) => Date.parse(a.start) - Date.parse(b.start))[0]} />)}</ul>
    <p className="mt-3 text-[13px] text-neutral-400">Stopping a series keeps what it has already recorded.</p>
  </>
}

function RuleRow({ rule: r, you, now, recording, next }: { rule: RecordingRule; you?: string; now: number; recording?: Recording; next?: Recording }) {
  const [edit, setEdit] = useState(false), [remove, setRemove] = useState(false)
  const change = useRecordingMutation(deleteRecordingRule)
  const nextWords = next ? `${dayWords(Date.parse(next.start), now, undefined, true).replace(/^(Today|Tonight)$/, (d) => d.toLowerCase())} at ${timeOfDay(Date.parse(next.start))} on ${next.number} ${next.channelName}` : undefined
  return <li className="flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-line py-3.5 last:border-b-0">
    <div className="min-w-[220px] flex-1">
      <p className="text-base font-[650]">{r.title}</p>
      <p className="text-[13px] text-neutral-400">{ruleWords(r, r.kind === 'manual' ? manualRepeatWords(r) : undefined, you)}</p>
      {(recording || nextWords) && <p className="mt-0.5 flex items-center gap-[7px] text-[13px] text-neutral-300">{recording && <span aria-hidden="true" className="size-2 rounded-full bg-red-400" />}{[recording ? 'Recording now' : '', nextWords ? `${recording ? 'then' : 'Next:'} ${nextWords}` : ''].filter(Boolean).join(' · ')}</p>}
    </div>
    {r.canCancel && <div className="flex flex-wrap gap-1.5">{r.kind !== 'manual' && <button type="button" className="btn btn-sm" onClick={() => setEdit(true)}>Options</button>}<button type="button" className="btn btn-ghost btn-sm" onClick={() => setRemove(true)}>Stop</button></div>}
    {edit && <RuleOptions rule={r} onClose={() => setEdit(false)} />}
    {remove && <Dialog title={`Stop recording ${r.title}?`} subtitle="What it has already recorded is kept. Upcoming recordings from it are cancelled." onClose={() => setRemove(false)}><div className="space-y-4 p-6"><button type="button" disabled={change.isPending} className={primaryAction} onClick={() => change.mutate(r.id, { onSuccess: () => setRemove(false) })}>Stop</button>{change.isError && <p role="alert" className="text-warn">{change.error.message}</p>}</div></Dialog>}
  </li>
}

function Empty({ children }: { children: ReactNode }) {
  return <p className="mt-5 rounded-xl border border-dashed border-line-2 px-5 py-8 text-sm text-neutral-400">{children}</p>
}

function QueryError({ error, retry }: { error: Error; retry: () => void }) {
  return <div role="alert" className="mt-4 space-y-2 text-sm text-warn"><p>{error.message}</p><button type="button" onClick={retry} className={secondaryAction}>Try again</button></div>
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
