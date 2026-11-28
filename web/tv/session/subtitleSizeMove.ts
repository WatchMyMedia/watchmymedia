import { useEffect, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { preferencesQuery, useUpdatePreferences } from '../../src/api/queries'
import { subtitleSize, type SubtitleSize } from '../../src/lib/subtitleSize'
import type { Preferences } from '../player'
import { localSettings, setLocalSettings } from '../app/localSettings'
import { useSession } from '../app/hooks'
import { from35 } from './features'

// Subtitle size is the person's from API level 35, kept on their server and
// the same on all their devices. Before that each TV kept its own
// (Settings › Playback › On this TV); a TV whose own size isn't Normal moves
// it up once, to a person on that server whose size is still Normal, so
// nobody finds their subtitles shrunk by the change. Below level 35 the TV
// keeps using its own.

/** The size the player draws: the person's on a server from level 35 once their preferences are in, else this TV's own. */
export function playerSubtitleSize(apiLevel: number | undefined, preferences: Pick<Preferences, 'subtitles'> | undefined, own: SubtitleSize): SubtitleSize {
  return from35(apiLevel) && preferences ? subtitleSize(preferences.subtitles?.size) : own
}

/** Who a move is remembered for: one person on one server. */
export const moveKey = (serverId: string, userId: number) => `${serverId}/${userId}`

/**
 * What this TV writes up to the person, if anything: its own size, when it
 * isn't Normal and the person's still is. The server answers Normal for a
 * person who never chose a size, so Normal is taken as not chosen. Nothing
 * before level 35, before the preferences are in, or once done.
 */
export function sizeToMove(apiLevel: number | undefined, preferences: Pick<Preferences, 'subtitles'> | undefined, own: SubtitleSize, done: boolean): SubtitleSize | undefined {
  if (done || !from35(apiLevel) || !preferences) return undefined
  if (subtitleSize(preferences.subtitles?.size) !== 'normal') return undefined
  return own === 'normal' ? undefined : own
}

/** Whether this TV has settled its size with the person (moved it, or found nothing to move). */
export function sizeMoveDone(key: string): boolean {
  return localSettings().sizeMoved.includes(key)
}

function markDone(key: string) {
  if (!sizeMoveDone(key)) setLocalSettings({ sizeMoved: [...localSettings().sizeMoved, key] })
}

/** Run once by the signed-in app: moves this TV's size up when it should. A failed save is tried again on the next start. */
export function useSubtitleSizeMove() {
  const session = useSession()
  const level = session.server.apiLevel
  const prefs = useQuery({ ...preferencesQuery, enabled: from35(level) })
  const update = useUpdatePreferences()
  const asked = useRef(false)
  const key = moveKey(session.server.id, session.account.userId)
  useEffect(() => {
    if (asked.current || !from35(level) || !prefs.data) return
    asked.current = true
    const size = sizeToMove(level, prefs.data, localSettings().subtitleSize, sizeMoveDone(key))
    if (!size) { markDone(key); return }
    update.mutate({ subtitles: { size } }, { onSuccess: () => markDone(key) })
  }, [level, prefs.data, key, update])
}
