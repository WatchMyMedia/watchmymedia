import { useLayoutEffect, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { liveTVChannelsQuery, liveTVRemindersQuery, useLiveReminder } from '../../../src/api/queries'
import { leadMinutes } from '../../../src/lib/liveReminders'
import { useFocus, useNav } from '../../app/hooks'
import { Button } from '../../ui/Button'
import { problemWords } from '../../ui/problemWords'
import { ProblemScreen, Skeleton } from '../../ui/States'
import { VScroll } from '../../ui/VScroll'
import { ProgrammeArt } from './ChannelArt'
import { useNow } from './clock'
import { LiveIcon } from './LiveIcon'
import { LiveTabs, type TabsProps } from './LiveTabs'
import { useStartFocus, useTabKeys } from './tabFocus'
import { reminderRow, remindersHeading } from './remindersModel'

/**
 * Reminders, a tab of Live TV: each with its picture, what and where, when
 * or what changed, and Remove one press to the right. OK on one opens it
 * in the guide.
 */
export function RemindersBody({ tabs }: { tabs: TabsProps }) {
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
    change.mutate({ reminderId: id }, { onSuccess: () => { next.current = neighbour ? `reminder/${neighbour.id}/remove` : 'live-tab/reminders' } })
  }
  useStartFocus(!reminders.isPending, tabs.chosen)
  useTabKeys('reminders')
  const lead = leadMinutes(reminders.data?.leadMinutes)
  const body = reminders.isPending ? <div className="live-reminders"><Skeleton width="100%" height="7.25rem"/><Skeleton width="100%" height="7.25rem"/></div>
    : reminders.error ? <ProblemScreen title="Couldn’t load reminders" actions={[{ id: 'reminders/retry', label: 'Try again', onPress: () => void reminders.refetch() }]}>{problemWords(reminders.error).body}</ProblemScreen>
      : !items.length ? <section className="live-empty">
        <h1 className="tv-title">No reminders</h1>
        <p className="tv-lede">Press OK on a later programme in the guide and choose Remind me. Reminders show on this TV while WatchMyMedia is open.</p>
        <div className="tv-actions live-empty__actions"><Button id="reminders/guide" variant="primary" autoFocus onPress={() => tabs.onTab('guide')}>Open the guide</Button></div>
      </section>
        : <div className="live-reminders">
          <p className="live-reminders__lede">{remindersHeading(items.length, lead)}</p>
          <div className="live-reminders__list"><VScroll pad={24}>
            {items.map((r, i) => {
              const row = reminderRow(r, now)
              const channel = channels.data?.items.find((c) => c.id === r.channelId)
              return <div key={r.id} className={`live-req live-req--${row.tone}`} data-row>
                <button type="button" className="tv-option live-req__main" data-focus={`reminder/${r.id}`} data-autofocus={i === 0 || undefined} aria-disabled={r.state === 'gone' || undefined}
                  aria-label={r.state === 'gone' ? row.label : `Open ${r.title} in the guide, ${row.state}, ${row.when}`}
                  onClick={() => { if (r.state !== 'gone' && r.channelId) nav.push({ name: 'liveGuide', channelId: r.channelId, at: Date.parse(r.start) }) }}>
                  <ProgrammeArt channel={channel ?? { number: r.number, name: r.channelName ?? r.number }} size="thumb" overlay={false}/>
                  <span className="tv-option__text"><span className="tv-option__label">{row.title}</span><span className="tv-option__detail">{row.sub}</span></span>
                  <span className="live-req__state">
                    <span className="live-req__status"><LiveIcon name={row.tone === 'gone' ? 'bellOff' : row.tone === 'moved' ? 'moved' : 'bell'}/>{row.state}</span>
                    <span className="live-req__when">{row.when}</span>
                  </span>
                </button>
                <Button id={`reminder/${r.id}/remove`} label={`Remove the reminder for ${r.title}`} busy={change.isPending && change.variables && 'reminderId' in change.variables && change.variables.reminderId === r.id} onPress={() => remove(r.id)}>Remove</Button>
              </div>
            })}
          </VScroll></div>
          {change.error && <p className="tv-error" role="alert">{problemWords(change.error).body}</p>}
        </div>
  return <div className="live-recordings">
    <LiveTabs {...tabs}/>
    <div className="live-rec__body live-rec__body--top live-body">{body}</div>
  </div>
}
