import { useSettingsPage } from '../../components/settings/hooks'
import { ServerOnly } from './SettingsLayout'
import { BackupsHub } from './backups/Hub'
import { RestorePage } from './backups/Restore'

/** Settings › Server › Backups (PLAN-ADMIN-SETTINGS.md §5.8): the page and Restore from a backup. */
export function BackupsSettings() {
  const page = useSettingsPage()
  return <ServerOnly tab="backups">{page?.task === 'restore' ? <RestorePage /> : <BackupsHub />}</ServerOnly>
}
