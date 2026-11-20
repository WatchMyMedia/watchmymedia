import { useMemo, useState, type FocusEvent } from 'react'
import type { LiveChannel } from '../../../src/api/liveTV'
import { useNav } from '../../app/hooks'
import { Row } from '../../ui/Row'
import { ProgrammeArt } from './ChannelArt'
import { useNow } from './clock'
import { LiveIcon } from './LiveIcon'
import { channelLabel, clockWords, onNowCards, onNowFocusLine, recordingNowLine, type OnNowCard } from './liveWords'

type Item = { kind: 'channel'; card: OnNowCard } | { kind: 'guide' } | { kind: 'recordings'; line?: string } | { kind: 'reminders'; line?: string }

const itemKey = (item: Item) => item.kind === 'channel' ? item.card.channel.id : item.kind

/** A card of the On now row: the programme's picture with the channel's logo and number, LIVE, progress; the title and minutes left under it. */
export function OnNowCardButton({ card, focusId, onPress }: { card: OnNowCard; focusId: string; onPress(): void }) {
  return <button type="button" data-focus={focusId} className="live-oncard" aria-label={card.label} onClick={onPress}>
    <ProgrammeArt channel={card.channel} programme={card.programme} size="card" live progress={card.progress}/>
    <span className="live-oncard__title">{card.title}</span>
    <span className="live-oncard__sub">{card.line}</span>
  </button>
}

/** A channel without listings: its number and name, LIVE, and "No listings". */
export function ChannelTile({ channel, focusId, onPress, autoFocus }: { channel: LiveChannel; focusId: string; onPress(): void; autoFocus?: boolean }) {
  return <button type="button" data-focus={focusId} data-autofocus={autoFocus || undefined} className={`live-tile${channel.available ? '' : ' live-tile--off'}`} aria-label={`Watch ${channelLabel(channel)}`} onClick={onPress}>
    <span className="live-tile__top"><b>{channel.number}</b><span>{channel.name}</span>{channel.available && <span className="live-badge"><span className="live-dot"/>LIVE</span>}</span>
    <span className="live-tile__sub">{channel.available ? 'No listings' : 'Unavailable'}</span>
  </button>
}

function Tile({ focusId, icon, title, line, label, onPress }: { focusId: string; icon: 'guide' | 'record' | 'bell'; title: string; line?: string; label?: string; onPress(): void }) {
  return <button type="button" data-focus={focusId} className="live-tile live-tile--action" aria-label={label} onClick={onPress}>
    <LiveIcon name={icon} className="live-tile__icon"/>
    <span className="live-tile__title">{title}</span>
    {line && <span className="live-tile__sub">{line}</span>}
  </button>
}

/**
 * The On now row (LiveHome on the canvas): each channel's programme, then
 * Full guide, Recordings (for people who may record) and Reminders. The
 * line under it says what OK watches. Without listings, channel tiles.
 * Home can draw it too, where the server's rows place Live TV.
 */
export function OnNowRow({ id = 'onnow', channels, listings, canRecord, recordingNow, reminders, onWatch }: {
  id?: string
  channels: readonly LiveChannel[]
  listings: boolean
  canRecord: boolean
  /** How many record now, for the Recordings tile. */
  recordingNow?: number
  /** How many reminders are set, for the Reminders tile; none hides it. */
  reminders?: number
  onWatch(channel: LiveChannel): void
}) {
  const nav = useNav()
  const now = useNow()
  const cards = useMemo(() => onNowCards(channels, now), [channels, now])
  const items = useMemo<Item[]>(() => [
    ...cards.map((card) => ({ kind: 'channel' as const, card })),
    ...(listings ? [{ kind: 'guide' as const }] : []),
    ...(canRecord ? [{ kind: 'recordings' as const, line: recordingNowLine(recordingNow ?? 0) }] : []),
    ...(reminders !== undefined ? [{ kind: 'reminders' as const, line: reminders ? `${reminders} set` : undefined }] : []),
  ], [cards, listings, canRecord, recordingNow, reminders])
  const [focused, setFocused] = useState<string>()
  const card = cards.find((c) => c.channel.id === focused) ?? cards[0]
  const onFocus = (e: FocusEvent) => {
    const key = (e.target as HTMLElement).dataset?.focus?.slice(id.length + 1)
    if (key) setFocused(key)
  }
  return <section className="live-onnow" onFocus={onFocus} aria-label={listings ? 'On now' : 'Live TV'}>
    <Row id={id} title={listings ? 'On now' : 'Live TV'} subtitle={listings ? `Live TV · ${clockWords(now)}` : undefined} variant="live" items={items} itemKey={itemKey}
      renderItem={(item, focusId) => {
        switch (item.kind) {
          case 'channel': return listings
            ? <OnNowCardButton card={item.card} focusId={focusId} onPress={() => onWatch(item.card.channel)}/>
            : <ChannelTile channel={item.card.channel} focusId={focusId} onPress={() => onWatch(item.card.channel)}/>
          case 'guide': return <Tile focusId={focusId} icon="guide" title="Full guide" onPress={() => nav.push({ name: 'liveGuide' })}/>
          case 'recordings': return <Tile focusId={focusId} icon="record" title="Recordings" line={item.line} label={['Recordings', item.line].filter(Boolean).join(', ')} onPress={() => nav.push({ name: 'recordings' })}/>
          case 'reminders': return <Tile focusId={focusId} icon="bell" title="Reminders" line={item.line} label={['Reminders', item.line].filter(Boolean).join(', ')} onPress={() => nav.push({ name: 'reminders' })}/>
        }
      }}/>
    <p className="live-onnow__line">{focused && !cards.some((c) => c.channel.id === focused) ? '' : onNowFocusLine(card)}</p>
  </section>
}
