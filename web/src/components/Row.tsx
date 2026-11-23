import type { ReactNode } from 'react'
import { Link, type LinkProps } from '@tanstack/react-router'

/** Horizontal scroller with heading, muted sub-label and "See all ›", read aloud as seeAllLabel. */
export function Row({
  title,
  subtitle,
  seeAll,
  seeAllLabel,
  children,
}: {
  title: string
  subtitle?: ReactNode
  seeAll?: LinkProps
  seeAllLabel?: string
  children: ReactNode
}) {
  return (
    <section>
      <div className="rowh">
        <h2>{title}</h2>
        {subtitle && <span className="note">{subtitle}</span>}
        {seeAll && (
          <Link {...seeAll} aria-label={seeAllLabel} className="more">
            See all ›
          </Link>
        )}
      </div>
      <div className="scrollbar-hide -mx-1 mt-3.5 flex snap-x snap-mandatory gap-3 overflow-x-auto px-1 pb-1.5 pt-1 md:gap-4">{children}</div>
    </section>
  )
}
