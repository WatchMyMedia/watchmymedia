import { useState } from 'react'
import { flushSync } from 'react-dom'
import { useQueryClient } from '@tanstack/react-query'
import { holdRemoval, useWatchlistPress, type WatchlistTarget } from '../../../src/api/watchlists'
import { onlyName, type Watchlist } from '../../../src/lib/watchlists'
import { MorePanel, type MenuItem } from '../details/DetailParts'
import type { GlyphName } from '../details/Glyph'
import { SavePanel } from './WatchlistPanels'
import { cardMenu, changeFailed, type CardMenuItem } from './watchlistTv'

const WATCHLIST_ITEM = 'card/watchlist'

const ICONS: Record<Exclude<CardMenuItem['kind'], 'watchlist'>, GlyphName> = { open: 'open', watched: 'check', request: 'plus' }

/**
 * A card's actions from Hold OK, where the server keeps watchlists: open
 * it, Mark watched, Request (a title the library lacks) and its watchlist
 * item, which focus starts on. Add and Remove keep the panel open with the
 * item's new words, which is all a TV says about them; Save to watchlist…
 * opens the picker in its place. On a watchlist's page (`page`) the item
 * takes the title off that watchlist and closes the panel, as the card
 * leaves the page. Return closes it, and focus goes back to the card.
 */
export function CardMenu({ title, kind, year, target, watchlisted, open, watched, page, onOpen, onWatched, onRequest, onProblem, onClose }: {
  title: string
  kind: 'movie' | 'show'
  year?: number
  target: WatchlistTarget
  watchlisted?: boolean
  /** OK's words for a title in the library ("Open the movie"); none for one it lacks. */
  open?: string
  /** A movie in the library: whether it is watched. */
  watched?: boolean
  page?: Pick<Watchlist, 'id' | 'name'>
  onOpen?(): void
  onWatched?(): void
  /** A title the library lacks that the person may ask for. */
  onRequest?(): void
  onProblem(words: string): void
  onClose(): void
}) {
  const qc = useQueryClient()
  // The card's own watchlisted is as it was when OK was held; what was pressed here since is this.
  const [saved, setSaved] = useState(watchlisted)
  const wl = useWatchlistPress(target, { watchlisted: saved }, (e, change) => { setSaved(true); onProblem(changeFailed(e, change)) })
  const [picking, setPicking] = useState(false)
  if (picking) return <SavePanel target={target} title={title} onClose={onClose}/>

  const menu = cardMenu({ kind, year, open, watched, request: !!onRequest, lists: wl.lists, saved: wl.saved, page })
  const then = (f?: () => void) => () => { onClose(); f?.() }
  const pressWatchlist = async () => {
    if (page) {
      void holdRemoval(qc, target, page.id, (e) => onProblem(changeFailed(e, { name: page.name, remove: true }))).commit()
      // The card goes as the panel closes, so focus lands on the title after it.
      flushSync(onClose)
      return
    }
    const r = await wl.press()
    if (r.kind === 'picker') setPicking(true)
    else if (r.kind === 'saved') setSaved(true)
    else if (r.kind === 'removed') { setSaved(false); void r.commit() }
    else onProblem(changeFailed(r.error, { name: onlyName(wl.lists), remove: false }))
  }
  const items: MenuItem[] = menu.items.map((m) => {
    switch (m.kind) {
      case 'open': return { id: 'card/open', label: m.label, icon: ICONS.open, onPress: then(onOpen) }
      case 'watched': return { id: 'card/watched', label: m.label, icon: ICONS.watched, onPress: then(onWatched) }
      case 'request': return { id: 'card/request', label: m.label, icon: ICONS.request, onPress: then(onRequest) }
      case 'watchlist': return { id: WATCHLIST_ITEM, label: m.label, icon: page || wl.saved || wl.picker ? 'bookmarkOn' : 'bookmark', onPress: () => void pressWatchlist() }
    }
  })
  return <MorePanel kicker={menu.kicker} title={title} label={`Actions for ${title}`} items={items} start={WATCHLIST_ITEM} onClose={onClose}/>
}
