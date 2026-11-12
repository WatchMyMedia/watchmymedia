import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { MAX_ROWS, moveRow, moveRowTo, movedWords, setHidden, type LayoutView, type RowLayoutEntry } from '../../lib/rowLayout'
import { useRowDrag } from './useRowDrag'

const square =
  'grid h-11 w-11 shrink-0 place-items-center rounded-md border border-line-2 text-neutral-200 hover:bg-surface-2 hover:text-accent disabled:border-line disabled:bg-transparent disabled:text-neutral-600'

/**
 * A screen's rows folded to bars (design/mockups/web/HomeEditRows and
 * HomeEditRowsPhone): drag one by its handle, or move it with the arrows,
 * the handle's arrow keys or Move up and Move down, and Hide or Show it.
 * Hidden rows stay in the list, greyed; a row that won't draw says why.
 */
export function RowLayoutEditor({ rows, view, lineOf, screenName, label, onChange, disabled = false, compact = false }: {
  rows: readonly RowLayoutEntry[]
  view?: LayoutView
  lineOf: (row: RowLayoutEntry) => string
  screenName: string
  label?: string
  onChange: (rows: RowLayoutEntry[]) => void
  disabled?: boolean
  /** Settings › Lists' Default rows (design/mockups/web/SettingsLists): arrows and Hide only, smaller bars. */
  compact?: boolean
}) {
  const [said, setSaid] = useState('')
  const help = useId()
  const list = useRef<HTMLOListElement>(null)
  // After a move, focus stays on what moved the row, or the other arrow at either end.
  const refocus = useRef<{ id: string; button: 'up' | 'down' | 'toggle' | 'grip' } | null>(null)
  const drag = useRowDrag(rows.length, (from, to) => {
    const next = moveRowTo(rows, from, to)
    onChange(next)
    setSaid(movedWords(rows[from].title, to, next.length))
  })

  useEffect(() => {
    const r = refocus.current
    if (!r || !list.current) return
    refocus.current = null
    const li = [...list.current.querySelectorAll<HTMLElement>('[data-row]')].find((el) => el.dataset.row === r.id)
    const want = li?.querySelector<HTMLButtonElement>(`[data-button="${r.button}"]`)
    const other = li?.querySelector<HTMLButtonElement>(`[data-button="${r.button === 'up' ? 'down' : 'up'}"]`)
    ;(want && !want.disabled ? want : other)?.focus()
  }, [rows])

  const move = (index: number, by: -1 | 1, button: 'up' | 'down' | 'grip') => {
    if (index + by < 0 || index + by >= rows.length) return
    refocus.current = { id: rows[index].id, button }
    onChange(moveRow(rows, index, by))
    setSaid(movedWords(rows[index].title, index + by, rows.length))
  }
  const toggle = (row: RowLayoutEntry) => {
    refocus.current = { id: row.id, button: 'toggle' }
    onChange(setHidden(rows, row.id, !row.hidden))
    setSaid(`${row.title} ${row.hidden ? 'shown' : 'hidden'}`)
  }
  const gripKeys = (index: number) => (e: KeyboardEvent<HTMLButtonElement>) => {
    if (e.key !== 'ArrowUp' && e.key !== 'ArrowDown') return
    e.preventDefault()
    move(index, e.key === 'ArrowUp' ? -1 : 1, 'grip')
  }

  return (
    <>
      <ol ref={list} aria-label={label ?? `${screenName}’s rows, in order`} className={`flex flex-col ${compact ? 'gap-1.5' : 'gap-2'}`}>
        {rows.map((row, index) => {
          const v = view?.rows[index]
          if (compact)
            return (
              <li key={row.id} data-row={row.id} className={`flex min-h-[56px] items-center gap-2.5 rounded-lg border border-line-2 bg-ground py-1.5 pl-3.5 pr-2 ${row.hidden ? 'border-dashed' : ''}`}>
                <span className={`min-w-0 flex-1 text-sm font-medium ${row.hidden ? 'text-neutral-400' : ''}`}>
                  {row.title}
                  {row.hidden && <span className="ml-1.5 rounded border border-neutral-700 px-1 text-[10px] font-semibold text-neutral-400">Hidden</span>}
                  <span className="mt-px block text-xs font-normal text-neutral-400">{lineOf(row)}</span>
                </span>
                <button type="button" data-button="up" disabled={disabled || index === 0} aria-label={`Move ${row.title} up`} onClick={() => move(index, -1, 'up')} className={square}>
                  <ArrowIcon up />
                </button>
                <button type="button" data-button="down" disabled={disabled || index === rows.length - 1} aria-label={`Move ${row.title} down`} onClick={() => move(index, 1, 'down')} className={square}>
                  <ArrowIcon />
                </button>
                <button
                  type="button"
                  data-button="toggle"
                  disabled={disabled}
                  aria-label={`${row.hidden ? 'Show' : 'Hide'} ${row.title}`}
                  onClick={() => toggle(row)}
                  className={`inline-flex min-h-11 min-w-[72px] shrink-0 items-center justify-center rounded-md border px-3 text-sm font-medium hover:bg-surface-2 ${row.hidden ? 'border-neutral-600 text-neutral-100' : 'border-line-2 text-neutral-200'}`}
                >
                  {row.hidden ? 'Show' : 'Hide'}
                </button>
              </li>
            )
          const lifted = drag.dragging === index
          return (
            <li key={row.id} ref={drag.item(index)} data-row={row.id} style={drag.style(index)}>
              {v?.divider && (
                <p className="mb-2.5 mt-2 flex items-center gap-3 text-[13px] font-medium text-orange-300 before:h-px before:flex-1 before:bg-warn/45 after:h-px after:flex-1 after:bg-warn/45">
                  {screenName} draws {MAX_ROWS} rows. Rows below this line aren’t shown.
                </p>
              )}
              <div
                className={`relative flex min-h-[68px] items-center gap-1.5 rounded-[10px] border py-1.5 pl-0.5 pr-1.5 sm:min-h-[72px] sm:gap-3 sm:py-2 sm:pl-1 sm:pr-2.5 ${
                  lifted
                    ? '-rotate-[.4deg] border-accent bg-surface-2 shadow-2xl ring-1 ring-accent'
                    : row.hidden
                      ? 'border-dashed border-line-2 bg-ground'
                      : 'border-line-2 bg-surface'
                }`}
              >
                <button
                  type="button"
                  data-button="grip"
                  disabled={disabled}
                  {...(disabled ? {} : drag.handle(index))}
                  onKeyDown={gripKeys(index)}
                  aria-label={`Drag ${row.title} to move it`}
                  aria-describedby={help}
                  className={`grid h-[52px] w-8 shrink-0 touch-none place-items-center rounded-md sm:w-9 ${
                    lifted ? 'cursor-grabbing text-accent' : 'cursor-grab text-neutral-500 hover:bg-surface-2 hover:text-neutral-100'
                  }`}
                >
                  <GripIcon />
                </button>
                <Thumbs style={row.style} hidden={row.hidden} />
                <span className="flex min-w-0 flex-1 flex-col gap-0.5 sm:gap-[3px]">
                  <span className={`flex flex-wrap items-center gap-x-2 gap-y-1 text-sm font-semibold sm:text-[15px] ${row.hidden ? 'text-neutral-400' : ''}`}>
                    {row.title}
                    {row.hidden && <span className="rounded border border-neutral-700 px-1.5 text-[10px] font-semibold tracking-wide text-neutral-400 sm:text-[11px]">Hidden</span>}
                  </span>
                  <span className="truncate text-xs text-neutral-400 sm:text-[13px]">{lineOf(row)}</span>
                  {v?.note && (
                    <span className="flex items-center gap-1.5 text-xs text-orange-300 sm:text-[13px]">
                      <InfoIcon />
                      {v.note}
                    </span>
                  )}
                </span>
                <span className="flex shrink-0 items-center gap-1.5">
                  <button type="button" data-button="up" disabled={disabled || index === 0} aria-label={`Move ${row.title} up`} onClick={() => move(index, -1, 'up')} className={square}>
                    <ArrowIcon up />
                  </button>
                  <button type="button" data-button="down" disabled={disabled || index === rows.length - 1} aria-label={`Move ${row.title} down`} onClick={() => move(index, 1, 'down')} className={square}>
                    <ArrowIcon />
                  </button>
                  <button
                    type="button"
                    data-button="toggle"
                    disabled={disabled}
                    aria-label={`${row.hidden ? 'Show' : 'Hide'} ${row.title}`}
                    onClick={() => toggle(row)}
                    className={`inline-flex h-11 min-w-11 items-center justify-center gap-1.5 rounded-md border text-sm font-medium hover:bg-surface-2 sm:min-w-[92px] sm:px-3 ${
                      row.hidden ? 'border-neutral-600 text-neutral-100' : 'border-line-2 text-neutral-200'
                    }`}
                  >
                    <EyeIcon off={!row.hidden} />
                    <span className="hidden sm:inline">{row.hidden ? 'Show' : 'Hide'}</span>
                  </button>
                </span>
                {lifted && drag.target !== undefined && (
                  <span aria-hidden="true" className="absolute -top-3.5 right-2 whitespace-nowrap rounded-full bg-accent px-2.5 py-1 text-xs font-semibold text-black sm:-right-3">
                    Position {drag.target + 1} of {rows.length}
                  </span>
                )}
              </div>
            </li>
          )
        })}
      </ol>
      <p id={help} className="sr-only">
        Drag with a mouse or finger, or use Move up and Move down.
      </p>
      <p aria-live="polite" className="sr-only">
        {said}
      </p>
    </>
  )
}

