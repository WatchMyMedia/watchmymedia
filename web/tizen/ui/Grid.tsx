import { useEffect, useState, type CSSProperties, type FocusEvent, type ReactNode } from 'react'
import { useGroupMemory } from '../app/hooks'
import { gridWindow } from '../app/focusRules'

export type GridProps<T> = {
  /** The grid's group id; its cards' ids are `<id>/<itemKey>`. */
  id: string
  items: readonly T[]
  itemKey(item: T, index: number): string
  variant: 'poster' | 'wide'
  /** 8 posters across, or 5 wide cards. */
  columns?: number
  /** Rows in view at once, for drawing only those near it. */
  visibleRows?: number
  renderItem(item: T, focusId: string, index: number): ReactNode
  /** Called when focus gets near the end of what has loaded, while `hasMore`. */
  onLoadMore?(): void
  hasMore?: boolean
}

const CELL_H = { poster: '22.75rem', wide: '15.5rem' }

/**
 * A grid of cards, virtualised by rows: only the rows around the focused
 * one are drawn, the track moves with a transform so the focused row and
 * the one above it stay in view, and more is asked for near the end.
 */
export function Grid<T>({ id, items, itemKey, variant, columns = variant === 'poster' ? 8 : 5, visibleRows = 2, renderItem, onLoadMore, hasMore }: GridProps<T>) {
  const remembered = useGroupMemory(id)
  const [focus, setFocus] = useState(() => Math.max(0, items.findIndex((item, i) => `${id}/${itemKey(item, i)}` === remembered)))
  const { first, from, to, loadMore } = gridWindow(focus, items.length, columns, visibleRows)
  const wantMore = loadMore && !!hasMore
  useEffect(() => { if (wantMore) onLoadMore?.() }, [wantMore, items.length, onLoadMore])

  const onFocus = (e: FocusEvent) => {
    const cell = (e.target as HTMLElement).closest?.('[data-index]')
    const i = cell ? Number(cell.getAttribute('data-index')) : -1
    if (i >= 0 && i !== focus) setFocus(i)
  }
  const rowH = `(var(--grid-cell-h) + var(--grid-row-gap))`
  const style = { '--columns': columns, '--grid-cell-h': CELL_H[variant], '--grid-row-gap': '1.5rem', transform: `translateY(calc(${-first} * ${rowH}))` } as CSSProperties
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
