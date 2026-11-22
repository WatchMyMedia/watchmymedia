import { useState } from 'react'
import { useWatchlistPress, type WatchlistTarget } from '../../api/watchlists'
import { showToast } from '../../lib/toast'
import { failureWords, onlyName, removedMessage, removeFailedWords, saveFailedWords, savedMessage } from '../../lib/watchlists'

/** A refused save or removal, in the message at the foot of the window. */
export function showWatchlistFailure(error: unknown, change: { name: string; remove: boolean }) {
  showToast({ text: failureWords(error, change.remove ? removeFailedWords(change.name) : saveFailedWords(change.name)) })
}

/**
 * The Watchlist button's and Add to Watchlist's press, the same everywhere:
 * with one watchlist or none it saves or removes and says so with Undo;
 * with several it opens Save to watchlist.
 */
export function useWatchlistToggle(target: WatchlistTarget | undefined, known: { holding?: readonly number[]; watchlisted?: boolean }) {
  const [open, setOpen] = useState(false)
  const w = useWatchlistPress(target, known, showWatchlistFailure)
  const press = async () => {
    if (w.picker || open) return setOpen((o) => !o)
    const out = await w.press()
    switch (out.kind) {
      case 'picker':
        setOpen(true)
        break
      case 'saved':
        showToast({ text: savedMessage(out.name), icon: 'saved', undo: () => void out.undo() })
        break
      case 'removed':
        showToast({ text: removedMessage(out.name), icon: 'removed', undo: out.undo, done: () => void out.commit() })
        break
      case 'failed':
        showWatchlistFailure(out.error, { name: onlyName(w.lists), remove: false })
        break
    }
  }
  return { ...w, open, setOpen, press }
}
