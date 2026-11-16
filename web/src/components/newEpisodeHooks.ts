import { useCallback, useEffect, useState, useSyncExternalStore } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { ApiError } from '../api/client'
import { useFollowShow } from '../api/follows'
import { serverQuery } from '../api/queries'
import { useAuth } from '../authContext'
import { notifyPermission, onReminderOpened, requestNotify } from '../lib/browserNotices'
import {
  bannerStorageKey, canFollow, followFailure, loadBanner, noteFollowAsked, notifySwitch, personKey, setNotifySwitch, shouldExplainFollow, type EpisodeBanner, type FollowFailure,
} from '../lib/newEpisodes'

// What new-episode notices read from the browser, kept current where it
// changes: this tab's own writes (changed()), other tabs' (the storage
// event), and the permission when the person comes back from the
// browser's own settings (the Permissions API's change event, else
// visibility and focus).

const listeners = new Set<() => void>()
/** Tells this tab's readers the switch, the banner or the permission moved; other tabs hear the storage event. */
export function newEpisodesChanged() {
  listeners.forEach((l) => l())
}

function subscribe(l: () => void) {
  listeners.add(l)
  window.addEventListener('storage', l)
  document.addEventListener('visibilitychange', l)
  window.addEventListener('focus', l)
  let status: PermissionStatus | undefined
  let gone = false
  navigator.permissions?.query({ name: 'notifications' }).then((s) => {
    if (gone) return
    status = s
    s.addEventListener('change', l)
  }).catch(() => { /* no Permissions API for notifications here */ })
  return () => {
    gone = true
    listeners.delete(l)
    window.removeEventListener('storage', l)
    document.removeEventListener('visibilitychange', l)
    window.removeEventListener('focus', l)
    status?.removeEventListener('change', l)
  }
}

export function useNotifyPermission(): NotificationPermission | 'unsupported' {
  return useSyncExternalStore(subscribe, notifyPermission, () => 'unsupported' as const)
}

/** The server and person this browser keeps cursors, switches and banners for; undefined until both are known. */
export function usePerson(): string | undefined {
  const auth = useAuth()
  const server = useQuery(serverQuery)
  const userId = auth.user?.id
  return server.data && userId !== undefined ? personKey(server.data.id, userId) : undefined
}

/** This browser's New episodes switch for the person: on unless turned off here. */
export function useNotifySwitch(person: string | undefined): boolean {
  const read = useCallback(() => (person ? notifySwitch(person) : false), [person])
  return useSyncExternalStore(subscribe, read, () => false)
}

/** The banner every tab of this browser shows until it is opened or dismissed. */
export function useStoredBanner(person: string | undefined): EpisodeBanner | undefined {
  // The stored text is the snapshot (stable between reads), parsed once it changes.
  const raw = useSyncExternalStore(subscribe, () => (person ? readRaw(bannerStorageKey(person)) : undefined), () => undefined)
  return raw && person ? loadBanner(person) : undefined
}

function readRaw(key: string): string | undefined {
  try { return localStorage.getItem(key) ?? undefined } catch { return undefined }
}

/** Whether this server has Notify me (API level 29). */
export function useCanFollow(): boolean {
  const server = useQuery(serverQuery)
  return canFollow(server.data?.apiLevel)
}

/**
 * A notification chosen while a tab is open: the worker focuses that tab
 * and names the page, a channel (reminders), a show or Home (new episodes).
 * Mounted once in the layout.
 */
export function useNotificationClicks() {
  const navigate = useNavigate()
  useEffect(() => onReminderOpened((path) => void navigate({ to: path })), [navigate])
}

/**
 * Asks the browser, as Continue in the explainer and the switch while not
 * asked yet do. Allow turns on this browser's New episodes switch for the
 * person; either answer redraws what reads the permission.
 */
export async function askForNewEpisodes(person: string | undefined) {
  const answer = await requestNotify()
  if (answer === 'granted' && person) setNotifySwitch(person, true)
  newEpisodesChanged()
  return answer
}

/**
 * Notify me on a show's page (show-off, show-on, show-fail, explainer):
 * pressing follows or stops at once, and a refused change goes back and
 * says why until the next press. The first follow in a browser that can
 * still ask opens the explainer, with the follow already made.
 */
export function useNotifyMe(show: { id: number; tmdbId: number; following?: boolean }) {
  const able = useCanFollow()
  const change = useFollowShow()
  const permission = useNotifyPermission()
  const person = usePerson()
  const [asking, setAsking] = useState(false)
  const following = !!show.following
  const press = () => {
    // One change at a time: the bell already shows the one being saved.
    if (change.isPending) return
    const follow = !following
    change.mutate({ showId: show.id, tmdbId: show.tmdbId, follow })
    if (follow && shouldExplainFollow(permission)) {
      noteFollowAsked()
      setAsking(true)
    }
  }
  const answer = (allow: boolean) => {
    setAsking(false)
    if (allow) void askForNewEpisodes(person)
  }
  const failure: FollowFailure | undefined = change.isError && change.variables
    ? followFailure(change.variables.follow, change.error instanceof ApiError ? change.error.status : undefined)
    : undefined
  return { able, following, press, asking, answer, failure }
}
