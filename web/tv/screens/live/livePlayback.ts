import { useEffect, useMemo } from 'react'
import { saveLiveQuality, savedLiveQuality } from '../../../src/lib/liveTV'
import { usePlayer, useSession } from '../../app/hooks'
import { platform } from '../../platform/current'
import type { ActiveSession } from '../../session/active'
import { FakePlayer, LivePlayback, type Player } from '../../player'

// The Live TV player's engine for the signed-in session: one LivePlayback
// per session, on the app's one Player, made when Live TV first plays and
// released when the session ends. Its quality is this TV's, kept as the
// web keeps it (src/lib/liveTV.ts).

const engines = new WeakMap<ActiveSession, { live: LivePlayback; preview: boolean }>()

/**
 * The browser preview's fictional server sends a stream nothing can open
 * (lakeside.local), so on `?fakes` the engine plays into a FakePlayer and
 * the screen draws a stand-in picture. Development only: the TV build has
 * neither the fakes nor this branch.
 */
function previewPlayer(): Player | undefined {
  if (!import.meta.env.DEV || !document.documentElement.dataset.fakes) return undefined
  const player = new FakePlayer()
  return player
}

export function useLivePlayback(): { live: LivePlayback; preview: boolean } | undefined {
  const session = useSession()
  const appPlayer = usePlayer()
  const made = useMemo(() => {
    let made = engines.get(session)
    if (made) return made
    const preview = previewPlayer()
    const player = preview ?? appPlayer
    if (!player) return undefined
    const live = new LivePlayback({
      api: session.api, player, mediaUrl: session.playerMediaUrl, clientName: session.client.name ?? platform.words.tv,
      profile: platform.profiles.live,
      quality: savedLiveQuality, saveQuality: saveLiveQuality,
    })
    made = { live, preview: !!preview }
    engines.set(session, made)
    return made
  }, [session, appPlayer])
  // The shell is one per session: when it goes, so does the engine.
  useEffect(() => () => { if (session.detached && made) { void made.live.release(); engines.delete(session) } }, [session, made])
  return made
}

// A reminder banner's Watch while the Live TV player is open changes its
// channel rather than opening another player on top.
let tuner: ((channelId: string) => void) | undefined

/** The open Live TV player registers itself here; returns the function that unregisters it. */
export function registerTuner(tune: (channelId: string) => void): () => void {
  tuner = tune
  return () => { if (tuner === tune) tuner = undefined }
}

/** Tunes the open Live TV player; false when none is open. */
export function tuneOpenPlayer(channelId: string): boolean {
  if (!tuner) return false
  tuner(channelId)
  return true
}

let watched: string | undefined

/** The channel watched last on this TV, where the guide starts. */
export function lastChannel(): string | undefined { return watched }
export function rememberChannel(channelId: string) { watched = channelId }
