import { PageHeader } from '../../components/SettingsPanel'
import { BackupSection } from '../../components/SettingsSections'
import { ServerOnly } from './SettingsLayout'

/** Settings › Backups: nightly backups, the ones on disk, and how to restore one. */
export function BackupsSettings() {
  return (
    <>
      <PageHeader title="Backups">
        A backup holds the database (libraries, matches, users, watch history) and <code className="text-neutral-200">config.yaml</code>; artwork is fetched again.
      </PageHeader>
      <ServerOnly>
        <BackupSection />
      </ServerOnly>
    </>
  )
}
