import { useQuery } from '@tanstack/react-query'
import { movieRelatedQuery, showRelatedQuery } from '../../api/queries'
import { relatedRows } from '../../lib/screenRows'
import { ScreenRows } from './ScreenRows'

/**
 * The end of a movie's or a show's page: the movie's collection
 * and More like this, drawn as Home's poster rows, under a line. Draw it once
 * the page's details have loaded; its rows arrive after, below the fold, and
 * nothing is drawn while they load, when none qualify or when the request
 * fails.
 */
export function RelatedRows({ kind, id }: { kind: 'movie' | 'show'; id: number }) {
  const related = useQuery(kind === 'movie' ? movieRelatedQuery(id) : showRelatedQuery(id))
  const rows = relatedRows(related.data)
  if (rows.length === 0) return null
  return (
    <div className="mt-9 space-y-8 border-t border-line pt-6 sm:mt-12 sm:space-y-10 sm:pt-8">
      {/* No libraries: the rows have no See all. */}
      <ScreenRows rows={rows} libraries={[]} />
    </div>
  )
}
