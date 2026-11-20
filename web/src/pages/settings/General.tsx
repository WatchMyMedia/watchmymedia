import { useSettingsPage } from '../../components/settings/hooks'
import { ServerOnly } from './SettingsLayout'
import { GeneralHub } from './general/Hub'
import { TmdbFlow } from './general/TmdbFlow'

/** Settings › Server › General: the page and the Add a TMDB key task. */
export function GeneralSettings() {
  const page = useSettingsPage()
  if (page?.task === 'tmdb') return <TmdbFlow />
  return <ServerOnly tab="general"><GeneralHub /></ServerOnly>
}
