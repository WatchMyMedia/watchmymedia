import { Fragment, useEffect, useId, useRef, useState } from 'react'

export type MoreItem = {
  label: string
  icon?: 'replace' | 'delete' | 'flag' | 'check' | 'checks' | 'plus' | 'missing' | 'skip' | 'poster'
  /** Shown in red: deletes something. */
  danger?: boolean
  /** A line above it, setting it apart from the items before. */
  divider?: boolean
  /**
   * The name of the group it belongs to, shown above the group's first item
   * (the episode page's show items, under the show's title). Items next to
   * each other with the same group form it; a line sets it apart.
   */
  group?: string
  onSelect: () => void
}

/** Items next to each other in the same group, or a run of items outside any. */
type Block = { group?: string; items: MoreItem[] }

function blocks(items: MoreItem[]): Block[] {
  const out: Block[] = []
  for (const it of items) {
    const last = out[out.length - 1]
    if (last && last.group === it.group && !it.divider) last.items.push(it)
    else out.push({ group: it.group, items: [it] })
  }
  return out
}

const icons = {
  check: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="m8 12 3 3 5-6" />
    </>
  ),
  checks: (
    <>
      <path d="m3 12 4 4 8-9" />
      <path d="m11 15 1 1 8-9" />
    </>
  ),
  plus: <path d="M12 5v14M5 12h14" />,
  missing: <rect x="4" y="5" width="16" height="14" rx="2" strokeDasharray="3 3" />,
  flag: <path d="M5 21V4h11l-1.5 4L16 12H5" />,
  replace: <path d="M4 12a8 8 0 0 1 14-5.3L20 9M20 4v5h-5M20 12a8 8 0 0 1-14 5.3L4 15M4 20v-5h5" />,
  delete: <path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" />,
  skip: <path d="M5 4v16M19 4v16M9 12h6M12 9l3 3-3 3" />,
  poster: (
    <>
      <rect x="5" y="3" width="14" height="18" rx="2" />
      <path d="m8 16 3-3 2 2 3-4" />
    </>
  ),
}

/**
 * The ⋯ button and its menu, as on the movie page (design/mockups/web/ReportProblem). Renders nothing without items.
 * tall matches the episode page's 44px buttons.
 */
export function MoreMenu({
  items,
  label = 'More',
  compact = false,
  tall = false,
  align = 'left',
}: {
  items: MoreItem[]
  label?: string
  compact?: boolean
  tall?: boolean
  align?: 'left' | 'right'
}) {
  const [open, setOpen] = useState(false)
  const box = useRef<HTMLDivElement>(null)
  const menuId = useId()

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (!box.current?.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    box.current?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus()
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  if (items.length === 0) return null
  const onMenuKey = (e: React.KeyboardEvent) => {
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return
    e.preventDefault()
    const all = [...(box.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]') ?? [])]
    const i = all.indexOf(document.activeElement as HTMLButtonElement)
    all[(i + (e.key === 'ArrowDown' ? 1 : all.length - 1)) % all.length]?.focus()
  }
  const button = compact
    ? `grid h-9 w-9 place-items-center rounded-md text-neutral-400 hover:bg-surface-2 hover:text-neutral-100 ${open ? 'bg-surface-2 text-neutral-100' : ''}`
    : `grid ${tall ? 'h-[44px]' : 'h-[40px]'} w-[44px] place-items-center rounded-md border ${open ? 'border-accent bg-surface-2' : 'border-line-2 bg-surface hover:bg-surface-2'}`
  return (
    <div ref={box} className="relative">
      <button type="button" aria-label={label} aria-haspopup="menu" aria-expanded={open} aria-controls={open ? menuId : undefined} onClick={() => setOpen(!open)} className={button}>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
          <circle cx="5" cy="12" r="2" />
          <circle cx="12" cy="12" r="2" />
          <circle cx="19" cy="12" r="2" />
        </svg>
      </button>
      {open && (
        <div
          id={menuId}
          role="menu"
          aria-label={label}
          onKeyDown={onMenuKey}
          className={`absolute top-[calc(100%+6px)] z-30 ${items.some((it) => it.group !== undefined) ? 'w-64' : 'w-60'} rounded-[10px] border border-line-2 bg-surface p-1.5 shadow-[0_18px_40px_rgba(0,0,0,.6)] ${align === 'right' ? 'right-0' : 'left-0'}`}
        >
          {blocks(items).map((b, i, all) => {
            const buttons = b.items.map((it) => (
              <button
                key={it.label}
                type="button"
                role="menuitem"
                onClick={() => {
                  setOpen(false)
                  it.onSelect()
                }}
                className={`flex min-h-10 w-full items-center gap-2.5 rounded-md px-2.5 text-left text-sm hover:bg-surface-2 focus:bg-surface-2 ${it.danger ? 'text-red-300' : 'text-neutral-200 hover:text-neutral-100'}`}
              >
                {it.icon && (
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    {icons[it.icon]}
                  </svg>
                )}
                {it.label}
              </button>
            ))
            const line = i > 0 && (b.items[0].divider || b.group !== undefined || all[i - 1].group !== undefined)
            return (
              <Fragment key={`${b.group ?? ''}${b.items[0].label}`}>
                {line && <div role="separator" className="mx-1 my-1.5 h-px bg-line-2" />}
                {b.group !== undefined ? (
                  <div role="group" aria-labelledby={`${menuId}-${i}`}>
                    <p id={`${menuId}-${i}`} className="truncate px-2.5 pb-1 pt-1.5 text-xs font-semibold text-neutral-400">
                      {b.group}
                    </p>
                    {buttons}
                  </div>
                ) : (
                  buttons
                )}
              </Fragment>
            )
          })}
        </div>
      )}
    </div>
  )
}
