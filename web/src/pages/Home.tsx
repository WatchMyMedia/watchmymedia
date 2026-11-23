import { useQuery } from '@tanstack/react-query'
import { healthQuery, homeQuery, librariesQuery, liveTVCapabilitiesQuery } from '../api/queries'
import { Link, useNavigate } from '@tanstack/react-router'
import { useSettingsAccess } from './settings/shared'
import { Hero } from '../components/Hero'
import { ContinueFeature } from '../components/home/ContinueFeature'
import { HomeNotices } from '../components/home/HomeNotices'
import { OnNowRow } from '../components/home/OnNowRow'
import { EditRows } from '../components/rows/EditRowsBar'
import { ScreenRows } from '../components/rows/ScreenRows'
import type { EditSearch } from '../lib/routeSearch'
import { drawable, homeRows, titleParts } from '../lib/screenRows'

/**
 * Home: what happened on the server, then Continue watching leading the page
 * (or the server's pick when nothing is in progress), the person's rows with
 * Live TV's On now where they put it, then Edit rows and the totals. With
 * ?edit, Edit rows in their place.
 */
export function Home({ search = {} }: { search?: EditSearch }) {
  const home = useQuery(homeQuery)
  const { admin } = useSettingsAccess()
  const health = useQuery({ ...healthQuery, enabled: admin })
  const caps = useQuery(liveTVCapabilitiesQuery)
  // Everyone reads libraries (the header's links do): See all needs their types.
  const libraries = useQuery(librariesQuery)
  const navigate = useNavigate()
  if (home.isPending) return <p className="text-neutral-400">Loading…</p>
  if (home.isError) return <p className="text-red-400">{home.error.message}</p>
  const h = home.data
  const rows = homeRows(h)
  const live = !!caps.data?.enabled && !!caps.data.allowed
  const continueRow = rows.find((r) => r.style === 'continue' && r.continue?.length)
  const empty = !rows.some(drawable)
  const libs = libraries.data?.items ?? []
  const totals = [`${h.totals.movies.toLocaleString()} movies`, `${h.totals.shows.toLocaleString()} shows`, `${h.totals.episodes.toLocaleString()} episodes`].join(' · ')
  if (search.edit)
    return (
      <EditRows
        screen="home"
        screenName="Home"
        adding={!!search.add}
        onAdding={(open) => void navigate({ to: '/', search: { edit: true, add: open || undefined }, replace: true })}
        onMakeList={() => void navigate({ to: '/lists/new', search: { screen: 'home' } })}
        onClose={() => void navigate({ to: '/', search: {} })}
        above={['Continue watching, or the newest episode or film when nothing is in progress, always at the top', 'always at the top']}
        below={['Totals · movies, shows and episodes, always at the bottom', 'always at the bottom']}
      />
    )
  return (
    <div className="flex flex-col">
      {admin && health.data && !health.data.tmdbConfigured && !!libs.length && (
        <div className="mb-5 rounded-xl border border-warn/40 bg-warn/10 p-4 text-sm text-neutral-300">
          <p>
            <strong className="text-neutral-100">Posters and title details are off.</strong> Add a TMDB key in{' '}
            <Link to="/settings/general" className="underline">
              Settings › General
            </Link>
            .
          </p>
          {empty && (
            <p className="mt-2">
              To watch files now, open a library from{' '}
              <Link to="/settings/libraries" className="underline">
                Settings › Libraries
              </Link>
              .
            </p>
          )}
        </div>
      )}
      <HomeNotices />
      <div className="mt-[22px]">{continueRow?.continue ? <ContinueFeature items={continueRow.continue} /> : h.hero && <Hero hero={h.hero} />}</div>
      {empty && !live && (
        <p className="mt-8 text-neutral-400">
          Nothing to show yet. Once files are scanned and matched on TMDB, new arrivals and recently aired episodes appear here.{admin ? ' Library status in your account menu says how the scan is going.' : ''}
        </p>
      )}
      <ScreenRows
        rows={rows}
        libraries={libs}
        skip={continueRow ? [continueRow.id] : []}
        live={live ? (row) => <OnNowRow title={titleParts(row)[0] || 'On now'} /> : undefined}
        className="mt-8 empty:hidden md:mt-10"
      />
      <div className="mt-10 flex flex-wrap items-center gap-x-3.5 gap-y-2.5 border-t border-line pt-5">
        <Link to="/" search={{ edit: true }} className="btn">
          Edit rows
        </Link>
        <span className="text-[13px] text-neutral-400">Move, hide and add rows. Your order follows you to every device.</span>
        <span className="text-[13px] text-dim md:ml-auto">{totals}</span>
      </div>
    </div>
  )
}
