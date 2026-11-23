import { useLayoutEffect, useRef, useState } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { liveTVRecordingsQuery } from '../../../src/api/queries'
import { useEntry, useFocus } from '../../app/hooks'
import { ChannelTiles } from './ChannelTiles'
import { Guide } from './GuideBody'
import { useLiveTV } from './liveData'
import { LiveGate } from './LiveGate'
import { RecordingsBody } from './RecordingsBody'
import { RemindersBody } from './RemindersBody'
import { liveTabs, type LiveTab } from './sectionModel'
import './live.css'

/** The tab each Live TV screen in the stack was on, kept while a player or page is on top of it. */
const kept = new WeakMap<object, LiveTab>()

/**
 * Live TV, the section the menu opens (and the guide or Recordings opened
 * from elsewhere): the Guide and Recordings tabs, and Reminders while
 * listings are on, over what the chosen tab holds. Choosing a tab keeps
 * focus on it; ▼ goes into what it holds. Return leaves Live TV.
 */
export function LiveSection({ initial, guide }: { initial: LiveTab; guide?: { channelId?: string; at?: number } }) {
  const tv = useLiveTV()
  const entry = useEntry()
  const focus = useFocus()
  const owner = entry?.memory
  const [tab, setTab] = useState<LiveTab>(() => (owner && kept.get(owner)) || initial)
  const tabs = liveTabs({ canRecord: tv.canRecord, listings: tv.listings })
  const shown = tabs.some((t) => t.id === tab) ? tab : 'guide'
  const now = useInfiniteQuery({ ...liveTVRecordingsQuery('now'), enabled: tv.available && tv.canRecord })
  const recordingNow = now.data?.pages[0]?.items.length ?? 0
  const moved = useRef(false)
  const [chosen, setChosen] = useState(false)
  const choose = (next: LiveTab) => {
    if (next === shown) return
    moved.current = true
    if (owner) kept.set(owner, next)
    setChosen(true)
    setTab(next)
  }
  // The tab just chosen keeps focus once its screen has drawn.
  useLayoutEffect(() => {
    if (!moved.current) return
    moved.current = false
    focus.focusId(`live-tab/${shown}`)
  }, [focus, shown])

  if (!tv.available || !tv.channels.data?.items.length) return <div className="tv-page live-section"><LiveGate {...tv}/></div>
  const props = { tab: shown, tabs, recordingNow, onTab: choose, chosen }
  return <div className="tv-page live-section">
    {shown === 'guide' ? (tv.listings ? <Guide tabs={props} canRecord={tv.canRecord} channelId={guide?.channelId} at={guide?.at}/> : <ChannelTiles tabs={props} canRecord={tv.canRecord}/>)
      : shown === 'recordings' ? <RecordingsBody tabs={props}/>
        : <RemindersBody tabs={props}/>}
  </div>
}

