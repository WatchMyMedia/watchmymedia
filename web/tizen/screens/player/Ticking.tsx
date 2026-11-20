import { useEffect, useState, type CSSProperties } from 'react'
import { clock } from '../../../src/lib/clock'
import { timeOfDay } from '../../../src/lib/liveGuide'
import { SUBTITLE_PX, type Cue, type FilePlayback, type FilePlaybackState } from '../../player'
import { SCRUBBER_ID } from './keys'
import { nextButton, nextFill } from './labels'
import { PlayerIcon } from './PlayerIcons'
import { bands, percent, scrubberView } from './scrubber'
import { useSelect } from './select'

// The parts of the player that move while it plays: the scrubber and its
// times, the clock, the Skip button's fill, the Next episode countdown and
// the subtitles. Each reads only what it draws, so the engine's ticks
// redraw these and nothing else.

type Props = { playback: FilePlayback }

const progressOf = (s: FilePlaybackState) => ({
  positionMs: s.positionMs, durationMs: s.durationMs, timelineMs: s.timelineMs, bufferedMs: s.bufferedMs,
  scrub: s.scrub, recordingNow: !!s.recording?.now, segments: s.segments,
})

/** The time of day, as at the top right of the controls, redrawn each minute. */
export function WallClock({ className }: { className?: string }) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = setTimeout(() => setNow(Date.now()), 60_000 - now % 60_000 + 50)
    return () => clearTimeout(timer)
  }, [now])
  return <span className={className}>{timeOfDay(now)}</span>
}

/**
 * The scrubber row: elapsed time, the track (buffered, played, the segment
 * bands, a growing recording's written part and its Now marker), the thumb
 * and, while seeking, the gap to the target with the seek thumbnail or the
 * target's time and the step above it; the length on the right.
 */
export function ScrubberRow({ playback, focused }: Props & { focused: boolean }) {
  const p = useSelect(playback, progressOf)
  const view = scrubberView({ ...p, recording: { now: p.recordingNow } })
  const total = p.timelineMs || p.durationMs
  const known = p.durationMs > 0
  const marks = bands(p.segments, p.durationMs, total)
  const pct = (n: number) => `${n.toFixed(2)}%`
  return <div className="pl-scrub">
    <span className="pl-scrub__time">{known ? clock(p.positionMs) : ''}</span>
    <div className={`pl-scrub__track${focused || view.seeking ? ' pl-scrub__track--on' : ''}`} tabIndex={-1} data-focus={SCRUBBER_ID} role="slider"
      aria-label="Position" aria-valuemin={0} aria-valuemax={Math.round(total / 1000)} aria-valuenow={Math.round(p.positionMs / 1000)} aria-valuetext={`${clock(p.positionMs)} of ${clock(total)}`}>
      {view.edge !== undefined && <>
        <span className="pl-scrub__unwritten" style={{ left: pct(view.edge) }}/>
        <span className="pl-scrub__written" style={{ width: pct(view.edge) }}/>
      </>}
      {view.edge === undefined && <span className="pl-scrub__buffered" style={{ width: pct(view.buffered) }}/>}
      {marks.map((b) => <span key={b.key} className={`pl-scrub__band${b.opening ? ' pl-scrub__band--opening' : ''}`} style={{ left: pct(b.left), width: pct(b.width) }}/>)}
      <span className="pl-scrub__played" style={{ width: pct(view.played) }}/>
      {view.gap && <>
        <span className="pl-scrub__gap" style={{ left: pct(view.gap.left), width: pct(view.gap.width) }}/>
        <span className="pl-scrub__now" style={{ left: pct(view.played) }}/>
      </>}
      {view.edge !== undefined && <>
        <span className="pl-scrub__edge" style={{ left: pct(view.edge) }}/>
        <span className="pl-scrub__edge-tag" style={{ left: pct(view.edge) }}>Now · <WallClock/></span>
      </>}
      <span className="pl-scrub__thumb" style={{ left: pct(view.thumb) }}/>
      {p.scrub && <SeekPreview scrub={p.scrub} at={view.thumb}/>}
    </div>
    <span className={`pl-scrub__time pl-scrub__time--end${p.recordingNow ? ' pl-scrub__time--planned' : ''}`}>{known ? clock(total) : ''}</span>
  </div>
}

