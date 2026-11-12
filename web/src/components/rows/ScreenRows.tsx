import type { LinkProps } from '@tanstack/react-router'
import { attribution, drawable, posterSubtitle, seeAllTarget, titleParts, type ScreenRow } from '../../lib/screenRows'
import { ContinueCard } from '../ContinueCard'
import { EpisodeCard } from '../EpisodeCard'
import { PosterCard } from '../PosterCard'
import { ReleasedCard } from '../ReleasedCard'
import { Row } from '../Row'

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

function ScreenRowView({ row, libraries }: { row: ScreenRow; libraries: readonly LibraryRef[] }) {
  const [title, muted] = titleParts(row)
  const line = row.source || row.subtitle ? <RowLine row={row} /> : muted
  return (
    <Row title={title} subtitle={line} seeAll={seeAllLink(row, libraries)}>
      {row.style === 'continue' && row.continue?.map((c) => <ContinueCard key={`${c.kind}-${c.fileId}`} item={c} />)}
      {row.style === 'episode' && row.episodes?.map((ep) => <EpisodeCard key={ep.episodeId} ep={ep} />)}
      {row.style === 'wide' && row.items?.map((it) => <ReleasedCard key={`${it.kind}-${it.id}`} item={it} />)}
      {row.style === 'poster' && row.items?.map((it) => <PosterCard key={`${it.kind}-${it.id}`} item={it} inRow subtitle={posterSubtitle(row, it)} />)}
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

function seeAllLink(row: ScreenRow, libraries: readonly LibraryRef[]): LinkProps | undefined {
  const target = seeAllTarget(row, libraries)
  if (!target) return undefined
  if (target.kind === 'list') return { to: '/lists/$listId', params: { listId: String(target.listId) } }
  return { to: target.type === 'tv' ? '/tv' : '/movies', search: target.search }
}
