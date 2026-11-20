import { useState, type ReactNode } from 'react'
import type { LiveChannel } from '../../../src/api/liveTV'
import { useKeys } from '../../app/hooks'
import { editChannelNumber } from '../../player'
import { Button } from '../../ui/Button'
import { Modal } from '../../ui/Overlays'
import { numberKey } from './liveRemote'
import { matchWords } from './liveWords'

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
 * Go to channel over the guide or Live TV: the number
 * typed with the remote's digit keys (the "-" key is the dot) or the
 * keypad; OK watches the one channel that number names, and nothing else
 * changes until then. Return closes it.
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
  return <Modal onClose={onClose} className="live-goto-host">
    <section role="dialog" aria-modal="true" aria-label="Go to channel" className="live-goto live-goto--centre">
      <Keypad digits={digits} text={text} matched={!!match} onKey={type} onCancel={onClose} watchLabel={match ? `Watch ${match.number}` : 'Watch'}
        onWatch={() => { if (match) onWatch(match) }} note="The remote’s number keys open this and type the channel, here or while watching. OK watches it; Return closes."/>
    </section>
  </Modal>
}

/** The player's Go to channel, in its corner over the picture: the engine holds the number and tunes only on OK. */
export function GoToChannelPanel({ digits, text, match, onKey, onWatch, onCancel }: { digits: string; text: string; match?: LiveChannel; onKey(k: string): void; onWatch(): void; onCancel(): void; children?: ReactNode }) {
  return <section role="dialog" aria-label="Go to channel" className="live-goto live-goto--corner">
    <Keypad digits={digits} text={text} matched={!!match} onKey={onKey} onCancel={onCancel} watchLabel={match ? `Watch ${match.number}` : 'Watch'}
      onWatch={onWatch} note="Typed with the remote’s number keys. Nothing changes until you press OK; Return closes this."/>
  </section>
}
