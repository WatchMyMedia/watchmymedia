import { useEffect, useRef, useState, type CSSProperties, type FocusEvent, type ReactNode } from 'react'
import { useGroupMemory } from '../app/hooks'
import { rowWindow } from '../app/focusRules'
import { ROW_STOP_EVENT } from '../app/input'
import { CARDS_IN_VIEW } from './tones'
import type { CardVariant } from './Card'

export type RowProps<T> = {
  /** The row's group id; its cards' ids are `<id>/<itemKey>`. */
  id: string
  title?: string
  subtitle?: string
  /** "Row 1 of 7", at the right of the title. */
  counter?: string
  items: readonly T[]
  itemKey(item: T, index: number): string
  /** The cards' kind: their width and how many fit across. */
  variant: CardVariant
  /** Draws one card, with `focusId` as its id. */
  renderItem(item: T, focusId: string, index: number): ReactNode
  /** Dims the cards before the focused one, as Home's row screens do. */
  dimPast?: boolean
}

function indexOf(target: EventTarget) {
  const cell = (target as HTMLElement).closest?.('[data-index]')
  return cell ? Number(cell.getAttribute('data-index')) : -1
}

/**
 * A horizontal row of cards. ◀ ▶ go card by card; the card before the
 * focused one stays in view; a quick run of ◀ stops at the first card (the
 * row gives a little and the rail pulses) while a single ◀ from it opens the
 * rail. The track moves with a transform and draws only the cards near the
 * view. Coming back to the screen, the row starts at the card it remembers.
 */
export function Row<T>({ id, title, subtitle, counter, items, itemKey, variant, renderItem, dimPast }: RowProps<T>) {
  const remembered = useGroupMemory(id)
  const [focus, setFocus] = useState(() => Math.max(0, items.findIndex((item, i) => `${id}/${itemKey(item, i)}` === remembered)))
  const ref = useRef<HTMLElement>(null)
  const { start, from, to } = rowWindow(Math.min(focus, items.length - 1), items.length, CARDS_IN_VIEW[variant])

  useEffect(() => {
    const el = ref.current
    if (!el) return
    const give = () => {
      el.classList.remove('tv-row--giving')
      void el.offsetWidth
      el.classList.add('tv-row--giving')
    }
    const done = () => el.classList.remove('tv-row--giving')
    el.addEventListener(ROW_STOP_EVENT, give)
    el.addEventListener('animationend', done)
    return () => { el.removeEventListener(ROW_STOP_EVENT, give); el.removeEventListener('animationend', done) }
  }, [])

  const onFocus = (e: FocusEvent) => {
    const i = indexOf(e.target)
    if (i >= 0 && i !== focus) setFocus(i)
  }
  const cell = variant === 'poster' ? 'var(--poster-w)' : 'var(--wide-w)'
  const step = `(var(--cell-w) + var(--card-gap))`
  return <section ref={ref} className="tv-row" data-group={id} aria-label={title}>
    {(title || counter) && <div className="tv-row__head">
      {title && <h2 className="tv-h2">{title}{subtitle && <small>{subtitle}</small>}</h2>}
      {counter && <span className="tv-row__count">{counter}</span>}
    </div>}
    <div className="tv-row__viewport" data-clip>
      <div className="tv-row__track" data-row onFocus={onFocus} style={{ '--cell-w': cell, transform: `translateX(calc(${-start} * ${step}))` } as CSSProperties}>
        {from > 0 && <span className="tv-row__spacer" style={{ width: `calc(${from} * ${step} - var(--card-gap))` }}/>}
        {items.slice(from, to).map((item, k) => {
          const i = from + k
          const key = itemKey(item, i)
          return <div key={key} className={`tv-row__cell${dimPast && i < focus ? ' tv-row__cell--past' : ''}`} data-index={i}>{renderItem(item, `${id}/${key}`, i)}</div>
        })}
      </div>
    </div>
  </section>
}
