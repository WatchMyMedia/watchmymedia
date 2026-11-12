import { Link } from '@tanstack/react-router'
import type { ReactNode } from 'react'
import type { CardSegment, CardTarget } from '../lib/cardLinks'

// The pieces a row card is drawn from (PLAN-HOME.md §6.1, board HomeCards):
// a picture, a title and a line, each part a link of its own. Link parts
// underline in gold on hover; the picture keeps the card's gold ring.

/**
 * The picture, a bigger pointer target for the line's most specific link.
 * It is not a tab stop and says nothing to a screen reader: the title and
 * the line's links read as the card's destinations.
 */
export function CardPicture({ to, label, children }: { to: CardTarget; label: string; children: ReactNode }) {
  return (
    <Link {...to} tabIndex={-1} aria-hidden="true" title={label} className="card group relative block">
      {children}
    </Link>
  )
}

/** The first line, linked. */
export function CardTitle({ text, to, label }: CardSegment) {
  return (
    <div className="mt-1.5 truncate text-sm font-medium">
      {to ? <CardLink to={to} label={label} text={text} /> : text}
    </div>
  )
}

/** The second or third line: runs of text, those with a target as links. The line never wraps. */
export function CardLine({ segments, className = 'text-neutral-500' }: { segments: CardSegment[]; className?: string }) {
  if (!segments.length) return null
  return (
    <div className={`truncate text-xs ${className}`}>
      {segments.map((s, i) => (s.to ? <CardLink key={i} to={s.to} label={s.label} text={s.text} className="font-medium text-neutral-300" /> : <span key={i}>{s.text}</span>))}
    </div>
  )
}

function CardLink({ to, label, text, className = '' }: { to: CardTarget; label?: string; text: string; className?: string }) {
  return (
    <Link {...to} aria-label={label} className={`rounded-sm decoration-[1.5px] underline-offset-[3px] hover:text-accent hover:underline focus-visible:text-accent focus-visible:underline ${className}`}>
      {text}
    </Link>
  )
}