/** Above the target while seeking: the seek thumbnail when the file has them, the target's time and the step ("+2 min"). */
function SeekPreview({ scrub, at }: { scrub: NonNullable<FilePlaybackState['scrub']>; at: number }) {
  const tile = scrub.thumbnail
  const style = { '--at': `${at.toFixed(2)}%` } as CSSProperties
  return <div className={`pl-seek${tile ? '' : ' pl-seek--bare'}`} style={style} aria-hidden="true">
    {tile && <div className="pl-seek__frame">
      <div className="pl-seek__tile" style={{ aspectRatio: `${tile.width} / ${tile.height}`, ...(tile.loaded ? tile.background : {}) }}/>
    </div>}
    <span className="pl-seek__line"><span className="pl-seek__when">{clock(scrub.targetMs)}</span><span className="pl-seek__step">{scrub.step}</span></span>
  </div>
}

const miniOf = (s: FilePlaybackState) => ({ positionMs: s.positionMs, durationMs: s.durationMs })

/** The small progress line while the card is up. */
export function MiniProgress({ playback }: Props) {
  const p = useSelect(playback, miniOf)
  return <div className="pl-mini" aria-hidden="true">
    <span>{clock(p.positionMs)}</span>
    <span className="pl-mini__bar"><span style={{ width: `${percent(p.positionMs, p.durationMs).toFixed(2)}%` }}/></span>
    <span>{clock(p.durationMs)}</span>
  </div>
}

const skipOf = (s: FilePlaybackState) => s.skip

/** Skip Intro, Recap, Credits or Ad: it takes focus when it comes; in auto-delay a fill runs across it for 3 s. */
export function SkipButton({ playback }: Props) {
  const skip = useSelect(playback, skipOf)
  if (!skip) return null
  return <div className="pl-skip-host">
    <button type="button" className="pl-skip" data-focus="skip" onClick={() => playback.skip()}>
      {skip.progress !== undefined && <span className="pl-skip__fill" style={{ width: `${(skip.progress * 100).toFixed(1)}%` }}/>}
      <span className="pl-skip__label">{skip.label}<PlayerIcon name="skip"/></span>
    </button>
  </div>
}

const countdownOf = (s: FilePlaybackState) => s.next

/** The Next episode card's main button: "Next episode in 7", filling as it counts, or "Play next episode". */
export function NextButton({ playback }: Props) {
  const next = useSelect(playback, countdownOf)
  if (!next) return null
  const fill = nextFill(next)
  return <button type="button" className="pl-next__btn pl-next__btn--fill" data-focus="next/play" data-autofocus onClick={() => playback.playNext()}>
    {fill > 0 && <span className="pl-next__count" style={{ width: `${(fill * 100).toFixed(1)}%` }} aria-hidden="true"/>}
    <span className="pl-next__label"><PlayerIcon name="play"/>{nextButton(next)}</span>
  </button>
}

const cuesOf = (s: FilePlaybackState) => ({ cues: s.subtitles.cues, size: s.subtitles.size })

/** The subtitles showing, at the TV's size, inside the safe area; raised above the controls or the card when they show. */
export function Cues({ playback, raised }: Props & { raised: boolean }) {
  const { cues, size } = useSelect(playback, cuesOf)
  if (!cues.length) return null
  const style = { fontSize: `${SUBTITLE_PX[size] / 16}rem` }
  const top = cues.filter((c) => c.top), bottom = cues.filter((c) => !c.top)
  return <>
    {top.length > 0 && <CueBlock cues={top} className="pl-cues pl-cues--top" style={style}/>}
    {bottom.length > 0 && <CueBlock cues={bottom} className={`pl-cues${raised ? ' pl-cues--raised' : ''}`} style={style}/>}
  </>
}

function CueBlock({ cues, className, style }: { cues: readonly Cue[]; className: string; style: CSSProperties }) {
  return <div className={className} style={style} aria-live="off">
    {cues.map((c) => <p key={c.id} className="pl-cue">{c.lines.map((line, i) => <span key={i} className="pl-cue__line">{line}</span>)}</p>)}
  </div>
}
