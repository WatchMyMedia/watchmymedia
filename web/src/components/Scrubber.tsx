import { useEffect, useLayoutEffect, useMemo, useReducer, useRef, useState, type PointerEvent } from 'react'
import { formatDuration } from '../lib/format'
import { SheetLoader, fetchImage, previewLeft, tileAt, tileBackground, type Trickplay } from '../lib/trickplay'

/** Where the viewer is scrubbing to, in seconds, and whether they are dragging there or only hovering. */
export type ScrubPreview = { seconds: number; dragging: boolean }

type Props = {
  time: number
  duration: number
  bufferedEnd: number
  /** A growing recording has a faded, unseekable future after this point. */
  available?: number
  /** The decision's seek thumbnails, when the file has them. */
  trickplay: Trickplay | null
  controlsShown: boolean
  onSeek: (seconds: number) => void
  onPreview: (preview: ScrubPreview | null) => void
}

const clock = (seconds: number) => formatDuration(seconds * 1000) || '0:00'

/**
 * The player's seek bar (the PlayerScrub boards): a click or a drag seeks,
 * and while the pointer hovers or drags, the seek thumbnail for that time
 * sits above it with the time under it, kept within the bar
 * (PLAN-TRICKPLAY.md §7.3). Without thumbnails the time shows alone. The
 * picture is decorative; the slider says the time.
 */
export function Scrubber({ time, duration, bufferedEnd, available, trickplay, controlsShown, onSeek, onPreview }: Props) {
  const barRef = useRef<HTMLDivElement>(null)
  const previewRef = useRef<HTMLDivElement>(null)
  const dragging = useRef(false)
  const [point, setPoint] = useState<{ fraction: number; dragging: boolean } | null>(null)
  // A sheet arriving draws its tile.
  const [, sheetCame] = useReducer((n: number) => n + 1, 0)
  const loader = useMemo(() => (trickplay ? new SheetLoader(trickplay, fetchImage, sheetCame) : null), [trickplay])

  // The sheet under the playhead, fetched when the controls show, so the
  // first tile of a scrub is there when it starts.
  const playheadSheet = trickplay ? tileAt(trickplay, time * 1000).sheet : -1
  useEffect(() => {
    if (controlsShown) loader?.want(playheadSheet)
  }, [controlsShown, loader, playheadSheet])

  const at = point ? point.fraction * duration : 0
  const tile = trickplay && point ? tileAt(trickplay, at * 1000) : null

  // The preview follows the pointer: placed after layout, once its width is known.
  useLayoutEffect(() => {
    const bar = barRef.current
    const preview = previewRef.current
    if (!bar || !preview || !point) return
    preview.style.left = `${previewLeft(point.fraction * bar.clientWidth, preview.offsetWidth, bar.clientWidth)}px`
  })

  const limit = Math.min(duration, available ?? duration)
  const maxFraction = duration > 0 ? limit / duration : 0
  const fractionAt = (e: PointerEvent<HTMLDivElement>) => {
    const r = barRef.current?.getBoundingClientRect()
    return r && r.width > 0 ? Math.min(Math.max((e.clientX - r.left) / r.width, 0), maxFraction) : 0
  }
  const show = (fraction: number, drag: boolean) => {
    if (trickplay) loader?.want(tileAt(trickplay, fraction * duration * 1000).sheet)
    setPoint({ fraction, dragging: drag })
    onPreview({ seconds: fraction * duration, dragging: drag })
  }
  // A scrub starts: fetch the sheets around here, and give failed ones another go.
  const start = (fraction: number) => {
    if (trickplay) loader?.scrubFrom(tileAt(trickplay, fraction * duration * 1000))
  }
  const end = () => {
    dragging.current = false
    setPoint(null)
    onPreview(null)
  }

  const played = point?.dragging ? point.fraction * 100 : duration ? (time / duration) * 100 : 0
  const buffered = duration ? (bufferedEnd / duration) * 100 : 0

  return (
    <div
      ref={barRef}
      role="slider"
      tabIndex={0}
      aria-label="Seek"
      aria-valuemin={0}
      aria-valuemax={Math.round(limit)}
      aria-valuenow={Math.round(Math.min(limit, point?.dragging ? at : time))}
      aria-valuetext={`${clock(point?.dragging ? at : time)} of ${clock(duration)}`}
      // A growing recording's slider also answers Up, Down, Home and End, within
      // what is written. Left and Right stay with the player's own keys, as for
      // any file, and the key goes on to the player, which keeps the controls up.
      onKeyDown={available === undefined ? undefined : (e) => {
        let target: number
        switch (e.key) { case 'ArrowDown': target = time - 10; break; case 'ArrowUp': target = time + 10; break; case 'Home': target = 0; break; case 'End': target = limit; break; default: return }
        e.preventDefault(); onSeek(Math.max(0, Math.min(target, limit)))
      }}
      className="relative -my-2 cursor-pointer touch-none rounded py-2 focus-visible:outline-2"
      onClick={(e) => e.stopPropagation()}
      onPointerDown={(e) => {
        if (!duration || e.button !== 0) return
        e.currentTarget.setPointerCapture(e.pointerId)
        dragging.current = true
        const f = fractionAt(e)
        if (!point) start(f)
        show(f, true)
      }}
      onPointerMove={(e) => {
        if (!duration || (!dragging.current && e.pointerType === 'touch')) return
        const f = fractionAt(e)
        if (!point) start(f)
        show(f, dragging.current)
      }}
      onPointerUp={(e) => {
        if (!dragging.current) return
        dragging.current = false
        const f = fractionAt(e)
        onSeek(f * duration)
        // A mouse is still over the bar; a finger has gone.
        if (e.pointerType === 'touch') end()
        else show(f, false)
      }}
      onPointerCancel={end}
      onPointerLeave={() => {
        if (!dragging.current) end()
      }}
    >
      <div className={`relative h-2 w-full rounded ${available === undefined ? 'bg-white/20' : 'bg-white/5'}`}>
        {available !== undefined && <div className="absolute inset-y-0 left-0 rounded bg-white/20" style={{ width: `${maxFraction * 100}%` }} />}
        <div className="absolute inset-y-0 left-0 rounded bg-white/30" style={{ width: `${buffered}%` }} />
        <div className="absolute inset-y-0 left-0 rounded bg-accent" style={{ width: `${played}%` }} />
        {point && !point.dragging && <div className="absolute -inset-y-1 -ml-px w-0.5 rounded-sm bg-white" style={{ left: `${point.fraction * 100}%` }} />}
        {point && <div className="absolute top-1/2 -ml-2 -mt-2 size-4 rounded-full bg-accent ring-2 ring-black/50" style={{ left: `${played}%` }} />}
        {point && (
          <div ref={previewRef} className="pointer-events-none absolute bottom-5 left-0 flex flex-col items-center gap-1.5" aria-hidden="true">
            {trickplay && tile && (
              <div className="rounded-[10px] bg-neutral-100/90 p-0.5 shadow-2xl shadow-black/60">
                <div
                  className="w-44 rounded-lg bg-black bg-no-repeat sm:w-64"
                  style={{ aspectRatio: `${trickplay.width} / ${trickplay.height}`, ...(loader?.loaded(tile.sheet) ? tileBackground(trickplay, tile) : {}) }}
                />
              </div>
            )}
            <span className="flex h-6 items-center rounded-md bg-black/70 px-2 text-[13px] font-bold tabular-nums text-neutral-100">{clock(at)}</span>
          </div>
        )}
      </div>
    </div>
  )
}
