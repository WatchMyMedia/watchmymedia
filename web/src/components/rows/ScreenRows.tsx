import type { LinkProps } from '@tanstack/react-router'
import { attribution, drawable, posterSubtitle, seeAllTarget, seeAllWords, titleParts, type ScreenRow, type SeeAllTarget } from '../../lib/screenRows'
import { ContinueCard } from '../ContinueCard'
import { EpisodeCard } from '../EpisodeCard'
import { PosterCard } from '../PosterCard'
import { ReleasedCard } from '../ReleasedCard'
import { Row } from '../Row'
import { SeeAllCard } from './SeeAllCard'

type LibraryRef = { id: number; type: string }

/**
 * A screen's rows in the person's order, each drawn by its style
 * (PLAN-ROWS.md §6.1). Rows with nothing in them, live (the web has no
 * Live TV row yet) and styles the web doesn't know draw nothing.
 */
export function ScreenRows({ rows, libraries }: { rows: readonly ScreenRow[]; libraries: readonly LibraryRef[] }) {
  return (
    <>
      {rows.filter(drawable).map((row) => (
        <ScreenRowView key={row.id} row={row} libraries={libraries} />
      ))}
    </>
  )
}

/**
 * One row, ending in a See all card when it has a link the web can follow
 * (PLAN-SEE-ALL.md decision 4): poster-shaped in a poster row, 16:9 in an
 * episode or wide row. Continue watching has no link, so no card.
 */
function ScreenRowView({ row, libraries }: { row: ScreenRow; libraries: readonly LibraryRef[] }) {
  const [title, muted] = titleParts(row)
  const line = row.source || row.subtitle ? <RowLine row={row} /> : muted
  const target = seeAllTarget(row, libraries)
  const link = target && seeAllLink(target)
  const words = target && seeAllWords(target, row.title)
  const shape = row.style === 'poster' ? 'poster' : row.style === 'episode' || row.style === 'wide' ? 'wide' : undefined
  return (
    <Row title={title} subtitle={line} seeAll={link} seeAllLabel={words?.label}>
      {row.style === 'continue' && row.continue?.map((c) => <ContinueCard key={`${c.kind}-${c.fileId}`} item={c} />)}
      {row.style === 'episode' && row.episodes?.map((ep) => <EpisodeCard key={ep.episodeId} ep={ep} />)}
      {row.style === 'wide' && row.items?.map((it) => <ReleasedCard key={`${it.kind}-${it.id}`} item={it} />)}
      {row.style === 'poster' && row.items?.map((it) => <PosterCard key={`${it.kind}-${it.id}`} item={it} inRow subtitle={posterSubtitle(row, it)} />)}
      {link && words && shape && <SeeAllCard key="see-all" link={link} words={words} shape={shape} list={target.kind === 'list'} />}
    </Row>
  )
}

/** A list row's "18 of 50 · From TMDB", the source linking to the list on its site. */
function RowLine({ row }: { row: ScreenRow }) {
  return (
    <>
      {attribution(row.subtitle, row.source).map((s, i) =>
        s.href ? (
          <a key={i} href={s.href} target="_blank" rel="noreferrer" className="underline decoration-neutral-600 underline-offset-2 hover:text-neutral-200">
            {s.text}
          </a>
        ) : (
          <span key={i}>{s.text}</span>
        ),
      )}
    </>
  )
}

function seeAllLink(target: SeeAllTarget): LinkProps {
  if (target.kind === 'list') return { to: '/lists/$listId', params: { listId: String(target.listId) } }
  return { to: target.type === 'tv' ? '/tv' : '/movies', search: target.search }
}
