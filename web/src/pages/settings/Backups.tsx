import { useSettingsPage } from '../../components/settings/hooks'
import { ServerOnly } from './SettingsLayout'
import { BackupsHub } from './backups/Hub'
import { RestorePage } from './backups/Restore'

/** Settings › Server › Backups: the page and Restore from a backup. */
export function BackupsSettings() {
  const page = useSettingsPage()
  return <ServerOnly tab="backups">{page?.task === 'restore' ? <RestorePage /> : <BackupsHub />}</ServerOnly>
}
