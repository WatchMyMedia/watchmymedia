import { useEffect, useState, type RefObject } from 'react'
import { api, unwrap } from '../api/client'

/**
 * AirPlay from Safari (PLAN-CAST.md §5.3). AirPlay hands the Apple TV a
 * URL, which it fetches itself, so it cannot carry an MSE stream (hls.js)
 * or the session cookie. Where AirPlay exists, the player plays native HLS
 * from a VOD playlist and puts a media token in every URL.
 */

type WebKitVideo = HTMLVideoElement & {
  webkitShowPlaybackTargetPicker?: () => void
  webkitCurrentPlaybackTargetIsWireless?: boolean
}

let native: boolean | undefined

/** Safari, on a Mac, iPhone or iPad: AirPlay is possible and native HLS plays. */
export function prefersNativeHls(): boolean {
  native ??=
    typeof window !== 'undefined' &&
    'WebKitPlaybackTargetAvailabilityEvent' in window &&
    document.createElement('video').canPlayType('application/vnd.apple.mpegurl') !== ''
  return native
}

let cached: { token: string; until: number } | null = null

/** A media token for URLs the Apple TV fetches, renewed well before it lapses. */
export async function mediaToken(): Promise<string> {
  if (cached && cached.until - Date.now() > 30 * 60_000) return cached.token
  const t = await api.POST('/api/auth/media-token').then(unwrap)
  cached = { token: t.token, until: Date.parse(t.expiresAt) }
  return t.token
}

export const withToken = (url: string, token: string) => `${url}${url.includes('?') ? '&' : '?'}access_token=${encodeURIComponent(token)}`

/** Whether an AirPlay target is around, whether this video is playing on one, and the picker. */
export function useAirPlay(videoRef: RefObject<HTMLVideoElement | null>) {
  const [available, setAvailable] = useState(false)
  const [active, setActive] = useState(false)
  useEffect(() => {
    const v = videoRef.current as WebKitVideo | null
    if (!v || !prefersNativeHls()) return
    const onAvailability = (e: Event) => setAvailable((e as Event & { availability?: string }).availability === 'available')
    const onWireless = () => setActive(!!v.webkitCurrentPlaybackTargetIsWireless)
    v.addEventListener('webkitplaybacktargetavailabilitychanged', onAvailability)
    v.addEventListener('webkitcurrentplaybacktargetiswirelesschanged', onWireless)
    return () => {
      v.removeEventListener('webkitplaybacktargetavailabilitychanged', onAvailability)
      v.removeEventListener('webkitcurrentplaybacktargetiswirelesschanged', onWireless)
    }
  }, [videoRef])
  const pick = () => (videoRef.current as WebKitVideo | null)?.webkitShowPlaybackTargetPicker?.()
  return { available, active, pick }
}
