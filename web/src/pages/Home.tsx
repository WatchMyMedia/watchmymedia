import { useQuery } from '@tanstack/react-query'
import { healthQuery, homeQuery, librariesQuery } from '../api/queries'
import { Link, useNavigate } from '@tanstack/react-router'
import { useSettingsAccess } from './settings/shared'
import { Hero } from '../components/Hero'
import { EditRows, EditRowsBar } from '../components/rows/EditRowsBar'
import { ScreenRows } from '../components/rows/ScreenRows'
import { formatBytes, relativeTime } from '../lib/format'
import type { EditSearch } from '../lib/routeSearch'
import { drawable, homeRows } from '../lib/screenRows'

/** Home: the hero, the person's rows, then the totals; or, with ?edit, Edit rows in their place. */
export function Home({ search = {} }: { search?: EditSearch }) {
  const home = useQuery(homeQuery)
  const { admin } = useSettingsAccess()
  const health = useQuery({ ...healthQuery, enabled: admin })
  // Everyone reads libraries (the header's links do): See all needs their types.
  const libraries = useQuery(librariesQuery)
  const navigate = useNavigate()
  if (home.isPending) return <p className="text-neutral-400">Loading…</p>
  if (home.isError) return <p className="text-red-400">{home.error.message}</p>
  const h = home.data
  const rows = homeRows(h)
  const empty = !rows.some(drawable)
  const libs = libraries.data?.items ?? []
  const totals = `${h.totals.movies} movies · ${h.totals.shows} shows · ${h.totals.episodes} episodes · ${h.totals.files} files · ${formatBytes(h.totals.totalSize)} · last scan ${relativeTime(h.lastScannedAt)}`
  if (search.edit)
    return (
      <EditRows
        screen="home"
        screenName="Home"
        adding={!!search.add}
        onAdding={(open) => void navigate({ to: '/', search: { edit: true, add: open || undefined }, replace: true })}
        onMakeList={() => void navigate({ to: '/lists/new', search: { screen: 'home' } })}
        onClose={() => void navigate({ to: '/', search: {} })}
        above={['Featured · the newest episode or film, always at the top', 'always at the top']}
        below={['Totals · movies, shows, files and the last scan, always at the bottom', 'always at the bottom']}
      />
    )
  return (
    <div className="space-y-10">
      {admin && health.data && !health.data.tmdbConfigured && !!libs.length && <div className="rounded-lg border border-warn/40 bg-warn/10 p-4 text-sm text-neutral-300">
        <p><strong className="text-neutral-100">Posters and title details are off.</strong> Add a TMDB key in <Link to="/settings/general" className="underline">Settings › General</Link>.</p>
        {empty && <p className="mt-2">To watch files now, open a library from <Link to="/settings/libraries" className="underline">Settings › Libraries</Link>.</p>}
      </div>}
      {h.hero && <Hero hero={h.hero} />}
      {empty && (
        <p className="text-neutral-400">
          Nothing to show yet. Once files are scanned and matched on TMDB, new arrivals and recently aired episodes appear here. Use the ⟳ button for scan status.
        </p>
      )}
      <ScreenRows rows={rows} libraries={libs} />
      <EditRowsBar onEdit={() => void navigate({ to: '/', search: { edit: true } })}>Move, hide and add rows. Your order follows you to every device.</EditRowsBar>
      <footer className="border-t border-line pt-4 text-xs text-neutral-500">{totals}</footer>
    </div>
  )
}
