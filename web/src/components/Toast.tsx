import { useEffect, useSyncExternalStore } from 'react'
import { currentToast, dismissToast, flushToast, subscribeToast, undoToast } from '../lib/toast'
import { BookmarkIcon } from './watchlists/WatchlistPicker'

/**
 * The one short message at the foot of the window, with Undo when it has
 * one (lib/toast.ts keeps it and its time). A polite live region that is
 * always there, so a screen reader hears each message. Leaving the page
 * ends the message, so a removal it held back is sent.
 */
export function ToastHost() {
  const t = useSyncExternalStore(subscribeToast, currentToast, currentToast)
  useEffect(() => {
    window.addEventListener('pagehide', flushToast)
    return () => window.removeEventListener('pagehide', flushToast)
  }, [])
  return (
    <div role="status" className="pointer-events-none fixed inset-x-4 bottom-4 z-50 flex justify-center sm:bottom-6">
      {t && (
        <div className="pointer-events-auto flex min-h-12 w-full items-center justify-between gap-2.5 rounded-[10px] border border-neutral-700 bg-surface-2 py-1.5 pl-4 pr-1.5 text-sm font-medium shadow-[0_18px_40px_rgba(0,0,0,.6)] sm:w-auto sm:justify-start">
          <span className="inline-flex min-w-0 items-center gap-2.5">
            {t.icon && <BookmarkIcon filled={t.icon === 'saved'} className={t.icon === 'removed' ? 'shrink-0 text-neutral-300' : 'shrink-0'} />}
            <span className="min-w-0">{t.text}</span>
          </span>
          {t.undo ? (
            <button type="button" onClick={() => undoToast(t.id)} className="min-h-9 shrink-0 rounded-md px-3 font-semibold text-accent hover:bg-line-2">
              Undo
            </button>
          ) : (
            <button type="button" onClick={() => dismissToast(t.id)} aria-label="Close" className="grid h-9 w-9 shrink-0 place-items-center rounded-md text-neutral-400 hover:bg-line-2 hover:text-neutral-100">
              ✕
            </button>
          )}
        </div>
      )}
    </div>
  )
}
