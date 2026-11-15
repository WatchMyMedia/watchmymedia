import { useSettingsPage } from '../../components/settings/hooks'
import { ServerOnly } from './SettingsLayout'
import { LiveTVHub } from './liveTV/Hub'
import { AddTunerFlow } from './liveTV/AddTuner'
import { TunerPage } from './liveTV/Tuner'
import { EditTunerFlow } from './liveTV/EditTuner'
import { AccessFlow } from './liveTV/Access'
import { GuidePage, GuideSetupFlow } from './liveTV/Guide'
import { RecordingsPage, RecordingsSetupFlow } from './liveTV/Recordings'
import { ChannelsPage } from './liveTV/Channels'

/**
 * Settings › Live TV and every page under it (PLAN-ADMIN-SETTINGS.md §4,
 * §5.4). Flows draw their own frame, which keeps members and admins the gate
 * keeps out; the hub and item pages sit inside ServerOnly.
 */
export function LiveTVSettings() {
  const page = useSettingsPage()
  const { task = '', params = {} } = page?.tab === 'live-tv' ? page : {}
  switch (task) {
    case 'add-tuner': return <AddTunerFlow step={params.step} rest={params.rest} />
    case 'edit-tuner': return <EditTunerFlow id={params.id} />
    case 'access': return <AccessFlow />
    case 'guide-setup': return <GuideSetupFlow step={params.step} />
    case 'recordings-setup': return <RecordingsSetupFlow step={params.step} />
  }
  return (
    <ServerOnly tab="live-tv">
      {task === 'tuner' ? <TunerPage id={params.id} />
        : task === 'guide' ? <GuidePage />
          : task === 'recordings' ? <RecordingsPage />
            : task === 'channels' ? <ChannelsPage />
              : <LiveTVHub />}
    </ServerOnly>
  )
}
