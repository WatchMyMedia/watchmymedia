import type { components } from '../../../api/schema'
import { hardwareState } from '../../../lib/transcoder'

type Settings = components['schemas']['Settings']

/** Ask again every 2 seconds while the encoder test or the GPU names are being checked. */
export function hardwarePoll(q: { state: { data?: Settings } }): number | false {
  const s = q.state.data
  return s?.hardwareAdapters?.checking || hardwareState(s?.hwaccel ?? 'auto', s?.capabilities) === 'checking' ? 2000 : false
}