/** Small shapes of the row's cards, beside its name on wide screens. */
function Thumbs({ style, hidden }: { style: string; hidden: boolean }) {
  const posters = style === 'poster'
  return (
    <span aria-hidden="true" className={`hidden w-[68px] shrink-0 items-end gap-[3px] sm:flex ${hidden ? 'opacity-35' : ''}`}>
      {(posters ? [0, 1, 2] : [0, 1]).map((i) => (
        <span key={i} className={`block rounded-sm bg-neutral-700 ring-1 ring-white/5 ${posters ? 'h-6 w-4' : 'h-[17px] w-[30px]'}`} />
      ))}
    </span>
  )
}

function GripIcon() {
  return (
    <svg width="16" height="20" viewBox="0 0 16 20" fill="currentColor" aria-hidden="true">
      {[4, 10, 16].map((y) => (
        <g key={y}>
          <circle cx="5" cy={y} r="1.6" />
          <circle cx="11" cy={y} r="1.6" />
        </g>
      ))}
    </svg>
  )
}

function ArrowIcon({ up = false }: { up?: boolean }) {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={up ? 'M12 19V5M6 11l6-6 6 6' : 'M12 5v14M6 13l6 6 6-6'} />
    </svg>
  )
}

function EyeIcon({ off }: { off: boolean }) {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {off ? (
        <>
          <path d="M3 3l18 18" />
          <path d="M10.6 6.1A9.8 9.8 0 0 1 12 6c6 0 9.5 6 9.5 6a17 17 0 0 1-3 3.6M6.5 7.6C4 9.3 2.5 12 2.5 12s3.5 6 9.5 6a9.5 9.5 0 0 0 4.2-1" />
          <path d="M9.9 10a3 3 0 0 0 4.1 4.1" />
        </>
      ) : (
        <>
          <path d="M2.5 12S6 6 12 6s9.5 6 9.5 6-3.5 6-9.5 6-9.5-6-9.5-6z" />
          <circle cx="12" cy="12" r="3" />
        </>
      )}
    </svg>
  )
}

function InfoIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="shrink-0">
      <circle cx="12" cy="12" r="9" />
      <path d="M12 8h.01M11 12h1v5h1" />
    </svg>
  )
}
