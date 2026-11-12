/** Server level 27 admits complete families; older receivers omit the input. */
export const ADAPTIVE_LEVEL = 27
export type AdaptiveOffer = { renditions: readonly { sessionId?: string; width: number; height: number; bitrate: number }[] }

export function requestAdaptive(level: number | undefined, quality: string, native = false): boolean {
  return (level ?? 0) >= ADAPTIVE_LEVEL && quality === 'auto' && !native
}

/** Call only for the fragment at the playhead or CAF's current video height.
 * A downloaded/requested variant or bandwidth estimate is not an observation. */
export function observedRendition(offer: AdaptiveOffer | null | undefined, height: number | undefined) {
  if (!Number.isInteger(height) || !height || height <= 0) return undefined
  const rung = offer?.renditions.find((r) => r.height === height && r.bitrate > 0)
  return rung ? { renditionHeight: rung.height, renditionBitrate: rung.bitrate } : undefined
}

/** One failed family may reload a single rendition in this logical play.
 * New track/seek requests retain this backoff; a new viewing gets a new budget. */
export class AdaptiveFallback {
  private used = false
  get enabled() { return !this.used }
  take(offer: AdaptiveOffer | null | undefined): boolean {
    if (!offer || this.used) return false
    this.used = true
    return true
  }
}

/** What a fatal player error does. A switch's own output failing goes back to
 * the output it replaced, family or not; only otherwise does an offered family
 * take its single-output fallback. Anything else is a failure. */
export function failureRoute(switchPending: boolean, familyFallback: boolean): 'switch' | 'family' | 'fail' {
  return switchPending ? 'switch' : familyFallback ? 'family' : 'fail'
}

export function adaptiveRestrictions(offer: AdaptiveOffer | null | undefined) {
  if (!offer) return { maxHeight: Infinity, maxBandwidth: Infinity }
  return { maxHeight: Math.max(...offer.renditions.map((r) => r.height)),
    maxBandwidth: Math.max(...offer.renditions.map((r) => r.bitrate)) }
}
