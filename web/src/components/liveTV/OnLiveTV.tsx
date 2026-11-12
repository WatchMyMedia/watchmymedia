import { useEffect, useState } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { liveTVGuideQuery, liveTVSearchQuery } from '../../api/queries'
import { FETCH_HOURS, episodeLine, fetchBlock, isOn, progress, timeOfDay } from '../../lib/liveGuide'
import { dayShort, searchTerm, searchWhen } from '../../lib/liveReminders'
import { HitPicture } from './LiveGuide'
import { RemindControls } from './LiveReminders'
import { useGuideExtras, useReminders } from './useGuideExtras'

/**
 * The Search page's On Live TV (PLAN-LIVE-TV-GUIDE.md §6.5; the LiveSearch
 * board): programmes on now and later whose titles have the words, between
 * the library's results and Not in your library. Left out when nothing
 * matches, or without listings at API level 15.
 */
export function OnLiveTV({ q }: { q: string }) {
  const extras = useGuideExtras()
  const term = searchTerm(q)
  const results = useQuery({ ...liveTVSearchQuery(term ?? ''), enabled: extras && !!term, placeholderData: keepPreviousData })
  const { lead, byProgramme } = useReminders(extras && !!results.data?.items.length)
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => { const timer = window.setInterval(() => setNow(Date.now()), 30_000); return () => window.clearInterval(timer) }, [])
  const listedThrough = useQuery({ ...liveTVGuideQuery(fetchBlock(now), FETCH_HOURS), enabled: extras && !!results.data?.items.length, select: (g) => g.state.listedThrough })
  const navigate = useNavigate()
  const hits = results.data?.items ?? []
  if (!extras || !term || hits.length === 0) return null
  const ends = listedThrough.data ? Date.parse(listedThrough.data) : undefined
  return <section aria-labelledby="on-live-tv" className="space-y-3">
    <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
      <h2 id="on-live-tv" className="text-lg font-semibold">On Live TV</h2>
      <span className="text-[13px] text-neutral-400">On now and later{ends ? `, until the listings end ${dayShort(ends, now)} ${timeOfDay(ends)}` : ''}</span>
    </div>
    <ul className="grid grid-cols-[repeat(auto-fill,minmax(min(16rem,100%),1fr))] gap-x-4 gap-y-5">
      {hits.map(({ channel, programme: p }) => {
        const on = isOn(p, now)
        return <li key={p.id} className="flex min-w-0 flex-col gap-2">
          <button type="button" onClick={() => void navigate({ to: '/live-tv', search: { channel: channel.id, programme: p.id, at: p.start } })}
            aria-label={`${p.title}${episodeLine(p) ? `, ${episodeLine(p)}` : ''}, ${searchWhen(p, channel.number, now)} ${channel.name}. Open in the guide`} className="group flex min-w-0 flex-col gap-1.5 text-left">
            <span className="relative block">
              <HitPicture p={p} channel={channel} className="w-full rounded-md ring-1 ring-line-2" />
              {on && <span className="absolute left-2 top-2 inline-flex items-center gap-1.5 rounded bg-brand px-2 py-0.5 text-[11px] font-bold tracking-wider text-white ring-1 ring-brand-line"><span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-current" />LIVE</span>}
            </span>
            <span className={`flex min-w-0 items-center gap-1.5 text-xs font-semibold ${on ? 'text-red-300' : 'text-neutral-300'}`}>{channel.logoUrl && <img src={channel.logoUrl} alt="" className="h-4 w-7 shrink-0 object-contain object-left" />}<span className="truncate">{searchWhen(p, channel.number, now)}</span></span>
            <span className="truncate text-sm font-semibold group-hover:text-accent">{p.title}</span>
            <span className="truncate text-xs text-neutral-400">{episodeLine(p) || p.categories?.join(' · ')}</span>
          </button>
          {on ? <>
            <span aria-hidden="true" className="block h-1 overflow-hidden rounded-full bg-line-2"><span className="block h-full bg-accent" style={{ width: `${progress(p, now)}%` }} /></span>
            {channel.available && <Link to="/live-tv/watch/$channelId" params={{ channelId: channel.id }} className="inline-flex min-h-9 items-center gap-2 self-start rounded-md btn-primary px-3.5 text-sm font-semibold"><svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M7 4v16l13-8z" /></svg>Watch {channel.number}</Link>}
          </> : <div className="flex flex-wrap items-center gap-1"><RemindControls programme={p} reminder={byProgramme.get(p.id)} lead={lead} now={now} primary={false} compact /></div>}
        </li>
      })}
    </ul>
  </section>
}
