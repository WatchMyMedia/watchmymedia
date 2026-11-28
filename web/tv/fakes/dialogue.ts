import type { components } from '../../src/api/schema'
import { FAKE_USER } from './data'
import { FakeServer, problem } from './server'

// The preview's sound settings and subtitle timings (API level 35). The
// account's Boost dialogue and Reduce loud sounds live here, so a play's
// decision names the effects the fake server "applied"; a subtitle timing
// is kept per file and track, the person's own over one saved for everyone,
// and every file's SDH track (s2) starts with 0.8 s later set for everyone.
// Modes: "soundfail" refuses a sound change, "timingfail" refuses timing
// saves, "member" (an account that isn't an admin) is refused Save for
// everyone; "level34" is a server below level 35 (start.ts).

type AudioEffects = components['schemas']['AudioEffects']
type SubtitleTiming = components['schemas']['SubtitleTiming']

/** The account's sound settings, as its preferences hold them. */
export const sound: { dialogueBoost: string; reduceLoudSounds: string } = { dialogueBoost: 'off', reduceLoudSounds: 'off' }

/** What a decision says the server applied: absent when both are off. */
export function effects(): AudioEffects | undefined {
  const dialogue = sound.dialogueBoost !== 'off' ? sound.dialogueBoost : undefined
  const loudness = sound.reduceLoudSounds !== 'off' ? sound.reduceLoudSounds : undefined
  return dialogue || loudness ? { ...(dialogue && { dialogue }), ...(loudness && { loudness }) } : undefined
}

const own = new Map<string, number>()
const shared = new Map<string, number>()
const key = (fileId: number | string, track: string) => `${fileId}/${track}`
const sharedOf = (fileId: number | string, track: string) => shared.get(key(fileId, track)) ?? (track === 's2' ? 800 : 0)

/** The timing a person's play of a track gets: their own, else the one for everyone. */
export function timingOf(fileId: number | string, track: string): SubtitleTiming {
  const mine = own.get(key(fileId, track))
  if (mine !== undefined) return { timingMs: mine, timingShared: false }
  const everyone = sharedOf(fileId, track)
  return { timingMs: everyone, timingShared: everyone !== 0 }
}

/** A decision's track with its timing, as the server writes it (absent for 0). */
export function withTiming<T extends { id: string }>(fileId: number, track: T, burned: boolean): T {
  const t = timingOf(fileId, track.id)
  if (!t.timingMs) return track
  return { ...track, timingMs: t.timingMs, ...(t.timingShared && { timingShared: true }), ...(burned && { timingApplied: true }) }
}

export function registerDialogue(server: FakeServer, mode: string) {
  server.route('PUT', '/api/files/:id/subtitles/:track/timing', async ({ params, body }) => {
    await new Promise((resolve) => setTimeout(resolve, 400))
    if (mode === 'timingfail') return problem(503, 'try again later')
    const b = body as { timingMs: number; everyone?: boolean }
    const k = key(params.id, params.track)
    if (b.everyone) {
      if (!FAKE_USER.isAdmin) return problem(403, 'admins only')
      shared.set(k, b.timingMs)
      own.delete(k)
    } else if (b.timingMs === 0 && sharedOf(params.id, params.track) === 0) own.delete(k)
    else own.set(k, b.timingMs)
    return timingOf(params.id, params.track)
  })
  server.route('DELETE', '/api/files/:id/subtitles/:track/timing', async ({ params }) => {
    await new Promise((resolve) => setTimeout(resolve, 400))
    if (mode === 'timingfail') return problem(503, 'try again later')
    own.delete(key(params.id, params.track))
    return timingOf(params.id, params.track)
  })
}
