import { useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { mediaListEntriesQuery } from '../../../src/api/queries'
import { isHeld, useDeleteWatchlist, useHeldRemovals, useUpdateWatchlist, watchlistsQuery } from '../../../src/api/watchlists'
import { MAX_ENTRIES, type MediaList, type MediaListEntry } from '../../../src/lib/mediaLists'
import {
  deleteWords, emptyFilterWords, emptyWatchlist, switchWords, watchlistCounts, watchlistFilters, watchlistSorts, withoutHeld, type WatchlistSort, type WatchlistType,
} from '../../../src/lib/watchlists'
import { useNav } from '../../app/hooks'
import { Button } from '../../ui/Button'
import { Chips } from '../../ui/Chips'
import { Grid } from '../../ui/Grid'
import { Icon } from '../../ui/Icon'
import { Dialog, Option, SidePanel } from '../../ui/Overlays'
import { SkeletonRow } from '../../ui/States'
import { problemWords } from '../../ui/problemWords'
import { useCardActions, useFocusHint, useFocusOnArrival, toneStyle } from '../browse/browseHooks'
import { useEntryState } from '../browse/entryState'
import { HintPill } from '../browse/Hints'
import { EntryMenu, EntryView } from '../browse/ListCards'
import { Glyph } from '../details/Glyph'
import { RequestDialog, type RequestTarget } from '../requests/RequestDialog'
import { NamePanel } from './WatchlistPanels'
import './watchlists.css'

type TypeChoice = WatchlistType | 'all'
const TYPES = watchlistFilters.map((f) => ({ value: (f.value ?? 'all') as TypeChoice, label: f.label }))

/** A watchlist's titles as the page shows them: both pages of up to MAX_ENTRIES, less any being taken off. */
function useTitles(list: MediaList, sort: WatchlistSort, type: WatchlistType | undefined) {
  const view = { sort, mediaType: type }
  const first = useQuery(mediaListEntriesQuery(list.id, 0, MAX_ENTRIES, view))
  // A watchlist holds more titles than one page has.
  const rest = useQuery({ ...mediaListEntriesQuery(list.id, MAX_ENTRIES, MAX_ENTRIES, view), enabled: (first.data?.total ?? 0) > MAX_ENTRIES })
  useHeldRemovals()
  const page = first.data && withoutHeld({ ...first.data, items: [...first.data.items, ...(rest.data?.items ?? [])] }, (t) => isHeld(t, list.id))
  return { items: page?.items, pending: first.isPending, error: first.error }
}

/**
 * A watchlist's page: the list page without a source, an update time or
 * rank numbers. At its top Rename, Delete and the switch that keeps or
 * removes watched titles; then All, Movies and TV shows and the sort
 * (Date added, newest saved first, unless chosen), over its titles. Hold OK
 * on a title opens its actions, with Remove from it and, for a title the
 * library lacks, Request. Delete asks first and then goes back.
 */
export function WatchlistBody({ list, type: opened }: { list: MediaList; type?: WatchlistType }) {
  const nav = useNav()
  const qc = useQueryClient()
  const { say } = useCardActions()
  const watchlists = useQuery(watchlistsQuery)
  const update = useUpdateWatchlist()
  const remove = useDeleteWatchlist()
  const [type, setType] = useEntryState<WatchlistType | undefined>('watchlist-type', opened)
  const [sort, setSort] = useEntryState<WatchlistSort>('watchlist-sort', 'added')
  const [panel, setPanel] = useState<'rename' | 'delete' | 'sort'>()
  const [menuOf, setMenuOf] = useState<MediaListEntry>()
  const [asking, setAsking] = useState<RequestTarget>()
  const root = useRef<HTMLDivElement>(null)
  const hint = useFocusHint(root)
  const titles = useTitles(list, sort, type)
  useFocusOnArrival()

  const empty = list.entries === 0 && !titles.items?.length
  const removeWatched = list.removeWatched !== false
  const sortLabel = watchlistSorts.find((s) => s.value === sort)?.label ?? watchlistSorts[0].label
  const close = () => setPanel(undefined)
  const flip = () => update.mutate({ id: list.id, patch: { removeWatched: !removeWatched } }, { onError: (e) => say(`Couldn’t change it. ${problemWords(e).title}.`) })
  const doDelete = () => remove.mutate(list.id, {
    onSuccess: () => nav.back(),
    onError: (e) => { close(); say(`Couldn’t delete ${list.name}. ${problemWords(e).title}.`) },
  })
  const words = deleteWords(list)

  return <div className="list-page" ref={root}>
    <div className="home-backdrop home-backdrop--short" style={toneStyle(list.name)} aria-hidden="true"><span className="home-backdrop__fade"/></div>
    <header className="list-head wl-page-head">
      <div className="list-head__text">
        <p className="tv-kicker home-kicker">Watchlist</p>
        <h1 className="list-title">{list.name}</h1>
        {list.entries > 0 && <p className="list-count">{watchlistCounts({ titles: list.entries, inLibrary: list.inLibrary })}</p>}
      </div>
      <HintPill words={hint} className="list-hint"/>
    </header>
    <div className="wl-actions" role="group" aria-label="This watchlist">
      <span className="dt-small"><Button id="watchlist/rename" autoFocus={empty} onPress={() => setPanel('rename')}><Glyph name="pencil"/>Rename</Button></span>
      <span className="dt-small"><Button id="watchlist/delete" onPress={() => setPanel('delete')}><Glyph name="trash"/>Delete</Button></span>
      <button type="button" role="switch" aria-checked={removeWatched} aria-describedby="wl-switch-note" data-focus="watchlist/switch" className="dt-switch" onClick={flip}>
        <span className="dt-switch__knob" aria-hidden="true"/>{switchWords.label}
      </button>
      <p id="wl-switch-note" className="wl-actions__note">{switchWords.detail}</p>
    </div>
    {empty
      ? <div className="wl-empty"><h2>{emptyWatchlist.title}</h2><p>{emptyWatchlist.detail}</p></div>
      : <>
        <div className="wl-filters">
          <Chips id="watchlist/type" label="Show" options={TYPES} value={type ?? 'all'} onChange={(v) => setType(v === 'all' ? undefined : v)}/>
          <div className="wl-filters__sort">
            <span>Sort</span>
            <button type="button" data-focus="watchlist/sort" className="tv-chip" aria-haspopup="listbox" aria-label={`Sort: ${sortLabel}`} onClick={() => setPanel('sort')}>{sortLabel}<Icon name="down"/></button>
          </div>
        </div>
        <div className="list-grid wl-grid">
          {titles.pending ? <div aria-hidden="true"><SkeletonRow variant="poster" title={false}/></div>
            : titles.error ? <p className="list-empty">{`Couldn’t load its titles. ${problemWords(titles.error).title}.`}</p>
              : !titles.items?.length ? <p className="list-empty">{type ? emptyFilterWords(type) : `${emptyWatchlist.title}.`}</p>
                : <div className="list-grid__body" data-autofocus>
                  {/* Keyed by place, so a title taken off hands focus to the one after it. */}
                  <Grid id={`list/${list.id}`} items={titles.items} itemKey={(_, i) => String(i)} variant="poster"
                    renderItem={(e, id) => <EntryView id={id} entry={e} ranked={false} watchlist menu onRequest={setAsking} onMenu={setMenuOf}/>}/>
                </div>}
        </div>
      </>}
    {menuOf && <EntryMenu entry={menuOf} page={list} onRequest={setAsking} onClose={() => setMenuOf(undefined)}/>}
    {panel === 'sort' && <SidePanel title="Sort by" onClose={close}>
      {watchlistSorts.map((o) => <Option key={o.value} id={`sort/${o.value}`} label={o.label} checked={o.value === sort} onPress={() => { close(); setSort(o.value) }}/>)}
    </SidePanel>}
    {panel === 'rename' && <NamePanel title="Rename watchlist" sub={list.name} initial={list.name} confirm="Save" note="Return keeps the name it has."
      lists={watchlists.data?.items ?? []} self={list} save={(name) => update.mutateAsync({ id: list.id, patch: { name } })} onClose={close}/>}
    {panel === 'delete' && <Dialog title={words.title} onCancel={close} actions={[
      { id: 'delete/cancel', label: 'Cancel', safe: true, onPress: close },
      { id: 'delete/confirm', label: 'Delete', variant: 'danger', busy: remove.isPending, onPress: doDelete },
    ]}>
      <p className="tv-body">{words.body}</p>
      <p className="tv-faint">Return keeps it.</p>
    </Dialog>}
    {asking && <RequestDialog target={asking} onClose={(requested) => {
      setAsking(undefined)
      if (!requested) return
      say('Requested. You’ll find it in Your requests.')
      void qc.invalidateQueries({ queryKey: ['lists', list.id] })
    }}/>}
  </div>
}
