import type { CSSProperties } from 'react'
import { cueBlocks } from '../lib/play/cues'
import type { Cue } from '../lib/play/vtt'
import { cueFontPx, type SubtitleSize } from '../lib/subtitleSize'
import type { PictureBox } from './usePlayerCues'

/**
 * The subtitles the player draws over the picture: at the foot of the
 * picture (raised over the controls while they show), or at its top where
 * the file placed them there; never under the open panel. Sized by the
 * person's Subtitle size against the picture's height.
 */
export function CueLayer({ cues, box, size, raise = 0, rightInset = 0 }: { cues: readonly Cue[]; box: PictureBox; size: SubtitleSize; raise?: number; rightInset?: number }) {
  if (!cues.length || box.height <= 0) return null
  const { top, bottom } = cueBlocks(cues)
  const fontSize = cueFontPx(box.height, size)
  const left = box.left
  const right = Math.max(box.outerWidth - (box.left + box.width), rightInset)
  const below = box.outerHeight - (box.top + box.height)
  const margin = box.height * 0.06
  const block = (list: readonly Cue[], style: CSSProperties) => (
    <div aria-hidden="true" className="pointer-events-none absolute z-[10] flex flex-col items-center gap-[.15em] px-[6%]" style={{ left, right, fontSize, ...style }}>
      {list.map((c) => (
        <p key={c.id} className="player-cue">
          <span>{c.lines.flatMap((line, i) => (i ? [<br key={i} />, line] : [line]))}</span>
        </p>
      ))}
    </div>
  )
  return (
    <>
      {top.length > 0 && block(top, { top: box.top + margin })}
      {bottom.length > 0 && block(bottom, { bottom: Math.max(below + margin, raise) })}
    </>
  )
}

