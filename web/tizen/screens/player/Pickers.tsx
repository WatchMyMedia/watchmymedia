import type { ReactNode } from 'react'
import { useKeys } from '../../app/hooks'
import type { KeyInput } from '../../app/keys'
import type { FilePlayback, FilePlaybackState } from '../../player'
import { SidePanel } from '../../ui/Overlays'
import { VScroll } from '../../ui/VScroll'
import { qualityRows, subtitlePanel, type OnlineRow, type PickRow, type SubtitleChoices } from './labels'
import { PlayerIcon } from './PlayerIcons'

// Audio, Subtitles and Quality, each its own side panel (the
// Samsung boards' change from Android TV's one sheet). A choice applies at
// once and the panel stays open; Return closes it, and focus goes back to
// the button that opened it. The chosen row has focus when it opens.

export type Picker = 'audio' | 'subtitles' | 'quality'

const TITLES: Record<Picker, string> = { audio: 'Audio', subtitles: 'Subtitles', quality: 'Quality' }
const NOTES: Record<Picker, string> = {
  audio: 'A choice applies at once, from where you are.',
  subtitles: 'The file’s own tracks first, then OpenSubtitles. A burned-in subtitle restarts the stream from here.',
  quality: 'A choice applies at once, from where you are.',
}

/** A panel's frame: the title, the rows (scrolled as focus moves), the note, and its keys. */
export function PlayerPanel({ title, wide, onClose, onKey, note, okHint, children, aside }: {
  title: string; wide?: boolean; onClose(): void; onKey(input: KeyInput): boolean; note?: string; okHint: string; children: ReactNode; aside?: ReactNode
}) {
  return <SidePanel title={title} onClose={onClose} className={`pl-panel${wide ? ' pl-panel--wide' : ''}`}>
    <PanelKeys onKey={onKey}/>
    {aside}
    <div className="pl-panel__list"><VScroll pad={24}>{children}</VScroll></div>
    {note && <p className="pl-panel__note">{note}</p>}
    <div className="pl-panel__keys" aria-hidden="true">
      <span className="tv-kbd">OK</span><span>{okHint}</span>
      <span className="pl-panel__gap"/>
      <span className="tv-kbd">Return</span><span>Closes this panel</span>
    </div>
  </SidePanel>
}

/**
 * Keys inside a panel, before the panel's own: every key counts as the
 * viewer being there, and the remote's media keys still play and pause the
 * video under it. Return, the arrows and OK go on to the panel.
 */
function PanelKeys({ onKey }: { onKey(input: KeyInput): boolean }) {
  useKeys(onKey)
  return null
}

/** One row: the label, its detail beside it, and the gold mark when chosen. */
function Row({ id, row, onPress, autoFocus }: { id: string; row: PickRow; onPress(): void; autoFocus?: boolean }) {
  return <button type="button" role="menuitemradio" aria-checked={row.checked} data-focus={id} data-autofocus={autoFocus || row.checked || undefined}
    className="tv-option pl-pick" onClick={onPress}>
    <span className="pl-pick__label">{row.label}</span>
    {row.detail && <span className="pl-pick__detail">{row.detail}</span>}
    {row.checked && <span className="pl-pick__mark"><PlayerIcon name="check"/></span>}
  </button>
}

/** An OpenSubtitles result: OK downloads it and turns it on. */
function OnlineResult({ row, onPress }: { row: OnlineRow; onPress(): void }) {
  return <button type="button" role="menuitem" data-focus={`online/${row.id}`} aria-disabled={row.state === 'off' || undefined}
    aria-label={`${row.label}${row.detail ? `, ${row.detail}` : ''}${row.state === 'get' ? ', download and turn on' : row.state === 'busy' ? ', downloading' : ''}`}
    className="tv-option pl-pick" onClick={row.state === 'get' ? onPress : undefined}>
    <span className="pl-pick__label">{row.label}</span>
    {row.detail && <span className="pl-pick__detail">{row.detail}</span>}
    {row.state === 'get' && <span className="pl-pick__get"><PlayerIcon name="download"/></span>}
    {row.state === 'busy' && <span className="pl-pick__busy">downloading…</span>}
  </button>
}

const Heading = ({ children }: { children: string }) => <h3 className="pl-panel__heading">{children}</h3>

type PickerState = Pick<FilePlaybackState, 'audio' | 'quality'> & { subtitles: SubtitleChoices }

export function PickerPanel({ picker, state, playback, onClose, onKey }: { picker: Picker; state: PickerState; playback: FilePlayback; onClose(): void; onKey(input: KeyInput): boolean }) {
  return <PlayerPanel title={TITLES[picker]} onClose={onClose} onKey={onKey} note={NOTES[picker]} okHint="Chooses; the panel stays open">
    {picker === 'audio' && <div role="radiogroup" className="pl-panel__rows">
      {state.audio.options.map((a, i) => <Row key={a.index} id={`audio/${a.index}`} autoFocus={state.audio.selected === undefined && i === 0}
        row={{ id: String(a.index), label: a.label, detail: a.detail, checked: a.index === state.audio.selected }} onPress={() => playback.selectAudio(a.index)}/>)}
    </div>}
    {picker === 'subtitles' && <Subtitles subtitles={state.subtitles} playback={playback}/>}
    {picker === 'quality' && <div role="radiogroup" className="pl-panel__rows">
      {qualityRows(state.quality).map((q) => <Row key={q.id} id={`quality/${q.id}`} row={q} onPress={() => playback.setQuality(q.id)}/>)}
    </div>}
  </PlayerPanel>
}

function Subtitles({ subtitles, playback }: { subtitles: PickerState['subtitles']; playback: FilePlayback }) {
  const panel = subtitlePanel(subtitles)
  const pick = (id: string) => playback.selectSubtitle(id)
  return <div role="radiogroup" className="pl-panel__rows">
    <Row id="subtitles/off" row={panel.off} onPress={() => playback.selectSubtitle(undefined)}/>
    {panel.own.length > 0 && panel.online && <Heading>In this file</Heading>}
    {panel.own.map((r) => <Row key={r.id} id={`subtitles/${r.id}`} row={r} onPress={() => pick(r.id)}/>)}
    {panel.online && <>
      <Heading>OpenSubtitles</Heading>
      {panel.online.downloaded.map((r) => <Row key={r.id} id={`subtitles/${r.id}`} row={r} onPress={() => pick(r.id)}/>)}
      {panel.online.results.map((r) => <OnlineResult key={r.id} row={r} onPress={() => playback.downloadSubtitle(r.id)}/>)}
      {panel.online.note && <p className="pl-panel__credit">{panel.online.note}</p>}
      <p className="pl-panel__credit">{panel.online.credit}</p>
    </>}
  </div>
}
