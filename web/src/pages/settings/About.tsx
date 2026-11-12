import { useQuery } from '@tanstack/react-query'
import { healthQuery } from '../../api/queries'
import { PageHeader, Panel } from '../../components/SettingsPanel'

const link = 'text-neutral-200 underline'

/**
 * Settings › About, for everyone: the version, and where titles, artwork and
 * lists come from. TMDB's terms ask for its notice here (PLAN-ROWS.md §3,
 * Decision 7); its logo is still to add beside it.
 */
export function AboutSettings() {
  const health = useQuery(healthQuery)
  return (
    <>
      <PageHeader title="About">This server’s version, and where its titles, artwork and lists come from.</PageHeader>
      <div className="space-y-6">
        <Panel title="WatchMyMedia">
          <p className="px-5 pb-4 text-sm text-neutral-400">
            Version <code className="text-neutral-200">{health.data?.version ?? '…'}</code> ·{' '}
            <a href="https://watchmy.media" target="_blank" rel="noreferrer" className={link}>
              watchmy.media
            </a>
          </p>
        </Panel>
        <Panel title="The Movie Database (TMDB)" description="Titles, artwork, episode details and many lists come from TMDB.">
          <p className="px-5 text-sm text-neutral-200">This product uses the TMDB API but is not endorsed or certified by TMDB.</p>
          <p className="px-5 pb-4 pt-2 text-sm">
            <a href="https://www.themoviedb.org" target="_blank" rel="noreferrer" className={link}>
              themoviedb.org
            </a>
          </p>
        </Panel>
        <Panel title="MDBList" description="Lists that say From MDBList come from mdblist.com, with the server admin’s own key.">
          <p className="px-5 pb-4 text-sm">
            <a href="https://mdblist.com" target="_blank" rel="noreferrer" className={link}>
              mdblist.com
            </a>
          </p>
        </Panel>
      </div>
    </>
  )
}
