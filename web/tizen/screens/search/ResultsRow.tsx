import { useState, type CSSProperties, type FocusEvent, type ReactNode } from 'react'
import { useGroupMemory } from '../../app/hooks'
import { rowWindow } from '../../app/focusRules'

/**
 * A row of results in Search's right-hand column, which holds fewer cards
 * than a screen-wide row: the kit Row's rules (card by card, the card before
 * the focused one in view, only the cards near the view drawn, back on the
 * card it remembers) for `visible` cards of `cell` width.
 */
export function ResultsRow<T>({ id, title, count, items, itemKey, cell, visible, renderItem, foot, room }: {
  id: string
  title: string
  count?: ReactNode
  items: readonly T[]
  itemKey(item: T): string
  /** A card's width, as CSS. */
  cell: string
  visible: number
  renderItem(item: T, focusId: string): ReactNode
  /** Under the row: the focused card's keys. */
  foot?: ReactNode
  /** The row's height from the start, as CSS: room for a line some cards get later (their server), so the rows under it never move. */
  room?: string
}) {
  const remembered = useGroupMemory(id)
  const [focus, setFocus] = useState(() => Math.max(0, items.findIndex((item) => `${id}/${itemKey(item)}` === remembered)))
  const { start, from, to } = rowWindow(Math.min(focus, items.length - 1), items.length, visible)
  const step = '(var(--cell-w) + var(--card-gap))'
  const onFocus = (e: FocusEvent) => {
    const cellEl = (e.target as HTMLElement).closest?.('[data-index]')
    const i = cellEl ? Number(cellEl.getAttribute('data-index')) : -1
    if (i >= 0 && i !== focus) setFocus(i)
  }
  return <section className="search-section" data-group={id} data-vitem aria-label={title}>
    <h2 className="tv-h2">{title}{count !== undefined && <small>{count}</small>}</h2>
    <div className="search-section__viewport" data-clip>
      <div className="search-section__track" data-row onFocus={onFocus} style={{ '--cell-w': cell, minHeight: room, transform: `translateX(calc(${-start} * ${step}))` } as CSSProperties}>
        {from > 0 && <span style={{ flex: 'none', width: `calc(${from} * ${step} - var(--card-gap))` }}/>}
        {items.slice(from, to).map((item, k) => <div key={itemKey(item)} className="search-section__cell" data-index={from + k}>{renderItem(item, `${id}/${itemKey(item)}`)}</div>)}
      </div>
    </div>
    {foot}
  </section>
}
