import { useEffect, useId, useRef, type ReactNode } from 'react'

/**
 * A modal over the page: closes on Escape, on a click outside, and with ✕.
 * side="right" makes it a drawer down the right edge, as Add a row is.
 */
export function Dialog({ title, subtitle, onClose, children, width = 'max-w-xl', side = 'center', footer }: { title: ReactNode; subtitle?: ReactNode; onClose: () => void; children: ReactNode; width?: string; side?: 'center' | 'right'; footer?: ReactNode }) {
  const id = useId()
  const panel = useRef<HTMLDivElement>(null)
  const close = useRef(onClose)
  useEffect(() => { close.current = onClose }, [onClose])
  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const root = panel.current
    if (!root) return
    const focusable = () => [...root.querySelectorAll<HTMLElement>('button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])')].filter((el) => el.getClientRects().length > 0)
    if (!root.contains(document.activeElement)) (focusable()[0] ?? root).focus()
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const onKey = (e: KeyboardEvent) => {
      if (document.querySelector('dialog[open]')) return // a native modal is above this panel
      if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close.current(); return }
      if (e.key !== 'Tab') return
      const items = focusable(), first = items[0], last = items.at(-1)
      if (!first) { e.preventDefault(); root.focus(); return }
      if (!root.contains(document.activeElement) || (e.shiftKey ? document.activeElement === first : document.activeElement === last)) {
        e.preventDefault(); (e.shiftKey ? last : first)?.focus()
      }
    }
    document.addEventListener('keydown', onKey, true)
    return () => {
      document.removeEventListener('keydown', onKey, true)
      document.body.style.overflow = overflow
      if (previous?.isConnected) previous.focus()
    }
  }, [])

  const drawer = side === 'right'
  return (
    <div className={drawer ? 'fixed inset-0 z-50 flex justify-end bg-black/70' : 'fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/80 p-4 sm:p-10'} onClick={onClose}>
      <div
        ref={panel}
        tabIndex={-1}
        className={drawer ? `flex h-full w-full ${width} flex-col border-l border-line-2 bg-surface shadow-2xl` : `w-full ${width} rounded-xl border border-line-2 bg-surface shadow-xl`}
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby={id}
      >
        <div className={`flex items-start justify-between gap-4 px-6 ${drawer ? 'pb-3 pt-5' : 'pt-5'}`}>
          <div className="min-w-0">
            <h2 id={id} className="text-xl font-bold">
              {title}
            </h2>
            {subtitle && <p className="mt-1 text-sm text-neutral-400">{subtitle}</p>}
          </div>
          <button type="button" onClick={onClose} aria-label="Close" className="min-h-[44px] min-w-[44px] rounded-md text-neutral-400 hover:bg-surface-2 hover:text-neutral-100">
            ✕
          </button>
        </div>
        {drawer ? <div className="min-h-0 flex-1 overflow-y-auto">{children}</div> : children}
        {footer}
      </div>
    </div>
  )
}
