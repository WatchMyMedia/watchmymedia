import type { ReactNode } from 'react'
import { Link, type LinkProps } from '@tanstack/react-router'

/** Horizontal scroller with heading, muted sub-label and "See all ›". */
export function Row({
  title,
  subtitle,
  seeAll,
  children,
}: {
  title: string
  subtitle?: ReactNode
  seeAll?: LinkProps
  children: ReactNode
}) {
  return (
    <section className="space-y-3">
      <div className="flex items-baseline justify-between gap-4">
        <h2 className="text-lg font-semibold">
          {title}
          {subtitle && <span className="ml-2 text-sm font-normal text-neutral-500">{subtitle}</span>}
        </h2>
        {seeAll && (
          <Link {...seeAll} className="text-sm text-neutral-400 hover:text-neutral-100">
            See all ›
          </Link>
        )}
      </div>
      <div className="scrollbar-hide -mx-1 flex snap-x snap-mandatory gap-4 overflow-x-auto px-1 pb-2 pt-1">{children}</div>
    </section>
  )
}
