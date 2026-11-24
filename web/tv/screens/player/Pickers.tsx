import { useState, type ReactNode } from 'react'
import { useFocus, useKeys } from '../../app/hooks'
import type { KeyInput } from '../../app/keys'
import type { FilePlayback, FilePlaybackState } from '../../player'
import { Button } from '../../ui/Button'
import { Option, PanelLine, SidePanel } from '../../ui/Overlays'
import { VScroll } from '../../ui/VScroll'
import { audioLine, qualityRows, qualitySentence, streamDetails, subtitleLine, subtitlePanel, type OnlineRow, type SubtitleChoices } from './labels'
import { PlayerIcon } from './PlayerIcons'
import { BACK } from '../../app/keys'

// The player's two panels from the right: Audio and subtitles, in one, and
// Quality. A choice applies at once and the panel stays open; Return
// closes it, or from a page inside it (Find more subtitles, Stream
// details) goes back to the row that opened that page; focus goes back to
// the button that opened the panel. The chosen row has focus when it opens.

export type Picker = 'tracks' | 'quality'

/** A panel's frame: the title, a line under it, the rows (scrolled as focus moves), and what is at its foot. */
export function PlayerPanel({ title, sub, onClose, onKey, foot, children, wide, aside }: {
  title: string; sub?: ReactNode; onClose(): void; onKey(input: KeyInput): boolean; foot?: ReactNode; children: ReactNode; wide?: boolean
  /** Above the rows, outside their scroll (the Episodes panel's season tabs). */
  aside?: ReactNode
}) {
  return <SidePanel title={title} sub={sub} onClose={onClose} wide={wide} className="pl-panel" foot={foot && (typeof foot === 'string' ? <p className="pl-panel__foot">{foot}</p> : foot)}>
    <PanelKeys onKey={onKey}/>
    {aside}
    <div className="pl-panel__list"><VScroll pad={24}>{children}</VScroll></div>
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

const Heading = ({ children }: { children: string }) => <h3 className="pl-panel__heading">{children}</h3>

type PickerState = Pick<FilePlaybackState, 'audio' | 'quality' | 'mode' | 'video' | 'stream'> & { subtitles: SubtitleChoices }

export function PickerPanel({ picker, state, playback, onClose, onKey }: { picker: Picker; state: PickerState; playback: FilePlayback; onClose(): void; onKey(input: KeyInput): boolean }) {
  return picker === 'tracks'
    ? <TracksPanel state={state} playback={playback} onClose={onClose} onKey={onKey}/>
    : <QualityPanel state={state} playback={playback} onClose={onClose} onKey={onKey}/>
}

/** Audio and subtitles in one panel, named in words: "Surround sound", "With sounds described". */
function TracksPanel({ state, playback, onClose, onKey }: { state: PickerState; playback: FilePlayback; onClose(): void; onKey(input: KeyInput): boolean }) {
  const focus = useFocus()
  const [online, setOnline] = useState(false)
  const panel = subtitlePanel(state.subtitles)
  const back = () => { setOnline(false); queueMicrotask(() => focus.focusId('tracks/more', focus.scope('modal'))) }
  if (online && panel.online) {
    const o = panel.online
    return <PlayerPanel key="online" title="Find more subtitles" sub="From OpenSubtitles, in your languages" onClose={back} onKey={onKey} foot={o.credit}>
      <div role="radiogroup" className="pl-panel__rows">
        {o.downloaded.map((r, i) => <Option key={r.id} id={`subtitles/${r.id}`} label={r.label} detail={r.detail} checked={r.checked} autoFocus={i === 0 && !r.checked}
          onPress={() => playback.selectSubtitle(r.id)}/>)}
        {o.results.map((r, i) => <OnlineResult key={r.id} row={r} autoFocus={!o.downloaded.length && i === 0} onPress={() => playback.downloadSubtitle(r.id)}/>)}
        {o.note && <p className="pl-panel__credit">{o.note}</p>}
      </div>
    </PlayerPanel>
  }
  const audio = state.audio.options
  const subtitles = state.subtitles.options.filter((s) => s.source !== 'opensubtitles' || s.id === state.subtitles.selected)
  return <PlayerPanel key="tracks" title="Audio and subtitles" onClose={onClose} onKey={onKey} foot={`A choice applies straight away. ${BACK} closes this.`}>
    {audio.length > 0 && <>
      <Heading>Audio</Heading>
      <div role="radiogroup" className="pl-panel__rows">
        {audio.map((a, i) => <Option key={a.index} id={`audio/${a.index}`} label={a.label} detail={audioLine(a)} checked={a.index === (state.audio.selected ?? audio[0].index)}
          autoFocus={state.audio.selected === undefined && i === 0} onPress={() => playback.selectAudio(a.index)}/>)}
      </div>
    </>}
    <Heading>Subtitles</Heading>
    <div role="radiogroup" className="pl-panel__rows">
      <Option id="subtitles/off" label="Off" checked={state.subtitles.selected === undefined} onPress={() => playback.selectSubtitle(undefined)}/>
      {subtitles.map((s) => <Option key={s.id} id={`subtitles/${s.id}`} label={s.label} detail={subtitleLine(s)} checked={s.id === state.subtitles.selected} onPress={() => playback.selectSubtitle(s.id)}/>)}
      {panel.online && <Option id="tracks/more" label="Find more subtitles" next onPress={() => setOnline(true)}/>}
    </div>
  </PlayerPanel>
}

/** An OpenSubtitles result: OK downloads it and turns it on. */
function OnlineResult({ row, onPress, autoFocus }: { row: OnlineRow; onPress(): void; autoFocus?: boolean }) {
  return <button type="button" role="menuitem" data-focus={`online/${row.id}`} data-autofocus={autoFocus || undefined} aria-disabled={row.state === 'off' || undefined}
    aria-label={`${row.label}${row.detail ? `, ${row.detail}` : ''}${row.state === 'get' ? ', download and turn on' : row.state === 'busy' ? ', downloading' : ''}`}
    className="tv-option" onClick={row.state === 'get' ? onPress : undefined}>
    <span className="tv-option__text"><span className="tv-option__label">{row.label}</span>{row.detail && <span className="tv-option__detail">{row.detail}</span>}</span>
    {row.state === 'get' && <span className="tv-option__end"><PlayerIcon name="download"/></span>}
    {row.state === 'busy' && <span className="tv-option__end">Downloading…</span>}
  </button>
}

/**
 * Quality: a sentence saying what plays now, the choices in words, and
 * Stream details one press away for whoever looks after the server. The
 * choice is kept for this TV; other devices keep their own.
 */
function QualityPanel({ state, playback, onClose, onKey }: { state: PickerState; playback: FilePlayback; onClose(): void; onKey(input: KeyInput): boolean }) {
  const focus = useFocus()
  const [details, setDetails] = useState(false)
  const back = () => { setDetails(false); queueMicrotask(() => focus.focusId('quality/details', focus.scope('modal'))) }
  if (details) {
    return <PlayerPanel key="details" title="Stream details" sub="The numbers, for whoever looks after the server" onClose={back} onKey={onKey}
      foot={<Button id="details/back" autoFocus onPress={back}>Back to Quality</Button>}>
      <dl className="pl-facts">
        {streamDetails(state).map((r) => <div key={r.label} className="pl-facts__row"><dt>{r.label}</dt><dd>{r.value}</dd></div>)}
      </dl>
    </PlayerPanel>
  }
  return <PlayerPanel key="quality" title="Quality" sub={qualitySentence(state)} onClose={onClose} onKey={onKey} foot="Your choice is kept for this TV. Other devices keep their own.">
    <div role="radiogroup" className="pl-panel__rows">
      {qualityRows(state.quality).map((q) => <Option key={q.id} id={`quality/${q.id}`} label={q.label} detail={q.detail} checked={q.checked} onPress={() => playback.setQuality(q.id)}/>)}
    </div>
    <PanelLine/>
    <Option id="quality/details" label="Stream details" detail="The numbers, for whoever looks after the server" next onPress={() => setDetails(true)}/>
  </PlayerPanel>
}
