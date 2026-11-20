import { useCallback, useState } from 'react'
import { itemsQuery, useLibraryByType, type ItemSort } from '../../../src/api/queries'
import { sortOptions } from '../../../src/lib/browseOptions'
import { useQuery } from '@tanstack/react-query'
import { useMediaUrls, useNav } from '../../app/hooks'
import type { ScreenProps } from '../../app/routes'
import { Card } from '../../ui/Card'
import { Chips } from '../../ui/Chips'
import { Grid } from '../../ui/Grid'
import { ProblemScreen, SkeletonRow } from '../../ui/States'
import { problemWords } from '../../ui/problemWords'

const PAGE = 48

/**
 * TV Shows and Movies, until the browsing feature replaces them: the
 * library's sorts as chips over the kit's virtualised Grid, to show the
 * grid's focus, its window and loading more.
 */
export function LibraryScreen({ route }: ScreenProps<'library'>) {
  const media = useMediaUrls()
  const nav = useNav()
  const { library, isPending, isError, error, refetch } = useLibraryByType(route.type)
  const [sort, setSort] = useState<ItemSort>('title')
  const [limit, setLimit] = useState(PAGE)
  const items = useQuery({ ...itemsQuery(library?.id ?? 0, { sort, filter: '', limit, offset: 0 }), enabled: !!library })
  const total = items.data?.total ?? 0
  const loaded = items.data?.items.length ?? 0
  const more = useCallback(() => { if (!items.isFetching) setLimit((l) => l + PAGE) }, [items.isFetching])
  if (isError) {
    const w = problemWords(error)
    return <ProblemScreen icon="offline" title={`Couldn’t load ${route.type === 'tv' ? 'TV Shows' : 'Movies'}. ${w.title}`} actions={[{ id: 'retry', label: 'Try again', onPress: () => void refetch() }]}>{w.body}</ProblemScreen>
  }
  return <div className="library-placeholder">
    <p className="tv-kicker">{route.type === 'tv' ? 'TV Shows' : 'Movies'} · placeholder for the browsing screens</p>
    <h1 className="tv-h1">{library?.name ?? (isPending ? '' : 'No library yet')}</h1>
    <Chips id="sort" label="Sort" options={sortOptions(route.type)} value={sort} onChange={(v) => { setSort(v); setLimit(PAGE) }}/>
    <div className="library-placeholder__grid">
      {items.isPending && !!library ? <SkeletonRow variant="poster" title={false}/>
        : <Grid id={`grid/${library?.id ?? 0}/${sort}`} variant="poster" items={items.data?.items ?? []} itemKey={(i) => `${i.kind}${i.id}`} hasMore={loaded < total} onLoadMore={more}
          renderItem={(i, id) => <Card id={id} variant="poster" title={i.title} subtitle={i.year ? String(i.year) : undefined} image={media.imageUrl(i.posterPath, 'w342')}
            onPress={() => nav.push(i.kind === 'show' ? { name: 'show', id: i.id } : { name: 'movie', id: i.id })}/>}/>}
    </div>
  </div>
}
