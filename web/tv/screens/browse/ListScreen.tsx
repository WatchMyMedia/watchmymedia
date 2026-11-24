import { useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../../src/api/client'
import { mediaListEntriesQuery, mediaListQuery } from '../../../src/api/queries'
import type { components } from '../../../src/api/schema'
import { emptyListWords, isWatchlist, MAX_ENTRIES, type MediaList, type MediaListEntry } from '../../../src/lib/mediaLists'
import { useNav } from '../../app/hooks'
import type { ScreenProps } from '../../app/routes'
import { Grid } from '../../ui/Grid'
import { Icon } from '../../ui/Icon'
import { ProblemScreen, SkeletonRow, SkeletonText } from '../../ui/States'
import { problemWords } from '../../ui/problemWords'
import { RequestDialog, type RequestTarget } from '../requests/RequestDialog'
import { useWatchlistsOn } from '../watchlists/useWatchlistsOn'
import { WatchlistBody } from '../watchlists/WatchlistPage'
import { CardActions } from './CardActions'
import { toneStyle, useCardActions, useFocusHint, useFocusOnArrival, type FocusHintWords } from './browseHooks'
import { HintPill } from './Hints'
import { EntryMenu, EntryView } from './ListCards'
import { failedWords, listCount, listKicker, listLine } from './listPage'
import './browse.css'

type MediaListEntries = components['schemas']['MediaListEntries']

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
  const [menuOf, setMenuOf] = useState<MediaListEntry>()
  const menu = useWatchlistsOn()
  useFocusOnArrival()
  const items = entries?.items ?? []
  return <div className="list-page" ref={root}>
    <div className="home-backdrop home-backdrop--short" style={toneStyle(list.name)} aria-hidden="true"><span className="home-backdrop__fade"/></div>
    <ListHeader list={list} inLibrary={entries?.inLibrary ?? list.inLibrary} total={entries?.total ?? list.entries} hint={hint}/>
    <div className="list-grid">
      {error ? <p className="list-empty">{`Couldn’t load its titles. ${problemWords(error).title}.`}</p>
        : items.length === 0 ? <p className="list-empty">{emptyListWords(list)}</p>
          : <div className="list-grid__body" data-autofocus>
            <Grid id={`list/${list.id}`} items={items} itemKey={(e) => String(e.rank)} variant="poster"
              renderItem={(e, id) => <EntryView id={id} entry={e} menu={menu} onRequest={setAsking} onMenu={setMenuOf}/>}/>
          </div>}
    </div>
    {menuOf && <EntryMenu entry={menuOf} onRequest={setAsking} onClose={() => setMenuOf(undefined)}/>}
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
  // A list holds at most MAX_ENTRIES titles: one request has them all, in rank order. A watchlist asks for its own, sorted.
  const entries = useQuery({ ...mediaListEntriesQuery(id, 0, MAX_ENTRIES), enabled: list.isSuccess && !isWatchlist(list.data) })
  return { list: list.data, listQuery: list, entries }
}

/**
 * A list's page (route `list`), a list row's See
 * all: its kicker, name, where it comes from and how much the library has,
 * then every title in rank order in eight columns. OK opens a title the
 * library has, or asks for a missing one when the person may (the request
 * dialog); Hold OK marks a movie watched, or opens a title's actions where
 * the server keeps watchlists. Not found titles take focus, and OK on them
 * does nothing. A watchlist's page is its own (WatchlistBody).
 */
export function ListScreen({ route }: ScreenProps<'list'>) {
  const nav = useNav()
  const { list, listQuery, entries } = useList(route.id)

  if (list && isWatchlist(list)) return <CardActions><WatchlistBody list={list} type={route.type}/></CardActions>
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
