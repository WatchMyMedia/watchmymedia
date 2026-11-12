import { Link } from '@tanstack/react-router'
import { PageHeader } from '../../components/SettingsPanel'
import { ActivitySection } from '../../components/SettingsSections'
import { ServerOnly } from './SettingsLayout'

/** Settings › Watch history: which plays are kept, for how long, and who sees what is playing. */
export function HistorySettings() {
  return (
    <>
      <PageHeader title="Watch history">
        Every play is recorded on this server: who watched what, on which device, from which address, and how it streamed. Admins see everyone&rsquo;s; other people see only their own. Nothing leaves the
        server.
      </PageHeader>
      <ServerOnly>
        <ActivitySection />
        <p className="mt-4 text-sm text-neutral-400">
          Whose plays are recorded is set per person under{' '}
          <Link to="/settings/users" className="text-neutral-200 underline">
            Users
          </Link>
          .
        </p>
      </ServerOnly>
    </>
  )
}
