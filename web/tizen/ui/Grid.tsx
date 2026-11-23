import { useEffect, useState, type CSSProperties, type FocusEvent, type ReactNode } from 'react'
import { useGroupMemory } from '../app/hooks'
import { gridWindow } from '../app/focusRules'

export type GridProps<T> = {
  /** The grid's group id; its cards' ids are `<id>/<itemKey>`. */
  id: string
  items: readonly T[]
  itemKey(item: T, index: number): string
  variant: 'poster' | 'wide'
  /** 6 posters across, or 4 wide cards. */
  columns?: number
  /** Rows in view at once, for drawing only those near it. */
  visibleRows?: number
  renderItem(item: T, focusId: string, index: number): ReactNode
  /** Called when focus gets near the end of what has loaded, while `hasMore`. */
  onLoadMore?(): void
  hasMore?: boolean
  /**
   * Draws the grid around this index before focus goes there (a jump, such
   * as the A–Z strip's): each new object moves it, whatever has focus.
   */
  reveal?: { index: number }
}

/**
 * A grid card's size, the boards' 232 × 348 poster: six across beside the
 * rail, the seventh peeking at the screen's edge. A screen narrows them
 * with `--grid-poster-w` on a parent. Wide cards keep the rows' size; four fit.
 * A cell is the picture and the title's two lines under it.
 */
const POSTER_W = 'var(--grid-poster-w, 14.5rem)'
const SIZES = {
  poster: { '--poster-w': POSTER_W, '--poster-h': `calc(${POSTER_W} * 1.5)`, '--card-w': POSTER_W, '--grid-cell-h': `calc(${POSTER_W} * 1.5 + 5.5rem)` } as CSSProperties,
  wide: { '--card-w': 'var(--wide-w)', '--grid-cell-h': 'calc(var(--wide-h) + 5.5rem)' } as CSSProperties,
}

/**
 * A grid of cards, virtualised by rows: only the rows around the focused
 * one are drawn, the track moves with a transform so the focused row and
 * the one above it stay in view, and more is asked for near the end.
 */
export function Grid<T>({ id, items, itemKey, variant, columns = variant === 'poster' ? 6 : 4, visibleRows = 2, renderItem, onLoadMore, hasMore, reveal }: GridProps<T>) {
  const remembered = useGroupMemory(id)
  const [focus, setFocus] = useState(() => Math.max(0, items.findIndex((item, i) => `${id}/${itemKey(item, i)}` === remembered)))
  // A jump asked for: draw around it now, so its card is there to take focus.
  const [revealed, setRevealed] = useState(reveal)
  if (reveal !== revealed) {
    setRevealed(reveal)
    if (reveal) setFocus(reveal.index)
  }
  const { first, from, to, loadMore } = gridWindow(focus, items.length, columns, visibleRows)
  const wantMore = loadMore && !!hasMore
  useEffect(() => { if (wantMore) onLoadMore?.() }, [wantMore, items.length, onLoadMore])

  const onFocus = (e: FocusEvent) => {
    const cell = (e.target as HTMLElement).closest?.('[data-index]')
    const i = cell ? Number(cell.getAttribute('data-index')) : -1
    if (i >= 0 && i !== focus) setFocus(i)
  }
  const rowH = `(var(--grid-cell-h) + var(--grid-row-gap))`
  const style = { ...SIZES[variant], '--columns': columns, '--grid-row-gap': '2.5rem', transform: `translateY(calc(${-first} * ${rowH}))` } as CSSProperties
  return <div className={`tv-grid tv-grid--${variant}`} data-group={id} data-vscroll>
    <div className="tv-grid__track" style={style} onFocus={onFocus}>
      {from > 0 && <span className="tv-grid__spacer" style={{ height: `calc(${from / columns} * ${rowH} - var(--grid-row-gap))` }}/>}
      {items.slice(from, to).map((item, k) => {
        const i = from + k
        const key = itemKey(item, i)
        return <div key={key} className="tv-grid__cell" data-index={i} style={{ height: 'var(--grid-cell-h)' }}>{renderItem(item, `${id}/${key}`, i)}</div>
      })}
    </div>
  </div>
}
