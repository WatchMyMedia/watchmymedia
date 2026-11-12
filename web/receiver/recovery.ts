import { TransferMeter } from '../src/lib/play/recovery'

type Transfer = Pick<PerformanceResourceTiming, 'name' | 'startTime' | 'responseStart' | 'responseEnd' | 'encodedBodySize' | 'transferSize'>

function recordBody(meter: TransferMeter, entry: Transfer) {
  // Cached or opaque responses cannot prove network capacity. Keep encoder
  // preparation/round-trip wait out of a completed body's transfer time.
  if (!(entry.transferSize > 0) || !(entry.encodedBodySize > 0) ||
    !(entry.responseStart > 0) || entry.responseStart < entry.startTime) return
  meter.record(entry.encodedBodySize, entry.responseStart, entry.responseEnd)
}

/** Only this play's completed media segments count as link evidence. */
export function recordCastTransfer(meter: TransferMeter, base: string, sessionId: string | undefined, entry: Transfer): void {
  if (!sessionId || !entry.name.startsWith(`${base}/api/playback/${sessionId}/`) ||
    !/\/v\d+\.m4s(?:\?|$)/.test(entry.name)) return
  // A small separate audio response must not overwrite the video estimate.
  recordBody(meter, entry)
}

/** Count only a completed segment for this live output generation. */
export function recordLiveCastTransfer(meter: TransferMeter, base: string, outputURL: string | undefined, entry: Transfer): void {
  const id = outputURL?.match(/\/api\/live-tv\/sessions\/([^/]+)\/hls\/live\.m3u8(?:\?|$)/)?.[1]
  if (!id || !entry.name.startsWith(`${base}/api/live-tv/sessions/${id}/hls/`) ||
    !/\/(?:segment-\d+\.(?:ts|m4s)|(?:low|high)-segment-\d+\.m4s)(?:\?|$)/.test(entry.name)) return
  recordBody(meter, entry)
}

export function castRecoveryAvailable(apiLevel: number): boolean {
  return apiLevel >= 24 && typeof PerformanceObserver !== 'undefined' && PerformanceObserver.supportedEntryTypes.includes('resource')
}
