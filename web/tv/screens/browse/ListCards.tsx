import { useRef } from 'react'
import type { WatchlistTarget } from '../../../src/api/watchlists'
import type { MediaListEntry } from '../../../src/lib/mediaLists'
import { entryTitle, type Watchlist } from '../../../src/lib/watchlists'
import { useHold, useMediaUrls } from '../../app/hooks'
import { Card } from '../../ui/Card'
import type { RequestTarget } from '../requests/RequestDialog'
import { CardMenu } from '../watchlists/CardMenu'
import { toneStyle, useCardActions } from './browseHooks'
import { gridLine } from './libraryView'
import { entryCard, entryLabel, missingLine, type EntryCard } from './listPage'
import { itemActions, okWords } from './rowCards'
import { Glyph } from './RowView'

// A list page's titles, a watchlist's among them: the library's cards, the
// missing and not found ones, and a title's actions from Hold OK.

/** A title the library doesn't have: TMDB's poster dimmed, with Request or where its request stands; or Not found, for an import TMDB couldn't find. */
function MissingCard({ id, entry, card, image, ranked, watchlist, onRequest, onHold }: {
  id: string; entry: MediaListEntry; card: Exclude<EntryCard, { kind: 'library' }>; image?: string; ranked: boolean; watchlist: boolean
  onRequest(target: RequestTarget): void; onHold?(): void
}) {
  const ref = useRef<HTMLButtonElement>(null)
  useHold(ref, onHold)
  const request = card.kind === 'missing' ? card.request : undefined
  const tone = card.kind === 'missing' ? (request ? 'request' : entry.requestState === 'downloading' ? 'downloading' : entry.requestState ? '' : 'muted') : ''
  return <button ref={ref} type="button" data-focus={id} className={`tv-card tv-card--poster ${card.kind === 'notFound' ? 'list-notfound' : 'list-missing'}`}
    aria-label={entryLabel(entry, card, ranked)} onClick={request && (() => onRequest(request))}>
    <span className="tv-card__art" style={toneStyle(entry.title)}>
      {ranked && <span className="list-rank">{entry.rank}</span>}
      {card.kind === 'notFound' ? <>
        <span className="list-notfound__glyph"><Glyph name="notFound"/></span>
        <span className="list-notfound__title">Not found</span>
        <span className="list-notfound__raw">{entry.title}</span>
      </> : <>
        <span className="tv-card__placeholder" aria-hidden="true"/>
        {image && <img className="tv-card__img" src={image} alt="" decoding="async" loading="lazy" draggable={false}/>}
        <span className={`list-pill${tone ? ` list-pill--${tone}` : ''}`}>{request && <Glyph name="plus"/>}{card.status}</span>
      </>}
    </span>
    <span className="tv-card__title">{entry.title}</span>
    <span className="tv-card__sub">{card.kind === 'notFound' ? 'Not found on TMDB' : missingLine(entry, watchlist)}</span>
  </button>
}

/**
 * One title on a list page. OK opens a title the library has, or asks for
 * a missing one; Hold OK marks a movie watched, or, with `menu` (the server
 * keeps watchlists), opens the title's actions (`onMenu`). A watchlist's
 * titles show no rank.
 */
export function EntryView({ id, entry, ranked = true, watchlist = false, menu = false, onRequest, onMenu }: {
  id: string; entry: MediaListEntry; ranked?: boolean; watchlist?: boolean; menu?: boolean
  onRequest(target: RequestTarget): void; onMenu(entry: MediaListEntry): void
}) {
  const media = useMediaUrls()
  const { run } = useCardActions()
  const card = entryCard(entry, menu)
  const item = entry.item
  const actionsPanel = menu && entryTitle(entry) ? () => onMenu(entry) : undefined
  if (card.kind === 'library' && item) {
    const actions = itemActions(item)
    return <span className="browse-card" data-hint={card.hint} data-hold={card.hold}>
      <Card id={id} variant="poster" title={item.title} subtitle={gridLine(item)} image={media.imageUrl(item.posterPath, 'w342')} progress={item.progress}
        badges={ranked ? [{ label: String(entry.rank) }] : undefined} done={!!item.watched} label={entryLabel(entry, card, ranked)}
        onPress={actions.press && (() => run(actions.press))} onHold={actionsPanel ?? (actions.hold && (() => run(actions.hold)))}/>
    </span>
  }
  if (card.kind === 'library') return null
  return <span className="browse-card" data-hint={card.hint} data-hold={card.kind === 'missing' ? card.hold : undefined}>
    <MissingCard id={id} entry={entry} card={card} image={media.imageUrl(entry.posterPath, 'w342')} ranked={ranked} watchlist={watchlist}
      onRequest={onRequest} onHold={card.kind === 'missing' ? actionsPanel : undefined}/>
  </span>
}

/**
 * A list title's actions: OK's page and Mark watched for one the library
 * has, Request for one it lacks, and its watchlist item. On a watchlist's
 * page (`page`) that item takes it off the watchlist.
 */
export function EntryMenu({ entry, page, onRequest, onClose }: { entry: MediaListEntry; page?: Pick<Watchlist, 'id' | 'name'>; onRequest(target: RequestTarget): void; onClose(): void }) {
  const { run, say } = useCardActions()
  const title = entryTitle(entry)
  if (!title) return null
  const item = entry.item
  // What the server keeps for a title it has no record of comes with the save.
  const target: WatchlistTarget = { ...title, title: entry.title, year: entry.year || undefined, posterPath: entry.posterPath || undefined }
  const card = entryCard(entry)
  const request = card.kind === 'missing' ? card.request : undefined
  const actions = item ? itemActions(item) : undefined
  const movie = entry.mediaType !== 'tv'
  return <CardMenu title={item?.title ?? entry.title} kind={movie ? 'movie' : 'show'} year={entry.year ?? item?.year} target={target} watchlisted={entry.watchlisted}
    open={actions && okWords(actions.press)} watched={item && movie ? !!item.watched : undefined} page={page}
    onOpen={actions && (() => run(actions.press))} onWatched={actions && (() => run(actions.hold))} onRequest={request && (() => onRequest(request))}
    onProblem={say} onClose={onClose}/>
}
