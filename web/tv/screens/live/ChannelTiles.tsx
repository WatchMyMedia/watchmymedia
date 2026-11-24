import { useState } from 'react'
import type { LiveChannel } from '../../../src/api/liveTV'
import { useKeys, useNav, useSession } from '../../app/hooks'
import { Button } from '../../ui/Button'
import { VScroll } from '../../ui/VScroll'
import { Dialog } from '../../ui/Overlays'
import { useNow } from './clock'
import { GoToChannelDialog } from './GoToChannel'
import { useLiveTV } from './liveData'
import { numberKey } from './liveRemote'
import { LiveTabs, type TabsProps } from './LiveTabs'
import { useStartFocus, useTabKeys } from './tabFocus'
import { channelLabel } from './liveWords'

/**
 * Live TV without listings: every channel as a tile, four across, the
 * first focused; OK watches. When no channel can play (the tuner can't be
 * found, or has no channels the server can use), it says so, with what
 * still works. The number keys open Go to channel.
 */
export function ChannelTiles({ tabs, canRecord }: { tabs: TabsProps; canRecord: boolean }) {
  const nav = useNav()
  const session = useSession()
  const tv = useLiveTV()
  const now = useNow()
  const [goto, setGoto] = useState<string>()
  const [unavailable, setUnavailable] = useState<LiveChannel>()
  const channels = tv.channels.data?.items ?? []
  const playable = channels.filter((c) => c.available)
  const watch = (channel: LiveChannel) => {
    if (!channel.available) { setUnavailable(channel); return }
    setGoto(undefined)
    nav.play({ kind: 'live', channelId: channel.id })
  }
  useStartFocus(true, tabs.chosen)
  useTabKeys('guide')
  useKeys((i) => {
    const k = numberKey(i.action, i.digit)
    if (i.action !== 'digit' || !k) return false
    setGoto(k)
    return true
  }, playable.length > 0 && goto === undefined)

  const header = <LiveTabs {...tabs}>{playable.length > 0 && <Button id="guide/goto" onPress={() => setGoto('')}>Go to channel</Button>}</LiveTabs>
  if (!playable.length) return <div className="live-guide">{header}<div className="live-body"><NoTuner server={session.server.name} canRecord={canRecord} onRetry={() => void tv.channels.refetch()} onRecordings={() => tabs.onTab('recordings')}/></div></div>
  return <div className="live-guide">
    {header}
    <section className="live-tiles live-body" aria-label="Channels">
      <h1 className="tv-title">Channels</h1>
      <p className="tv-lede tv-muted live-tiles__line">There are no listings on {session.server.name} yet. Every channel still plays.</p>
      <div className="live-tiles__grid"><VScroll pad={24}>
        <div className="live-tiles__list" data-group="channels">
          {channels.map((c, i) => <button key={c.id} type="button" className="live-tile" data-focus={`channels/${c.id}`} data-autofocus={i === 0 || undefined}
            aria-label={`Watch ${channelLabel(c)}${c.available ? '' : ', not available'}`} aria-disabled={!c.available || undefined} onClick={() => watch(c)}>
            <span className="live-tile__num">{c.number}</span>
            <span className="live-tile__name">{c.name}</span>
          </button>)}
        </div>
      </VScroll></div>
    </section>
    {goto !== undefined && <GoToChannelDialog channels={channels} initial={goto} now={now} onWatch={watch} onClose={() => setGoto(undefined)}/>}
    {unavailable && <Dialog title={channelLabel(unavailable)} narrow onCancel={() => setUnavailable(undefined)} actions={[{ id: 'unavailable/ok', label: 'OK', safe: true, onPress: () => setUnavailable(undefined) }]}>
      <p className="tv-lede">This channel isn’t available right now. It isn’t in the tuner’s last channel list.</p>
    </Dialog>}
  </div>
}

/**
 * No channel can play: the tuner can't be found, or has none the server
 * can use. What still works, and Try again.
 */
export function NoTuner({ server, canRecord, onRetry, onRecordings }: { server: string; canRecord: boolean; onRetry(): void; onRecordings(): void }) {
  return <section className="live-notuner" role="alert" aria-label="No channel can play">
    <h1 className="tv-title">No channel can play right now</h1>
    <p className="tv-lede">{server} can’t find any of its channels on the tuner. The tuner may be unplugged or restarting.</p>
    <p className="tv-lede tv-muted">{canRecord ? 'Recordings you already have still play.' : 'Whoever looks after the server can check the tuner.'}</p>
    <div className="tv-actions live-notuner__actions">
      <Button id="notuner/retry" variant="primary" autoFocus onPress={onRetry}>Try again</Button>
      {canRecord && <Button id="notuner/recordings" onPress={onRecordings}>Recordings</Button>}
    </div>
  </section>
}
