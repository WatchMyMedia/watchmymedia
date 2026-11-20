import { useLayoutEffect, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { liveTVChannelsQuery, liveTVRemindersQuery, useLiveReminder } from '../../../src/api/queries'
import { leadMinutes } from '../../../src/lib/liveReminders'
import { useFocus, useNav } from '../../app/hooks'
import { problemWords } from '../../ui/problemWords'
import { ProblemScreen, Skeleton } from '../../ui/States'
import { VScroll } from '../../ui/VScroll'
import { ProgrammeArt } from './ChannelArt'
import { useNow } from './clock'
import { LiveIcon } from './LiveIcon'
import { reminderRow, remindersHeading } from './remindersModel'
import './live.css'

/**
 * Reminders: each with its picture, what and where,
 * when or what changed, and Remove. OK on one opens it in the guide.
 */
export function RemindersScreen() {
  const nav = useNav()
  const now = useNow()
  const reminders = useQuery(liveTVRemindersQuery)
  const channels = useQuery(liveTVChannelsQuery)
  const change = useLiveReminder()
  const focus = useFocus()
  const items = reminders.data?.items ?? []
  // A removed row's neighbour takes focus once the list has redrawn without it.
  const next = useRef<string>(undefined)
  useLayoutEffect(() => {
    if (next.current && focus.focusId(next.current)) next.current = undefined
  })
  const remove = (id: string) => {
    if (change.isPending) return
    const i = items.findIndex((r) => r.id === id)
    const neighbour = items[i + 1] ?? items[i - 1]
    change.mutate({ reminderId: id }, { onSuccess: () => { next.current = neighbour ? `reminder/${neighbour.id}/remove` : undefined } })
  }
  const lead = leadMinutes(reminders.data?.leadMinutes)
  const head = <header className="live-page__head">
    <p className="live-page__crumb">Live TV › Reminders</p>
    <h1 className="live-page__title">Reminders</h1>
    {reminders.data && <p className="live-page__lede">{remindersHeading(items.length, lead)}</p>}
  </header>
  if (reminders.isPending) return <div className="tv-page live-page">{head}<div className="live-reminders"><Skeleton width="100%" height="7.25rem"/><Skeleton width="100%" height="7.25rem"/></div></div>
  if (reminders.error) return <div className="tv-page live-page"><ProblemScreen icon="offline" title="Couldn’t load reminders" actions={[{ id: 'reminders/retry', label: 'Try again', onPress: () => void reminders.refetch() }]}>{problemWords(reminders.error).body}</ProblemScreen></div>
  return <div className="tv-page live-page">
    {head}
    <div className="live-reminders">
      {items.length ? <VScroll pad={24}>
        <div className="live-reminders__list">
          {items.map((r) => {
            const row = reminderRow(r, now)
            const channel = channels.data?.items.find((c) => c.id === r.channelId)
            return <div key={r.id} className={`live-req live-req--${row.tone}`} data-row>
              <button type="button" className="live-req__main" data-focus={`reminder/${r.id}`} aria-label={r.state === 'gone' ? row.label : `Open ${r.title} in the guide, ${row.state}, ${row.when}`}
                onClick={() => { if (r.state !== 'gone' && r.channelId) nav.push({ name: 'liveGuide', channelId: r.channelId, at: Date.parse(r.start) }) }}>
                <ProgrammeArt channel={channel ?? { number: r.number, name: r.channelName ?? r.number }} size="thumb"/>
                <span className="live-req__text"><span className="live-req__title">{row.title}</span><span className="live-req__sub">{row.sub}</span></span>
                <span className="live-req__state">
                  <span className="live-req__status"><LiveIcon name={row.tone === 'gone' ? 'bellOff' : row.tone === 'moved' ? 'moved' : 'bell'}/>{row.state}</span>
                  <span className="live-req__when">{row.when}</span>
                </span>
              </button>
              <span className="live-req__remove">
                <button type="button" className="tv-btn tv-btn--ghost" data-focus={`reminder/${r.id}/remove`} aria-label={`Remove the reminder for ${r.title}`} aria-disabled={change.isPending || undefined}
                  onClick={() => remove(r.id)}>Remove</button>
              </span>
            </div>
          })}
        </div>
      </VScroll> : <p className="live-reminders__empty">No reminders. Hold OK on a later programme in the guide and choose Remind me.</p>}
      {change.error && <p className="tv-error" role="alert">{problemWords(change.error).body}</p>}
    </div>
    <p className="live-page__foot">Reminders show on this TV while WatchMyMedia is open. One set on another device reaches this TV the next time the app opens here.</p>
  </div>
}
