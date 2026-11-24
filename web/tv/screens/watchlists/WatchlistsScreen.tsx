import { useRef, useState, type CSSProperties } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useCreateWatchlist, watchlistsQuery } from '../../../src/api/watchlists'
import { emptyWatchlists, limitWords, type Watchlist } from '../../../src/lib/watchlists'
import { useFocus, useMediaUrls, useNav } from '../../app/hooks'
import { ProblemScreen, Skeleton, SkeletonText } from '../../ui/States'
import { VScroll } from '../../ui/VScroll'
import { problemWords } from '../../ui/problemWords'
import { placeholderTone } from '../../ui/tones'
import { Glyph } from '../details/Glyph'
import { NamePanel } from './WatchlistPanels'
import { tileWords, watchlistsLine } from './watchlistTv'
import './watchlists.css'
import { BACK } from '../../app/keys'

const NEW_TILE = 'watchlists/new'
const tileFocus = (id: number) => `watchlists/${id}`

/** A watchlist's tile: its first four posters, its name, its counts, and whether it keeps watched titles. */
function Tile({ w, autoFocus, onPress }: { w: Watchlist; autoFocus: boolean; onPress(): void }) {
  const media = useMediaUrls()
  const words = tileWords(w)
  const [a, b] = placeholderTone(w.name)
  const posters = [0, 1, 2, 3].map((i) => w.posters?.[i])
  return <button type="button" data-focus={tileFocus(w.id)} data-autofocus={autoFocus || undefined} className="wls-tile" aria-label={words.label} onClick={onPress}>
    <span className="wls-posters" aria-hidden="true">
      {posters.map((p, i) => <span key={i} className={`wls-poster${p ? '' : ' wls-poster--none'}`} style={p ? { '--ph-a': a, '--ph-b': b } as CSSProperties : undefined}>
        {p && <img src={media.imageUrl(p, 'w185')} alt="" decoding="async" loading="lazy" draggable={false}/>}
      </span>)}
    </span>
    <span className="wls-tile__name">{w.name}</span>
    <span className="wls-tile__count">{words.count}</span>
    {words.keeps && <span className="wls-tile__keeps">{words.keeps}</span>}
  </button>
}

/**
 * Watchlists (route `watchlists`, from Servers): New watchlist, then the
 * person's watchlists in the order they were made, three to a row. Focus
 * starts on the first watchlist, or on New watchlist with none. OK opens a
 * watchlist's page; New watchlist names one with the keyboard.
 */
export function WatchlistsScreen() {
  const nav = useNav()
  const focus = useFocus()
  const lists = useQuery(watchlistsQuery)
  const create = useCreateWatchlist()
  const [naming, setNaming] = useState(false)
  const made = useRef<number | undefined>(undefined)

  if (lists.isPending) return <div className="wls" aria-busy="true">
    <span className="tv-sr" role="status">Loading your watchlists</span>
    <div className="wls-head" aria-hidden="true"><div className="wls-head__text"><SkeletonText width="18rem"/></div></div>
    <div className="wls-body" aria-hidden="true"><div className="wls-grid">{[0, 1, 2].map((i) => <Skeleton key={i} width="100%" height="21.5rem" radius="var(--radius-m)"/>)}</div></div>
  </div>
  if (lists.isError) {
    const words = problemWords(lists.error)
    return <ProblemScreen icon="offline" title={`Couldn’t load your watchlists. ${words.title}`}
      actions={[{ id: 'watchlists/retry', label: 'Try again', onPress: () => void lists.refetch() }, { id: 'watchlists/back', label: 'Back', onPress: () => nav.back() }]}>{words.body}</ProblemScreen>
  }
  const items = lists.data.items
  const full = items.length >= lists.data.max
  return <div className="wls">
    <header className="wls-head">
      <div className="wls-head__text">
        <h1 className="wls-title">Watchlists</h1>
        {watchlistsLine(items.length) && <p className="wls-sub">{watchlistsLine(items.length)}</p>}
      </div>
      <p className="wls-hint" aria-hidden="true"><span className="tv-kbd">OK</span><span>{items.length ? 'Open the watchlist' : 'Name a new watchlist'}</span>
        <span className="browse-pill__gap"/><span className="tv-kbd">{BACK}</span><span>Back</span></p>
    </header>
    <div className="wls-body">
      <VScroll pad={48}>
        <div className="wls-grid">
          <button type="button" data-focus={NEW_TILE} data-autofocus={items.length === 0 || undefined} className="wls-tile wls-tile--new" aria-disabled={full || undefined}
            onClick={full ? undefined : () => { made.current = undefined; setNaming(true) }}>
            <Glyph name="bookmarkPlus"/>
            <span>New watchlist</span>
            {full && <span className="wls-tile__limit">{limitWords(lists.data.max)}</span>}
          </button>
          {items.map((w, i) => <Tile key={w.id} w={w} autoFocus={i === 0} onPress={() => nav.push({ name: 'list', id: w.id })}/>)}
        </div>
        {items.length === 0 && <div className="wls-empty"><h2>{emptyWatchlists.title}</h2><p>{emptyWatchlists.detail}</p></div>}
      </VScroll>
    </div>
    {naming && <NamePanel title="New watchlist" confirm="Create" note={`${BACK} goes back to your watchlists.`} lists={items} max={lists.data.max}
      save={(name) => create.mutateAsync(name).then((w) => { made.current = w.id })} onClose={() => setNaming(false)}
      returnFocus={() => made.current !== undefined && focus.focusId(tileFocus(made.current))}/>}
  </div>
}
