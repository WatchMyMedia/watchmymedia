import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { liveTVCapabilitiesQuery, liveTVChannelsQuery, serverQuery } from '../api/queries'
import { useAuth } from '../authContext'
import { filterLiveChannels, guideTimes } from '../lib/liveTV'
import { RecordingsLink } from '../components/liveTV/LiveRecordings'
import { LiveBadge } from '../components/liveTV/LiveBadge'
import { ListingsGuide } from '../components/liveTV/LiveGuide'
import { showListings } from '../lib/liveGuide'
import { serverRecords } from '../lib/liveRecordings'
import type { GuideSearch } from '../lib/routeSearch'

export function LiveTV({ open }: { open?: GuideSearch } = {}) {
  const auth = useAuth()
  const admin = !!auth.user?.isAdmin && auth.adminAllowed
  const caps = useQuery(liveTVCapabilitiesQuery)
  const server = useQuery(serverQuery)
  const listings = showListings(server.data?.apiLevel, caps.data)
  const channels = useQuery({ ...liveTVChannelsQuery, enabled: !!caps.data?.allowed && !!caps.data?.enabled && !listings })
  const [query, setQuery] = useState('')
  const [selectedId, setSelectedId] = useState('')
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => { const timer = window.setInterval(() => setNow(Date.now()), 30_000); return () => window.clearInterval(timer) }, [])
  const filtered = filterLiveChannels(channels.data?.items ?? [], query)
  const selected = filtered.find((channel) => channel.id === selectedId) ?? filtered[0]
  const times = guideTimes(now)
  const minuteOffset = (now - times[0].getTime()) / 60_000
  const timeLabel = (date: Date) => date.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })
  if (caps.isPending) return <p className="text-neutral-400">Loading Live TV…</p>
  if (caps.isError) return <p role="alert" className="text-warn">{caps.error.message}</p>
  if (!caps.data.enabled || !caps.data.allowed) return <section className="mx-auto my-14 max-w-lg space-y-4 text-center">
    <AntennaIcon />
    <h1 className="text-2xl font-semibold">{!caps.data.enabled ? (caps.data.setupComplete ? 'Live TV is off' : 'Live TV is not set up') : 'Live TV is not available to this account'}</h1>
    <p className="text-neutral-400">{admin ? (caps.data.setupComplete ? 'Enable your tuner and choose who can watch in Live TV settings.' : 'Connect your HDHomeRun antenna tuner and choose who can watch.') : 'Ask your server’s admin to enable Live TV for your account.'}</p>
    {admin && <Link to="/settings/live-tv" className="inline-flex min-h-11 items-center rounded-md btn-primary px-5 font-semibold">{caps.data.setupComplete ? 'Open Live TV settings' : 'Set up Live TV'}</Link>}
  </section>
  // Without listings (an older server, listings off, or none yet) the guide stays as 0.5.0 drew it.
  if (listings) return <ListingsGuide key={open?.programme ?? ''} admin={admin} playback={!!caps.data.playback} open={open} />
  return <div className="mx-auto max-w-7xl space-y-5">
    <header className="flex flex-wrap items-end justify-between gap-3">
      <div><p className="mb-1 text-xs font-semibold uppercase tracking-widest text-neutral-400">Your antenna, anywhere</p><h1 className="text-3xl font-semibold tracking-tight">Live TV</h1><RecordingsLink /></div>
      <p className="text-sm tabular-nums text-neutral-400">{channels.data?.items.length ?? 0} channels · {timeLabel(new Date(now))}</p>
    </header>
    {channels.isError && <p role="alert" className="rounded-lg border border-warn/50 p-4 text-warn">{channels.error.message}</p>}
    {channels.isPending && <p className="py-10 text-neutral-400">Loading channels…</p>}
    {selected && <section aria-label="Selected channel" className="flex flex-wrap items-center gap-5 rounded-xl border border-line bg-surface p-5 sm:gap-8 sm:p-6">
      <div className="grid h-24 w-28 shrink-0 place-items-center rounded-lg border border-line-2 bg-ground text-4xl font-bold tabular-nums tracking-tight text-accent">{selected.number}</div>
      <div className="min-w-0 flex-1 space-y-2"><LiveBadge /><h2 className="break-words text-xl font-semibold sm:text-2xl">{selected.name}</h2><p className="text-sm text-neutral-400">{selected.available ? 'No programme listings. Watch the current broadcast.' : 'This channel is no longer in the tuner’s latest lineup.'}</p></div>
      {selected.available && caps.data.playback ? <Link to="/live-tv/watch/$channelId" params={{ channelId: selected.id }} className="inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-md btn-primary px-6 font-semibold sm:w-auto"><span aria-hidden="true">▶</span> Watch live</Link> : <span className="text-sm text-warn">{selected.available ? 'Playback unavailable' : 'Unavailable'}</span>}
    </section>}
    <div className="flex flex-wrap items-center justify-between gap-3">
      <h2 className="text-lg font-semibold">Channels <span className="ml-2 text-sm font-normal text-neutral-400">Today</span></h2>
      <label className="w-full sm:w-72"><span className="sr-only">Go to channel by number or name</span><input type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Go to channel: 7.1 or name" className="min-h-11 w-full rounded-md border border-line-2 bg-surface px-3 text-sm placeholder:text-neutral-400" /></label>
    </div>
    {filtered.length > 0 ? <section aria-label="Channel guide" className="overflow-hidden rounded-xl border border-line bg-ground">
      <div className="flex border-b border-line-2 bg-surface">
        <div className="flex w-30 shrink-0 items-center px-3 text-xs font-semibold uppercase tracking-wide text-neutral-400 sm:w-42">Channel</div>
        <div className="grid flex-1 grid-cols-2 sm:grid-cols-4">{times.map((time, index) => <div key={time.getTime()} className={`border-l border-line px-3 py-3 text-xs font-medium tabular-nums text-neutral-300 ${index > 1 ? 'hidden sm:block' : ''}`}>{timeLabel(time)}</div>)}</div>
      </div>
      <div className="relative">
        <ul className="divide-y divide-line">{filtered.map((channel) => <li key={channel.id}>
          <button type="button" onClick={() => setSelectedId(channel.id)} aria-pressed={selected.id === channel.id} className={`flex min-h-18 w-full text-left hover:bg-surface-2 ${selected.id === channel.id ? 'relative z-10 bg-surface ring-2 ring-inset ring-accent' : ''}`}>
            <span className="flex w-30 shrink-0 flex-col justify-center gap-1 border-r border-line-2 px-3 sm:w-42 sm:flex-row sm:items-center sm:justify-start sm:gap-3"><strong className="text-lg tabular-nums">{channel.number}</strong><span className="line-clamp-2 text-xs text-neutral-300 sm:text-sm">{channel.name}</span></span>
            <span className="relative flex flex-1 items-center px-4"><span aria-hidden="true" className="pointer-events-none absolute inset-0 grid grid-cols-2 sm:grid-cols-4">{times.map((time, index) => <span key={time.getTime()} className={`border-l border-line ${index > 1 ? 'hidden sm:block' : ''}`} />)}</span><span className={`relative text-sm ${channel.available ? 'text-neutral-400' : 'text-warn'}`}>{channel.available ? 'No listings' : 'Unavailable'}</span></span>
          </button>
        </li>)}</ul>
        <div aria-hidden="true" className="pointer-events-none absolute inset-y-0 right-0 left-30 sm:left-42"><span className="absolute inset-y-0 w-px bg-accent/60 sm:hidden" style={{ left: `${minuteOffset / 60 * 100}%` }} /><span className="absolute inset-y-0 hidden w-px bg-accent/60 sm:block" style={{ left: `${minuteOffset / 120 * 100}%` }} /></div>
      </div>
    </section> : !channels.isPending && <section className="rounded-xl border border-dashed border-line-2 p-10 text-center"><h2 className="text-lg font-semibold">{query ? 'No matching channels' : 'No channels yet'}</h2><p className="mt-2 text-sm text-neutral-400">{query ? 'Try a channel number or another name.' : 'Run a channel scan in the HDHomeRun app, then refresh the lineup in Live TV settings.'}</p>{admin && !query && <Link to="/settings/live-tv" className="mt-4 inline-flex min-h-11 items-center underline">Open Live TV settings</Link>}</section>}
    <p className="text-xs leading-relaxed text-neutral-400">Live broadcasts use a tuner while you watch. A brief rolling buffer supports pauses.{serverRecords(server.data?.apiLevel, caps.data) && ' Scheduled recordings have priority and may release a tuner someone is watching on.'}</p>
  </div>
}

function AntennaIcon() { return <svg aria-hidden="true" viewBox="0 0 48 48" fill="none" stroke="currentColor" strokeWidth="2" className="mx-auto h-12 w-12 text-accent"><rect x="7" y="16" width="34" height="25" rx="5" /><path d="m15 6 9 10L34 6M18 41v3m12-3v3" /><path d="m21 23 10 6-10 6z" /></svg> }
