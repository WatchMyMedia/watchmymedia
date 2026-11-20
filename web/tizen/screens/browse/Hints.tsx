import { Fragment, useLayoutEffect, useRef, type FocusEvent, type HTMLAttributes, type ReactNode } from 'react'
import type { FocusHintWords } from './browseHooks'

// The remote's keys the browsing screens name: a row of key caps along the
// foot (LibraryAZ), and the hint for whatever has focus (LibraryRows' pill,
// ListPage's header), read from the focused card's data-hint and data-hold.

export type Hint = { keys: string[]; label: string } | { note: string }

/** "▶ From the last column: A–Z   CH ▲ CH ▼ A screen up or down". */
export function KeyRow({ hints, className = 'browse-hints' }: { hints: readonly Hint[]; className?: string }) {
  return <div className={className} aria-hidden="true">
    {hints.map((h, i) => <Fragment key={i}>
      {i > 0 && <span className="browse-hints__gap"/>}
      {'note' in h ? <span className="browse-hints__note">{h.note}</span>
        : <>{h.keys.map((k) => <span key={k} className="tv-kbd">{k}</span>)}<span>{h.label}</span></>}
    </Fragment>)}
  </div>
}

/** "OK Open the movie · Hold OK Mark watched", hidden when the focused element says nothing. */
export function HintPill({ words, className = 'browse-pill' }: { words: FocusHintWords; className?: string }) {
  return <p className={className} aria-hidden="true" hidden={!words.ok && !words.hold}>
    {words.ok && <><span className="tv-kbd">OK</span><span>{words.ok}</span></>}
    {words.hold && <><span className="browse-pill__gap"/><span className="tv-kbd">Hold OK</span><span>{words.hold}</span></>}
  </p>
}

/**
 * A row of controls wider than its box (the library's chips), kept in view
 * as focus moves along it: the track moves with a transform, `pad` px
 * inside the edges. `trackProps` go on the track (a data-row, its group).
 */
export function HScroll({ children, className, trackClassName, pad = 16, trackProps }: {
  children: ReactNode; className: string; trackClassName: string; pad?: number; trackProps?: HTMLAttributes<HTMLDivElement> & Record<`data-${string}`, string | boolean | undefined>
}) {
  const view = useRef<HTMLDivElement>(null)
  const track = useRef<HTMLDivElement>(null)
  const offset = useRef(0)
  const moveTo = (wanted: number) => {
    const v = view.current, t = track.current
    if (!v || !t) return
    const next = Math.max(0, Math.min(wanted, t.offsetWidth - v.clientWidth + 2 * pad))
    if (next === offset.current) return
    offset.current = next
    t.style.transform = `translateX(${-next}px)`
  }
  const onFocus = (e: FocusEvent) => {
    const v = view.current, t = track.current
    if (!v || !t) return
    const box = t.getBoundingClientRect(), r = (e.target as HTMLElement).getBoundingClientRect()
    const left = r.left - box.left, right = r.right - box.left
    if (left - offset.current < pad) moveTo(left - pad)
    else if (right - offset.current > v.clientWidth - pad) moveTo(right - v.clientWidth + pad)
  }
  // The row may have grown shorter (fewer genres): no further along than its end.
  useLayoutEffect(() => moveTo(offset.current))
  return <div className={className} ref={view} data-clip>
    <div {...trackProps} className={trackClassName} ref={track} onFocus={onFocus}>{children}</div>
  </div>
}
