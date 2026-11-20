import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useMarkWatched } from '../../../src/api/watch'
import { useNav } from '../../app/hooks'
import { problemWords } from '../../ui/problemWords'
import { CardActionsContext } from './browseHooks'
import type { CardAction } from './rowCards'

type GridAction = Extract<CardAction, { kind: 'grid' }>

/** How long a notice stays at the foot of the screen. */
const TOAST_MS = 3200

function Toast({ toast }: { toast?: { text: string; n: number } }) {
  // The notice that has had its time; a newer one shows until it has had its own.
  const [over, setOver] = useState<number>()
  useEffect(() => {
    if (!toast) return
    const timer = setTimeout(() => setOver(toast.n), TOAST_MS)
    return () => clearTimeout(timer)
  }, [toast])
  return <p className="browse-toast" role="status" aria-live="polite" hidden={!toast || over === toast.n}>{toast?.text}</p>
}

/**
 * The browsing screens' card actions: open a page, play, mark a movie
 * watched (the notice says so, or why it couldn't), and a library row's See
 * all sorting the grid below it (`onGrid`).
 */
export function CardActions({ children, onGrid }: { children: ReactNode; onGrid?: (action: GridAction) => void }) {
  const nav = useNav()
  const { mutate: markWatched } = useMarkWatched()
  const [toast, setToast] = useState<{ text: string; n: number }>()
  const say = useCallback((text: string) => setToast((t) => ({ text, n: (t?.n ?? 0) + 1 })), [])
  const run = useCallback((action: CardAction | undefined) => {
    switch (action?.kind) {
      case 'open': nav.push(action.route); break
      case 'play': nav.play(action.request); break
      case 'grid': onGrid?.(action); break
      case 'notMatched': say('This file isn’t matched to a movie or show yet. An admin can match it on the server’s web page.'); break
      case 'watched':
        markWatched({ type: 'movie', id: action.movieId, watched: action.watched }, {
          onSuccess: () => say(action.watched ? 'Marked as watched' : 'Marked as unwatched'),
          onError: (error) => say(`Couldn’t mark it. ${problemWords(error).title}.`),
        })
        break
    }
  }, [nav, onGrid, say, markWatched])
  const actions = useMemo(() => ({ run, say }), [run, say])
  return <CardActionsContext.Provider value={actions}>{children}<Toast toast={toast}/></CardActionsContext.Provider>
}
