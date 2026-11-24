import { useRef, type FocusEvent, type ReactNode } from 'react'

/**
 * A vertical list moved with a transform as focus goes down it. 'nearest'
 * keeps the focused item (or its `data-vitem` section) `pad` px inside the
 * view; 'start' puts each section at the top, as Home's one row a screen.
 */
export function VScroll({ children, align = 'nearest', pad = 48, className }: { children: ReactNode; align?: 'nearest' | 'start'; pad?: number; className?: string }) {
  const view = useRef<HTMLDivElement>(null)
  const track = useRef<HTMLDivElement>(null)
  const offset = useRef(0)
  const onFocus = (e: FocusEvent) => {
    const v = view.current, t = track.current
    if (!v || !t) return
    const target = e.target as HTMLElement
    const item = target.closest<HTMLElement>('[data-vitem]') ?? target
    const top = item.getBoundingClientRect().top - t.getBoundingClientRect().top
    const bottom = top + item.getBoundingClientRect().height
    // Inside the viewport's own padding, which leaves room for a focused row's ring.
    const style = getComputedStyle(v)
    const height = v.clientHeight - (parseFloat(style.paddingTop) || 0) - (parseFloat(style.paddingBottom) || 0)
    let next = offset.current
    if (align === 'start') next = top - pad
    else if (top - next < pad) next = top - pad
    else if (bottom - next > height - pad) next = bottom - height + pad
    next = Math.max(0, Math.min(next, t.scrollHeight - height))
    if (next === offset.current) return
    offset.current = next
    t.style.transform = `translateY(${-next}px)`
  }
  return <div ref={view} className={`tv-vscroll${className ? ` ${className}` : ''}`} data-vscroll>
    <div ref={track} className="tv-vscroll__track" onFocus={onFocus}>{children}</div>
  </div>
}
