import { useEffect, useEffectEvent, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { liveTVCapabilitiesQuery, liveTVChannelsQuery, liveTVRemindersQuery } from '../../../src/api/queries'
import { leadMinutes, reminderNotice } from '../../../src/lib/liveReminders'
import { useKeys, useNav } from '../../app/hooks'
import { ProgrammeArt } from './ChannelArt'
import { LiveIcon } from './LiveIcon'
import { tuneOpenPlayer } from './livePlayback'
import { BANNER_MS, bannerReminder, loadSeen, markSeen, nextCheck } from './remindersModel'
import './live.css'
import { BACK } from '../../app/keys'

function appStorage(): Storage | undefined {
  try { return window.localStorage } catch { return undefined }
}

/**
 * A Live TV reminder over whatever is on screen while the app is
 * open. It never takes focus: Hold OK watches the
 * channel, Return dismisses it, and it goes by itself after 15 s. Each one
 * shows once on this TV. The shell mounts it once; `player` lowers it under
 * the player's top bar.
 */
export function ReminderBanner({ player }: { player?: boolean }) {
  const nav = useNav()
  const caps = useQuery(liveTVCapabilitiesQuery)
  const enabled = !!caps.data?.enabled && !!caps.data.allowed && !!caps.data.guide
  const reminders = useQuery({ ...liveTVRemindersQuery, enabled })
  const items = reminders.data?.items ?? []
  const lead = leadMinutes(reminders.data?.leadMinutes)
  const [now, setNow] = useState(() => Date.now())
  const [seen, setSeen] = useState(() => loadSeen(appStorage(), Date.now()))
  const shown = enabled ? bannerReminder(items, lead, now, seen) : undefined
  const channels = useQuery({ ...liveTVChannelsQuery, enabled: !!shown })

  // Wake for the next one coming due, and every 15 s while one is ("Starting in 4 min").
  const next = enabled ? nextCheck(items, lead, now) : undefined
  useEffect(() => {
    if (next === undefined) return
    const timer = setTimeout(() => setNow(Date.now()), Math.min(Math.max(next - Date.now(), 1000), 2 ** 31 - 1))
    return () => clearTimeout(timer)
  }, [next, now])

  const close = () => { if (shown) setSeen((s) => markSeen(appStorage(), s, shown, Date.now())) }
  // It goes by itself after a while, and counts as seen.
  const key = shown ? `${shown.id}@${shown.start}` : ''
  const expire = useEffectEvent(close)
  useEffect(() => {
    if (!key) return
    const timer = setTimeout(expire, BANNER_MS)
    return () => clearTimeout(timer)
  }, [key])

  const watch = () => {
    const id = shown?.channelId
    close()
    if (id && !tuneOpenPlayer(id)) nav.play({ kind: 'live', channelId: id })
  }
  useKeys((i) => {
    if (i.action === 'back') { close(); return true }
    if (i.action === 'hold' && shown?.channelId) { watch(); return true }
    return false
  }, !!shown, 'overlay')

  if (!shown) return null
  const words = reminderNotice(shown, now)
  const channel = channels.data?.items.find((c) => c.id === shown.channelId)
  const programme = channel && [channel.now, channel.next].find((p) => p?.id === shown.programmeId)
  return <aside className={`live-remind${player ? ' live-remind--player' : ''}`} role="status" aria-live="polite" aria-label={`Reminder: ${words.heading}, ${words.title}, ${words.line}`}>
    <div className="live-remind__top">
      <ProgrammeArt channel={channel ?? { number: shown.number, name: shown.channelName ?? shown.number }} programme={programme} size="panel"/>
      <span className="live-remind__text">
        <span className="live-remind__heading"><LiveIcon name="bell"/>{words.heading}</span>
        <span className="live-remind__title">{words.title}</span>
        <span className="live-remind__line">{words.line}</span>
      </span>
    </div>
    <div className="live-remind__hints">
      {shown.channelId && <span className="live-hint"><span className="tv-kbd">Hold OK</span>Watch {shown.number}</span>}
      <span className="live-hint"><span className="tv-kbd">{BACK}</span>Dismiss</span>
    </div>
  </aside>
}
