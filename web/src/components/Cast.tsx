import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { liveTVChannelsQuery } from '../api/queries'
import { chooseDevice, playOrPause, send, stopCasting, useCast, type CastSnapshot } from '../cast/sender'
import type { LiveInfo, StateMessage } from '../cast/protocol'
import { liveBar, remoteChannel } from '../lib/liveCast'
import { LiveBadge } from './liveTV/LiveBadge'

/** Google's Cast icon; filled while connected. */
export function CastIcon({ connected, size = 18 }: { connected?: boolean; size?: number }) {
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} aria-hidden="true" fill="currentColor">
      <path d="M21 3H3c-1.1 0-2 .9-2 2v3h2V5h18v14h-7v2h7c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM1 18v3h3c0-1.66-1.34-3-3-3zm0-4v2c2.76 0 5 2.24 5 5h2c0-3.87-3.13-7-7-7zm0-4v2c4.97 0 9 4.03 9 9h2c0-6.08-4.93-11-11-11z" />
      {connected && <path d="M5 7v1.63c3.96 1.28 7.09 4.41 8.37 8.37H19V7H5z" />}
    </svg>
  )
}

/**
 * The header's Cast button (PLAN-CAST.md §5.1): shown while a Cast device is
 * on the network. It opens Chrome's device picker, or while connected a menu
 * with the TV's name and Stop casting. In the live player (variant player)
 * notLive says the connected TV can't play a channel yet: the menu opens
 * once by itself to say so (the LiveCast board's notLive state).
 */
export function CastButton({ variant = 'header', notLive = false }: { variant?: 'header' | 'player'; notLive?: boolean }) {
  const cast = useCast()
  const [menu, setMenu] = useState(false)
  const [told, setTold] = useState(false)
  const root = useRef<HTMLDivElement>(null)
  const connected = cast.status === 'connected'
  if (notLive && connected && !told) {
    setTold(true)
    setMenu(true)
  }
  if (!connected && told) setTold(false)
  useEffect(() => {
    if (!menu) return
    const away = (e: PointerEvent) => { if (!root.current?.contains(e.target as Node)) setMenu(false) }
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') setMenu(false) }
    document.addEventListener('pointerdown', away)
    document.addEventListener('keydown', key)
    return () => { document.removeEventListener('pointerdown', away); document.removeEventListener('keydown', key) }
  }, [menu])
  if (cast.status === 'unavailable' || cast.status === 'noDevices') return null
  const device = cast.deviceName ?? 'a TV'
  const look = variant === 'player'
    ? `grid min-h-11 min-w-11 place-items-center rounded-md bg-white/10 px-3 hover:bg-white/20 ${connected ? 'text-accent' : 'text-white'} ${menu && connected ? 'ring-1 ring-inset ring-accent' : ''}`
    : `grid h-9 w-9 place-items-center rounded-md border border-line-2 bg-surface hover:text-neutral-100 ${connected ? 'text-accent' : 'text-neutral-300'}`
  return (
    <div ref={root} className="relative shrink-0">
      <button
        type="button"
        onClick={() => (connected ? setMenu((m) => !m) : void chooseDevice())}
        aria-label={connected ? `${notLive ? 'Connected' : 'Casting'} to ${device}` : 'Cast to a TV'}
        aria-haspopup={connected ? 'menu' : undefined}
        aria-expanded={connected ? menu : undefined}
        title={connected ? `${notLive ? 'Connected' : 'Casting'} to ${device}` : 'Cast'}
        className={`${look} ${cast.status === 'connecting' ? 'animate-pulse' : ''}`}
      >
        <CastIcon connected={connected} />
      </button>
      {menu && connected && (
        <div role="menu" aria-label="Cast" className={`absolute right-0 top-[calc(100%+8px)] z-40 rounded-[10px] border border-line-2 bg-surface p-1.5 text-sm text-neutral-200 shadow-2xl ${notLive ? 'w-80 max-w-[calc(100vw-1.5rem)]' : 'w-60'}`}>
          <p className="mx-2.5 mb-1.5 mt-1 text-[11px] font-semibold uppercase tracking-wider text-neutral-400">{notLive ? 'Connected' : 'Casting'} to {device}</p>
          {notLive && (
            <div className="mx-1 mb-2 flex gap-2.5 rounded-lg border border-line-2 bg-ground p-2.5">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="mt-0.5 shrink-0 text-warn"><circle cx="12" cy="12" r="9" /><path d="M12 11v5M12 8h.01" /></svg>
              <span className="flex flex-col gap-1 text-[13px] leading-snug text-neutral-300"><b className="text-sm font-semibold text-neutral-100">This TV can’t play Live TV yet</b>The WatchMyMedia on {device} is older than Live TV casting. Once it updates, channels cast like anything else. Live TV keeps playing here.</span>
            </div>
          )}
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              setMenu(false)
              stopCasting()
            }}
            className="block min-h-10 w-full rounded-md px-2.5 py-2 text-left hover:bg-surface-2"
          >
            Stop casting
          </button>
        </div>
      )}
    </div>
  )
}

