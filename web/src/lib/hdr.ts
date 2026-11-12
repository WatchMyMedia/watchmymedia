import type { Profile } from './capabilities'

/**
 * The API level whose servers read Profile.hdrTypes and the dovi keys
 * (PLAN-HDR-PROFILE.md). Older ones refuse hdrTypes with a 422.
 */
export const HDR_PROFILE_API_LEVEL = 8

/**
 * The Dolby Vision keys the web player and the Cast receiver can use, with
 * the codec string a decoder is asked about (level 6, 4K at 24 fps).
 * Profile 7 needs MKV, which neither plays.
 */
export const DOVI_CODECS: readonly (readonly [key: string, codec: string])[] = [
  ['dovi5', 'dvh1.05.06'],
  ['dovi8', 'dvh1.08.06'],
]

/** What the screen shows: HDR10, HLG and Dolby Vision. */
export type Screen = { hdr10: boolean; hlg: boolean; dolbyVision: boolean }

/**
 * The profile's HDR fields (PLAN-HDR-PROFILE.md §2, §6.3): `hdrTypes` lists
 * what the screen shows, even when that is nothing, and a dovi key needs a
 * decoder for that profile and a screen that shows Dolby Vision
 * (decision 4).
 */
export function hdrFields(screen: Screen, decodes: (codec: string) => boolean): { hdrTypes: string[]; doviKeys: string[] } {
  const doviKeys = screen.dolbyVision ? DOVI_CODECS.filter(([, codec]) => decodes(codec)).map(([key]) => key) : []
  const hdrTypes = [screen.hdr10 && 'hdr10', screen.hlg && 'hlg', screen.dolbyVision && 'dovi'].filter((t): t is string => !!t)
  return { hdrTypes, doviKeys }
}

/**
 * The profile for a server at `level`: below HDR_PROFILE_API_LEVEL, the one
 * sent before, without hdrTypes or dovi keys (decision 9).
 */
export function forApiLevel(p: Profile, level: number): Profile {
  if (level >= HDR_PROFILE_API_LEVEL) return p
  const older: Profile = { ...p, videoCodecs: p.videoCodecs?.filter((c) => !c.startsWith('dovi')) }
  delete older.hdrTypes
  return older
}
