import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import type { components } from '../../../src/api/schema'
import { hideFromContinueWatching, useMarkWatched } from '../../../src/api/watch'
import type { ContinueTarget } from '../../../src/lib/continueWatching'
import { unwrap } from '../../../src/api/client'
import { useQueryClient } from '@tanstack/react-query'
import { usePeers, useSession } from '../../app/hooks'
import type { TaggedContinue } from './oneCardHome'
import { showNote } from '../../ui/notes'
import { continueMenuWords } from './continueWords'
import { watchlistTitle } from '../../../src/lib/watchlists'
import { useNav } from '../../app/hooks'
import { problemWords } from '../../ui/problemWords'
import { CardMenu } from '../watchlists/CardMenu'
import { CardActionsContext } from './browseHooks'
import { itemActions, okWords, type CardAction } from './rowCards'
import { ContinueMenu } from './ContinueMenu'

type GridAction = Extract<CardAction, { kind: 'grid' }>
type BrowseItem = components['schemas']['BrowseItem']

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

/** A library title's actions from Hold OK: OK's page, Mark watched for a movie, and its watchlist item. */
function ItemMenu({ item, run, say, onClose }: { item: BrowseItem; run(action: CardAction | undefined): void; say(text: string): void; onClose(): void }) {
  const target = watchlistTitle(item.kind, item.tmdbId)
  if (!target) return null
  const { press, hold } = itemActions(item)
  const movie = item.kind === 'movie'
  return <CardMenu title={item.title} kind={movie ? 'movie' : 'show'} year={item.year} target={target} watchlisted={item.watchlisted}
    open={okWords(press)} watched={movie ? !!item.watched : undefined} onOpen={() => run(press)} onWatched={() => run(hold)} onProblem={say} onClose={onClose}/>
}

/**
 * The browsing screens' card actions: open a page, play, mark a movie
 * watched (the notice says so, or why it couldn't), a library row's See
 * all sorting the grid below it (`onGrid`), a title's actions panel, and a
 * Continue watching card's (Remove from Continue watching).
 */
export function CardActions({ children, onGrid, onRetry }: { children: ReactNode; onGrid?: (action: GridAction) => void; onRetry?: (serverId: string) => void }) {
  const nav = useNav()
  const session = useSession()
  const peers = usePeers()
  const qc = useQueryClient()
  const { mutate: markWatched } = useMarkWatched()
  const [toast, setToast] = useState<{ text: string; n: number }>()
  const [menu, setMenu] = useState<BrowseItem>()
  const [continued, setContinued] = useState<TaggedContinue>()
  // Removing a card takes it off every server that has it (one card across servers); each through its own client.
  const remove = useCallback((item: TaggedContinue, target: ContinueTarget) => {
    const copies = item.copies ?? [{ server: item.oneCard?.server.id ?? session.server.id, target }]
    const hide = (server: string, t: ContinueTarget) => {
      const peer = server === session.server.id ? undefined : peers.find((p) => p.id === server)
      if (!peer) return hideFromContinueWatching(qc, t)
      return peer.api.PUT('/api/watch/{type}/{id}/hidden', { params: { path: { type: t.type, id: t.id } } }).then(unwrap)
    }
    void Promise.all(copies.map((c) => hide(c.server, c.target))).then(
      () => showNote(continueMenuWords({ kind: target.type }).removed, 'check'),
      (error: unknown) => showNote(`Couldn’t remove it. ${problemWords(error).title}.`, 'alert'),
    ).finally(() => {
      void qc.invalidateQueries({ queryKey: ['home'] })
      void qc.invalidateQueries({ predicate: ({ queryKey }) => queryKey[0] === 'peer' && queryKey[2] === 'home' })
    })
  }, [session, peers, qc])
  const say = useCallback((text: string) => setToast((t) => ({ text, n: (t?.n ?? 0) + 1 })), [])
  const run = useCallback((action: CardAction | undefined) => {
    switch (action?.kind) {
      case 'open': nav.push(action.route); break
      case 'play': nav.play(action.request); break
      case 'grid': onGrid?.(action); break
      case 'menu': setMenu(action.item); break
      case 'continueMenu': setContinued(action.item); break
      case 'retryServer': onRetry?.(action.id); break
      case 'notMatched': say('This file isn’t matched to a movie or show yet. An admin can match it on the server’s web page.'); break
      case 'watched':
        markWatched({ type: 'movie', id: action.movieId, watched: action.watched }, {
          onSuccess: () => say(action.watched ? 'Marked as watched' : 'Marked as unwatched'),
          onError: (error) => say(`Couldn’t mark it. ${problemWords(error).title}.`),
        })
        break
    }
  }, [nav, onGrid, onRetry, say, markWatched])
  const actions = useMemo(() => ({ run, say }), [run, say])
  return <CardActionsContext.Provider value={actions}>
    {children}
    {menu && <ItemMenu item={menu} run={run} say={say} onClose={() => setMenu(undefined)}/>}
    {continued && <ContinueMenu item={continued} onRemove={(t) => remove(continued, t)} onClose={() => setContinued(undefined)}/>}
    <Toast toast={toast}/>
  </CardActionsContext.Provider>
}
