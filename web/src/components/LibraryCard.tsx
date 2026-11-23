import { Link } from '@tanstack/react-router'
import { CardLine, CardPicture, CardTitle } from './CardParts'
import { Poster } from './Poster'
import { CardFlag, CardProgress, PosterCard, WatchedTick, type BrowseItem } from './PosterCard'
import { posterLinks } from '../lib/cardLinks'
import { movieLine, qualityWords, scoreWords, showStatusWords } from '../lib/cardFacts'
import { addedWords, leftToWatch, newFlag, showCardLine, youWords } from '../lib/browsePrefs'
import { formatDate, formatRuntime, formatBytes } from '../lib/format'
import { useNow } from './useNow'

/**
 * A title in a library's grid: the poster with New ("2 new" for a show's new
 * episodes), watched and progress marks, the title, and for a movie its year
 * and length; for a show its seasons and what is left ("3 seasons · 2 left")
 * and where it stands ("Next episode Dec 1", "Ended"). Files not matched yet keep their own card. role is a person's
 * part in it, on their page.
 */
export function LibraryCard({ item, role }: { item: BrowseItem; role?: string }) {
  if (item.kind === 'unmatched') return <PosterCard item={item} />
  const show = item.kind === 'show'
  const first = show ? showCardLine(item) : movieLine(item)
  const flag = newFlag(item)
  const second = show ? showStatusWords(item) : ''
  const links = posterLinks(item, first)
  return (
    <div>
      <CardPicture {...links.picture}>
        <Poster path={item.posterPath} alt={item.title} />
        {flag && <CardFlag>{flag}</CardFlag>}
        {item.watched && <WatchedTick />}
        {!item.watched && item.progress ? <CardProgress pct={Math.round(item.progress * 100)} /> : null}
      </CardPicture>
      <CardTitle {...links.title} />
      <CardLine segments={links.line} className={show ? 'text-neutral-300' : 'text-neutral-400'} />
      {second && <CardLine segments={[{ text: second }]} />}
      {role && <CardLine segments={[{ text: role }]} />}
    </div>
  )
}

const cellBase = 'shrink-0 truncate text-[13.5px]'
const cell = `${cellBase} text-neutral-300`

/**
 * A library as a list: one row a title, each a link to it, with the facts a
 * viewer sorts by. Narrow windows keep the title, the length or next episode
 * and where the person stands. columns adds Quality and Size.
 */
export function BrowseList({ items, type, columns = {} }: { items: BrowseItem[]; type: 'movie' | 'tv'; columns?: { quality?: boolean; size?: boolean } }) {
  const head = 'shrink-0 truncate'
  return (
    <div>
      <div aria-hidden="true" className="flex items-center gap-3 border-b border-line-2 px-2.5 pb-2 md:gap-4 text-xs font-semibold uppercase tracking-[.04em] text-dim">
        <span className="w-9 shrink-0 md:w-11" />
        <span className="min-w-0 flex-1">{type === 'tv' ? 'Show' : 'Title'}</span>
        {type === 'movie' ? (
          <>
            <span className={`${head} w-[84px] max-sm:w-[68px]`}>Length</span>
            <span className={`${head} w-16 max-md:hidden`}>Rated</span>
            <span className={`${head} w-[190px] max-lg:hidden`}>Genres</span>
            <span className={`${head} w-14 max-md:hidden`}>Rating</span>
            <span className={`${head} w-[110px] max-md:hidden`}>Added</span>
          </>
        ) : (
          <>
            <span className={`${head} w-24 max-md:hidden`}>Seasons</span>
            <span className={`${head} w-[130px] max-sm:w-[96px]`}>Left to watch</span>
            <span className={`${head} w-[170px] max-sm:hidden`}>Next episode</span>
            <span className={`${head} w-[120px] max-md:hidden`}>Last aired</span>
            <span className={`${head} w-14 max-md:hidden`}>Rating</span>
          </>
        )}
        {columns.quality && <span className={`${head} w-24 max-md:hidden`}>Quality</span>}
        {columns.size && <span className={`${head} w-20 text-right max-md:hidden`}>Size</span>}
        {type === 'movie' && <span className={`${head} w-[110px] max-sm:w-[84px]`}>You</span>}
      </div>
      <ul>
        {items.map((it, i) => (
          <li key={`${it.kind}-${it.id}`} data-row={i} className="scroll-mt-24 border-t border-surface-2 first:border-t-0">
            <ListRow item={it} columns={columns} type={type} />
          </li>
        ))}
      </ul>
    </div>
  )
}

