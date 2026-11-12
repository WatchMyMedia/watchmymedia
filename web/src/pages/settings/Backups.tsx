import { PageHeader } from '../../components/SettingsPanel'
import { BackupSection } from '../../components/SettingsSections'
import { ServerOnly } from './SettingsLayout'

/** Settings › Backups: nightly backups, the ones on disk, and how to restore one. */
export function BackupsSettings() {
  return (
    <>
      <PageHeader title="Backups">
        A backup holds the database (libraries, matches, accounts, watch history) and <code className="text-neutral-200">config.yaml</code>; artwork is fetched again. It keeps no one signed in: after a restore, everyone signs in again.
      </PageHeader>
      <ServerOnly>
        <BackupSection />
      </ServerOnly>
    </>
  )
}
