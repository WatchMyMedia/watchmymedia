import { useEffect, useState, type CSSProperties } from 'react'
import { clock } from '../../../src/lib/clock'
import { timeOfDay } from '../../../src/lib/liveGuide'
import { SUBTITLE_PX, type Cue, type FilePlayback, type FilePlaybackState } from '../../player'
import { useNow } from '../live/clock'
import { SCRUBBER_ID } from './keys'
import { elapsedWords, timeWords } from './labels'
import { PlayerIcon } from './PlayerIcons'
import { bands, scrubberView } from './scrubber'
import { useSelect } from './select'
import { BACK } from '../../app/keys'

// The parts of the player that move while it plays: the bar and the time
// over it, the clock, the Skip button's fill and the subtitles. Each reads only what it draws, so the engine's ticks
// redraw these and nothing else.

type Props = { playback: FilePlayback }

const progressOf = (s: FilePlaybackState) => ({
  positionMs: s.positionMs, durationMs: s.durationMs, timelineMs: s.timelineMs, bufferedMs: s.bufferedMs,
  scrub: s.scrub, recordingNow: !!s.recording?.now, segments: s.segments,
})

/** The time of day, top right, redrawn each minute. */
export function WallClock({ className }: { className?: string }) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = setTimeout(() => setNow(Date.now()), 60_000 - now % 60_000 + 50)
    return () => clearTimeout(timer)
  }, [now])
  return <span className={className}>{timeOfDay(now)}</span>
}

/**
 * The bar and the line over it: the time at the left; what is left and
 * when it ends at the right (while seeking, what is left from there; a
 * recording still recording, how far it has got). The bar marks the intro
 * and the credits, a growing recording's unwritten part, and while seeking
 * the picture and the time you would land on, above the target.
 */
export function ScrubberRow({ playback, focused }: Props & { focused: boolean }) {
  const p = useSelect(playback, progressOf)
  const now = useNow(60_000)
  const view = scrubberView({ ...p, recording: { now: p.recordingNow } })
  const known = p.durationMs > 0
  const marks = bands(p.segments, p.durationMs, p.timelineMs || p.durationMs)
  const total = p.timelineMs || p.durationMs
  const words = timeWords({ ...p, targetMs: p.scrub?.targetMs }, now)
  const pct = (n: number) => `${n.toFixed(2)}%`
  return <div className="pl-scrub">
    <div className="pl-scrub__line">
      <span className="pl-scrub__time">{known ? elapsedWords(p.positionMs) : ''}</span>
      {words && <span className="pl-scrub__left"><b>{words.strong}</b>{words.soft && <span>{words.soft}</span>}</span>}
    </div>
    <div className={`pl-scrub__track${focused || view.seeking ? ' pl-scrub__track--on' : ''}`} tabIndex={-1} data-focus={SCRUBBER_ID} role="slider"
      aria-label="Position" aria-valuemin={0} aria-valuemax={Math.round(total / 1000)} aria-valuenow={Math.round(p.positionMs / 1000)} aria-valuetext={`${clock(p.positionMs)} of ${clock(total)}`}>
      {view.edge !== undefined && <>
        <span className="pl-scrub__written" style={{ width: pct(view.edge) }}/>
        <span className="pl-scrub__unwritten" style={{ left: pct(view.edge) }}/>
      </>}
      {marks.map((b) => <span key={b.key} className="pl-scrub__band" style={{ left: pct(b.left), width: pct(b.width) }}/>)}
      <span className="pl-scrub__played" style={{ width: pct(view.played) }}/>
      {view.gap && <span className="pl-scrub__gap" style={{ left: pct(view.gap.left), width: pct(view.gap.width) }}/>}
      <span className="pl-scrub__thumb" style={{ left: pct(view.thumb) }}/>
      {p.scrub && <SeekPreview scrub={p.scrub} at={view.thumb}/>}
    </div>
  </div>
}

/** Above the target while seeking: the seek thumbnail when the file has them, with a gold edge, and the time you would land on. */
function SeekPreview({ scrub, at }: { scrub: NonNullable<FilePlaybackState['scrub']>; at: number }) {
  const tile = scrub.thumbnail
  const style = { '--at': `${at.toFixed(2)}%` } as CSSProperties
  return <div className={`pl-seek${tile ? '' : ' pl-seek--bare'}`} style={style} aria-hidden="true">
    {tile && <div className="pl-seek__frame" style={{ aspectRatio: `${tile.width} / ${tile.height}`, ...(tile.loaded ? tile.background : {}) }}/>}
    <span className="pl-seek__when">{clock(scrub.targetMs)}</span>
  </div>
}

const seekOf = (s: FilePlaybackState) => ({ positionMs: s.positionMs, targetMs: s.scrub?.targetMs })

/** While seeking, under the bar: what OK and Return do, and that holding goes faster. */
export function SeekHints({ playback }: Props) {
  const p = useSelect(playback, seekOf)
  return <p className="pl-hints" aria-hidden="true">
    {p.targetMs !== undefined && <span><span className="tv-kbd">OK</span>Play from {clock(p.targetMs)}</span>}
    <span><span className="tv-kbd">{BACK}</span>Stay at {clock(p.positionMs)}</span>
    <span>Hold an arrow to go faster</span>
  </p>
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
