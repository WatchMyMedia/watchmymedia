import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { AUTO, autoNow, type Quality } from '../lib/quality'

/**
 * The player's quality menu: a button that opens the list above it, with
 * Automatic's current step under its row (design/mockups/web/PlayerPicker).
 * The list is a menu of radio items: arrows move, Escape and Tab close.
 * The player's own keys leave it alone (it carries data-player-keys="off").
 */
export function QualityMenu({
  value,
  options,
  autoStep,
  onPick,
  onOpenChange,
  heading = 'Quality',
  note,
  sub,
  tall,
}: {
  value: Quality
  options: Quality[]
  /** The step Automatic is at; unset until a decision says. */
  autoStep?: string
  onPick: (q: Quality) => void
  onOpenChange?: (open: boolean) => void
  /** The list's heading: "Quality on the TV" on the live remote. */
  heading?: string
  /** The words under the list, in place of the player's. */
  note?: string
  /** A line under an option's label, such as "Live TV default". */
  sub?: (q: Quality) => string | undefined
  /** The 44 px button of the live remote's controls. */
  tall?: boolean
}) {
  const [open, setOpenState] = useState(false)
  const root = useRef<HTMLDivElement>(null)
  const button = useRef<HTMLButtonElement>(null)
  const items = useRef<(HTMLButtonElement | null)[]>([])
  const id = useId()
  const setOpen = (o: boolean) => {
    setOpenState(o)
    onOpenChange?.(o)
  }

  // Opening puts focus on the current pick; a click elsewhere closes.
  useEffect(() => {
    if (!open) return
    items.current[Math.max(0, options.findIndex((q) => q.id === value.id))]?.focus()
    const onDown = (e: PointerEvent) => {
      if (!root.current?.contains(e.target as Node)) {
        setOpenState(false)
        onOpenChange?.(false)
      }
    }
    document.addEventListener('pointerdown', onDown)
    return () => document.removeEventListener('pointerdown', onDown)
    // Only on opening: the list does not move focus while it is open.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  const onKey = (e: KeyboardEvent) => {
    const list = items.current.filter((b): b is HTMLButtonElement => !!b)
    const at = list.indexOf(document.activeElement as HTMLButtonElement)
    const move = (i: number) => list[(i + list.length) % list.length]?.focus()
    if (e.key === 'Escape') {
      e.preventDefault()
      setOpen(false)
      button.current?.focus()
    } else if (e.key === 'ArrowDown') {
      e.preventDefault()
      move(at + 1)
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      move(at - 1)
    } else if (e.key === 'Home') {
      e.preventDefault()
      move(0)
    } else if (e.key === 'End') {
      e.preventDefault()
      move(list.length - 1)
    } else if (e.key === 'Tab') {
      setOpen(false)
    }
  }

  return (
    <div ref={root} className="relative" data-player-keys="off" onClick={(e) => e.stopPropagation()}>
      <button
        ref={button}
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? id : undefined}
        onClick={() => setOpen(!open)}
        onKeyDown={(e) => {
          if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
            e.preventDefault()
            setOpen(true)
          }
        }}
        className={`inline-flex ${tall ? 'min-h-11 px-3' : 'min-h-[36px] px-2.5'} items-center gap-1.5 whitespace-nowrap rounded-md border bg-black/60 text-sm ${open ? 'border-accent' : 'border-white/20'}`}
      >
        <span className="sr-only">Quality: </span>
        {value.label}
        <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="m6 9 6 6 6-6" />
        </svg>
      </button>
      {open && (
        <div
          id={id}
          role="menu"
          aria-label="Quality"
          onKeyDown={onKey}
          className="absolute bottom-[calc(100%+8px)] right-0 z-20 w-[17rem] max-w-[calc(100vw-2rem)] rounded-[10px] border border-line-2 bg-surface/95 p-1.5 shadow-2xl"
        >
          <p className="mx-2.5 mb-1.5 mt-1 text-[11px] font-semibold uppercase tracking-wider text-neutral-400">{heading}</p>
          {options.map((q, i) => {
            const on = q.id === value.id
            return (
              <button
                key={q.id}
                ref={(el) => {
                  items.current[i] = el
                }}
                type="button"
                role="menuitemradio"
                aria-checked={on}
                tabIndex={-1}
                onClick={() => {
                  setOpen(false)
                  button.current?.focus()
                  if (!on) onPick(q)
                }}
                className={`flex w-full items-start gap-2.5 rounded-md px-2.5 py-2 text-left text-sm hover:bg-surface-2 focus:bg-surface-2 focus:outline-none ${on ? 'font-semibold text-white' : 'text-neutral-200'}`}
              >
                <span className="grid h-5 w-4 shrink-0 place-items-center text-accent">
                  {on && (
                    <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                      <path d="m5 12 5 5 9-10" />
                    </svg>
                  )}
                </span>
                <span className="flex min-w-0 flex-col gap-0.5">
                  {q.label}
                  {q.id === AUTO && on && autoStep && <span className="text-xs font-normal text-neutral-400">{autoNow(autoStep)}</span>}
                  {sub?.(q) && <span className="text-xs font-normal text-neutral-400">{sub(q)}</span>}
                </span>
              </button>
            )
          })}
          <p className="mx-2.5 mb-1 mt-1.5 border-t border-line pt-2 text-xs leading-snug text-neutral-400">
            {note ?? <>{options[0]?.id === AUTO && <>Automatic adjusts quality as your connection changes. </>}A pick here also becomes this browser&rsquo;s default.</>}
          </p>
        </div>
      )}
    </div>
  )
}
