import { Link, type LinkProps } from '@tanstack/react-router'
import type { SeeAllWords } from '../../lib/screenRows'

/**
 * A row's last card: the size of the row's pictures, with no
 * title under it, a grid glyph for a library or the list glyph for a list,
 * and the card's two lines. It goes where the heading's See all goes.
 */
export function SeeAllCard({ link, words, shape, list }: { link: LinkProps; words: SeeAllWords; shape: 'poster' | 'wide'; list: boolean }) {
  const size = shape === 'poster' ? 'aspect-[2/3] w-[168px]' : 'aspect-video w-[304px]'
  return (
    <Link
      {...link}
      aria-label={words.label}
      className={`card flex shrink-0 snap-start flex-col items-center justify-center gap-1.5 self-start border border-line-2 bg-surface-2 px-2.5 text-center ${size}`}
    >
      <span className="grid h-9 w-9 place-items-center rounded-full bg-accent/20 text-accent">{list ? <ListGlyph /> : <GridGlyph />}</span>
      <span className="text-[15px] font-semibold leading-tight text-balance">{words.title}</span>
      {words.detail && <span className="text-[13px] leading-tight text-neutral-400">{words.detail}</span>}
    </Link>
  )
}

function GridGlyph() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="4" y="4" width="6.5" height="6.5" rx="1.5" />
      <rect x="13.5" y="4" width="6.5" height="6.5" rx="1.5" />
      <rect x="4" y="13.5" width="6.5" height="6.5" rx="1.5" />
      <rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.5" />
    </svg>
  )
}

function ListGlyph() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M9 6h11M9 12h11M9 18h11" />
      <circle cx="4.5" cy="6" r="1" />
      <circle cx="4.5" cy="12" r="1" />
      <circle cx="4.5" cy="18" r="1" />
    </svg>
  )
}