function ListRow({ item, columns, type }: { item: BrowseItem; columns: { quality?: boolean; size?: boolean }; type: 'movie' | 'tv' }) {
  const you = youWords(item)
  const left = item.kind === 'show' ? leftToWatch(item) : undefined
  const now = useNow(60 * 60_000)
  const body = (
    <>
      <span className="w-9 shrink-0 md:w-11">
        <Poster path={item.posterPath} size="w92" alt="" className="rounded-[5px]" />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-sm font-medium text-neutral-100 group-hover:text-accent">{item.title}</span>
        <span className="block truncate text-[12.5px] text-neutral-400">
          {item.kind === 'unmatched' ? item.fileName : item.kind === 'show' ? item.genres.slice(0, 2).join(', ') || item.year || '' : item.year || ''}
        </span>
      </span>
      {item.kind === 'movie' && (
        <>
          <span className={`${cell} w-[84px] max-sm:w-[68px]`}>{item.runtimeMin ? formatRuntime(item.runtimeMin) : '—'}</span>
          <span className={`${cell} w-16 max-md:hidden`}>{item.contentRating || '—'}</span>
          <span className={`${cell} w-[190px] max-lg:hidden`}>{item.genres.slice(0, 2).join(', ') || '—'}</span>
          <span className={`${cell} w-14 max-md:hidden`}>{scoreWords(item.rating) || '—'}</span>
          <span className={`${cell} w-[110px] max-md:hidden`}>{addedWords(item.addedAt, now) || '—'}</span>
        </>
      )}
      {item.kind === 'show' && (
        <>
          <span className={`${cell} w-24 max-md:hidden`}>{item.seasonCount ? `${item.seasonCount} ${item.seasonCount === 1 ? 'season' : 'seasons'}` : '—'}</span>
          <span className={`${cellBase} w-[130px] max-sm:w-[96px] ${left?.left ? 'text-neutral-100' : 'text-neutral-400'}`}>{left?.text ?? '—'}</span>
          <span className={`${cell} w-[170px] max-sm:hidden`}>{showStatusWords(item) || '—'}</span>
          <span className={`${cell} w-[120px] max-md:hidden`}>{item.lastAirDate ? formatDate(item.lastAirDate) : '—'}</span>
          <span className={`${cell} w-14 max-md:hidden`}>{scoreWords(item.rating) || '—'}</span>
        </>
      )}
      {item.kind === 'unmatched' && <span className={`${cell} w-[170px] text-warn`}>{item.subtitle}</span>}
      {columns.quality && <span className={`${cell} w-24 max-md:hidden`}>{qualityWords(item.quality, item.hdr) || '—'}</span>}
      {columns.size && <span className={`${cell} w-20 text-right tabular-nums max-md:hidden`}>{item.totalSize ? formatBytes(item.totalSize) : '—'}</span>}
      {type === 'movie' && <span className={`${cellBase} w-[110px] max-sm:w-[84px] ${you?.gold ? 'text-accent' : 'text-neutral-400'}`}>{you?.text ?? ''}</span>}
    </>
  )
  const row = 'group flex items-center gap-3 rounded-[10px] px-2.5 py-2 hover:bg-surface md:gap-4'
  if (item.kind === 'movie')
    return (
      <Link to="/movies/$movieId" params={{ movieId: String(item.id) }} className={row}>
        {body}
      </Link>
    )
  if (item.kind === 'show')
    return (
      <Link to="/shows/$showId" params={{ showId: String(item.id) }} className={row}>
        {body}
      </Link>
    )
  return (
    <Link to={item.libraryType === 'tv' ? '/tv' : '/movies'} search={{ view: 'unmatched' }} className={row}>
      {body}
    </Link>
  )
}
