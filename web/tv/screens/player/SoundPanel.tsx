import { useEffect, useRef, type PointerEvent as ReactPointerEvent } from 'react'
import { SHARE_DONE, SHARE_HELP, SHARE_LABEL, TIMING_BURNED, TIMING_FAILED, TIMING_HELP, TIMING_SHARED, type TimingView } from '../../../src/lib/play/subtitleTiming'
import { SOUND_LEVELS, SOUND_NOTE, SOUND_ROWS } from '../../../src/lib/sound'
import type { FilePlayback, SoundState } from '../../player'
import { platform } from '../../platform/current'
import { Chips } from '../../ui/Chips'
import { Icon } from '../../ui/Icon'
import { Option } from '../../ui/Overlays'
import { BACK } from '../../app/keys'
import { panelFoot, pointerFoot, resetDetail, SOUND_ROW, soundLine, TIMING_ROW } from './soundWords'

// The Audio and subtitles panel's parts from API level 35: Sound under the
// audio tracks (Off, Medium, High as chips in the row: ◀ ▶ move, OK
// chooses), and under the subtitles, while one is on, Subtitle timing (◀
// Earlier, ▶ Later, 0.1 s a press, holding repeats; Reset and Save for
// everyone as rows under it, OK on them).

const Heading = ({ children }: { children: string }) => <h3 className="pl-panel__heading">{children}</h3>

/** Boost dialogue and Reduce loud sounds. A change reloads the play where it is; the panel stays open. */
export function SoundRows({ sound, playback }: { sound: SoundState; playback: FilePlayback }) {
  return <>
    <Heading>Sound</Heading>
    {SOUND_ROWS.map((row) => {
      const line = soundLine(sound, row.id, row.help)
      return <div key={row.id} className="pl-sound" role="group" aria-label={row.label}>
        <p className="pl-sound__label">{row.label}</p>
        {line.kind === 'busy' && <><p className="pl-sound__busy" role="status"><span className="tv-spinner" aria-hidden="true"/>{line.text}</p><p className="pl-sound__help">{line.more}</p></>}
        {line.kind === 'error' && <p className="pl-sound__error" role="alert">{line.text}</p>}
        {line.kind === 'help' && <p className="pl-sound__help">{line.text}</p>}
        <div className="pl-sound__chips">
          <Chips id={`${SOUND_ROW}${row.id}`} label={row.label} options={SOUND_LEVELS.map((l) => ({ value: l.id, label: l.label }))} value={sound[row.id]}
            onChange={(level) => playback.changeSound(row.id, level)}/>
        </div>
      </div>
    })}
    <p className="pl-sound__note">{SOUND_NOTE}</p>
  </>
}

/**
 * Pressing and holding with the pointer: one step at once, then more
 * while it stays down, no faster than the TV repeats a held key.
 */
function useHoldRepeat(step: () => void) {
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined)
  const latest = useRef(step)
  useEffect(() => { latest.current = step })
  const stop = () => clearTimeout(timer.current)
  useEffect(() => stop, [])
  const every = Math.max(100, platform.repeatMs)
  const again = () => { latest.current(); timer.current = setTimeout(again, every) }
  return {
    onPointerDown: (e: ReactPointerEvent) => { e.preventDefault(); stop(); latest.current(); timer.current = setTimeout(again, 400) },
    onPointerUp: stop, onPointerLeave: stop, onPointerCancel: stop,
    // The press already stepped; the click it ends with does nothing more.
    onClick: (e: { stopPropagation(): void }) => e.stopPropagation(),
  }
}

/** Subtitle timing, while a track is on. The cues move as it is pressed, beside the open panel. */
export function TimingRows({ timing, playback }: { timing: TimingView; playback: FilePlayback }) {
  const earlier = useHoldRepeat(() => playback.stepTiming(-1))
  const later = useHoldRepeat(() => playback.stepTiming(1))
  const failed = timing.status === 'failed'
  const shared = timing.status === 'shared'
  return <>
    <Heading>Subtitle timing</Heading>
    <div className="pl-timing" role="group" aria-label={`Subtitle timing, ${timing.words}`} tabIndex={-1} data-focus={TIMING_ROW}
      onClick={() => { if (failed) playback.retryTiming() }}>
      <button type="button" tabIndex={-1} className="pl-timing__step" aria-label="Earlier, 0.1 seconds" {...earlier}><Icon name="left"/>Earlier</button>
      <span className="pl-timing__value" role="status">
        <span className="pl-timing__words">{timing.words}</span>
        {timing.setForEveryone && <span className="pl-timing__shared">{TIMING_SHARED}</span>}
      </span>
      <button type="button" tabIndex={-1} className="pl-timing__step" aria-label="Later, 0.1 seconds" {...later}>Later<Icon name="right"/></button>
    </div>
    {timing.burned && <p className="pl-timing__burned">{TIMING_BURNED}</p>}
    {failed ? <p className="pl-timing__help pl-timing__help--error" role="alert">{TIMING_FAILED} Press OK to try again.</p>
      : <p className="pl-timing__help">{TIMING_HELP}</p>}
    {timing.canReset && <Option id="timing/reset" label="Reset" detail={resetDetail(timing.resetTo)} onPress={() => playback.resetTiming()}/>}
    {(timing.canShare || shared) && <Option id="timing/everyone" label={shared ? SHARE_DONE : SHARE_LABEL} detail={SHARE_HELP}
      end={shared ? <span className="pl-timing__done"><Icon name="check"/></span> : undefined} onPress={() => { if (!shared) playback.shareTiming() }}/>}
  </>
}

/** The panel's foot: the keys of the row focused, in words on a remote that points. */
export function PanelFoot({ focused, timingFailed }: { focused: string | undefined; timingFailed: boolean }) {
  const foot = panelFoot(focused)
  const said = platform.pointer ? pointerFoot(foot, BACK) : undefined
  if (foot === 'sentence' || said) return <p className="pl-panel__foot">{said ?? `A choice applies straight away. ${BACK} closes this.`}</p>
  const kbd = (k: string) => <span className="tv-kbd">{k}</span>
  return <p className="pl-panel__foot pl-panel__keys">
    {foot === 'timing' ? <>
      <span>{kbd('◀')}Earlier</span><span>{kbd('▶')}Later</span><span>Hold to repeat</span>
      {timingFailed && <span>{kbd('OK')}Try again</span>}
    </> : <>
      <span>{kbd('◀')}{kbd('▶')}Move</span><span>{kbd('OK')}Choose</span>
    </>}
    <span>{kbd(BACK)}Closes this</span>
  </p>
}
