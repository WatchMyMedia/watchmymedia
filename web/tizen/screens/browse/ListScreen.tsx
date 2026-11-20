import { useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../../src/api/client'
import { mediaListEntriesQuery, mediaListQuery } from '../../../src/api/queries'
import type { components } from '../../../src/api/schema'
import { MAX_ENTRIES, type MediaList, type MediaListEntry } from '../../../src/lib/mediaLists'
import { useMediaUrls, useNav } from '../../app/hooks'
import type { ScreenProps } from '../../app/routes'
import { Card } from '../../ui/Card'
import { Grid } from '../../ui/Grid'
import { Icon } from '../../ui/Icon'
import { ProblemScreen, SkeletonRow, SkeletonText } from '../../ui/States'
import { problemWords } from '../../ui/problemWords'
import { RequestDialog, type RequestTarget } from '../requests/RequestDialog'
import { CardActions } from './CardActions'
import { toneStyle, useCardActions, useFocusHint, useFocusOnArrival, type FocusHintWords } from './browseHooks'
import { HintPill } from './Hints'
import { gridLine } from './libraryView'
import { entryCard, entryLabel, failedWords, listCount, listKicker, listLine, missingLine, type EntryCard } from './listPage'
import { itemActions } from './rowCards'
import { Glyph } from './RowView'
import './browse.css'

type MediaListEntries = components['schemas']['MediaListEntries']

/** A title the library doesn't have: TMDB's poster dimmed, with Request or where its request stands; or Not found, for an import TMDB couldn't find. */
function MissingCard({ id, entry, card, image, onRequest }: { id: string; entry: MediaListEntry; card: Exclude<EntryCard, { kind: 'library' }>; image?: string; onRequest(target: RequestTarget): void }) {
  const request = card.kind === 'missing' ? card.request : undefined
  const tone = card.kind === 'missing' ? (request ? 'request' : entry.requestState === 'downloading' ? 'downloading' : entry.requestState ? '' : 'muted') : ''
  return <button type="button" data-focus={id} className={`tv-card tv-card--poster ${card.kind === 'notFound' ? 'list-notfound' : 'list-missing'}`}
    aria-label={entryLabel(entry, card)} onClick={request && (() => onRequest(request))}>
    <span className="tv-card__art" style={toneStyle(entry.title)}>
      <span className="list-rank">{entry.rank}</span>
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
    <span className="tv-card__sub">{card.kind === 'notFound' ? 'Not found on TMDB' : missingLine(entry)}</span>
  </button>
}

/** One title on the list, in its rank's place. */
function EntryView({ id, entry, onRequest }: { id: string; entry: MediaListEntry; onRequest(target: RequestTarget): void }) {
  const media = useMediaUrls()
  const { run } = useCardActions()
  const card = entryCard(entry)
  const item = entry.item
  if (card.kind === 'library' && item) {
    const actions = itemActions(item)
    return <span className="browse-card" data-hint={card.hint} data-hold={card.hold}>
      <Card id={id} variant="poster" title={item.title} subtitle={gridLine(item)} image={media.imageUrl(item.posterPath, 'w342')} progress={item.progress}
        badges={[{ label: String(entry.rank) }, ...(item.watched ? [{ label: '✓', tone: 'gold' as const }] : [])]} label={entryLabel(entry, card)}
        onPress={actions.press && (() => run(actions.press))} onHold={actions.hold && (() => run(actions.hold))}/>
    </span>
  }
  if (card.kind === 'library') return null
  return <span className="browse-card" data-hint={card.hint}>
    <MissingCard id={id} entry={entry} card={card} image={media.imageUrl(entry.posterPath, 'w342')} onRequest={onRequest}/>
  </span>
}

function ListHeader({ list, inLibrary, total, hint }: { list: MediaList; inLibrary: number; total: number; hint: FocusHintWords }) {
  const failed = failedWords(list)
  return <header className="list-head">
    <div className="list-head__text">
      <p className="tv-kicker home-kicker">{listKicker(list)}</p>
      <h1 className="list-title">{list.name}</h1>
      <p className="list-line">{listLine(list)}</p>
      <p className="list-count">{listCount(inLibrary, total, list.notFound)}</p>
    </div>
    {failed ? <p className="list-failed" role="status"><Icon name="alert"/><span>{failed}</span></p> : <HintPill words={hint} className="list-hint"/>}
  </header>
}

/** The page under the header: the titles, and the request dialog over them. */
function ListBody({ list, entries, error }: { list: MediaList; entries?: MediaListEntries; error: unknown }) {
  const qc = useQueryClient()
  const { say } = useCardActions()
  const root = useRef<HTMLDivElement>(null)
  const hint = useFocusHint(root)
  const [asking, setAsking] = useState<RequestTarget>()
  useFocusOnArrival()
  const items = entries?.items ?? []
  return <div className="list-page" ref={root}>
    <div className="home-backdrop home-backdrop--short" style={toneStyle(list.name)} aria-hidden="true"><span className="home-backdrop__fade"/></div>
    <ListHeader list={list} inLibrary={entries?.inLibrary ?? list.inLibrary} total={entries?.total ?? list.entries} hint={hint}/>
    <div className="list-grid">
      {error ? <p className="list-empty">{`Couldn’t load its titles. ${problemWords(error).title}.`}</p>
        : items.length === 0 ? <p className="list-empty">{list.fetchedAt || list.source.kind === 'import' || list.source.kind === 'library' ? 'Nothing is on this list.' : 'The list hasn’t been fetched yet.'}</p>
          : <div className="list-grid__body" data-autofocus>
            <Grid id={`list/${list.id}`} items={items} itemKey={(e) => String(e.rank)} variant="poster"
              renderItem={(e, id) => <EntryView id={id} entry={e} onRequest={setAsking}/>}/>
          </div>}
    </div>
    {asking && <RequestDialog target={asking} onClose={(requested) => {
      setAsking(undefined)
      if (!requested) return
      say('Requested. You’ll find it in Your requests.')
      void qc.invalidateQueries({ queryKey: ['lists', list.id] })
    }}/>}
  </div>
}

function useList(id: number) {
  const list = useQuery(mediaListQuery(id))
  // A list holds at most MAX_ENTRIES titles: one request has them all, in rank order.
  const entries = useQuery({ ...mediaListEntriesQuery(id, 0, MAX_ENTRIES), enabled: list.isSuccess })
  return { list: list.data, listQuery: list, entries }
}

/**
 * A list's page (route `list`), a list row's See
 * all: its kicker, name, where it comes from and how much the library has,
 * then every title in rank order in eight columns. OK opens a title the
 * library has, or asks for a missing one when the person may (the request
 * dialog); Hold OK marks a movie watched. Not found titles take focus, and
 * OK on them does nothing.
 */
export function ListScreen({ route }: ScreenProps<'list'>) {
  const nav = useNav()
  const { list, listQuery, entries } = useList(route.id)

  if (listQuery.isPending || (list && entries.isPending)) return <div className="list-page" aria-busy="true">
    <span className="tv-sr" role="status">Loading the list</span>
    <div className="list-head" aria-hidden="true"><div className="list-head__text"><SkeletonText width="14rem"/><SkeletonText width="30rem"/><SkeletonText width="20rem"/></div></div>
    <div className="list-grid" aria-hidden="true"><SkeletonRow variant="poster" title={false}/></div>
  </div>
  if (listQuery.isError || !list) {
    const gone = listQuery.error instanceof ApiError && listQuery.error.status === 404
    const words = problemWords(listQuery.error)
    return <ProblemScreen icon={gone ? 'alert' : 'offline'} title={gone ? 'This list isn’t here any more' : `Couldn’t load this list. ${words.title}`}
      actions={gone ? [{ id: 'list/back', label: 'Back', onPress: () => nav.back() }] : [{ id: 'list/retry', label: 'Try again', onPress: () => void listQuery.refetch() }, { id: 'list/back', label: 'Back', onPress: () => nav.back() }]}>
      {gone ? 'It was deleted, or it’s someone else’s.' : words.body}
    </ProblemScreen>
  }
  return <CardActions><ListBody list={list} entries={entries.data} error={entries.error}/></CardActions>
}
