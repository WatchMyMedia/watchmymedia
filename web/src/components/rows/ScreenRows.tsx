import type { ReactNode } from 'react'
import type { LinkProps } from '@tanstack/react-router'
import { movieLine } from '../../lib/cardFacts'
import { attribution, drawable, posterSubtitle, seeAllTarget, seeAllWords, titleParts, type ScreenRow, type SeeAllTarget } from '../../lib/screenRows'
import { ContinueCard } from '../ContinueCard'
import { EpisodeCard } from '../EpisodeCard'
import { PosterCard } from '../PosterCard'
import { ReleasedCard } from '../ReleasedCard'
import { Row } from '../Row'
import { SeeAllCard } from './SeeAllCard'

type LibraryRef = { id: number; type: string }

/**
 * A screen's rows in the person's order, each drawn by its style.
 * Rows with nothing in them and styles the web doesn't know draw nothing.
 * live is drawn by the screen (Home's On now) where the person put it, when
 * the screen gives one; skip leaves out rows the screen draws elsewhere
 * (Home's Continue watching, which leads the page).
 */
export function ScreenRows({ rows, libraries, screenType, live, skip = [], className }: { rows: readonly ScreenRow[]; libraries: readonly LibraryRef[]; screenType?: 'movie' | 'tv'; live?: (row: ScreenRow) => ReactNode; skip?: readonly string[]; className?: string }) {
  return (
    <>
      {rows
        .filter((row) => !skip.includes(row.id) && (drawable(row) || (row.style === 'live' && !!live)))
        .map((row) =>
          row.style === 'live' && live ? (
            <div key={row.id} className={className}>
              {live(row)}
            </div>
          ) : (
            <div key={row.id} className={className}>
              <ScreenRowView row={row} libraries={libraries} screenType={screenType} />
            </div>
          ),
        )}
    </>
  )
}

/**
 * One row, ending in a See all card when it has a link the web can follow:
 * poster-shaped in a poster row, 16:9 in an
 * episode or wide row. Continue watching has no link, so no card.
 */
function ScreenRowView({ row, libraries, screenType }: { row: ScreenRow; libraries: readonly LibraryRef[]; screenType?: 'movie' | 'tv' }) {
  const [title, muted] = titleParts(row)
  const line = row.source || row.subtitle ? <RowLine row={row} /> : muted
  const target = seeAllTarget(row, libraries, screenType)
  const link = target && seeAllLink(target)
  const words = target && seeAllWords(target, row.title)
  const shape = row.style === 'poster' ? 'poster' : row.style === 'episode' || row.style === 'wide' ? 'wide' : undefined
  return (
    <Row title={title} subtitle={line} seeAll={link} seeAllLabel={words?.label}>
      {row.style === 'continue' && row.continue?.map((c) => <ContinueCard key={`${c.kind}-${c.fileId}`} item={c} />)}
      {row.style === 'episode' && row.episodes?.map((ep) => <EpisodeCard key={ep.episodeId} ep={ep} />)}
      {row.style === 'wide' && row.items?.map((it) => <ReleasedCard key={`${it.kind}-${it.id}`} item={it} />)}
      {row.style === 'poster' && row.items?.map((it) => <PosterCard key={`${it.kind}-${it.id}`} item={it} inRow subtitle={(it.kind === 'movie' && movieLine(it)) || posterSubtitle(row, it)} />)}
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
  if (target.kind === 'list') return { to: '/lists/$listId', params: { listId: String(target.listId) }, search: { type: target.type } }
  return { to: target.type === 'tv' ? '/tv' : '/movies', search: target.search }
}