/** "Casting to …" along the bottom of every page while the TV plays something. */
export function CastBar() {
  const cast = useCast()
  const r = cast.receiver
  if (cast.status === 'connected' && r?.live && (r.phase === 'playing' || r.phase === 'loading')) return <LiveCastBar cast={cast} receiver={r} live={r.live} />
  if (cast.status !== 'connected' || !r?.fileId || (r.phase !== 'playing' && r.phase !== 'loading')) return null
  return (
    <div className="sticky bottom-0 z-30 border-t border-line bg-ground/95 backdrop-blur">
      <div className="flex items-center gap-3 px-4 py-2 md:px-12">
        <span className="text-accent">
          <CastIcon connected />
        </span>
        <Link to="/play/$fileId" params={{ fileId: String(r.fileId) }} search={{ title: r.subtitle ? `${r.title} · ${r.subtitle}` : r.title }} className="min-w-0 flex-1">
          <div className="truncate text-sm font-semibold">{r.title}</div>
          <div className="truncate text-xs text-neutral-400">
            {r.subtitle ? `${r.subtitle} · ` : ''}on {cast.deviceName ?? 'TV'}
          </div>
        </Link>
        <button type="button" onClick={playOrPause} aria-label={cast.paused ? 'Play' : 'Pause'} className="grid h-10 w-10 place-items-center rounded-md bg-surface text-neutral-100 hover:bg-surface-2">
          {cast.paused ? '▶' : '❚❚'}
        </button>
      </div>
    </div>
  )
}

/**
 * The casting bar while the TV plays a channel (the LiveCast board's bar
 * view): the channel and what is on, leading back to the live remote. The
 * logo and episode line come from this page's channel list; the TV's state
 * carries text only.
 */
function LiveCastBar({ cast, receiver: r, live }: { cast: CastSnapshot; receiver: StateMessage; live: LiveInfo }) {
  const channels = useQuery(liveTVChannelsQuery)
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => { const timer = window.setInterval(() => setNow(Date.now()), 30_000); return () => window.clearInterval(timer) }, [])
  const [brokenLogo, setBrokenLogo] = useState('')
  const channel = remoteChannel(live.channelId, live, channels.data?.items)
  const device = cast.deviceName ?? 'TV'
  const bar = liveBar({ receiver: r, channel, device, now })
  if (!bar) return null
  const logo = channel.logoUrl && brokenLogo !== channel.logoUrl ? channel.logoUrl : undefined
  const name = [channel.number, channel.name].filter(Boolean).join(' ')
  return (
    <div className="sticky bottom-0 z-30 border-t border-line bg-ground/95 backdrop-blur">
      <div className="flex items-center gap-3 px-4 py-2 md:px-12">
        <span className="text-accent">
          <CastIcon connected />
        </span>
        <Link to="/live-tv/watch/$channelId" params={{ channelId: live.channelId }} aria-label={`Back to the remote for ${name} on ${device}`} className="flex min-w-0 flex-1 items-center gap-3">
          {logo && <img src={logo} alt="" onError={() => setBrokenLogo(logo)} className="h-7 w-10 shrink-0 object-contain" />}
          <span className="flex min-w-0 flex-1 flex-col gap-0.5">
            <span className="flex min-w-0 items-center gap-2"><span className="truncate text-sm font-semibold">{bar.title}</span>{bar.badge && <LiveBadge behind={!!live.paused} />}</span>
            <span className="truncate text-xs text-neutral-400">{bar.sub}</span>
            {bar.pct > 0 && <span aria-hidden="true" className="mt-0.5 block h-[3px] max-w-80 overflow-hidden rounded-full bg-white/15"><span className="block h-full bg-accent" style={{ width: `${bar.pct}%` }} /></span>}
          </span>
        </Link>
        {bar.ask && <button type="button" onClick={() => void send({ type: 'stillWatching', keep: true })} className="min-h-10 shrink-0 rounded-md btn-primary px-4 text-sm font-semibold">Keep watching</button>}
        {bar.controls && <button type="button" onClick={playOrPause} aria-label={cast.paused ? `Play on ${device}` : `Pause on ${device}`} className="grid h-10 w-10 shrink-0 place-items-center rounded-md border border-line-2 bg-surface text-neutral-100 hover:bg-surface-2">
          <PlayPauseIcon paused={cast.paused} />
        </button>}
      </div>
    </div>
  )
}

/** Play while paused, Pause while playing. */
export function PlayPauseIcon({ paused, size = 16 }: { paused: boolean; size?: number }) {
  return paused
    ? <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M7 4v16l13-8z" /></svg>
    : <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><rect x="6" y="5" width="4" height="14" rx="1" /><rect x="14" y="5" width="4" height="14" rx="1" /></svg>
}
