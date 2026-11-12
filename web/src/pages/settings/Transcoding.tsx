import { PageHeader } from '../../components/SettingsPanel'
import { TranscodeSection } from '../../components/SettingsSections'
import { StreamingLimits } from '../../components/StreamingLimits'
import { ServerOnly } from './SettingsLayout'

/** Settings › Transcoding: the encoder ffmpeg uses and what this machine can do. */
export function TranscodingSettings() {
  return (
    <>
      <PageHeader title="Transcoding">When a device can&rsquo;t play a file as it is, ffmpeg converts it while it streams. A hardware encoder keeps that fast and quiet.</PageHeader>
      <ServerOnly>
        <div className="space-y-6"><TranscodeSection /><StreamingLimits /></div>
      </ServerOnly>
    </>
  )
}
