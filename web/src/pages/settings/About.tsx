import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { healthQuery } from '../../api/queries'
import { Button, Card, HubPage, ItemRow, Message } from '../../components/settings/Frame'
import { settingsCrumbs } from '../../lib/settingsRoutes'
import { versionChanged } from '../../lib/youSettings'

const link = 'text-neutral-200 underline'

/**
 * Settings › You › About, for everyone: the version, and where titles,
 * artwork and lists come from. TMDB's terms ask for its notice here
 * (PLAN-ROWS.md §3, Decision 7); its logo is still to add beside it.
 *
 * The version is read every 30 seconds. When it changes while the page is
 * open, the server was updated and this web app with it, so the page offers
 * to reload (PLAN-ADMIN-SETTINGS.md §5.10).
 */
export function AboutSettings() {
  const health = useQuery(healthQuery)
  const version = health.data?.version
  const [first, setFirst] = useState(version)
  if (first === undefined && version !== undefined) setFirst(version)
  const updated = versionChanged(first, version)
  return (
    <HubPage
      crumbs={settingsCrumbs('about')}
      title="About"
      lede="This server’s version, and where its titles, artwork and lists come from."
      banner={
        <>
          {updated && (
            <Message title={`The server is now ${version}`} actions={<Button small onClick={() => window.location.reload()}>Reload</Button>}>
              It was updated while this page was open. Reload to use the new version of the web app.
            </Message>
          )}
          {health.isError && (
            <Message tone="err" title="Couldn’t reach the server">The version shows again when it answers. Check your connection.</Message>
          )}
        </>
      }
    >
      <Card>
        <ItemRow
          title="WatchMyMedia"
          detail={
            <>
              {version ? <>Version <code className="text-neutral-200">{version}</code></> : health.isError ? 'Version not known while the server doesn’t answer' : 'Version loading'} ·{' '}
              <a href="https://watchmy.media" target="_blank" rel="noreferrer" className={link}>watchmy.media</a>
            </>
          }
        />
        <ItemRow
          title="The Movie Database (TMDB)"
          detail={
            <>
              Titles, artwork, episode details and many lists come from TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB.{' '}
              <a href="https://www.themoviedb.org" target="_blank" rel="noreferrer" className={link}>themoviedb.org</a>
            </>
          }
        />
        <ItemRow
          title="MDBList"
          detail={
            <>
              Lists that say From MDBList come from mdblist.com, with the server admin’s own key.{' '}
              <a href="https://mdblist.com" target="_blank" rel="noreferrer" className={link}>mdblist.com</a>
            </>
          }
        />
      </Card>
    </HubPage>
  )
}
