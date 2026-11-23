import { useEffect, useRef, useState, type ReactNode } from 'react'
import { listFilterChips, meterParts, progressWords, type ListFilter, type ListTally } from '../lib/listProgress'
import { MoreIcon } from './icons'

/**
 * How much of a list the person has and has watched: a line, a bar in
 * three parts (watched, to watch, not in the library) and its legend.
 */
export function ListProgress({ tally }: { tally: ListTally }) {
  const w = progressWords(tally)
  const parts = meterParts(tally)
  if (!tally.total) return null
  return (
    <div className="panel flex flex-wrap items-center gap-x-8 gap-y-3 px-[18px] py-4">
      <div className="flex min-w-0 flex-[1_1_320px] flex-col gap-2.5">
        <p className="text-[15px] font-semibold">
          {w.headline} <span className="font-normal text-neutral-400">· {w.watched}</span>
        </p>
        <span role="img" aria-label={w.label} className="flex h-2 overflow-hidden rounded bg-line-2">
          <i className="block h-full bg-accent" style={{ width: `${parts.watched}%` }} />
          <i className="block h-full bg-dim" style={{ width: `${parts.toWatch}%` }} />
        </span>
      </div>
      <ul className="flex flex-wrap gap-x-5 gap-y-1.5 text-[13px] text-neutral-300">
        {w.legend.map((l) => (
          <li key={l.key} className="flex items-center">
            <span aria-hidden="true" className={`mr-[7px] inline-block size-2.5 rounded-[3px] ${l.key === 'watched' ? 'bg-accent' : l.key === 'toWatch' ? 'bg-dim' : 'bg-line-2 ring-1 ring-inset ring-line-3'}`} />
            {l.text}
          </li>
        ))}
      </ul>
    </div>
  )
}

/** The chips over a list's titles, each with its count. */
export function ListFilters({ tally, value, onChange, children }: { tally: ListTally; value: ListFilter; onChange: (f: ListFilter) => void; children?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2.5">
      <div role="group" aria-label="Show" className="flex flex-wrap gap-2">
        {listFilterChips(tally).map((c) => (
          <button key={c.value} type="button" aria-pressed={value === c.value} onClick={() => onChange(c.value)} className="chip">
            {c.label}
            <span className="n">{c.count.toLocaleString()}</span>
          </button>
        ))}
      </div>
      {children}
    </div>
  )
}

/** ⋯ beside a list's buttons: a menu of what is done less often, such as Delete. */
export function ListMoreMenu({ label, children }: { label: string; children: (close: () => void) => ReactNode }) {
  const [open, setOpen] = useState(false)
  const root = useRef<HTMLSpanElement>(null)
  useEffect(() => {
    if (!open) return
    root.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus()
    const away = (e: PointerEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false)
    }
    const key = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false)
        root.current?.querySelector<HTMLButtonElement>('button')?.focus()
      }
    }
    document.addEventListener('pointerdown', away)
    document.addEventListener('keydown', key)
    return () => {
      document.removeEventListener('pointerdown', away)
      document.removeEventListener('keydown', key)
    }
  }, [open])
  return (
    <span
      ref={root}
      className="relative inline-flex"
      onBlur={(e) => {
        if (open && e.relatedTarget && !root.current?.contains(e.relatedTarget)) setOpen(false)
      }}
    >
      <button type="button" aria-label={label} aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((o) => !o)} className="ib">
        <MoreIcon />
      </button>
      {open && (
        <div role="menu" aria-label={label} className="menu absolute right-0 top-[calc(100%+6px)]">
          {children(() => setOpen(false))}
        </div>
      )}
    </span>
  )
}
