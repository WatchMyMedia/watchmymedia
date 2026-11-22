import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { useWatchlistPicker, type WatchlistTarget } from '../../api/watchlists'
import type { Watchlist } from '../../lib/watchlists'
import { canUseName, failureWords, limitWords, MAX_NAME, nameProblem, removeFailedWords, saveFailedWords, titlesWords } from '../../lib/watchlists'
import { nameInput, pickerButton } from './styles'


/** The bookmark the Watchlist button, the picker and its messages carry: filled, in gold, when saved. */
export function BookmarkIcon({ filled = false, size = 16, className = '' }: { filled?: boolean; size?: number; className?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill={filled ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className={`${filled ? 'text-accent' : ''} ${className}`}>
      <path d="M6 3h12a1 1 0 0 1 1 1v17l-7-4.5L5 21V4a1 1 0 0 1 1-1z" />
    </svg>
  )
}

/**
 * A trigger with Save to watchlist under it. The picker opens to the side
 * that has room, so a card near the right edge keeps it on screen; at phone
 * width it is a sheet from the foot of the window instead.
 */
export function WithPicker({ open, onClose, target, title, children, className = '' }: { open: boolean; onClose: () => void; target: WatchlistTarget; title: string; children: ReactNode; className?: string }) {
  const box = useRef<HTMLSpanElement>(null)
  const [right, setRight] = useState(false)
  useEffect(() => {
    if (!open || !box.current) return
    const r = box.current.getBoundingClientRect()
    setRight(r.left > window.innerWidth / 2)
  }, [open])
  return (
    <span ref={box} className={`relative ${className}`}>
      {children}
      {open && <WatchlistPicker target={target} title={title} align={right ? 'right' : 'left'} onClose={onClose} anchor={box} />}
    </span>
  )
}

/**
 * Save to watchlist: each of the person's watchlists with a check, toggled
 * on its own and at once, New watchlist… at the foot, and Done. Escape, a
 * press outside and Done close it, and the trigger gets the focus back.
 */
function WatchlistPicker({ target, title, align, onClose, anchor }: { target: WatchlistTarget; title: string; align: 'left' | 'right'; onClose: () => void; anchor: React.RefObject<HTMLSpanElement | null> }) {
  const id = useId()
  const panel = useRef<HTMLDivElement>(null)
  const picker = useWatchlistPicker(target)
  const [naming, setNaming] = useState(false)
  const [name, setName] = useState('')
  const [problem, setProblem] = useState<string | undefined>()
  const close = useRef(onClose)
  useEffect(() => {
    close.current = onClose
  }, [onClose])

  useEffect(() => {
    const trigger = anchor.current?.querySelector<HTMLElement>('button')
    // The scrim at phone width is outside the panel, so pressing it closes too.
    const onDown = (e: PointerEvent) => {
      const at = e.target as Node
      if (!panel.current?.contains(at) && !trigger?.contains(at)) close.current()
    }
    // On the window, ahead of a Dialog's own Escape on the document, so
    // inside the Request dialog Escape closes only the picker.
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.stopPropagation()
      close.current()
    }
    document.addEventListener('pointerdown', onDown)
    window.addEventListener('keydown', onKey, true)
    return () => {
      document.removeEventListener('pointerdown', onDown)
      window.removeEventListener('keydown', onKey, true)
      if (trigger?.isConnected) trigger.focus()
    }
  }, [anchor])

  const items = picker.items ?? []
  const loaded = !!picker.items
  useEffect(() => {
    if (loaded) panel.current?.querySelector<HTMLElement>('input, button')?.focus()
  }, [loaded])

  const atLimit = items.length >= picker.max
  const taken = nameProblem(name, items)
  const toggle = (w: Watchlist) => {
    setProblem(undefined)
    picker.toggle(w).catch((e: unknown) => setProblem(failureWords(e, w.contains ? removeFailedWords(w.name) : saveFailedWords(w.name))))
  }
  const create = () => {
    if (!canUseName(name, items)) return
    setProblem(undefined)
    picker.create.mutate(name, {
      onSuccess: () => {
        setNaming(false)
        setName('')
      },
    })
  }

  return (
    <>
      <div aria-hidden="true" className="fixed inset-0 z-40 bg-black/60 sm:hidden" />
      <div
        ref={panel}
        role="dialog"
        aria-labelledby={`${id}-title`}
        aria-describedby={`${id}-what`}
        className={`z-50 box-border border-line-2 bg-surface shadow-[0_18px_40px_rgba(0,0,0,.6)] max-sm:fixed max-sm:inset-x-0 max-sm:bottom-0 max-sm:rounded-t-2xl max-sm:border-t max-sm:px-3 max-sm:pb-5 max-sm:pt-2.5 sm:absolute sm:top-[calc(100%+6px)] sm:w-[22rem] sm:max-w-[calc(100vw-32px)] sm:rounded-[10px] sm:border sm:px-3 sm:pb-3 sm:pt-4 ${align === 'right' ? 'sm:right-0' : 'sm:left-0'}`}
      >
        <span aria-hidden="true" className="mx-auto mb-3.5 block h-1 w-9 rounded-sm bg-neutral-600 sm:hidden" />
        <h2 id={`${id}-title`} className="px-2 text-base font-semibold leading-snug">
          Save to watchlist
        </h2>
        <p id={`${id}-what`} className="mt-0.5 truncate px-2 text-[13px] text-neutral-400">
          {title}
        </p>
        {picker.query.isPending && <p className="px-2 py-3 text-sm text-neutral-400">Loading…</p>}
        {picker.query.isError && (
          <p role="alert" className="px-2 py-3 text-sm text-warn">
            {picker.query.error.message}
          </p>
        )}
        {loaded && (
          <ul className={`mt-2.5 ${items.length > 5 ? 'max-h-[242px] overflow-y-auto' : ''}`}>
            {items.map((w) => (
              <li key={w.id}>
                <label className="flex min-h-11 cursor-pointer items-center gap-3 rounded-md px-2 text-sm hover:bg-surface-2 max-sm:min-h-12">
                  <input type="checkbox" checked={!!w.contains} onChange={() => toggle(w)} className="h-[18px] w-[18px] shrink-0 accent-accent [color-scheme:dark]" />
                  <span className="min-w-0 flex-1 truncate font-medium">{w.name}</span>
                  <span className="whitespace-nowrap text-[13px] text-neutral-400">{titlesWords(w.titles)}</span>
                </label>
              </li>
            ))}
          </ul>
        )}
        {problem && (
          <p role="alert" className="mt-2 px-2 text-[13px] leading-snug text-red-300">
            {problem}
          </p>
        )}
        {loaded && !naming && !atLimit && (
          <button type="button" onClick={() => setNaming(true)} className="flex min-h-11 w-full items-center rounded-md pl-[38px] pr-2 text-left text-sm font-medium hover:bg-surface-2 max-sm:min-h-12">
            New watchlist…
          </button>
        )}
        {loaded && atLimit && <p className="mt-1 border-t border-line-2 px-2 pt-3 text-[13px] leading-normal text-neutral-400">{limitWords(picker.max)}</p>}
        {naming && (
          <form
            className="mt-1 border-t border-line-2 px-2 pt-3"
            onSubmit={(e) => {
              e.preventDefault()
              create()
            }}
          >
            <label htmlFor={`${id}-name`} className="block text-[13px] text-neutral-400">
              Name
            </label>
            <input
              id={`${id}-name`}
              autoFocus
              value={name}
              maxLength={MAX_NAME}
              autoComplete="off"
              aria-invalid={!!taken}
              aria-describedby={taken || picker.create.isError ? `${id}-err` : undefined}
              onChange={(e) => {
                setName(e.target.value)
                picker.create.reset()
              }}
              className={nameInput}
            />
            {(taken || picker.create.isError) && (
              <p id={`${id}-err`} role="alert" className="mt-2 text-[13px] leading-snug text-red-300">
                {taken ?? failureWords(picker.create.error, 'Couldn’t make the watchlist.')}
              </p>
            )}
            <div className="mt-3 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => {
                  setNaming(false)
                  setName('')
                  picker.create.reset()
                }}
                className={`${pickerButton} border border-line-2 bg-surface font-medium hover:bg-surface-2`}
              >
                Cancel
              </button>
              <button type="submit" disabled={!canUseName(name, items) || picker.create.isPending} className={`${pickerButton} btn-primary font-semibold disabled:opacity-50`}>
                Create
              </button>
            </div>
          </form>
        )}
        {!naming && (
          <div className="mt-3 flex justify-end px-2">
            <button type="button" onClick={onClose} className={`${pickerButton} border border-line-2 bg-surface font-medium hover:bg-surface-2 max-sm:w-full`}>
              Done
            </button>
          </div>
        )}
      </div>
    </>
  )
}
