import { useState, type ReactNode } from 'react'
import type { LiveChannel } from '../../../src/api/liveTV'
import { useKeys } from '../../app/hooks'
import { editChannelNumber } from '../../player'
import { Button } from '../../ui/Button'
import { Modal } from '../../ui/Overlays'
import { numberKey } from './liveRemote'
import { leftWords, matchWords, onNow } from './liveWords'
import { BACK } from '../../app/keys'

const KEYS = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '.', '0', '\b']

function Keypad({ digits, text, matched, onKey, onWatch, onCancel, watchLabel, note }: { digits: string; text: string; matched: boolean; onKey(k: string): void; onWatch(): void; onCancel(): void; watchLabel: string; note: string }) {
  return <>
    <h2 className="live-goto__title">Go to channel</h2>
    <div className="live-goto__field" role="status" aria-label={`Channel ${digits}`}>{digits}<span className="live-goto__caret" aria-hidden="true"/></div>
    <p className={`live-goto__match${matched ? ' live-goto__match--hit' : ''}`}>{text}</p>
    <div className="live-goto__keys">
      {KEYS.map((k) => <button key={k} type="button" className="live-key" data-focus={`goto/${k === '\b' ? 'delete' : k === '.' ? 'dot' : k}`}
        aria-label={k === '\b' ? 'Delete' : k === '.' ? 'Dot' : k} onClick={() => onKey(k)}>{k === '\b' ? '⌫' : k}</button>)}
    </div>
    <div className="live-goto__actions">
      <Button id="goto/cancel" onPress={onCancel}>Cancel</Button>
      <Button id="goto/watch" variant="primary" autoFocus onPress={onWatch}>{watchLabel}</Button>
    </div>
    <p className="live-goto__note">{note}</p>
  </>
}

/**
 * Go to channel over the guide: the number typed with the remote's digit
 * keys (the "-" key is the dot) or the pad beside it; Watch (focused)
 * tunes the one channel that number names, and nothing changes until then.
 * Return, like Cancel, closes it without tuning.
 */
export function GoToChannelDialog({ channels, initial, now, onWatch, onClose }: { channels: readonly LiveChannel[]; initial: string; now: number; onWatch(channel: LiveChannel): void; onClose(): void }) {
  const [digits, setDigits] = useState(initial)
  const type = (k: string) => setDigits((d) => editChannelNumber(d, k))
  // The modal's own keys: under a modal the screen sees none.
  useKeys((i) => {
    const k = numberKey(i.action, i.digit)
    if (!k) return false
    type(k)
    return true
  }, true, 'modal')
  const { text, match } = matchWords(channels, digits, now)
  const on = match && onNow(match, now)
  return <Modal onClose={onClose} className="tv-dialog-host">
    <section role="dialog" aria-modal="true" aria-label="Go to channel" className="tv-dialog live-goto2">
      <div className="live-goto2__main">
        <h2 className="tv-dialog__title">Go to channel</h2>
        <div className="live-goto2__field" role="status" aria-label={`Channel ${digits || 'not typed yet'}`}>{digits || <span className="live-goto2__empty">–</span>}</div>
        {match ? <div className="live-goto2__match">
          <span className="live-chan">{match.number}</span>
          <span className="live-goto2__what"><b>{match.name}</b><span>{on ? `${on.title} · ${leftWords(Date.parse(on.end), now)}` : 'No listings'}</span></span>
        </div> : <p className="live-goto2__hint">{text}</p>}
        <div className="tv-actions live-goto2__actions">
          <Button id="goto/watch" variant="primary" autoFocus disabled={!match} why="Type a channel’s number first" onPress={() => { if (match) onWatch(match) }}>{match ? `Watch ${match.number}` : 'Watch'}</Button>
          <Button id="goto/cancel" onPress={onClose}>Cancel</Button>
        </div>
        <p className="live-goto2__note">Type with the remote’s number keys, or use the pad. It tunes only on OK.</p>
      </div>
      <div className="live-goto2__pad" role="group" aria-label="Number pad">
        {KEYS.map((k) => <button key={k} type="button" className="live-goto2__key" data-focus={`goto/${k === '\b' ? 'delete' : k === '.' ? 'dot' : k}`}
          aria-label={k === '\b' ? 'Delete' : k === '.' ? 'Dot' : k} onClick={() => type(k)}>{k === '\b' ? 'Del' : k}</button>)}
      </div>
    </section>
  </Modal>
}

/** The player's Go to channel, in its corner over the picture: the engine holds the number and tunes only on OK. */
export function GoToChannelPanel({ digits, text, match, onKey, onWatch, onCancel }: { digits: string; text: string; match?: LiveChannel; onKey(k: string): void; onWatch(): void; onCancel(): void; children?: ReactNode }) {
  return <section role="dialog" aria-label="Go to channel" className="live-goto live-goto--corner">
    <Keypad digits={digits} text={text} matched={!!match} onKey={onKey} onCancel={onCancel} watchLabel={match ? `Watch ${match.number}` : 'Watch'}
      onWatch={onWatch} note={`Typed with the remote’s number keys. Nothing changes until you press OK; ${BACK} closes this.`}/>
  </section>
}
