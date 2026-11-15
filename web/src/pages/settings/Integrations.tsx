import { useEffect } from 'react'
import { useGo, useSettingsPage } from '../../components/settings/hooks'
import { settingsHref } from '../../lib/settingsRoutes'
import { AddArrFlow, SeerrFlow } from './integrations/AddFlow'
import { ConnectionPage } from './integrations/ConnectionPage'
import { IntegrationsHub } from './integrations/Hub'
import { ConnectionTaskPage } from './integrations/Task'
import { ServerOnly } from './SettingsLayout'

/**
 * Settings › Integrations (PLAN-ADMIN-SETTINGS.md §5.2, PLAN-ARR.md): the
 * hub, one connection's page, Add Radarr or Sonarr, Connect Seerr, and each
 * of a connection's steps by itself.
 */
export function IntegrationsSettings() {
  const page = useSettingsPage()
  const go = useGo()
  const app = page?.params.app
  const id = Number(page?.params.id)
  const known = page?.task !== 'add' || app === 'radarr' || app === 'sonarr' || app === 'seerr'
  useEffect(() => {
    if (!known) go(settingsHref('integrations'), { replace: true })
  }, [known, go])

  if (!page || page.tab !== 'integrations' || page.kind === 'hub' || !known) {
    return <ServerOnly tab="integrations"><IntegrationsHub /></ServerOnly>
  }
  if (page.kind === 'item') return <ServerOnly tab="integrations"><ConnectionPage id={id} /></ServerOnly>
  if (page.task === 'add') return app === 'seerr' ? <SeerrFlow step={page.params.step} /> : <AddArrFlow kind={app as 'radarr' | 'sonarr'} step={page.params.step} />
  return <ConnectionTaskPage task={page.task} id={id} />
}
