import { useSettingsPage } from '../../components/settings/hooks'
import { ServerOnly } from './SettingsLayout'
import { EncoderFlow } from './transcoding/EncoderFlow'
import { TranscodingHub } from './transcoding/Hub'
import { UploadLimitPage } from './transcoding/UploadLimit'

/**
 * Settings › Server › Transcoding: the page,
 * the Choose an encoder flow and the Remote upload limit page.
 */
export function TranscodingSettings() {
  const page = useSettingsPage()
  if (page?.task === 'encoder') return <EncoderFlow step={page.params.step} />
  return <ServerOnly tab="transcoding">{page?.task === 'upload-limit' ? <UploadLimitPage /> : <TranscodingHub />}</ServerOnly>
}
