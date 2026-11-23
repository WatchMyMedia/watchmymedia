import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { liveTVGuideQuery } from '../../api/queries'
import type { components } from '../../api/schema'
import { FETCH_HOURS, duration, episodeLine, fetchBlock, isOn, progress, timeOfDay } from '../../lib/liveGuide'
import { dayShort } from '../../lib/liveReminders'
import { PlayIcon } from '../icons'
import { useNow } from '../useNow'
import { RecordControls } from './LiveRecordings'
import { RemindControls } from './LiveReminders'
import { useReminders } from './useGuideExtras'

type Hit = components['schemas']['LiveTVSearchHit']

/**
 * The Search page's On live TV: programmes on now and later whose titles
 * have the words, each with its channel, when it is on, and what can be done
 * now: Watch while it's on, Record, Remind me. The title opens the guide at
 * it. Drawn by the page once the guide has answered with something.
 */
export function OnLiveTV({ hits }: { hits: readonly Hit[] }) {
  const now = useNow()
  const { lead, byProgramme } = useReminders(hits.length > 0)
  const listedThrough = useQuery({ ...liveTVGuideQuery(fetchBlock(now), FETCH_HOURS), enabled: hits.length > 0, select: (g) => g.state.listedThrough })
  if (!hits.length) return null
  const ends = listedThrough.data ? Date.parse(listedThrough.data) : undefined
  return (
    <section aria-labelledby="h-live">
      <div className="rowh">
        <h2 id="h-live">On live TV</h2>
        <span className="note">
          {hits.length}
          {ends ? ` · until the listings end ${dayShort(ends, now)} ${timeOfDay(ends)}` : ''}
        </span>
        <Link to="/live-tv" className="more">
          Guide ›
        </Link>
      </div>
      <ul className="panel mt-3.5">
        {hits.map((hit) => (
          <HitRow key={hit.programme.id} hit={hit} now={now} lead={lead} reminder={byProgramme.get(hit.programme.id)} />
        ))}
      </ul>
    </section>
  )
}

function HitRow({ hit: { channel, programme: p }, now, lead, reminder }: { hit: Hit; now: number; lead: number; reminder: Parameters<typeof RemindControls>[0]['reminder'] }) {
  const on = isOn(p, now)
  const start = Date.parse(p.start)
  const end = Date.parse(p.end)
  const line = [episodeLine(p) || p.categories?.slice(0, 2).join(' · ') || '', p.new ? 'New' : ''].filter(Boolean).join(' · ')
  const when = on ? `until ${timeOfDay(end)} · ${duration(end - now)} left` : `${dayShort(start, now)} ${timeOfDay(start)} · ${duration(end - start)}`
  const short = channel.name.split(/\s+/)[0]
  return (
    <li className="flex flex-wrap items-center gap-3.5 border-t border-line px-3.5 py-3 first:border-t-0">
      <span role="img" aria-label={`Channel ${channel.number} ${channel.name}`} className="flex min-h-[54px] w-16 shrink-0 flex-col items-center justify-center rounded-[10px] border border-line-2 bg-surface-2 leading-tight">
        <b className="text-base font-bold">{channel.number}</b>
        <small className="max-w-full truncate px-1 text-[11px] font-semibold tracking-[.04em] text-neutral-400">{short}</small>
      </span>
      <div className="flex min-w-0 flex-[1_1_260px] flex-col gap-1">
        <Link
          to="/live-tv"
          search={{ channel: channel.id, programme: p.id, at: p.start }}
          aria-label={`${p.title}${line ? `, ${line}` : ''}, ${on ? 'on now' : `${dayShort(start, now)} ${timeOfDay(start)}`} on ${channel.number} ${channel.name}. Open in the guide`}
          className="truncate text-[15px] font-medium hover:text-accent"
        >
          {p.title}
        </Link>
        {line && <p className="truncate text-[12.5px] text-neutral-400">{line}</p>}
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
          {on && <span className="pill bg-red-400/15 text-red-400">On now</span>}
          <span className="text-[13px] text-neutral-300">{when}</span>
          {on && (
            <span className="meter min-w-20 flex-[0_1_140px]" role="img" aria-label={`${Math.round(progress(p, now))}% through`}>
              <i style={{ width: `${progress(p, now)}%` }} />
            </span>
          )}
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        {on && channel.available && (
          <Link to="/live-tv/watch/$channelId" params={{ channelId: channel.id }} className="btn btn-primary">
            <PlayIcon size={15} />
            Watch {channel.number}
          </Link>
        )}
        <RecordControls programme={p} now={now} />
        {!on && <RemindControls programme={p} reminder={reminder} lead={lead} now={now} primary={false} compact />}
      </div>
    </li>
  )
}
