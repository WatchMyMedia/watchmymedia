import { PlaybackSection } from '../../components/PlaybackSection'
import { PageHeader } from '../../components/SettingsPanel'

/** Settings › Playback: how this person's episodes skip and carry on, on every device. */
export function PlaybackSettings() {
  return (
    <>
      <PageHeader title="Playback">
        Intros, recaps and credits are found from chapter marks and by comparing audio across a season. These settings are yours and apply on every device.
      </PageHeader>
      <PlaybackSection />
      <p className="mt-4 text-sm text-neutral-400">Changes save as you make them.</p>
    </>
  )
}
