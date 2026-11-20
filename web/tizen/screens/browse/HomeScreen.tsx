import { useQuery } from '@tanstack/react-query'
import { homeQuery } from '../../../src/api/queries'
import { drawable, homeRows, type ScreenRow } from '../../../src/lib/screenRows'
import { useMediaUrls, useNav } from '../../app/hooks'
import { Button } from '../../ui/Button'
import { Card, type CardVariant } from '../../ui/Card'
import { Row } from '../../ui/Row'
import { ProblemScreen, SkeletonRow, SkeletonText } from '../../ui/States'
import { VScroll } from '../../ui/VScroll'
import { problemWords } from '../../ui/problemWords'

type Drawn = { key: string; title: string; subtitle?: string; image?: string; progress?: number; open(): void }

/** A row's items as cards, whatever its style. */
function useCards(row: ScreenRow): { variant: CardVariant; cards: Drawn[] } {
  const media = useMediaUrls()
  const nav = useNav()
  switch (row.style) {
    case 'continue':
      return { variant: 'continue', cards: (row.continue ?? []).map((c) => ({ key: `f${c.fileId}`, title: c.title, subtitle: c.subtitle,
        image: media.imageUrl(c.stillPath ?? c.backdropPath, 'w780'), progress: c.durationMs ? c.positionMs / c.durationMs : 0,
        open: () => nav.play({ kind: 'file', fileId: c.fileId, startMs: c.positionMs, title: c.title, subtitle: c.subtitle }) })) }
    case 'episode':
      return { variant: 'episode', cards: (row.episodes ?? []).map((e) => ({ key: `e${e.episodeId}`, title: e.showTitle, subtitle: `S${e.seasonNumber} E${e.episodeNumber} · ${e.title}`,
        image: media.imageUrl(e.stillPath ?? e.showBackdropPath, 'w780'), open: () => nav.push({ name: 'show', id: e.showId, season: e.seasonNumber, episodeId: e.episodeId }) })) }
    default: {
      const wide = row.style === 'wide'
      return { variant: wide ? 'wide' : 'poster', cards: (row.items ?? []).map((i) => ({ key: `${i.kind}${i.id}`, title: i.title, subtitle: i.subtitle ?? (i.year ? String(i.year) : undefined),
        image: media.imageUrl(wide ? i.backdropPath : i.posterPath, wide ? 'w780' : 'w342'), progress: i.progress,
        open: () => nav.push(i.kind === 'show' ? { name: 'show', id: i.id } : { name: 'movie', id: i.id }) })) }
    }
  }
}

function HomeRow({ row, index, count }: { row: ScreenRow; index: number; count: number }) {
  const { variant, cards } = useCards(row)
  return <div data-vitem className="home-row">
    <Row id={`row/${row.id}`} title={row.title} subtitle={row.subtitle} counter={`Row ${index + 1} of ${count}`} variant={variant} items={cards} itemKey={(c) => c.key}
      renderItem={(c, id) => <Card id={id} variant={variant} title={c.title} subtitle={c.subtitle} image={c.image} progress={c.progress} onPress={c.open}/>}/>
  </div>
}

/**
 * Home, until the browsing feature replaces it: the server's rows in the
 * person's order, drawn with the kit's Row and Card, to show the shell, the
 * focus rules and the data layer working end to end.
 */
export function HomeScreen() {
  const home = useQuery(homeQuery)
  if (home.isPending) return <div className="home-placeholder"><SkeletonText width="24rem"/><SkeletonRow variant="continue"/><SkeletonRow variant="poster"/></div>
  if (home.isError) {
    const words = problemWords(home.error)
    return <ProblemScreen icon="offline" title={`Couldn’t load Home. ${words.title}`} actions={[{ id: 'retry', label: 'Try again', onPress: () => void home.refetch() }]}>{words.body}</ProblemScreen>
  }
  const rows = homeRows(home.data).filter(drawable)
  return <VScroll pad={64}>
    <div className="home-placeholder">
      <p className="tv-kicker">Home · placeholder for the browsing screens</p>
      {rows.length === 0 && <div className="tv-actions"><Button id="empty" autoFocus>Nothing here yet</Button></div>}
      {rows.map((row, i) => <HomeRow key={row.id} row={row} index={i} count={rows.length}/>)}
    </div>
  </VScroll>
}
