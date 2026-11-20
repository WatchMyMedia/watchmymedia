import { useState } from 'react'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import type { LiveChannel } from '../../../src/api/liveTV'
import { liveTVRecordingsQuery, liveTVRemindersQuery } from '../../../src/api/queries'
import { useKeys, useNav } from '../../app/hooks'
import { Button, KeyHints } from '../../ui/Button'
import { Grid } from '../../ui/Grid'
import { Dialog } from '../../ui/Overlays'
import { useLiveTV } from './liveData'
import { LiveGate } from './LiveGate'
import { useNow } from './clock'
import { GoToChannelDialog } from './GoToChannel'
import { channelLabel, clockWords } from './liveWords'
import { numberKey } from './liveRemote'
import { ChannelTile, OnNowRow } from './OnNowRow'
import './live.css'

/**
 * Live TV (LiveHome on the canvas): the On now row with each channel's
 * programme, Full guide, Recordings and Reminders. Without listings, every
 * channel as a tile. The number keys open Go to channel.
 */
export function LiveScreen() {
  const nav = useNav()
  const tv = useLiveTV()
  const now = useNow()
  const [goto, setGoto] = useState<string>()
  const [unavailable, setUnavailable] = useState<LiveChannel>()
  const reminders = useQuery({ ...liveTVRemindersQuery, enabled: tv.available && tv.listings })
  const recording = useInfiniteQuery({ ...liveTVRecordingsQuery('now'), enabled: tv.available && tv.canRecord })
  const channels = tv.channels.data?.items ?? []
  const watch = (channel: LiveChannel) => {
    if (!channel.available) { setUnavailable(channel); return }
    setGoto(undefined)
    nav.play({ kind: 'live', channelId: channel.id })
  }
  const ready = tv.available && channels.length > 0
  useKeys((i) => {
    const k = numberKey(i.action, i.digit)
    if (i.action !== 'digit' || !k) return false
    setGoto(k)
    return true
  }, ready && goto === undefined)

  const gate = <LiveGate {...tv}/>
  if (!ready) return <div className="tv-page live-home">{gate}</div>
  return <div className="tv-page live-home">
    <div className="tv-glow tv-glow--top"/>
    <header className="live-home__head">
      <h1 className="tv-h1">Live TV</h1>
      <span className="live-clock">{clockWords(now)}</span>
    </header>
    {tv.listings ? <div className="live-home__rows">
      <OnNowRow channels={channels} listings canRecord={tv.canRecord} recordingNow={recording.data?.pages[0]?.items.length}
        reminders={reminders.data ? reminders.data.items.filter((r) => r.state !== 'gone').length : undefined} onWatch={watch}/>
    </div> : <div className="live-home__grid">
      <p className="tv-lede">Listings aren’t on this server yet. Pick a channel and press OK to watch.</p>
      <div className="tv-actions live-home__actions">
        <span className="tv-note">{channels.filter((c) => c.available).length} channels available</span>
        {tv.canRecord && <Button id="live/recordings" icon="live" onPress={() => nav.push({ name: 'recordings' })}>Recordings</Button>}
        <Button id="live/goto" onPress={() => setGoto('')}>Go to channel</Button>
      </div>
      <Grid id="channels" variant="wide" items={channels} itemKey={(c) => c.id} visibleRows={3}
        renderItem={(c, focusId) => <ChannelTile channel={c} focusId={focusId} onPress={() => watch(c)}/>}/>
    </div>}
    <KeyHints hints={[{ key: 'OK', label: 'Watch' }, { key: '0–9', label: 'Go to channel' }, ...(tv.listings ? [{ key: 'Guide', label: 'Full guide' }] : [])]}/>
    {goto !== undefined && <GoToChannelDialog channels={channels} initial={goto} now={now} onWatch={watch} onClose={() => setGoto(undefined)}/>}
    {unavailable && <Dialog title={channelLabel(unavailable)} onCancel={() => setUnavailable(undefined)} actions={[{ id: 'unavailable/ok', label: 'OK', safe: true, onPress: () => setUnavailable(undefined) }]}>
      <p className="tv-body">This channel isn’t available right now. It isn’t in the tuner’s last channel list.</p>
    </Dialog>}
  </div>
}
